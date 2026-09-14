package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumPostgresFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowQuorumWiringPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    WiringFixture f;
    ApprovalCommandRepository commands;
    ApprovalWorkflowQuorumFacade facade;
    ApprovalRequestContext.Actor actor;

    @BeforeEach @SuppressWarnings("unchecked") void initialize() {
        f = new WiringFixture();
        f.initialize(POSTGRES);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var jdbc = new NamedParameterJdbcTemplate(f.jdbc);
        commands = new ApprovalCommandRepository(jdbc, mapper);
        // Disposable current-owner authority; no other identity or provider is admitted.
        var permissions = Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:CREATE", "ACTION.APPROVAL_REQUEST:UPDATE");
        var identities = mock(ApprovalIdentityDirectory.class);
        when(identities.require(TENANT, REQUESTER)).thenReturn(new ApprovalIdentityDirectory.Subject(
                TENANT, REQUESTER, null, person(REQUESTER), "Requester", null, null, "ACTIVE",
                List.of("APPROVAL_OPERATOR"), List.copyOf(permissions)));
        ApprovalRequestContext.set(REQUESTER, TENANT, person(REQUESTER), "Requester", Set.of("APPROVAL_OPERATOR"), permissions);
        ApprovalAttachmentLifecycleTestWiring.bindDefault(commands, jdbc, identities, mapper);
        var source = mock(ObjectProvider.class);
        when(source.getIfAvailable()).thenReturn(f);
        facade = new ApprovalWorkflowQuorumFacade(commands, jdbc, mapper, f.tx.getTransactionManager(), source,
                new AuditOutboxRecorder(jdbc, mapper, "dwp-approval-server", "test", "test"));
        actor = new ApprovalRequestContext.Actor(REQUESTER, TENANT, person(REQUESTER), "Requester", Set.of(),
                Set.of("ACTION.APPROVAL_REQUEST:UPDATE"));
    }

    @AfterEach void clearCurrentActor() { ApprovalRequestContext.clear(); }

    private final class WiringFixture extends ApprovalWorkflowQuorumPostgresFixture {
        @Override void prepareDraft(ApprovalWorkflowQuorumDefinition definition) {
            super.prepareDraft(definition);
            captureCurrentPayload("DRAFT_CREATED");
        }

        @Override void bindTypedForm(ApprovalWorkflowQuorumDefinition definition) {
            super.bindTypedForm(definition);
            jdbc.update("UPDATE apr_request_payloads SET schema_version=schema_version+1 WHERE request_id=?", request);
            captureCurrentPayload("DRAFT_UPDATED");
        }

        private void captureCurrentPayload(String changeType) {
            // Keep the native immutable revision hook and actual canonical digest in this disposable fixture.
            var mapper = new ObjectMapper();
            final java.util.Map<String, Object> payload;
            try {
                payload = mapper.readValue(jdbc.queryForObject("SELECT payload::text FROM apr_request_payloads WHERE request_id=?",
                        String.class, request), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
            var canonical = ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(payload));
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE apr_request_payloads SET payload=?::jsonb,payload_sha256=? WHERE request_id=?",
                        canonical, ApprovalFormSchemaV2Canonical.sha256(canonical), request);
                commands.appendPayloadRevision(actor, request, changeType, "quorum-wiring-fixture", "Disposable current payload");
            });
        }

        String payloadHash() {
            return jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?", String.class, request);
        }
    }

    static ApprovalWorkflowQuorumDefinition one(Mode mode, Integer value) {
        return ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(new ApprovalWorkflowQuorumDefinition.Stage(
                "FINANCE", "Finance", "FINANCE_REVIEWER", new Rule(mode, value), 15, List.of())));
    }

    @ParameterizedTest @EnumSource(Mode.class) void ownerSubmitCreatesRealSealedTasksNotLegacyAny(Mode mode) {
        var definition = one(mode, mode == Mode.COUNT ? Integer.valueOf(2) : mode == Mode.PERCENT ? Integer.valueOf(67) : null);
        f.prepareDraft(definition);
        facade.submit(actor, f.request, 0, "quorum-submit");
        assertEquals("IN_REVIEW", f.jdbc.queryForObject("SELECT status FROM apr_requests WHERE request_id=?", String.class, f.request));
        assertEquals(3, f.count("apr_quorum_candidates"));
        assertEquals(3, f.jdbc.queryForObject("SELECT count(*) FROM apr_tasks WHERE status='CLAIMED' AND assignee_user_id IS NOT NULL", Integer.class));
        assertEquals(mode.name(), f.jdbc.queryForObject("SELECT approval_mode FROM apr_steps", String.class));
        assertEquals(mode == Mode.ANY ? 1 : mode == Mode.COUNT ? 2 : 3,
                f.jdbc.queryForObject("SELECT threshold FROM apr_quorum_stage_runtime", Integer.class));
    }

    @Test void legacySubmitCannotExecuteTaggedDefinitionAsAny() {
        f.prepareDraft(one(Mode.ALL, null));
        assertThrows(BaseException.class, () -> f.tx.executeWithoutResult(tx -> commands.submit(actor, f.request, 0, "legacy")));
        assertEquals(0, f.count("apr_steps"));
        assertEquals("DRAFT", f.jdbc.queryForObject("SELECT status FROM apr_requests", String.class));
    }

    @Test void unavailableCandidateSourceRollsBackOwnerSubmitCas() {
        f.prepareDraft(one(Mode.COUNT, 2));
        f.unknown = true;
        assertThrows(BaseException.class, () -> facade.submit(actor, f.request, 0, "unknown"));
        assertEquals("DRAFT", f.jdbc.queryForObject("SELECT status FROM apr_requests", String.class));
        assertEquals(0, f.count("apr_quorum_stage_runtime"));
        assertEquals(0, f.count("apr_integration_outbox"));
    }

    @Test void readonlyPreflightUsesOwnerFormAndRouteValidationWithoutWrites() {
        f.prepareDraft(one(Mode.COUNT, 2));
        var result = facade.simulate(actor, f.request, input());
        assertTrue(result.readOnly());
        assertEquals(0, f.count("apr_steps"));
        assertEquals(0, f.count("apr_quorum_votes"));
        assertEquals(0, f.count("apr_request_events"));
        assertEquals(0, f.count("sys_audit_outbox"));
        f.jdbc.update("UPDATE apr_requests SET title='' WHERE request_id=?", f.request);
        assertThrows(BaseException.class, () -> facade.simulate(actor, f.request, input()));
        assertEquals(0, f.count("apr_request_events"));
    }

    @Test void readonlyPreflightCannotBorrowAnotherRequestersIdentity() {
        f.prepareDraft(one(Mode.ANY, null));
        var other = new ApprovalRequestContext.Actor(100L, TENANT, person(100), "Other", Set.of(), Set.of());
        assertThrows(BaseException.class, () -> facade.simulate(other, f.request, input()));
        assertEquals(0, f.count("apr_quorum_stage_runtime"));
    }

    @Test void ownerWithdrawalCancelsClaimedLeasesAndFrozenStagesAtomically() {
        f.prepareDraft(one(Mode.ALL, null));
        facade.submit(actor, f.request, 0, "submit");
        f.dueTimers();
        var leases = f.sla.claim("worker-old", 30, 10);
        assertEquals(2, leases.size());
        f.tx.executeWithoutResult(tx -> {
            facade.cancel(actor, f.request);
            commands.withdraw(actor, f.request, 1, "withdraw");
        });
        assertEquals("WITHDRAWN", f.jdbc.queryForObject("SELECT status FROM apr_requests", String.class));
        assertEquals("CANCELLED", f.status("FINANCE"));
        assertEquals(0, f.sla.claim("worker-new", 30, 10).size());
        long count = f.count("apr_integration_outbox");
        for (var lease : leases) assertFalse(f.sla.finish(lease));
        assertEquals(count, f.count("apr_integration_outbox"));
    }

    private ApprovalWorkflowQuorumSimulation.Input input() {
        return new ApprovalWorkflowQuorumSimulation.Input(f.pins, REQUESTER, person(REQUESTER), 1, f.payloadHash(), List.of());
    }

    @Test void clientPayloadAndStageEvidenceMustMatchBeforeAnyVoteWrite() {
        f.prepareDraft(one(Mode.COUNT, 2)); facade.submit(actor, f.request, 0, "submit");
        var command = f.command("FINANCE", 101, 101, Decision.APPROVE);
        var voter = new ApprovalRequestContext.Actor(101L, TENANT, person(101), "Voter", Set.of("FINANCE_REVIEWER"),
                Set.of("ACTION.APPROVAL_TASK:VIEW", "ACTION.APPROVAL_TASK:APPROVE"));
        var task = mock(ApprovalQueryRepository.TaskAccess.class); var summary = mock(ApprovalDtos.TaskSummary.class);
        when(task.summary()).thenReturn(summary); when(summary.requestId()).thenReturn(f.request); when(summary.taskId()).thenReturn(command.taskId());
        var decision = new ApprovalDtos.DecisionRequest("APPROVE", "", command.expectedTaskVersion());
        assertEquals(com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT,
                assertThrows(BaseException.class, () -> facade.decide(voter, task, decision, null)).getErrorCode());
        assertThrows(BaseException.class, () -> facade.decide(voter, task, decision,
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1, command.expectedStageVersion(), f.pins, 2, f.payloadHash())));
        assertThrows(BaseException.class, () -> facade.decide(voter, task, decision,
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1, command.expectedStageVersion(), f.pins, 1, "b".repeat(64))));
        assertThrows(BaseException.class, () -> facade.decide(voter, task, decision,
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1, command.expectedStageVersion() + 1, f.pins, 1, f.payloadHash())));
        assertEquals(0, f.count("apr_quorum_votes"));
        assertEquals("IN_REVIEW", facade.decide(voter, task, decision,
                new ApprovalWorkflowQuorumFacade.ExpectedVote(1, command.expectedStageVersion(), f.pins, 1, f.payloadHash())).requestStatus());
        assertEquals(1, f.count("apr_quorum_votes"));
    }

    @Test void fullOwnerProjectionExposesOnlyOpaqueFrozenSeatAndRedactedProjectionHasNoQuorum() {
        f.prepareDraft(one(Mode.ALL, null)); facade.submit(actor, f.request, 0, "submit");
        var command = f.command("FINANCE", 101, 101, Decision.APPROVE);
        var summary = mock(ApprovalDtos.TaskSummary.class); when(summary.requestId()).thenReturn(f.request);
        when(summary.taskId()).thenReturn(command.taskId());
        var full = new ApprovalDtos.TaskDetail(summary, java.util.Map.of(), java.util.Map.of(), List.of(), false, true, false,
                new ApprovalDtos.ContentAccess("FULL", "CURRENT_AUTHORITY_VERIFIED", java.time.Instant.now()));
        var detail = facade.detail(actor, full);
        assertEquals(person(101), detail.quorum().principalPersonPublicId());
        assertEquals(command.expectedStageVersion(), detail.quorum().stageVersion());
        assertEquals(f.pins.workflowDefinitionSha256(), detail.quorum().pins().workflowDefinitionSha256());
        var redacted = new ApprovalDtos.TaskDetail(summary, java.util.Map.of(), java.util.Map.of(), List.of(), false, false, false,
                new ApprovalDtos.ContentAccess("REDACTED", "CURRENT_AUTHORITY_UNAVAILABLE", java.time.Instant.now()));
        assertSame(redacted, facade.detail(actor, redacted)); assertNull(redacted.quorum());
    }

    @Test void actualTypedOwnerSubmitMatchesConditionalStageRouteWithoutLegacyNumericTextCoercion() {
        var definition = ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(
                new ApprovalWorkflowQuorumDefinition.Stage("FINANCE", "Finance", "FINANCE_REVIEWER", new Rule(Mode.ANY, null), 15,
                        List.of(), java.util.Map.of("all", List.of(java.util.Map.of("field", "amount", "operator", "EQ", "value", "20.00")))),
                new ApprovalWorkflowQuorumDefinition.Stage("EXTRA", "Extra", "FINANCE_REVIEWER", new Rule(Mode.ALL, null), 15,
                        List.of(), java.util.Map.of("all", List.of(java.util.Map.of("field", "amount", "operator", "GTE", "value", 100))))));
        f.prepareDraft(definition); f.bindTypedForm(definition);
        facade.submit(actor, f.request, 0, "typed-conditional-submit");
        assertEquals("IN_PROGRESS", f.status("FINANCE")); assertEquals("SKIPPED", f.status("EXTRA"));
        assertEquals(3, f.count("apr_quorum_candidates")); assertEquals(0, f.count("apr_quorum_votes"));
    }

    @Test @SuppressWarnings("unchecked") void candidateSourceOutageDoesNotPreventCurrentOwnerWithdrawalAndNonownerCannotCancel() {
        f.prepareDraft(one(Mode.ALL, null)); facade.submit(actor, f.request, 0, "submit");
        var named = new NamedParameterJdbcTemplate(f.jdbc); var mapper = new ObjectMapper().findAndRegisterModules();
        var missing = mock(ObjectProvider.class);
        var unavailable = new ApprovalWorkflowQuorumFacade(commands, named, mapper, f.tx.getTransactionManager(), missing,
                new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test"));
        var other = new ApprovalRequestContext.Actor(100L, TENANT, person(100), "Other", Set.of(), Set.of());
        assertThrows(BaseException.class, () -> unavailable.cancel(other, f.request));
        assertEquals("IN_PROGRESS", f.status("FINANCE"));
        f.tx.executeWithoutResult(status -> { unavailable.cancel(actor, f.request); commands.withdraw(actor, f.request, 1, "withdraw"); });
        assertEquals("CANCELLED", f.status("FINANCE"));
        assertEquals("WITHDRAWN", f.jdbc.queryForObject("SELECT status FROM apr_requests", String.class));
    }
}
