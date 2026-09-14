package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.informationreplay.*;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.InformationReceiptInstalledTestFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real immutable request/stage/receipt SQL; installed-profile and Auth signatures are explicitly fixtures. */
@Testcontainers
class ApprovalInformationReceiptSourcePostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowInformationAdmissionPostgresTest setup;
    ApprovalWorkflowQuorumPostgresFixture f;
    ApprovalInformationReceiptSource source;
    InformationReceiptBody lookup;
    ApprovalWorkflowQuorumInformationRuntime.RequestCommand command;
    TransactionTemplate reads;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final InformationReceiptInstalledContext installed=new InformationReceiptInstalledContext();
    @BeforeAll static void keys() throws Exception {ApprovalWorkflowInformationAdmissionPostgresTest.keys();}
    @BeforeEach void initialize() throws Exception {
        initialize(PG);
    }
    void initialize(PostgreSQLContainer<?> postgres) throws Exception {
        setup=new ApprovalWorkflowInformationAdmissionPostgresTest();setup.initialize(postgres);f=setup.f;var original=setup.command();
        var pins=f.pins;var publicPins=new ApprovalDtos.WorkflowRuntimePins(pins.workflowVersionId(),pins.workflowVersion(),pins.workflowDefinitionSha256(),
                pins.formSchemaSha256(),pins.policyVersion(),pins.policySha256());
        var precondition=new ApprovalDtos.QuorumVotePrecondition(1,original.expectedQuorum().stageVersion(),publicPins,1,original.expectedQuorum().payloadSha256(),0L);
        byte[] body=mapper.writeValueAsBytes(new ApprovalDtos.DecisionRequest("REQUEST_INFO",original.reason(),original.expectedTaskVersion(),precondition));
        command=new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(original.requestId(),original.taskId(),original.expectedTaskVersion(),0,
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1,original.expectedQuorum().stageVersion(),f.pins,1,original.expectedQuorum().payloadSha256(),0L),
                original.idempotencyKey(),original.reason(),sha(body));
        var proof=setup.proof(command);f.tx.execute(tx->{setup.ledger.append(setup.complete(command,proof));return null;});
        lookup=new InformationReceiptBody("REQUEST_INFO",Base64.getEncoder().encodeToString(body));
        source=new ApprovalInformationReceiptSource(new NamedParameterJdbcTemplate(f.jdbc),mapper);
        reads=new TransactionTemplate(f.tx.getTransactionManager());reads.setReadOnly(true);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @AfterEach void clear() {InformationReceiptInstalledTestFixture.clear();}
    ApprovalInformationReceiptSource.Seal capture() {
        var request=InformationReceiptInstalledTestFixture.install(f.request,command.idempotencyKey());
        var owner=installed.capture(request,f.request,command.idempotencyKey());return reads.execute(tx->source.capture(owner,lookup));
    }
    String database() {
        return f.jdbc.queryForObject("""
                SELECT jsonb_build_object('request',(SELECT to_jsonb(r) FROM apr_requests r WHERE request_id=?),
                    'commands',(SELECT jsonb_agg(to_jsonb(c) ORDER BY idempotency_key) FROM apr_quorum_information_commands c),
                    'markers',(SELECT jsonb_agg(to_jsonb(m) ORDER BY idempotency_key) FROM apr_quorum_information_completion_transactions m),
                    'admissions',(SELECT jsonb_agg(to_jsonb(a) ORDER BY idempotency_key) FROM apr_quorum_information_admissions a),
                    'tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY task_id) FROM apr_tasks t WHERE request_id=?),
                    'stages',(SELECT jsonb_agg(to_jsonb(s) ORDER BY generation,stage_key) FROM apr_quorum_stage_runtime s),
                    'timers',(SELECT jsonb_agg(to_jsonb(t) ORDER BY timer_id) FROM apr_quorum_sla_timers t),
                    'audit',(SELECT count(*) FROM sys_audit_outbox),'events',(SELECT count(*) FROM apr_request_events),
                    'outbox',(SELECT count(*) FROM apr_integration_outbox))::text
                """,String.class,f.request,f.request);
    }
    @Test void receiptEvidencePinsActualOriginalStageAndTaskAndHasNoWritesInsideReadonlyTransaction() {
        String before=database();var seal=capture();var bindings=seal.bindings();
        assertEquals(25,bindings.get("owner").size());assertEquals(13,bindings.get("source").size());assertEquals(11,bindings.get("target").size());assertEquals(30,bindings.get("admission").size());
        assertEquals(command.taskId().toString(),bindings.get("target").get("taskId").textValue());assertEquals(1,bindings.get("source").get("generation").longValue());
        assertEquals(command.expectedQuorum().stageVersion(),bindings.get("source").get("sourceStageRevision").longValue());
        assertEquals(100,bindings.get("target").get("principalId").longValue());assertEquals(before,database());
        assertEquals("on",reads.execute(tx->f.jdbc.queryForObject("SHOW transaction_read_only",String.class)));
        assertEquals("repeatable read",reads.execute(tx->f.jdbc.queryForObject("SHOW transaction_isolation",String.class)));
    }
    @Test void nonActorCannotDiscoverCommandReceiptEvenThoughItIsTheRequestersOwnRequest() {
        setup.actor(REQUESTER);String before=database();assertEquals(ErrorCode.NOT_FOUND,assertThrows(BaseException.class,this::capture).getErrorCode());assertEquals(before,database());
    }
    @Test void alteredOriginalBodyAndOperationAreRejectedWithoutChangingAnything() {
        String before=database();lookup=new InformationReceiptBody("REQUEST_INFO",Base64.getEncoder().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(BaseException.class,this::capture);assertEquals(before,database());
        lookup=new InformationReceiptBody("REPLY",lookup.originalBodyBase64());assertThrows(BaseException.class,this::capture);assertEquals(before,database());
    }
    @Test void committedCurrentPolicyChangeDeniesRatherThanMigratingTheOriginalPins() {
        f.jdbc.update("UPDATE apr_policy_rules SET version=version+1 WHERE tenant_id=42 AND policy_key='SLA_ESCALATION'");
        String before=database();assertThrows(BaseException.class,this::capture);assertEquals(before,database());
    }
    @Test void readonlyEvidenceCannotBorrowAWriteTransactionOrAnUninstalledProfile() {
        var request=InformationReceiptInstalledTestFixture.install(f.request,command.idempotencyKey());var owner=installed.capture(request,f.request,command.idempotencyKey());
        assertThrows(BaseException.class,()->f.tx.execute(tx->source.capture(owner,lookup)));
        request.removeAttribute(com.dwp.services.approval.security.ApprovalPilotPepRegistry.class.getName()+".authorities");
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->installed.capture(request,f.request,command.idempotencyKey())).getErrorCode());
    }
    @Test void freshReadCommittedRecheckDetectsChangesHiddenByTheInitialRepeatableRead() {
        var request=InformationReceiptInstalledTestFixture.install(f.request,command.idempotencyKey());var owner=installed.capture(request,f.request,command.idempotencyKey());
        var fresh=new TransactionTemplate(f.tx.getTransactionManager());fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.execute(tx->{var before=source.capture(owner,lookup);fresh.execute(change->{f.jdbc.update("UPDATE apr_requests SET version=version+1 WHERE request_id=?",f.request);return null;});
            before.requireSame(source.capture(owner,lookup));var current=new TransactionTemplate(f.tx.getTransactionManager());current.setReadOnly(true);
            current.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);current.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThrows(BaseException.class,()->current.execute(check->{before.requireSame(source.capture(owner,lookup));return null;}));return null;});
    }
    @Test void terminalOriginalReceiptReadDoesNotInvokeOldTaskActionOrRepairTheCancelledGeneration() {
        f.jdbc.update("UPDATE apr_requests SET status='WITHDRAWN',version=version+1 WHERE request_id=?",f.request);
        String before=database();assertEquals("COMPLETED",capture().receipt().status());assertEquals(before,database());
    }
    @Test void currentFormDraftAndWorkflowHeadAdvanceCannotRetargetTheOriginalWorkflowAndSchema() {
        f.jdbc.update("UPDATE apr_forms SET lifecycle_state='DRAFT' WHERE form_id=(SELECT form_id FROM apr_form_versions WHERE form_version_id=(SELECT form_version_id FROM apr_requests WHERE request_id=?))",f.request);
        f.jdbc.update("UPDATE apr_workflow_definitions SET current_version=91,version=version+1 WHERE workflow_id=?",f.workflow);
        String before=database();assertEquals(f.pins.workflowVersionId().toString(),capture().bindings().get("owner").get("workflowVersionId").textValue());assertEquals(before,database());
    }
}
