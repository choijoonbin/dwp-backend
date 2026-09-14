package com.dwp.services.approval.security;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.*;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual INFO engine consumes the production Form normalizer; quorum authority remains an explicit domain fixture. */
@Testcontainers(disabledWithoutDocker = true)
class ApprovalWorkflowQuorumInformationSourcePostgresTest extends ApprovalFormUserCommandPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "apr14-info-source");
    private ApprovalWorkflowQuorumInformationRuntime info;
    private ApprovalFormPayloadNormalization normalizer;
    private UUID requestId;

    @BeforeEach void prepareInformationRound() {
        initializeUserCommands(POSTGRES);
        requestId = createUserRequest("info-source-create").requestId();
        var requester = ApprovalRequestContext.require();
        ApprovalRequestContext.set(99L, 42L, opaque(99), "Owner", requester.roles(), requester.permissions());
        when(identities.require(42, 99)).thenReturn(new ApprovalIdentityDirectory.Subject(42L, 99L, null,
                opaque(99), "Owner", null, null, "ACTIVE", List.copyOf(requester.roles()), List.copyOf(requester.permissions())));
        var definition = ApprovalWorkflowQuorumDefinition.fromStages(60, List.of(new ApprovalWorkflowQuorumDefinition.Stage(
                "FINANCE", "Finance", "FINANCE_REVIEWER", new Rule(Mode.ALL, null), 15, List.of())));
        UUID workflowVersion = UUID.randomUUID();
        jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,"
                + "definition_sha256,lifecycle_state,published_at,published_by) VALUES(?,42,?,90,?::jsonb,?,'PUBLISHED',now(),100)",
                workflowVersion, workflowId, definition.canonicalJson(), definition.sha256());
        jdbc.update("UPDATE apr_requests SET workflow_version_id=?,requester_person_public_id=?,status='IN_REVIEW',"
                + "submitted_at=now(),due_at=now()+interval '60 minutes' WHERE request_id=?", workflowVersion, opaque(99), requestId);
        var mapper = new ObjectMapper().findAndRegisterModules(); var named = new NamedParameterJdbcTemplate(jdbc);
        var audit = new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test");
        // This fixture does not claim to prove the separate signed Candidate/VOTER transport.
        var authority = new ApprovalWorkflowQuorumAuthority() {
            public CandidatePool candidates(Pins pins, UUID request, ApprovalWorkflowQuorumDefinition.Stage stage, Instant now) {
                return new CandidatePool(42, pins.workflowVersionId(), stage.candidateRole(), List.of(subject(100), subject(101), subject(102)),
                        "domain-fixture", true, false, now, now.plusSeconds(60));
            }
            public CurrentAuthority voter(Snapshot snapshot, long actor, long principal, Instant now) {
                return new CurrentAuthority(AccessMode.NORMAL, "domain-fixture", now, now.plusSeconds(60), subject(actor), subject(principal), null);
            }
        };
        var runtime = new ApprovalWorkflowQuorumRuntime(named, mapper, transaction, authority, audit);
        Pins pins = runtime.canonicalPins(42, requestId, definition); runtime.start(42, requestId, pins, definition);
        info = new ApprovalWorkflowQuorumInformationRuntime(named, mapper, transaction, authority, audit);
        var seat = jdbc.queryForMap("SELECT candidate.task_id,task.version FROM apr_quorum_candidates candidate "
                + "JOIN apr_tasks task ON task.task_id=candidate.task_id WHERE candidate.request_id=? AND principal_user_id=100", requestId);
        info.request(new ApprovalRequestContext.Actor(100L, 42L, opaque(100), "Reviewer", Set.of("FINANCE_REVIEWER"), Set.of()),
                new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(requestId, (UUID) seat.get("task_id"),
                    ((Number) seat.get("version")).longValue(), version(), new ApprovalWorkflowQuorumFacade.ExpectedVote(1, 1, pins, 1, hash()),
                    "info-source-request", "Please attach evidence"));
        var formNormalization = ReflectionTestUtils.getField(commands, "formNormalization");
        var references = (ApprovalFormReferenceNormalizer) ReflectionTestUtils.getField(formNormalization, "normalizer");
        normalizer = new ApprovalFormPayloadNormalizationConfig().approvalFormPayloadNormalization(commands, references, mapper);
        action("request-information-response.action", requestId);
        http.removeHeader("Idempotency-Key"); http.addHeader("Idempotency-Key", "info-source-response"); sourceCalls.set(0);
    }

    @AfterEach void cleanContexts() { clear(); }
    private long version() { return jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?", Long.class, requestId); }
    private String hash() { return jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?", String.class, requestId).strip(); }
    private ApprovalWorkflowQuorumInformationRuntime.Receipt reply(Map<String, Object> patch) {
        return info.reply(ApprovalRequestContext.require(), new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(
                requestId, version(), 1, "info-source-response", "Evidence attached", patch), normalizer);
    }
    private static UUID opaque(long user) { return UUID.nameUUIDFromBytes(("info-source-person:" + user).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static Subject subject(long user) { return new Subject(42, user, opaque(user), IdentityPlane.TENANT, true, Set.of("FINANCE_REVIEWER"), true); }

    @Test void storedComputedSixIsStrippedAndActualBeanRecalculatesEightBeforeNewGeneration() {
        assertThat(jdbc.queryForObject("SELECT payload->>'total' FROM apr_request_payloads WHERE request_id=?", String.class, requestId)).isEqualTo("6");
        var result = reply(Map.of("units", "4")); assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.payloadRevision()).isEqualTo(2); assertThat(result.generation()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payload->>'total' FROM apr_request_payloads WHERE request_id=?", String.class, requestId)).isEqualTo("8");
        assertThat(sourceCalls).hasValue(1); assertThat(lastPins.targetRequestId()).isEqualTo(requestId);
        assertThat(lastAuthority.routeContractKey()).isEqualTo("route.approvals.work.request-information-response.action");
    }

    @Test void clientComputedTamperCannotChangeRoundPayloadEventsOrOutbox() {
        var before = databaseState(); assertThatThrownBy(() -> reply(Map.of("units", "4", "total", "666"))).isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before); assertThat(sourceCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = SourceResult.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void actualVisibleUserSourceFailureNeverFinalizesPayloadOrGeneration(SourceResult source) {
        sourceResult = source; var before = databaseState();
        if (source == SourceResult.UNAVAILABLE) assertThat(reply(Map.of("units", "4")).status()).isEqualTo("UNKNOWN");
        else assertThatThrownBy(() -> reply(Map.of("units", "4"))).isInstanceOf(BaseException.class);
        assertThat(databaseState()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT MAX(generation) FROM apr_quorum_stage_runtime WHERE request_id=?", Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM apr_quorum_information_rounds WHERE request_id=?", String.class, requestId)).isEqualTo("OPEN");
    }

    @Test void hiddenUserDoesNotBorrowSourceViewAndCalculatedDependencyStillRecomputes() {
        sourcePermissions(false); var actor = ApprovalRequestContext.require();
        ApprovalRequestContext.set(99L, 42L, opaque(99), "Owner", actor.roles(), actor.permissions());
        var result = reply(Map.of("mode", "OTHER", "units", "4")); assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(sourceCalls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload,'reviewer') FROM apr_request_payloads WHERE request_id=?", Boolean.class, requestId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT payload->>'total' FROM apr_request_payloads WHERE request_id=?", String.class, requestId)).isEqualTo("8");
    }
}
