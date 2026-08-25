package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalRecoveryAuditorAssignmentPostgresTest {

    private static final long PROBE_COOLDOWN_SECONDS = 3600;
    private static final long MAXIMUM_PROBE_COOLDOWN_SECONDS = 7200;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private ApprovalRecoveryAuditorAssignmentRepository repository;
    private ApprovalIntegrationOutboxRepository integrationOutbox;
    private ApprovalOwnerPredicateEvaluator ownerPredicates;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new ApprovalRecoveryAuditorAssignmentRepository(jdbc);
        integrationOutbox = new ApprovalIntegrationOutboxRepository(jdbc);
        ownerPredicates = new ApprovalOwnerPredicateEvaluator(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
                mock(ApprovalIdentityDirectory.class));
        jdbc.update("INSERT INTO apr_tenants (tenant_id) VALUES (42)");
    }

    @Test
    void assignsCompleteEvidenceAndUnlocksRecoveryForAnIndependentOperator() {
        UUID outboxId = seed("PENDING", 100L, "FAILED");
        var target = claim("worker-a").get(0);

        assertThat(repository.markAssigned(
                target,
                "worker-a",
                assignment(300, "revision-1"))).isTrue();

        AssignmentRow row = row(outboxId);
        assertThat(row).isEqualTo(new AssignmentRow(
                "ASSIGNED", 300L, "RS_APPROVALS", "revision-1", true));
        ownerPredicates.lockAndValidate(actor(400), "OUTBOX_EVENT", outboxId, 0);
        jdbc.update("""
                UPDATE apr_integration_outbox
                   SET status = 'SENDING', locked_by = 'relay-a',
                       locked_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                 WHERE outbox_id = ?
                """, outboxId);
        integrationOutbox.markPublished(outboxId, "relay-a");
        assertThat(row(outboxId)).isEqualTo(new AssignmentRow(
                "ASSIGNED", 300L, "RS_APPROVALS", "revision-1", true));
    }

    @Test
    void unavailableOrNoCandidateRemainsRetryableAndRecoveryFailsClosed() {
        UUID outboxId = seed("PENDING", 100L, "FAILED");
        var target = claim("worker-a").get(0);

        assertThat(repository.markUnavailable(
                target, "worker-a", 3,
                PROBE_COOLDOWN_SECONDS, MAXIMUM_PROBE_COOLDOWN_SECONDS,
                "no candidate")).isTrue();

        AssignmentRow row = row(outboxId);
        assertThat(row.state()).isEqualTo("RETRY");
        assertThat(row.auditorUserId()).isNull();
        assertRecoveryUnavailable(outboxId, actor(400));
    }

    @Test
    void concurrentWorkersClaimAnOutboxEventOnlyOnce() throws Exception {
        seed("PENDING", 100L, "FAILED");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<ApprovalRecoveryAuditorAssignmentRepository.AssignmentTarget>> first =
                    executor.submit(() -> {
                        await(start);
                        return claim("worker-a");
                    });
            Future<List<ApprovalRecoveryAuditorAssignmentRepository.AssignmentTarget>> second =
                    executor.submit(() -> {
                        await(start);
                        return claim("worker-b");
                    });
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS).size()
                    + second.get(5, TimeUnit.SECONDS).size()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
                SELECT recovery_auditor_assignment_attempt_count
                  FROM apr_integration_outbox
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void assignedEvidenceIsImmutableEvenWhenAnotherWorkerRetriesAssignment() {
        UUID outboxId = seed("PENDING", 100L, "FAILED");
        var target = claim("worker-a").get(0);
        assertThat(repository.markAssigned(
                target, "worker-a", assignment(300, "revision-1"))).isTrue();

        assertThat(repository.markAssigned(
                target, "worker-a", assignment(301, "revision-2"))).isFalse();
        assertThat(claim("worker-b")).isEmpty();
        assertThat(row(outboxId)).isEqualTo(new AssignmentRow(
                "ASSIGNED", 300L, "RS_APPROVALS", "revision-1", true));
    }

    @Test
    void assignmentUsesTheExactEventManagementScopeAndRejectsCrossScopeEvidence() {
        UUID outboxId = seed("PENDING", 100L, "FAILED", "RS_TEAM_A");
        var target = claim("worker-a").getFirst();

        assertThat(target.managementResourceSetKey()).isEqualTo("RS_TEAM_A");
        assertThat(repository.markAssigned(
                target, "worker-a", assignment(
                        300, "RS_APPROVALS", "wrong-scope"))).isFalse();
        assertThat(repository.markAssigned(
                target, "worker-a", assignment(
                        300, "RS_TEAM_A", "scope-a-revision"))).isTrue();
        assertThat(row(outboxId).resourceSetKey()).isEqualTo("RS_TEAM_A");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_integration_outbox
                   SET recovery_auditor_resource_set_key = 'RS_TEAM_B'
                 WHERE outbox_id = ?
                """, outboxId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void originatorCanNeverBePersistedAsTheAssignedAuditor() {
        UUID outboxId = seed("PENDING", 100L, "FAILED");
        var target = claim("worker-a").get(0);

        assertThat(repository.markAssigned(
                target, "worker-a", assignment(100, "revision-1"))).isFalse();
        assertThat(repository.markUnavailable(
                target, "worker-a", 3,
                PROBE_COOLDOWN_SECONDS, MAXIMUM_PROBE_COOLDOWN_SECONDS,
                "originator selected")).isTrue();

        assertThat(row(outboxId).auditorUserId()).isNull();
        assertRecoveryUnavailable(outboxId, actor(400));
    }

    @Test
    void legacyNullEvidenceIsNeverClaimedAndAlwaysFailsRecoveryClosed() {
        UUID outboxId = seed("LEGACY_UNASSIGNED", null, "FAILED");

        assertThat(claim("worker-a")).isEmpty();
        assertRecoveryUnavailable(outboxId, actor(400));
    }

    @Test
    void publishedDeliveryTerminatesUnneededAssignmentWithoutFutureProbes() {
        UUID outboxId = seed("PENDING", 100L, "SENDING");
        jdbc.update("""
                UPDATE apr_integration_outbox
                   SET locked_by = 'relay-a',
                       locked_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                 WHERE outbox_id = ?
                """, outboxId);

        integrationOutbox.markPublished(outboxId, "relay-a");

        assertThat(row(outboxId).state()).isEqualTo("NOT_REQUIRED");
        assertThat(claim("worker-a")).isEmpty();
    }

    @Test
    void retryImmediatelyBeforeTheMaximumConvergesToUnclaimableExhausted() {
        UUID outboxId = seed("RETRY", 100L, "FAILED");
        jdbc.update("""
                UPDATE apr_integration_outbox
                   SET recovery_auditor_assignment_attempt_count = 2,
                       recovery_auditor_assignment_available_at = CURRENT_TIMESTAMP
                 WHERE outbox_id = ?
                """, outboxId);

        var terminalTarget = claim("worker-a").get(0);
        assertThat(terminalTarget.attemptCount()).isEqualTo(3);
        assertThat(repository.markUnavailable(
                terminalTarget, "worker-a", 3,
                PROBE_COOLDOWN_SECONDS, MAXIMUM_PROBE_COOLDOWN_SECONDS,
                "no candidate")).isTrue();

        AssignmentRow exhausted = row(outboxId);
        assertThat(exhausted.state()).isEqualTo("EXHAUSTED");
        assertThat(exhausted.auditorUserId()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT recovery_auditor_assignment_next_probe_at
                           >= recovery_auditor_assignment_exhausted_at
                              + INTERVAL '3599 seconds'
                  FROM apr_integration_outbox
                 WHERE outbox_id = ?
                """, Boolean.class, outboxId)).isTrue();
        assertThat(claim("worker-b")).isEmpty();
        assertRecoveryUnavailable(outboxId, actor(400));
    }

    @Test
    void cooldownOpensANewBoundedEpochAndPreservesAuditableConvergence() {
        UUID outboxId = seed("RETRY", 100L, "FAILED");
        jdbc.update("""
                UPDATE apr_integration_outbox
                   SET recovery_auditor_assignment_attempt_count = 2,
                       recovery_auditor_assignment_available_at = CURRENT_TIMESTAMP
                 WHERE outbox_id = ?
                """, outboxId);
        var terminalTarget = claim("worker-a").get(0);
        assertThat(repository.markUnavailable(
                terminalTarget, "worker-a", 3,
                PROBE_COOLDOWN_SECONDS, MAXIMUM_PROBE_COOLDOWN_SECONDS,
                "temporary outage")).isTrue();
        assertThat(claim("worker-b")).isEmpty();

        jdbc.update("""
                UPDATE apr_integration_outbox
                   SET recovery_auditor_assignment_exhausted_at =
                           CURRENT_TIMESTAMP - INTERVAL '2 hours',
                       recovery_auditor_assignment_next_probe_at =
                           CURRENT_TIMESTAMP - INTERVAL '1 hour'
                 WHERE outbox_id = ?
                """, outboxId);
        var probeTarget = claim("worker-b").get(0);

        assertThat(probeTarget.assignmentEpoch()).isEqualTo(2);
        assertThat(probeTarget.attemptCount()).isEqualTo(1);
        assertThat(repository.markAssigned(
                probeTarget, "worker-b", assignment(300, "revision-2"))).isTrue();
        assertThat(row(outboxId).state()).isEqualTo("ASSIGNED");
        assertThat(assignmentEvents(outboxId)).containsExactly(
                "EPOCH_EXHAUSTED:1:3:AUTH_RESOLUTION_UNAVAILABLE",
                "AUTOMATIC_PROBE_EPOCH_OPENED:2:0:COOLDOWN_ELAPSED");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_recovery_auditor_assignment_events
                   SET reason_code = 'TAMPERED'
                 WHERE outbox_id = ?
                """, outboxId)).isInstanceOf(DataAccessException.class);
    }

    private List<ApprovalRecoveryAuditorAssignmentRepository.AssignmentTarget> claim(
            String workerId) {
        return repository.claim(
                10, workerId, 30, 3, PROBE_COOLDOWN_SECONDS);
    }

    private List<String> assignmentEvents(UUID outboxId) {
        return jdbc.query("""
                SELECT event_type, assignment_epoch, attempt_count, reason_code
                  FROM apr_recovery_auditor_assignment_events
                 WHERE outbox_id = ?
                 ORDER BY occurred_at, assignment_event_id
                """, (result, ignored) -> result.getString("event_type")
                + ":" + result.getInt("assignment_epoch")
                + ":" + result.getInt("attempt_count")
                + ":" + result.getString("reason_code"), outboxId);
    }

    private UUID seed(String assignmentState, Long originatorUserId, String status) {
        return seed(assignmentState, originatorUserId, status, "RS_APPROVALS");
    }

    private UUID seed(
            String assignmentState,
            Long originatorUserId,
            String status,
            String managementScope) {
        UUID outboxId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_integration_outbox (
                    outbox_id, event_id, tenant_id, event_type, payload,
                    payload_sha256, status, event_originator_user_id,
                    recovery_auditor_assignment_state,
                    management_resource_set_key)
                VALUES (?, ?, 42, 'approval.request.submitted', '{}'::jsonb,
                        ?, ?, ?, ?, ?)
                """, outboxId, UUID.randomUUID(), "a".repeat(64), status,
                originatorUserId, assignmentState, managementScope);
        return outboxId;
    }

    private AssignmentRow row(UUID outboxId) {
        return jdbc.queryForObject("""
                SELECT recovery_auditor_assignment_state, assigned_auditor_user_id,
                       recovery_auditor_resource_set_key,
                       recovery_auditor_assignment_revision,
                       recovery_auditor_assigned_at IS NOT NULL AS has_assigned_at
                  FROM apr_integration_outbox
                 WHERE outbox_id = ?
                """, (result, ignored) -> new AssignmentRow(
                result.getString("recovery_auditor_assignment_state"),
                (Long) result.getObject("assigned_auditor_user_id"),
                result.getString("recovery_auditor_resource_set_key"),
                result.getString("recovery_auditor_assignment_revision"),
                result.getBoolean("has_assigned_at")), outboxId);
    }

    private ApprovalRecoveryAuditorResolver.Assignment assignment(
            long selectedUserId, String revision) {
        return assignment(selectedUserId, "RS_APPROVALS", revision);
    }

    private ApprovalRecoveryAuditorResolver.Assignment assignment(
            long selectedUserId,
            String resourceSetKey,
            String revision) {
        return new ApprovalRecoveryAuditorResolver.Assignment(
                selectedUserId, resourceSetKey, revision);
    }

    private ApprovalRequestContext.Actor actor(long userId) {
        return new ApprovalRequestContext.Actor(
                userId, 42L, null, "Operator", Set.of(), Set.of());
    }

    private void assertRecoveryUnavailable(
            UUID outboxId, ApprovalRequestContext.Actor actor) {
        assertThatThrownBy(() -> ownerPredicates.lockAndValidate(
                actor, "OUTBOX_EVENT", outboxId, 0))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record AssignmentRow(
            String state,
            Long auditorUserId,
            String resourceSetKey,
            String revision,
            boolean hasAssignedAt) {
    }
}
