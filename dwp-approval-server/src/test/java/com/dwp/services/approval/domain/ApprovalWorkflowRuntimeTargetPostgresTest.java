package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeActionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalWorkflowRuntimeTargetPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    NamedParameterJdbcTemplate jdbc;
    @BeforeEach void initialize() {
        f=new ApprovalWorkflowQuorumPostgresFixture();f.initialize(POSTGRES);f.pool=java.util.List.of(100L,101L,102L);
        f.start(one(Mode.ALL,null));jdbc=new NamedParameterJdbcTemplate(f.jdbc);actor(100);
    }
    @AfterEach void clear() {ApprovalRequestContext.clear();}
    private void actor(long id) {ApprovalRequestContext.set(id,TENANT,person(id),Set.of("FINANCE_REVIEWER"),Set.of("ACTION.APPROVAL_TASK:UPDATE"));}
    private Snapshot snapshot() {
        var store=new ApprovalWorkflowQuorumRuntimeStore(jdbc,new ObjectMapper());
        return store.stages(TENANT,f.request,false).getFirst().snapshot();
    }
    private UUID task(long principal) {return f.command("FINANCE",principal,principal,Decision.APPROVE).taskId();}
    private ApprovalWorkflowRuntimeTarget target(ApprovalWorkflowRuntimeTarget.Use use,long principal,UUID evidence) {
        return f.tx.execute(status->ApprovalWorkflowRuntimeTarget.seal(jdbc,snapshot(),use,task(principal),evidence));
    }
    private WorkflowRuntimeActionContext.Current current() {
        var evidence=new ApprovalDecisionRevisionContext.Evidence("psr-"+"a".repeat(64),OffsetDateTime.now().plusMinutes(1),"ctx","scope",
                "route.approvals.work.task-decision.action","111");
        return new WorkflowRuntimeActionContext.Current(ApprovalRequestContext.require(),evidence,"NORMAL");
    }
    private ApprovalWorkflowRuntimeSource source() {
        return f.tx.execute(status->ApprovalWorkflowRuntimeSource.seal(jdbc,snapshot(),current(),"POST","/v1/tasks/"+task(100)+"/decisions","key-1",null));
    }
    @Test void castUsesActualServerActorAndLockedSeatWithExactElevenFields() {
        var target=target(ApprovalWorkflowRuntimeTarget.Use.CAST,100,null).json();
        assertEquals(11,target.size());assertEquals("FINANCE",target.get("stepKey").textValue());
        assertEquals(100,target.get("actorId").longValue());assertEquals(person(100).toString(),target.get("principalPersonPublicId").textValue());
        assertTrue(target.get("evidenceId").isNull());assertTrue(target.get("delegation").isNull());assertEquals(0,f.count("apr_quorum_votes"));
    }
    @Test void seatRecheckCannotSubstituteTheCurrentCallerForOriginalPrincipal() {
        actor(102);var target=target(ApprovalWorkflowRuntimeTarget.Use.SEAT_RECHECK,100,null).json();
        assertEquals(100,target.get("actorId").longValue());assertEquals(100,target.get("principalId").longValue());
    }
    @Test void castDeniesClaimedAssigneeIdentityMismatchAndRequesterSelfApproval() {
        f.jdbc.update("UPDATE apr_tasks SET assignee_person_public_id=? WHERE task_id=?",person(102),task(100));
        assertThrows(BaseException.class,()->target(ApprovalWorkflowRuntimeTarget.Use.CAST,100,null));
        f.jdbc.update("UPDATE apr_tasks SET assignee_person_public_id=? WHERE task_id=?",person(100),task(100));actor(REQUESTER);
        assertThrows(BaseException.class,()->target(ApprovalWorkflowRuntimeTarget.Use.CAST,100,null));assertEquals(0,f.count("apr_quorum_votes"));
    }
    @Test void initialDelegationUsesExactSource100NotAlternate101() {
        f.delegate(200,100);f.delegate(200,101);actor(200);
        var value=target(ApprovalWorkflowRuntimeTarget.Use.CAST,100,null).json();
        assertEquals(100,value.get("delegation").get("delegatorUserId").longValue());
        assertTrue(value.get("delegation").get("authorityRoleId").isNull());
    }
    @Test void ambiguousInitialGrantAndExpiredOriginalAreDenied() {
        f.delegate(200,100);f.delegate(200,100);actor(200);
        assertThrows(BaseException.class,()->target(ApprovalWorkflowRuntimeTarget.Use.CAST,100,null));
        f.jdbc.update("UPDATE apr_delegations SET ends_at=now()-interval '1 second'");
        assertThrows(BaseException.class,()->target(ApprovalWorkflowRuntimeTarget.Use.CAST,100,null));
    }
    @Test void acceptedVoteRechecksImmutableOriginalUuidAndCannotBeHealedByReplacement() {
        var original=f.delegate(200,100);f.runtime.vote(f.command("FINANCE",200,100,Decision.APPROVE));actor(102);
        UUID vote=f.jdbc.queryForObject("SELECT vote_id FROM apr_quorum_votes",UUID.class);
        var value=target(ApprovalWorkflowRuntimeTarget.Use.VOTE_RECHECK,100,vote).json();
        assertEquals(original.toString(),value.get("delegation").get("id").textValue());assertEquals(200,value.get("actorId").longValue());
        f.jdbc.update("UPDATE apr_delegations SET lifecycle_state='REVOKED' WHERE delegation_id=?",original);
        f.delegate(200,100);f.delegate(200,101);
        assertThrows(BaseException.class,()->target(ApprovalWorkflowRuntimeTarget.Use.VOTE_RECHECK,100,vote));assertEquals(1,f.count("apr_quorum_votes"));
    }
    @Test void voteRecheckCannotInferEvidenceFromAnotherSeatOrInventUuid() {
        f.runtime.vote(f.command("FINANCE",100,100,Decision.APPROVE));actor(102);
        UUID vote=f.jdbc.queryForObject("SELECT vote_id FROM apr_quorum_votes",UUID.class);
        assertThrows(BaseException.class,()->target(ApprovalWorkflowRuntimeTarget.Use.VOTE_RECHECK,101,vote));
        assertThrows(BaseException.class,()->target(ApprovalWorkflowRuntimeTarget.Use.VOTE_RECHECK,100,UUID.randomUUID()));
        assertThrows(BaseException.class,()->target(ApprovalWorkflowRuntimeTarget.Use.VOTE_RECHECK,100,null));
        assertEquals(vote.toString(),target(ApprovalWorkflowRuntimeTarget.Use.VOTE_RECHECK,100,vote).json().get("evidenceId").textValue());
    }
    @Test void sealingNeedsTransactionAndExactStoredSnapshotGeneration() {
        assertThrows(BaseException.class,()->ApprovalWorkflowRuntimeTarget.seal(jdbc,snapshot(),ApprovalWorkflowRuntimeTarget.Use.CAST,task(100),null));
        var s=snapshot();var wrong=new Snapshot(s.pins(),s.requestId(),s.stepId(),2,s.requesterUserId(),s.requesterPersonPublicId(),s.payloadRevision(),
                s.payloadSha256(),s.minimumRejectReasonLength(),s.candidateRole(),s.rule(),s.candidates(),s.authorityRevision(),s.openedAt());
        assertThrows(BaseException.class,()->f.tx.execute(status->ApprovalWorkflowRuntimeTarget.seal(jdbc,wrong,ApprovalWorkflowRuntimeTarget.Use.CAST,task(100),null)));
    }
    @Test void sealedSourceHasExactOwner25Stage15PinsAndLeavesAllLedgersUnchanged() {
        long outbox=f.count("apr_integration_outbox"),tasks=f.count("apr_tasks");var value=source().bindings();
        assertEquals(25,value.get("owner").size());assertEquals(15,value.get("stage").size());
        assertEquals("SEALED",value.get("stage").get("poolMode").textValue());assertEquals(3,value.get("stage").get("requiredVotes").intValue());
        assertEquals(3,value.get("stage").get("candidateCount").intValue());assertEquals(1,value.get("stage").get("generation").longValue());
        assertEquals(outbox,f.count("apr_integration_outbox"));assertEquals(tasks,f.count("apr_tasks"));assertEquals(0,f.count("apr_quorum_votes"));
    }
    @Test void sealedSourceRecheckDetectsRequestVersionDriftAndPayloadPolicyChanges() {
        var before=source();f.jdbc.update("UPDATE apr_requests SET version=version+1 WHERE request_id=?",f.request);
        assertThrows(BaseException.class,()->before.requireSame(source()));
        f.jdbc.update("UPDATE apr_request_payloads SET schema_version=2 WHERE request_id=?",f.request);
        assertThrows(BaseException.class,this::source);assertEquals(0,f.count("apr_quorum_votes"));
    }
    @Test void currentFormDraftAndWorkflowHeadAdvanceDoNotRetargetSealedSource() {
        f.jdbc.update("UPDATE apr_forms SET lifecycle_state='DRAFT' WHERE form_id=(SELECT form_id FROM apr_form_versions WHERE form_version_id="
                +"(SELECT form_version_id FROM apr_requests WHERE request_id=?))",f.request);
        f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=91 WHERE workflow_id=?",f.workflow);
        assertEquals(f.workflowVersion.toString(),source().bindings().get("owner").get("workflowVersionId").textValue());
    }
}
