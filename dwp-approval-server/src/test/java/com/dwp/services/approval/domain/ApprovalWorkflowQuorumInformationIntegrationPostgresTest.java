package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.api.ApprovalController;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandMetadata;
import com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandProof;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real V22 engine/PG and real Service transaction advice; admission is explicitly a test double, not crypto evidence. */
@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowQuorumInformationIntegrationPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalWorkflowQuorumPostgresFixture f;
    ApprovalQueryRepository queries;
    ApprovalCommandRepository commands;
    ApprovalWorkflowQuorumFacade quorum;
    ApprovalWorkflowQuorumInformationFacade information;
    ApprovalWorkflowQuorumCommandMetadata metadata;
    ApprovalService service;
    ApprovalController controller;
    UUID taskId;
    ApprovalDtos.DecisionRequest decision;

    @BeforeEach void initialize() {
        f = new ApprovalWorkflowQuorumPostgresFixture(); f.initialize(POSTGRES);
        var definition = one(Mode.ALL, null); f.prepareDraft(definition); f.bindTypedForm(definition);
        var payload = Map.<String, Object>of("summary", "Runtime submission", "amount", "20");
        String json = ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(payload));
        f.jdbc.update("UPDATE apr_request_payloads SET payload=?::jsonb,payload_sha256=? WHERE request_id=?",
                json, ApprovalFormSchemaV2Canonical.sha256(json), f.request);
        f.jdbc.update("UPDATE apr_requests SET status='IN_REVIEW',submitted_at=now(),due_at=now()+interval '60 minutes' WHERE request_id=?", f.request);
        f.pool = List.of(100L, 101L, 102L); f.pins = f.runtime.canonicalPins(TENANT, f.request, definition);
        f.runtime.start(TENANT, f.request, f.pins, definition);
        commands = spy(new ApprovalCommandRepository(new NamedParameterJdbcTemplate(f.jdbc), new ObjectMapper().findAndRegisterModules())); queries = mock(ApprovalQueryRepository.class);
        when(commands.quorumWorkflow(TENANT, f.request)).thenReturn(definition);
        when(queries.request(any(), eq(f.request))).thenAnswer(invocation -> requestSummary());
        metadata = mock(ApprovalWorkflowQuorumCommandMetadata.class);
        when(metadata.current(any(), any(), any())).thenAnswer(invocation -> {
            var actor = (ApprovalRequestContext.Actor) invocation.getArgument(0);
            return new ApprovalWorkflowQuorumCommandProof.Verified(TENANT, actor.userId(), actor.personPublicId(),
                    invocation.getArgument(1), invocation.getArgument(2), "POST", "/test-only",
                    invocation.getArgument(1) == ApprovalWorkflowQuorumCommandProof.Purpose.REQUEST_REPLY ? "original-reply-1" : "original-command-1", "b".repeat(64),
                    "test-only-admission", Instant.now(), Instant.now().plusSeconds(30), null, "NORMAL");
        });
        var named = new NamedParameterJdbcTemplate(f.jdbc); var mapper = new ObjectMapper().findAndRegisterModules();
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("testQuorumAuthority", f);
        var work = mock(ApprovalWorkAuthority.class);
        when(work.require(anyString(), anyBoolean())).thenAnswer(invocation -> ApprovalRequestContext.require());
        when(work.requireCurrent(anyString())).thenAnswer(invocation -> ApprovalRequestContext.require());
        ApprovalFormPayloadNormalization normalizer = (a,r,v,h,s,p,b,e) -> new ApprovalFormSchemaV2Evaluator().evaluate(
                new ApprovalFormSchemaV2Compiler().compile(new ApprovalCommandPayloadSupport(mapper).object(s, "Schema")), p, true).payload();
        information = new ApprovalWorkflowQuorumInformationFacade(commands, queries, named, mapper, f.tx.getTransactionManager(),
                beans.getBeanProvider(ApprovalWorkflowQuorumAuthority.class), normalizer, metadata, work,
                beans.getBeanProvider(ApprovalOwnerPredicateEvaluator.class), new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test"));
        quorum = new ApprovalWorkflowQuorumFacade(commands, named, mapper, f.tx.getTransactionManager(),
                beans.getBeanProvider(ApprovalWorkflowQuorumAuthority.class), mock(AuditOutboxRecorder.class), information);
        var target = new ApprovalService(queries, commands, mock(AuditOutboxRecorder.class), mock(ApprovalIdentityDirectory.class), null, null, quorum);
        var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        var advice = new TransactionInterceptor(); advice.setTransactionManager(f.tx.getTransactionManager());
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); proxy.addAdvice(advice);
        service = (ApprovalService) proxy.getProxy();
        controller = new ApprovalController(service, mock(ApprovalDraftService.class), metadata);
        actor(100);
        var vote = f.command("FINANCE", 100, 100, Decision.APPROVE); taskId = vote.taskId();
        var summary = new ApprovalDtos.TaskSummary(taskId, f.request, "QUORUM", "Quorum", "", "Workflow", "Workflow",
                "FINANCE", "Finance", 1, "Requester", "", "CLAIMED", "NORMAL", "INTERNAL", 0, Instant.now(), Instant.now().plusSeconds(60), vote.expectedTaskVersion());
        when(queries.taskDetail(any(), eq(taskId))).thenReturn(new ApprovalQueryRepository.TaskAccess(summary, REQUESTER, 100L, "FINANCE_REVIEWER", false, null));
        when(queries.requestPayload(TENANT, f.request)).thenReturn(payload);
        when(queries.requestFormSchema(TENANT, f.request)).thenReturn(detail().formSchema());
        when(queries.timeline(TENANT, f.request)).thenReturn(List.of());
        var precondition = new ApprovalDtos.QuorumVotePrecondition(1, vote.expectedStageVersion(), ApprovalWorkflowQuorumBindings.publicPins(f.pins),
                1, payloadHash(), requestSummary().version());
        decision = new ApprovalDtos.DecisionRequest("REQUEST_INFO", "Please attach evidence", vote.expectedTaskVersion(), precondition);
    }

    @AfterEach void clear() { ApprovalRequestContext.clear(); }
    void actor(long user) { ApprovalRequestContext.set(user, TENANT, person(user), "Actor", Set.of("FINANCE_REVIEWER"),
            Set.of("ACTION.APPROVAL_TASK:UPDATE", "ACTION.APPROVAL_TASK:APPROVE", "ACTION.APPROVAL_REQUEST:UPDATE", "ACTION.APPROVAL_FORM:VIEW")); }
    String payloadHash() { return f.jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?", String.class, f.request).strip(); }
    ApprovalDtos.RequestSummary requestSummary() {
        var row = f.jdbc.queryForMap("SELECT status,version FROM apr_requests WHERE request_id=?", f.request);
        return new ApprovalDtos.RequestSummary(f.request, "QUORUM", "Quorum", "", "Workflow", "Workflow", "FINANCE", "Finance", 1, 1,
                (String) row.get("status"), "NORMAL", "INTERNAL", "Evidence", null, null, null, ((Number) row.get("version")).longValue());
    }
    ApprovalDtos.RequestDetail detail() {
        var row = f.jdbc.queryForMap("SELECT v.form_version_id,v.form_id,v.schema_payload::text,v.schema_sha256,p.payload::text "
                + "FROM apr_requests r JOIN apr_form_versions v ON v.form_version_id=r.form_version_id "
                + "JOIN apr_request_payloads p ON p.request_id=r.request_id WHERE r.request_id=?", f.request);
        var support = new ApprovalCommandPayloadSupport(new ObjectMapper());
        return new ApprovalDtos.RequestDetail(requestSummary(), f.workflow, (UUID) row.get("form_id"),
                support.object((String) row.get("payload"), "Payload"), support.object((String) row.get("schema_payload"), "Schema"), List.of(),
                (UUID) row.get("form_version_id"), ((String) row.get("schema_sha256")).strip());
    }
    void openRound() { controller.decide(taskId, decision, null); actor(REQUESTER); }

    @Test void controllerTranslatesUnknownOnlyAfterActualServiceProxyCommitsOriginalIntent() {
        f.unknown = true;
        var error = assertThrows(BaseException.class, () -> controller.decide(taskId, decision, null));
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, error.getErrorCode());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(1, f.count("apr_quorum_information_commands")); assertEquals(0, f.count("apr_quorum_information_rounds"));
        assertEquals("IN_REVIEW", requestSummary().status()); assertEquals(0, f.count("apr_quorum_votes"));
        f.unknown = false; controller.decide(taskId, decision, null);
        assertEquals(1, f.count("apr_quorum_information_commands")); assertEquals(1, f.count("apr_quorum_information_rounds"));
        assertEquals("NEEDS_INFO", requestSummary().status()); verify(metadata, times(2)).clear();
    }

    @Test void replyUnknownCommitsIntentWithoutNewPayloadOrGenerationAndSameKeyCanRecover() {
        openRound(); var response = new ApprovalDtos.InformationResponseRequest("Evidence attached", Map.of("amount", "30"), requestSummary().version(), 1L);
        f.unknown = true; long before = f.count("apr_tasks");
        var error = assertThrows(BaseException.class, () -> controller.respondToInformationRequest(f.request, response, null));
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, error.getErrorCode());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(2, f.count("apr_quorum_information_commands")); assertEquals(before, f.count("apr_tasks"));
        assertEquals("NEEDS_INFO", requestSummary().status());
        f.unknown = false; controller.respondToInformationRequest(f.request, response, null);
        assertEquals("IN_REVIEW", requestSummary().status()); assertEquals(2, f.count("apr_quorum_information_commands"));
        assertEquals(2, f.jdbc.queryForObject("SELECT MAX(generation) FROM apr_quorum_stage_runtime WHERE request_id=?", Integer.class, f.request));
    }

    @Test void fullOwnerDetailIsReadFirstAndOnlyOriginalRequesterReceivesPinnedOpenRound() {
        openRound(); var original = detail(); when(queries.requestDetail(any(), eq(f.request))).thenReturn(original);
        var result = service.requestDetail(f.request);
        assertEquals(1L, result.informationGeneration()); assertEquals(2L, result.informationRound().targetGeneration());
        assertEquals(f.pins.workflowVersionId(), result.informationRound().pins().workflowVersionId());
        assertEquals(original.payload(), result.payload()); assertEquals(payloadHash(), result.informationRound().payloadSha256());
        var order = inOrder(queries, commands); order.verify(queries).ensureTenant(TENANT);
        order.verify(queries).requestDetail(ApprovalRequestContext.require(), f.request); order.verify(commands).quorumWorkflow(TENANT, f.request);
        actor(101); assertSame(original, service.requestDetail(f.request));
    }

    @Test void deniedOwnerCannotReadTagOrAugmentAnything() {
        when(queries.requestDetail(any(), eq(f.request))).thenThrow(new BaseException(ErrorCode.NOT_FOUND));
        clearInvocations(commands); assertThrows(BaseException.class, () -> service.requestDetail(f.request));
        verifyNoInteractions(commands); assertEquals(0, f.count("apr_quorum_information_rounds"));
    }

    @Test void legacyDetailIsReturnedUnchangedWithoutAnyQuorumInformationRead() {
        var original = detail(); when(queries.requestDetail(any(), eq(f.request))).thenReturn(original);
        when(commands.quorumWorkflow(TENANT, f.request)).thenReturn(null);
        assertSame(original, service.requestDetail(f.request)); verifyNoInteractions(metadata);
    }

    @Test void substitutedWorkflowFormVersionAndPayloadCannotReceiveRoundMetadata() {
        openRound(); var original = detail();
        var wrongWorkflow = new ApprovalDtos.RequestDetail(original.request(), UUID.randomUUID(), original.formId(), original.payload(), original.formSchema(),
                original.timeline(), original.formVersionId(), original.formSchemaSha256());
        var wrongVersion = new ApprovalDtos.RequestDetail(original.request(), original.workflowId(), original.formId(), original.payload(), original.formSchema(),
                original.timeline(), UUID.randomUUID(), original.formSchemaSha256());
        var wrongPayload = new ApprovalDtos.RequestDetail(original.request(), original.workflowId(), original.formId(), Map.of("amount", "100"), original.formSchema(),
                original.timeline(), original.formVersionId(), original.formSchemaSha256());
        for (var substituted : List.of(wrongWorkflow, wrongVersion, wrongPayload)) {
            when(queries.requestDetail(any(), eq(f.request))).thenReturn(substituted);
            assertEquals(ErrorCode.RESOURCE_CONFLICT, assertThrows(BaseException.class, () -> service.requestDetail(f.request)).getErrorCode());
        }
        assertEquals(1, f.count("apr_quorum_information_rounds")); assertEquals(0, f.count("apr_quorum_votes"));
    }

    @Test void absentInformationGenerationAndRequestVersionFailBeforeAnyIntentWrite() {
        var precondition = decision.quorum();
        var noVersion = new ApprovalDtos.DecisionRequest("REQUEST_INFO", decision.comment(), decision.expectedVersion(),
                new ApprovalDtos.QuorumVotePrecondition(precondition.generation(), precondition.expectedStageVersion(), precondition.pins(),
                        precondition.payloadRevision(), precondition.payloadSha256()));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, assertThrows(BaseException.class, () -> controller.decide(taskId, noVersion, null)).getErrorCode());
        verify(metadata, never()).prepare(any(), any()); assertEquals(0, f.count("apr_quorum_information_commands"));
        actor(REQUESTER);
        assertEquals(ErrorCode.RESOURCE_CONFLICT, assertThrows(BaseException.class, () -> controller.respondToInformationRequest(f.request,
                new ApprovalDtos.InformationResponseRequest("Evidence", Map.of(), 0L), null)).getErrorCode());
        assertEquals(0, f.count("apr_quorum_information_commands"));
    }

    @Test void suppliedTypedSchemaHashMismatchFailsBeforeAnyAdditionalInformationQuery() {
        openRound(); var original = detail(); var noReads = mock(NamedParameterJdbcTemplate.class);
        var beans = new DefaultListableBeanFactory();
        var guarded = new ApprovalWorkflowQuorumInformationFacade(commands, queries, noReads, new ObjectMapper(), f.tx.getTransactionManager(),
                beans.getBeanProvider(ApprovalWorkflowQuorumAuthority.class), null, metadata, null,
                beans.getBeanProvider(ApprovalOwnerPredicateEvaluator.class), null);
        var substituted = new ApprovalDtos.RequestDetail(original.request(), original.workflowId(), original.formId(), original.payload(), original.formSchema(),
                original.timeline(), original.formVersionId(), "0".repeat(64));
        assertEquals(ErrorCode.RESOURCE_CONFLICT, assertThrows(BaseException.class, () -> guarded.detail(ApprovalRequestContext.require(), substituted)).getErrorCode());
        verifyNoInteractions(noReads); assertEquals(1, f.count("apr_quorum_information_rounds"));
    }

    @Test void changedStoredPayloadWithoutMatchingPinnedDigestCannotReceiveInformationMetadata() {
        openRound();
        f.jdbc.update("UPDATE apr_request_payloads SET payload=jsonb_set(payload,'{amount}','\"100\"'::jsonb) WHERE request_id=?", f.request);
        when(queries.requestDetail(any(), eq(f.request))).thenReturn(detail());
        assertEquals(ErrorCode.RESOURCE_CONFLICT, assertThrows(BaseException.class, () -> service.requestDetail(f.request)).getErrorCode());
        assertEquals(1, f.count("apr_quorum_information_rounds")); assertEquals(0, f.count("apr_quorum_votes"));
    }
}
