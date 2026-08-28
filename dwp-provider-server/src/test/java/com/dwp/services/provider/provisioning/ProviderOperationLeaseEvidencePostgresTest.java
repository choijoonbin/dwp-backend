package com.dwp.services.provider.provisioning;

import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.ProviderOperationsRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderOperationLeaseEvidencePostgresTest {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ProviderOperationLeaseRepository leases;
    private ProviderOperationEvidenceRepository evidence;
    private ProviderOperationProjectionCoordinator projections;

    @BeforeEach
    void migrateDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        leases = new ProviderOperationLeaseRepository(jdbc);
        evidence = new ProviderOperationEvidenceRepository(jdbc);
        projections = new ProviderOperationProjectionCoordinator(transaction, leases, evidence);
    }

    @Test
    void reclaimedLeaseAbandonsStaleEvidenceAndFencesTheOldWorker() {
        LeasedOperation stale = leasedOperation("TENANT_ACTIVATE");
        UUID operationId = stale.operationId();
        UUID staleToken = stale.leaseToken();
        long stepId = runningStep(stale, 1);
        expire(stale);

        UUID recoveredToken = leases.claim(
                operationId, version(operationId), true, "recovery-worker", LEASE_DURATION);
        transaction.executeWithoutResult(status -> evidence.abandonRunning(
                operationId, recoveredToken, LEASE_DURATION));

        assertThat(attempt(stepId, 1))
                .containsEntry("lifecycle_state", "ABANDONED")
                .containsEntry("error_code", "OPERATION_LEASE_EXPIRED");
        assertThat(step(stepId))
                .containsEntry("lifecycle_state", "FAILED")
                .containsEntry("attempt_count", 1);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                evidence.succeedAttempt(
                        operationId, staleToken, LEASE_DURATION, stepId, 1,
                        "stale-result", "{\"status\":\"stale\"}")))
                .isInstanceOf(ProviderOperationLeaseRepository.OperationLeaseLostException.class);
        assertThat(attempt(stepId, 1)).containsEntry("lifecycle_state", "ABANDONED");

        int nextAttempt = transaction.execute(status -> evidence.startAttempt(
                operationId, recoveredToken, LEASE_DURATION, stepId, "a".repeat(64)));
        assertThat(nextAttempt).isEqualTo(2);
        transaction.executeWithoutResult(status -> evidence.succeedAttempt(
                operationId, recoveredToken, LEASE_DURATION, stepId, nextAttempt,
                "recovered-result", "{\"status\":\"ready\"}"));
        transaction.executeWithoutResult(status -> leases.complete(operationId, recoveredToken));

        assertThat(attempt(stepId, 2)).containsEntry("lifecycle_state", "SUCCEEDED");
        assertThat(step(stepId))
                .containsEntry("lifecycle_state", "SUCCEEDED")
                .containsEntry("attempt_count", 2);
        assertThat(operation(operationId))
                .containsEntry("lifecycle_state", "SUCCEEDED")
                .containsEntry("lease_owner", null)
                .containsEntry("lease_token", null)
                .containsEntry("lease_expires_at", null);
    }

    @Test
    void activeLeaseRejectsRetryAndExpiredLeaseHasOnlyOneConcurrentWinner() throws Exception {
        LeasedOperation active = leasedOperation("TENANT_ACTIVATE");
        UUID activeOperation = active.operationId();
        assertThatThrownBy(() -> leases.claim(
                activeOperation, version(activeOperation), true,
                "competing-worker", LEASE_DURATION))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("already executing");

        LeasedOperation expired = leasedOperation("TENANT_ACTIVATE");
        UUID expiredOperation = expired.operationId();
        expire(expired);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() ->
                    concurrentClaim(expiredOperation, "worker-a", ready, start));
            Future<Boolean> second = executor.submit(() ->
                    concurrentClaim(expiredOperation, "worker-b", ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            assertThat(operation(expiredOperation))
                    .containsEntry("lifecycle_state", "EXECUTING")
                    .containsEntry("version", 2L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void partialTerminalStateAlwaysClearsItsLease() {
        UUID operationId = partialOperation();
        UUID token = leases.claim(operationId, 0L, true, "partial-worker", LEASE_DURATION);

        transaction.executeWithoutResult(status -> leases.markPartial(
                operationId, token, "SAFE_FAILURE", "Safe bounded failure evidence."));

        assertThat(operation(operationId))
                .containsEntry("lifecycle_state", "PARTIAL")
                .containsEntry("failure_code", "SAFE_FAILURE")
                .containsEntry("failure_message", "Safe bounded failure evidence.")
                .containsEntry("lease_owner", null)
                .containsEntry("lease_token", null)
                .containsEntry("lease_expires_at", null);
    }

    @Test
    void reclaimedWorkerProjectionCannotBeOverwrittenByTheExpiredWorker() {
        LeasedOperation stale = leasedOperation("TENANT_ONBOARD");
        UUID operationId = stale.operationId();
        UUID staleToken = stale.leaseToken();
        UUID tenantId = projectionTenant(stale);
        long stepId = runningStep(stale, 1);
        expire(stale);
        UUID recoveredToken = leases.claim(
                operationId, version(operationId), true,
                "recovered-success", LEASE_DURATION);
        transaction.executeWithoutResult(status -> evidence.abandonRunning(
                operationId, recoveredToken, LEASE_DURATION));
        int recoveredAttempt = transaction.execute(status -> evidence.startAttempt(
                operationId, recoveredToken, LEASE_DURATION, stepId, "e".repeat(64)));

        projections.succeed(
                operationId, recoveredToken, LEASE_DURATION, stepId, recoveredAttempt,
                () -> readyProjection(tenantId));

        assertThatThrownBy(() -> projections.fail(
                operationId, staleToken, LEASE_DURATION, stepId, 1,
                "STALE_FAILURE", "A stale worker must be fenced.",
                () -> failedProjection(tenantId), () -> audit(operationId, "FAILED")))
                .isInstanceOf(ProviderOperationLeaseRepository.OperationLeaseLostException.class);
        assertProjection(tenantId, "ACTIVE", "READY", "READY");

        LeasedOperation staleFailure = leasedOperation("TENANT_ONBOARD");
        UUID failureOperation = staleFailure.operationId();
        UUID staleFailureToken = staleFailure.leaseToken();
        UUID failureTenant = projectionTenant(staleFailure);
        long failureStep = runningStep(staleFailure, 1);
        expire(staleFailure);
        UUID recoveredFailureToken = leases.claim(
                failureOperation, version(failureOperation), true,
                "recovered-failure", LEASE_DURATION);
        transaction.executeWithoutResult(status -> evidence.abandonRunning(
                failureOperation, recoveredFailureToken, LEASE_DURATION));
        int failureAttempt = transaction.execute(status -> evidence.startAttempt(
                failureOperation, recoveredFailureToken, LEASE_DURATION,
                failureStep, "f".repeat(64)));

        projections.fail(
                failureOperation, recoveredFailureToken, LEASE_DURATION,
                failureStep, failureAttempt, "SAFE_FAILURE", "Safe failure.",
                () -> failedProjection(failureTenant), () -> audit(failureOperation, "FAILED"));

        assertThatThrownBy(() -> projections.succeed(
                failureOperation, staleFailureToken, LEASE_DURATION, failureStep, 1,
                () -> readyProjection(failureTenant)))
                .isInstanceOf(ProviderOperationLeaseRepository.OperationLeaseLostException.class);
        assertProjection(failureTenant, "PROVISIONING", "FAILED", "FAILED");
        assertThat(operation(failureOperation)).containsEntry("lifecycle_state", "PARTIAL");
    }

    @Test
    void scheduleProjectionRollsBackWhenItsEvidenceCannotBePersistedAndThenRetries() {
        LeasedOperation operation = leasedOperation("TENANT_ACTIVATE");
        UUID token = operation.leaseToken();
        UUID operationId = operation.operationId();
        long stepId = runningStep(operation, 1);
        UUID maintenanceId = draftMaintenance(operationId);
        ProviderOperationsRepository operations = new ProviderOperationsRepository(jdbc);

        assertThatThrownBy(() -> projections.succeed(
                operationId, token, LEASE_DURATION, stepId, 2,
                () -> scheduleProjection(operations, operationId)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("evidence changed");
        assertThat(maintenanceState(maintenanceId)).isEqualTo("DRAFT");
        assertThat(step(stepId)).containsEntry("lifecycle_state", "RUNNING");

        projections.succeed(
                operationId, token, LEASE_DURATION, stepId, 1,
                () -> scheduleProjection(operations, operationId));
        assertThat(maintenanceState(maintenanceId)).isEqualTo("SCHEDULED");
        assertThat(step(stepId)).containsEntry("lifecycle_state", "SUCCEEDED");
    }

    @Test
    void terminalAuditAndLeaseReleaseCommitOrRollBackTogether() {
        LeasedOperation success = leasedOperation("TENANT_ONBOARD");
        UUID successToken = success.leaseToken();
        UUID successOperation = success.operationId();
        projectionTenant(success);

        assertThatThrownBy(() -> projections.complete(
                successOperation, successToken, LEASE_DURATION,
                () -> audit(successOperation, "INVALID_OUTCOME")))
                .isInstanceOf(RuntimeException.class);
        assertThat(operation(successOperation)).containsEntry("lifecycle_state", "EXECUTING");
        assertThat(auditCount(successOperation)).isZero();

        projections.complete(
                successOperation, successToken, LEASE_DURATION,
                () -> audit(successOperation, "SUCCESS"));
        assertThat(operation(successOperation))
                .containsEntry("lifecycle_state", "SUCCEEDED")
                .containsEntry("lease_token", null);
        assertThat(auditCount(successOperation)).isEqualTo(1);

        LeasedOperation failure = leasedOperation("TENANT_ONBOARD");
        UUID failureToken = failure.leaseToken();
        UUID failureOperation = failure.operationId();
        UUID tenantId = projectionTenant(failure);
        long stepId = runningStep(failure, 1);
        assertThatThrownBy(() -> projections.fail(
                failureOperation, failureToken, LEASE_DURATION, stepId, 1,
                "SAFE_FAILURE", "Safe failure.", () -> failedProjection(tenantId),
                () -> audit(failureOperation, "INVALID_OUTCOME")))
                .isInstanceOf(RuntimeException.class);
        assertProjection(tenantId, "PROVISIONING", "PENDING_EXTERNAL", "PROVISIONING");
        assertThat(operation(failureOperation)).containsEntry("lifecycle_state", "EXECUTING");
        assertThat(step(stepId)).containsEntry("lifecycle_state", "RUNNING");
        assertThat(auditCount(failureOperation)).isZero();

        projections.fail(
                failureOperation, failureToken, LEASE_DURATION, stepId, 1,
                "SAFE_FAILURE", "Safe failure.", () -> failedProjection(tenantId),
                () -> audit(failureOperation, "FAILED"));
        assertProjection(tenantId, "PROVISIONING", "FAILED", "FAILED");
        assertThat(operation(failureOperation)).containsEntry("lifecycle_state", "PARTIAL");
        assertThat(auditCount(failureOperation)).isEqualTo(1);
    }

    private ProviderOperationProjectionCoordinator.ProjectionResult readyProjection(UUID tenantId) {
        requireOne(jdbc.update("""
                UPDATE prv_tenants
                   SET lifecycle_state = 'ACTIVE', onboarding_state = 'READY'
                 WHERE provider_tenant_id = ?
                """, tenantId));
        requireOne(jdbc.update("""
                UPDATE prv_tenant_service_instances
                   SET lifecycle_state = 'READY', external_resource_id = 'auth:ready',
                       applied_schema_version = 1
                 WHERE provider_tenant_id = ? AND service_key = 'auth'
                """, tenantId));
        return new ProviderOperationProjectionCoordinator.ProjectionResult(
                "auth:ready", "{\"status\":\"ready\"}");
    }

    private void failedProjection(UUID tenantId) {
        requireOne(jdbc.update("""
                UPDATE prv_tenants SET onboarding_state = 'FAILED'
                 WHERE provider_tenant_id = ?
                """, tenantId));
        requireOne(jdbc.update("""
                UPDATE prv_tenant_service_instances SET lifecycle_state = 'FAILED'
                 WHERE provider_tenant_id = ? AND service_key = 'auth'
                """, tenantId));
    }

    private ProviderOperationProjectionCoordinator.ProjectionResult scheduleProjection(
            ProviderOperationsRepository operations,
            UUID operationId) {
        UUID maintenanceId = operations.scheduleMaintenanceWindow(operationId, operatorId())
                .orElseThrow();
        return new ProviderOperationProjectionCoordinator.ProjectionResult(
                maintenanceId.toString(),
                "{\"lifecycle\":\"SCHEDULED\"}");
    }

    private UUID projectionTenant(LeasedOperation operation) {
        return transaction.execute(status -> {
            leases.renewOwned(
                    operation.operationId(), operation.leaseToken(), LEASE_DURATION);
            UUID organizationId = jdbc.queryForObject("""
                    INSERT INTO prv_organizations (organization_key, display_name)
                    VALUES (?, 'Lease projection organization') RETURNING organization_id
                    """, UUID.class,
                    "lease-org-" + UUID.randomUUID().toString().substring(0, 12));
            UUID tenantId = jdbc.queryForObject("""
                    INSERT INTO prv_tenants (
                        tenant_key, organization_id, display_name, service_tier, data_region,
                        isolation_model, lifecycle_state, onboarding_state, environment_key)
                    VALUES (?, ?, 'Lease projection tenant', 'ENTERPRISE', 'ap-northeast-2',
                            'POOL', 'PROVISIONING', 'PENDING_EXTERNAL', 'production')
                    RETURNING provider_tenant_id
                    """, UUID.class,
                    "lease-tenant-" + UUID.randomUUID().toString().substring(0, 12),
                    organizationId);
            requireOne(jdbc.update(
                    "UPDATE prv_operations SET provider_tenant_id = ? WHERE operation_id = ?",
                    tenantId, operation.operationId()));
            jdbc.update("""
                    INSERT INTO prv_tenant_service_instances (
                        provider_tenant_id, service_key, lifecycle_state)
                    VALUES (?, 'auth', 'PROVISIONING')
                    """, tenantId);
            return tenantId;
        });
    }

    private UUID draftMaintenance(UUID operationId) {
        return jdbc.queryForObject("""
                INSERT INTO prv_maintenance_windows (
                    tracking_key, title, summary, scope_type, impact_type,
                    expected_impact_seconds, lifecycle_state, starts_at, ends_at,
                    customer_notice_at, minimum_notice_hours, operation_id, created_by, updated_by)
                VALUES (?, 'Lease evidence maintenance', 'Atomic scheduling regression',
                        'GLOBAL', 'NO_IMPACT', 0, 'DRAFT',
                        CURRENT_TIMESTAMP + INTERVAL '10 days',
                        CURRENT_TIMESTAMP + INTERVAL '10 days 1 hour',
                        CURRENT_TIMESTAMP, 1, ?, ?, ?)
                RETURNING maintenance_window_id
                """, UUID.class, "LEASE-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(),
                operationId, operatorId(), operatorId());
    }

    private void audit(UUID operationId, String outcome) {
        long operatorId = operatorId();
        long actorId = jdbc.queryForObject(
                "SELECT auth_user_id FROM prv_operators WHERE provider_operator_id = ?",
                Long.class, operatorId);
        UUID tenantId = jdbc.queryForObject("""
                SELECT provider_tenant_id FROM prv_operations WHERE operation_id = ?
                """, UUID.class, operationId);
        UUID organizationId = jdbc.queryForObject("""
                SELECT organization_id FROM prv_tenants WHERE provider_tenant_id = ?
                """, UUID.class, tenantId);
        boolean succeeded = "SUCCESS".equals(outcome);
        jdbc.update("""
                INSERT INTO prv_audit_events (
                    actor_id, action, target_type, target_id, outcome, correlation_id,
                    redacted_snapshot, provider_operator_id, provider_tenant_id,
                    organization_id, event_category)
                VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, 'TENANT_LIFECYCLE')
                """, actorId,
                succeeded
                        ? "provider.tenant-onboarding.succeeded"
                        : "provider.tenant-onboarding.step-failed",
                succeeded ? "PROVIDER_TENANT" : "PROVIDER_OPERATION",
                succeeded ? tenantId.toString() : operationId.toString(),
                outcome, "lease-regression:" + operationId, operatorId,
                tenantId, organizationId);
    }

    private int auditCount(UUID operationId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE correlation_id = ?
                """, Integer.class, "lease-regression:" + operationId);
    }

    private String maintenanceState(UUID maintenanceId) {
        return jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_maintenance_windows WHERE maintenance_window_id = ?
                """, String.class, maintenanceId);
    }

    private void assertProjection(
            UUID tenantId,
            String lifecycleState,
            String onboardingState,
            String serviceState) {
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, onboarding_state FROM prv_tenants WHERE provider_tenant_id = ?
                """, tenantId))
                .containsEntry("lifecycle_state", lifecycleState)
                .containsEntry("onboarding_state", onboardingState);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_tenant_service_instances
                 WHERE provider_tenant_id = ? AND service_key = 'auth'
                """, String.class, tenantId)).isEqualTo(serviceState);
    }

    private long operatorId() {
        return jdbc.queryForObject("SELECT MIN(provider_operator_id) FROM prv_operators", Long.class);
    }

    private void requireOne(int updated) {
        if (updated != 1) throw new IllegalStateException("Expected one provider projection row.");
    }

    private boolean concurrentClaim(
            UUID operationId,
            String worker,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            leases.claim(
                    operationId, version(operationId), true, worker, LEASE_DURATION);
            return true;
        } catch (BaseException rejected) {
            return false;
        }
    }

    private LeasedOperation leasedOperation(String operationType) {
        long operatorId = jdbc.queryForObject(
                "SELECT MIN(provider_operator_id) FROM prv_operators", Long.class);
        UUID operationId = jdbc.queryForObject("""
                INSERT INTO prv_operations (
                    operation_type, idempotency_key, lifecycle_state, risk_tier,
                    requested_by, justification, plan_hash, plan, version)
                VALUES (?, ?, 'PREVIEWED', 'L2', ?,
                        'lease regression', ?, '{}'::jsonb, 0)
                RETURNING operation_id
                """, UUID.class, operationType, "lease:" + UUID.randomUUID(), operatorId,
                "b".repeat(64));
        UUID token = leases.claim(
                operationId, 0L, false, "lease-test-worker", LEASE_DURATION);
        return new LeasedOperation(operationId, token);
    }

    private void expire(LeasedOperation operation) {
        transaction.executeWithoutResult(status -> {
            leases.renewOwned(
                    operation.operationId(), operation.leaseToken(), LEASE_DURATION);
            requireOne(jdbc.update("""
                    UPDATE prv_operations
                       SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                     WHERE operation_id = ? AND lease_token = ?
                    """, operation.operationId(), operation.leaseToken()));
        });
    }

    private long version(UUID operationId) {
        return jdbc.queryForObject(
                "SELECT version FROM prv_operations WHERE operation_id = ?",
                Long.class, operationId);
    }

    private UUID partialOperation() {
        long operatorId = jdbc.queryForObject(
                "SELECT MIN(provider_operator_id) FROM prv_operators", Long.class);
        return jdbc.queryForObject("""
                INSERT INTO prv_operations (
                    operation_type, idempotency_key, lifecycle_state, risk_tier,
                    requested_by, justification, plan_hash, plan, version)
                VALUES ('TENANT_ACTIVATE', ?, 'PARTIAL', 'L2', ?,
                        'partial regression', ?, '{}'::jsonb, 0)
                RETURNING operation_id
                """, UUID.class, "partial:" + UUID.randomUUID(), operatorId, "c".repeat(64));
    }

    private long runningStep(LeasedOperation operation, int attemptNumber) {
        return transaction.execute(status -> {
            leases.renewOwned(
                    operation.operationId(), operation.leaseToken(), LEASE_DURATION);
            long stepId = jdbc.queryForObject("""
                    INSERT INTO prv_operation_steps (
                        operation_id, step_order, step_key, lifecycle_state, target_service)
                    VALUES (?, 1, 'CONTROL_RECORD', 'PENDING', 'dwp-provider-server')
                    RETURNING operation_step_id
                    """, Long.class, operation.operationId());
            int started = evidence.startAttempt(
                    operation.operationId(), operation.leaseToken(), LEASE_DURATION,
                    stepId, "d".repeat(64));
            assertThat(started).isEqualTo(attemptNumber);
            return stepId;
        });
    }

    private Map<String, Object> operation(UUID operationId) {
        return jdbc.queryForMap("""
                SELECT lifecycle_state, failure_code, failure_message, version,
                       lease_owner, lease_token, lease_expires_at
                  FROM prv_operations WHERE operation_id = ?
                """, operationId);
    }

    private Map<String, Object> step(long stepId) {
        return jdbc.queryForMap("""
                SELECT lifecycle_state, attempt_count, last_error_code
                  FROM prv_operation_steps WHERE operation_step_id = ?
                """, stepId);
    }

    private Map<String, Object> attempt(long stepId, int attemptNumber) {
        return jdbc.queryForMap("""
                SELECT lifecycle_state, error_code
                  FROM prv_operation_step_attempts
                 WHERE operation_step_id = ? AND attempt_number = ?
                """, stepId, attemptNumber);
    }

    private record LeasedOperation(UUID operationId, UUID leaseToken) {
    }
}
