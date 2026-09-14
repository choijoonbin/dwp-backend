package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual runtime completion/savepoints and private original signature fixtures, not installed Auth activation. */
@Testcontainers
class ApprovalInformationCompletionRuntimePostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalInformationCompletionAdmissionsPostgresTest setup;
    @BeforeAll static void keys() throws Exception {ApprovalWorkflowInformationAdmissionPostgresTest.keys();}
    @BeforeEach void initialize() throws Exception {setup=new ApprovalInformationCompletionAdmissionsPostgresTest();setup.initialize(PG);}
    @AfterEach void clear() {setup.clear();}
    String business() {
        var f=setup.setup.f;
        return f.jdbc.queryForObject("""
                SELECT jsonb_build_object('request',(SELECT to_jsonb(r) FROM apr_requests r WHERE request_id=?),
                    'tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY task_id) FROM apr_tasks t WHERE request_id=?),
                    'stages',(SELECT jsonb_agg(to_jsonb(s) ORDER BY generation,stage_key) FROM apr_quorum_stage_runtime s),
                    'rounds',(SELECT jsonb_agg(to_jsonb(r) ORDER BY round_id) FROM apr_quorum_information_rounds r),
                    'timers',(SELECT jsonb_agg(to_jsonb(t) ORDER BY timer_id) FROM apr_quorum_sla_timers t),
                    'markers',(SELECT count(*) FROM apr_quorum_information_completion_transactions),
                    'admissions',(SELECT count(*) FROM apr_quorum_information_admissions),
                    'audit',(SELECT count(*) FROM sys_audit_outbox),'events',(SELECT count(*) FROM apr_request_events),
                    'outbox',(SELECT count(*) FROM apr_integration_outbox),'payload',(SELECT to_jsonb(p) FROM apr_request_payloads p WHERE request_id=?))::text
                """,String.class,f.request,f.request,f.request);
    }
    @Test void productionCallbackRunsAfterCompletedSqlAndStampCommitsAtomicallyWithTheSameNativeParent() {
        var actor=ApprovalRequestContext.require();var callback=setup.admissions.request(actor,setup.command);
        var receipt=setup.setup.info.request(actor,setup.command,()->{},callback);
        assertEquals("COMPLETED",receipt.status());assertEquals(1,setup.setup.f.count("apr_quorum_information_admissions"));
        assertEquals(1,setup.setup.f.count("apr_quorum_information_completion_transactions"));
    }
    @Test void lateUnavailableCompletionRollsBackEveryBusinessWriteAndCommitsOnlyOriginalUnknownIntent() {
        String before=business();var actor=ApprovalRequestContext.require();
        var receipt=setup.setup.info.request(actor,setup.command,()->{},ignored->{throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);});
        assertEquals("UNKNOWN",receipt.status());assertEquals(before,business());assertEquals(1,setup.setup.f.count("apr_quorum_information_commands"));
        assertEquals("UNKNOWN",setup.setup.f.jdbc.queryForObject("SELECT status FROM apr_quorum_information_commands",String.class));
        var completed=setup.setup.info.request(actor,setup.command,()->{},setup.admissions.request(actor,setup.command));
        assertEquals("COMPLETED",completed.status());assertEquals(1,setup.setup.f.count("apr_quorum_information_commands"));
        assertEquals(1,setup.setup.f.count("apr_quorum_information_admissions"));
    }
    @Test void actualExpiredPrivateAdmissionAfterCompletedSqlBecomesDurableUnknownWithoutAnyMarkerOrStamp() {
        String before=business();var actor=ApprovalRequestContext.require();var callback=setup.admissions.request(actor,setup.command);
        long expiry=setup.proof.expiresAt().getEpochSecond();
        var receipt=setup.setup.info.request(actor,setup.command,()->{},completed->{
            double wait=Math.max(0,expiry+1-System.currentTimeMillis()/1000.0);setup.setup.f.jdbc.queryForObject("SELECT pg_sleep(?)",Object.class,wait);
            callback.accept(completed);
        });
        assertEquals("UNKNOWN",receipt.status());assertEquals(before,business());assertEquals(1,setup.setup.f.count("apr_quorum_information_commands"));
    }
    @Test void originalPrincipalAuthorityDenialIsNeverTurnedIntoUnknownOrHealedByAnotherPrincipal() {
        String before=business();setup.setup.f.withdrawnRoles.add(100L);
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->setup.setup.info.request(ApprovalRequestContext.require(),setup.command,
                ()->{},setup.admissions.request(ApprovalRequestContext.require(),setup.command))).getErrorCode());
        assertEquals(before,business());assertEquals(0,setup.setup.f.count("apr_quorum_information_commands"));
    }
    @Test void completedHistoricalCommandNeverInvokesTheCompletionCallbackAndCannotBackfillAStamp() {
        var actor=ApprovalRequestContext.require();setup.setup.info.request(actor,setup.command);
        var receipt=setup.setup.info.request(actor,setup.command,()->{},ignored->fail("Historical replay must not stamp"));
        assertEquals("COMPLETED",receipt.status());assertEquals(0,setup.setup.f.count("apr_quorum_information_admissions"));
        assertEquals(1,setup.setup.f.count("apr_quorum_information_completion_transactions"));
    }
    @Test void replyLateUnavailableKeepsOriginalRoundPayloadTasksTimersAndGenerationUnchanged() {
        setup.setup.info.request(ApprovalRequestContext.require(),setup.command);setup.setup.actor(ApprovalWorkflowQuorumPostgresFixture.REQUESTER);
        var reply=new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(setup.command.requestId(),1,1,"runtime-reply","New evidence",Map.of("amount","30"),
                com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.sha("reply-body"));
        ApprovalFormPayloadNormalization normalizer=(a,r,f,h,s,p,b,v)->new ApprovalFormSchemaV2Evaluator().evaluate(
                new ApprovalFormSchemaV2Compiler().compile(new ApprovalCommandPayloadSupport(setup.mapper).object(s,"Schema")),p,true).payload();
        String before=business();var receipt=setup.setup.info.reply(ApprovalRequestContext.require(),reply,normalizer,()->{},ignored->{throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);});
        assertEquals("UNKNOWN",receipt.status());assertEquals(before,business());assertEquals(2,setup.setup.f.count("apr_quorum_information_commands"));
    }
    @Test void replyPrivateOriginalCallbackStampsRequesterAndSourceRoundButReceiptAlwaysAdvancesGeneration() throws Exception {
        var reviewer=ApprovalRequestContext.require();
        var original=setup.setup.info.request(reviewer,setup.command,()->{},setup.admissions.request(reviewer,setup.command));
        setup.setup.actor(ApprovalWorkflowQuorumPostgresFixture.REQUESTER);
        var body=new ApprovalDtos.InformationResponseRequest("Evidence attached",Map.of(),original.requestVersion(),original.generation());
        byte[] bytes=setup.mapper.writeValueAsBytes(body);
        var reply=new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(setup.command.requestId(),body.expectedVersion(),body.sourceGeneration(),
                "runtime-reply-stamped",body.message(),body.payload(),com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.sha(bytes));
        setup.cache(setup.setup.proof("REQUEST_REPLY",reply.requestId(),reply.expectedRequestVersion(),reply.sourceGeneration(),reply.idempotencyKey(),reply.rawBodySha256()),
                bytes,com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandProof.Purpose.REQUEST_REPLY,reply.requestId());
        var requester=ApprovalRequestContext.require();
        var receipt=setup.setup.info.reply(requester,reply,(a,r,f,h,s,p,b,v)->p,()->{},setup.admissions.reply(requester,reply));
        assertFalse(receipt.materialChange());assertEquals(original.generation()+1,receipt.generation());assertEquals(original.roundId(),receipt.roundId());
        assertEquals(2,setup.setup.f.count("apr_quorum_information_admissions"));
        var stamp=setup.setup.f.jdbc.queryForMap("SELECT admission->>'commandActorId' AS actor,source_generation,task_id,round_id FROM apr_quorum_information_admissions WHERE idempotency_key=?",reply.idempotencyKey());
        assertEquals(Long.toString(requester.userId()),stamp.get("actor"));assertEquals(original.generation(),((Number)stamp.get("source_generation")).longValue());
        assertEquals(setup.command.taskId(),stamp.get("task_id"));assertEquals(original.roundId(),stamp.get("round_id"));
        setup.setup.info.reply(requester,reply,(a,r,f,h,s,p,b,v)->p,()->{},ignored->fail("Historical reply cannot stamp again"));
        assertEquals(2,setup.setup.f.count("apr_quorum_information_admissions"));
    }
}
