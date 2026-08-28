package com.dwp.services.provider.provisioning;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderOperationWriterFenceMigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void cleanDatabase() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        flyway(null).clean();
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void v55RequiresExactLeaseForEvidenceProjectionAuditAndOnboardingCommands() {
        flyway("53").migrate();
        long operatorId = jdbc.queryForObject(
                "SELECT MIN(provider_operator_id) FROM prv_operators", Long.class);
        UUID operationId = executingOperation(operatorId);
        UUID tenantId = onboardingTenant("writer-fence");
        associateTenant(operationId, tenantId);
        jdbc.update("""
                INSERT INTO prv_tenant_service_instances (
                    provider_tenant_id, service_key, lifecycle_state)
                VALUES (?, 'auth', 'PROVISIONING')
                """, tenantId);
        long stepId = runningStep(operationId, 3);
        runningAttempt(stepId, 3, "e".repeat(64));
        UUID containmentTenant = onboardingTenant("containment");
        jdbc.update("""
                UPDATE prv_tenants
                   SET lifecycle_state = 'ACTIVE', onboarding_state = 'READY',
                       auth_tenant_id = 81001
                 WHERE provider_tenant_id = ?
                """, containmentTenant);

        flyway(null).migrate();

        assertOldWorkerWriteRejected(() -> jdbc.update("""
                UPDATE prv_operation_steps
                   SET lifecycle_state = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP
                 WHERE operation_step_id = ?
                """, stepId));
        assertOldWorkerWriteRejected(() -> jdbc.update("""
                UPDATE prv_operation_step_attempts
                   SET lifecycle_state = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP
                 WHERE operation_step_id = ? AND attempt_number = 3
                """, stepId));
        assertOldWorkerWriteRejected(() -> jdbc.update("""
                UPDATE prv_tenants
                   SET onboarding_state = 'READY', lifecycle_state = 'ACTIVE'
                 WHERE provider_tenant_id = ?
                """, tenantId));
        assertOldWorkerWriteRejected(() -> jdbc.update("""
                UPDATE prv_tenant_service_instances
                   SET lifecycle_state = 'READY', external_resource_id = 'stale-auth',
                       applied_schema_version = 1
                 WHERE provider_tenant_id = ? AND service_key = 'auth'
                """, tenantId));
        assertOldWorkerWriteRejected(() -> insertTerminalAudit(operationId, tenantId, operatorId));
        assertOldWorkerWriteRejected(() -> jdbc.update("""
                UPDATE prv_operations
                   SET lifecycle_state = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP
                 WHERE operation_id = ?
                """, operationId));
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> bind(
                operationId, UUID.randomUUID())))
                .rootCause()
                .hasMessageContaining("ownership is lost");

        UUID leaseToken = UUID.randomUUID();
        assertThat(jdbc.update("""
                UPDATE prv_operations
                   SET lifecycle_state = 'EXECUTING', failure_code = NULL,
                       failure_message = NULL, lease_owner = 'v55-recovery',
                       lease_token = ?, lease_expires_at = clock_timestamp() + INTERVAL '5 minutes',
                       updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE operation_id = ? AND lifecycle_state = 'PARTIAL'
                """, leaseToken, operationId)).isOne();
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            bind(operationId, leaseToken);
            releasePartial(operationId);
        }))
                .rootCause()
                .hasMessageContaining("canonical audit");

        MutationFixture activation = onboardingActivationMutation(
                operationId, tenantId, operatorId, true);
        assertOldWorkerWriteRejected(() -> claimCommand(activation.commandId()));
        transaction.executeWithoutResult(ignored -> {
            bind(operationId, leaseToken);
            claimCommand(activation.commandId());
            updateMutation(activation.mutationId(), "EXECUTING");
        });
        assertOldWorkerWriteRejected(() -> applyCommand(activation.commandId()));
        transaction.executeWithoutResult(ignored -> {
            bind(operationId, leaseToken);
            applyCommand(activation.commandId());
            updateMutation(activation.mutationId(), "SUCCEEDED");
        });

        MutationFixture containment = onboardingActivationMutation(
                operationId, containmentTenant, operatorId, false);
        assertThat(claimCommand(containment.commandId())).isOne();
        assertThat(jdbc.update("""
                UPDATE prv_tenants
                   SET lifecycle_state = 'SUSPENDED'
                 WHERE provider_tenant_id = ?
                """, containmentTenant)).isOne();

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            bind(operationId, leaseToken);
            insertTerminalAudit(operationId, tenantId, operatorId);
            releaseSucceeded(operationId);
            jdbc.update("""
                    UPDATE prv_tenant_service_instances
                       SET lifecycle_state = 'READY', external_resource_id = 'released-too-soon',
                           applied_schema_version = 1
                     WHERE provider_tenant_id = ? AND service_key = 'auth'
                    """, tenantId);
        }))
                .rootCause()
                .hasMessageContaining("stale or expired");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE action = 'provider.tenant-onboarding.succeeded' AND target_id = ?
                """, Integer.class, tenantId.toString())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_operations WHERE operation_id = ?
                """, String.class, operationId)).isEqualTo("EXECUTING");

        transaction.executeWithoutResult(ignored -> {
            bind(operationId, leaseToken);
            jdbc.update("""
                    UPDATE prv_operation_steps
                       SET lifecycle_state = 'RUNNING', attempt_count = 4,
                           last_error_code = NULL, last_error_message = NULL,
                           started_at = CURRENT_TIMESTAMP, completed_at = NULL
                     WHERE operation_step_id = ?
                    """, stepId);
            runningAttempt(stepId, 4, "f".repeat(64));
            jdbc.update("""
                    UPDATE prv_tenants
                       SET lifecycle_state = 'ACTIVE', onboarding_state = 'READY',
                           auth_tenant_id = 991
                     WHERE provider_tenant_id = ?
                    """, tenantId);
            jdbc.update("""
                    UPDATE prv_tenant_service_instances
                       SET lifecycle_state = 'READY', external_resource_id = 'auth:991',
                           applied_schema_version = 1
                     WHERE provider_tenant_id = ? AND service_key = 'auth'
                    """, tenantId);
            jdbc.update("""
                    UPDATE prv_operation_steps
                       SET lifecycle_state = 'SUCCEEDED', external_reference = 'auth:991',
                           redacted_result = '{"status":"ready"}'::jsonb,
                           completed_at = CURRENT_TIMESTAMP
                     WHERE operation_step_id = ?
                    """, stepId);
            jdbc.update("""
                    UPDATE prv_operation_step_attempts
                       SET lifecycle_state = 'SUCCEEDED',
                           redacted_result = '{"status":"ready"}'::jsonb,
                           completed_at = CURRENT_TIMESTAMP
                     WHERE operation_step_id = ? AND attempt_number = 4
                    """, stepId);
            insertTerminalAudit(operationId, tenantId, operatorId);
            releaseSucceeded(operationId);
        });
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, lease_owner, lease_token, lease_expires_at
                  FROM prv_operations WHERE operation_id = ?
                """, operationId))
                .containsEntry("lifecycle_state", "SUCCEEDED")
                .containsEntry("lease_owner", null)
                .containsEntry("lease_token", null)
                .containsEntry("lease_expires_at", null);
        assertThat(jdbc.queryForMap("""
                SELECT provider_operation_id, provider_operation_lease_token
                  FROM prv_audit_events
                 WHERE action = 'provider.tenant-onboarding.succeeded'
                   AND target_id = ?
                """, tenantId.toString()))
                .containsEntry("provider_operation_id", operationId)
                .containsEntry("provider_operation_lease_token", leaseToken);
        assertOldWorkerWriteRejected(() -> jdbc.update("""
                UPDATE prv_tenants
                   SET auth_tenant_id = 992
                 WHERE provider_tenant_id = ?
                """, tenantId));
        assertOldWorkerWriteRejected(() -> jdbc.update("""
                UPDATE prv_tenant_service_instances
                   SET external_resource_id = 'stale-after-release'
                 WHERE provider_tenant_id = ? AND service_key = 'auth'
                """, tenantId));
        assertThat(jdbc.update("""
                UPDATE prv_tenant_service_instances
                   SET health_snapshot = '{"status":"healthy"}'::jsonb
                 WHERE provider_tenant_id = ? AND service_key = 'auth'
                """, tenantId)).isOne();

        MaintenanceFixture maintenance = leasedMaintenance(tenantId, operatorId);
        assertForeignMaintenanceAuditRejected(
                maintenance, containmentTenant, operatorId, true);
        assertForeignMaintenanceAuditRejected(
                maintenance, containmentTenant, operatorId, false);
    }

    private UUID executingOperation(long operatorId) {
        UUID operationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_operations (
                    operation_id, operation_type, idempotency_key, lifecycle_state, risk_tier,
                    requested_by, justification, plan_hash, plan, version)
                VALUES (?, 'TENANT_ONBOARD', ?, 'EXECUTING', 'L2', ?,
                        'writer fence regression', ?, '{}'::jsonb, 7)
                """, operationId, "writer-fence:" + operationId, operatorId,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
        return operationId;
    }

    private UUID onboardingTenant(String marker) {
        UUID organizationId = jdbc.queryForObject("""
                INSERT INTO prv_organizations (organization_key, display_name)
                VALUES (?, 'Provider writer fence organization')
                RETURNING organization_id
                """, UUID.class, "writer-fence-org-" + marker + "-"
                + UUID.randomUUID().toString().substring(0, 8));
        UUID tenantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_tenants (
                    provider_tenant_id, tenant_key, organization_id, display_name,
                    service_tier, data_region, isolation_model, lifecycle_state,
                    onboarding_state, environment_key)
                VALUES (?, ?, ?, 'Provider writer fence tenant', 'ENTERPRISE',
                        'ap-northeast-2', 'POOL', 'PROVISIONING',
                        'CONTROL_PLANE_READY', 'production')
                """, tenantId, "writer-fence-" + marker + "-" + tenantId, organizationId);
        return tenantId;
    }

    private void associateTenant(UUID operationId, UUID tenantId) {
        jdbc.update("""
                UPDATE prv_operations SET provider_tenant_id = ? WHERE operation_id = ?
                """, tenantId, operationId);
    }

    private long runningStep(UUID operationId, int attemptCount) {
        return jdbc.queryForObject("""
                INSERT INTO prv_operation_steps (
                    operation_id, step_order, step_key, lifecycle_state,
                    target_service, attempt_count)
                VALUES (?, 1, 'AUTH_TENANT', 'RUNNING', 'dwp-provider-server', ?)
                RETURNING operation_step_id
                """, Long.class, operationId, attemptCount);
    }

    private void runningAttempt(long stepId, int attemptNumber, String fingerprint) {
        jdbc.update("""
                INSERT INTO prv_operation_step_attempts (
                    operation_step_id, attempt_number, lifecycle_state, request_fingerprint)
                VALUES (?, ?, 'RUNNING', ?)
                """, stepId, attemptNumber, fingerprint);
    }

    private void assertOldWorkerWriteRejected(Runnable write) {
        assertThatThrownBy(write::run)
                .rootCause()
                .hasMessageContaining("transaction-local lease binding");
    }

    private void bind(UUID operationId, UUID leaseToken) {
        assertThat(jdbc.queryForObject("""
                SELECT prv_bind_provider_operation_lease(?, ?)
                """, UUID.class, operationId, leaseToken)).isEqualTo(operationId);
    }

    private void insertTerminalAudit(UUID operationId, UUID tenantId, long operatorId) {
        long actorId = jdbc.queryForObject("""
                SELECT auth_user_id FROM prv_operators WHERE provider_operator_id = ?
                """, Long.class, operatorId);
        UUID organizationId = jdbc.queryForObject("""
                SELECT organization_id FROM prv_tenants WHERE provider_tenant_id = ?
                """, UUID.class, tenantId);
        jdbc.update("""
                INSERT INTO prv_audit_events (
                    audit_event_id, actor_id, action, target_type, target_id,
                    outcome, correlation_id, redacted_snapshot, provider_operator_id,
                    provider_tenant_id, organization_id, event_category)
                VALUES (gen_random_uuid(), ?, 'provider.tenant-onboarding.succeeded',
                        'PROVIDER_TENANT', ?, 'SUCCESS', ?, '{}'::jsonb, ?, ?, ?,
                        'TENANT_LIFECYCLE')
                """, actorId, tenantId.toString(), "v55:" + operationId,
                operatorId, tenantId, organizationId);
    }

    private void releaseSucceeded(UUID operationId) {
        assertThat(jdbc.update("""
                UPDATE prv_operations
                   SET lifecycle_state = 'SUCCEEDED', failure_code = NULL,
                       failure_message = NULL, completed_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE operation_id = ? AND lifecycle_state = 'EXECUTING'
                """, operationId)).isOne();
    }

    private void releasePartial(UUID operationId) {
        jdbc.update("""
                UPDATE prv_operations
                   SET lifecycle_state = 'PARTIAL', failure_code = 'RETRY_FAILED',
                       failure_message = 'Retry failed before terminal evidence.',
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE operation_id = ? AND lifecycle_state = 'EXECUTING'
                """, operationId);
    }

    private MutationFixture onboardingActivationMutation(
            UUID operationId,
            UUID tenantId,
            long operatorId,
            boolean providerBound) {
        UUID mutationId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        long tenantVersion = jdbc.queryForObject("""
                SELECT version FROM prv_tenants WHERE provider_tenant_id = ?
                """, Long.class, tenantId);
        jdbc.update("""
                INSERT INTO prv_tenant_mutations (
                    mutation_id, provider_tenant_id, mutation_type, idempotency_key,
                    payload_sha256, expected_tenant_version, target_revision,
                    previous_payload, desired_payload, lifecycle_state,
                    requested_by, correlation_id)
                VALUES (?, ?, 'LIFECYCLE', ?, ?, ?, 1,
                        '{"lifecycleState":"PROVISIONING"}'::jsonb,
                        CASE WHEN ? THEN jsonb_build_object(
                            'lifecycleState', 'ACTIVE',
                            'providerOperationId', ?::text)
                        ELSE '{"lifecycleState":"SUSPENDED"}'::jsonb END,
                        'PENDING', ?, ?)
                """, mutationId, tenantId, "v55-mutation:" + mutationId,
                "1".repeat(64), tenantVersion, providerBound, operationId,
                operatorId, "v55-mutation:" + mutationId);
        jdbc.update("""
                INSERT INTO prv_tenant_command_outbox (
                    command_id, mutation_id, command_order, target_service,
                    command_type, expected_revision, target_revision,
                    payload_sha256, payload)
                VALUES (?, ?, 1, 'AUTH', 'LIFECYCLE', 0, 1, ?,
                        CASE WHEN ? THEN '{"lifecycleState":"ACTIVE"}'::jsonb
                             ELSE '{"lifecycleState":"SUSPENDED"}'::jsonb END)
                """, commandId, mutationId, "2".repeat(64), providerBound);
        return new MutationFixture(mutationId, commandId);
    }

    private int claimCommand(UUID commandId) {
        return jdbc.update("""
                UPDATE prv_tenant_command_outbox
                   SET lifecycle_state = 'LEASED', lease_owner = 'mutation-worker',
                       lease_token = gen_random_uuid(),
                       lease_expires_at = clock_timestamp() + INTERVAL '1 minute',
                       attempt_count = attempt_count + 1
                 WHERE command_id = ? AND lifecycle_state = 'PENDING'
                """, commandId);
    }

    private void applyCommand(UUID commandId) {
        jdbc.update("""
                UPDATE prv_tenant_command_outbox
                   SET lifecycle_state = 'APPLIED', response_payload = '{}'::jsonb,
                       applied_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                 WHERE command_id = ? AND lifecycle_state = 'LEASED'
                """, commandId);
    }

    private void updateMutation(UUID mutationId, String lifecycleState) {
        jdbc.update("""
                UPDATE prv_tenant_mutations
                   SET lifecycle_state = ?,
                       started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                       completed_at = CASE WHEN ? = 'SUCCEEDED' THEN CURRENT_TIMESTAMP END
                 WHERE mutation_id = ?
                """, lifecycleState, lifecycleState, mutationId);
    }

    private MaintenanceFixture leasedMaintenance(UUID tenantId, long operatorId) {
        UUID operationId = UUID.randomUUID();
        UUID maintenanceId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_operations (
                    operation_id, provider_tenant_id, operation_type, idempotency_key,
                    lifecycle_state, risk_tier, requested_by, justification, plan_hash, plan)
                VALUES (?, ?, 'MAINTENANCE_SCHEDULE', ?, 'PREVIEWED', 'L2', ?,
                        'maintenance audit attribution regression', ?, '{}'::jsonb)
                """, operationId, tenantId, "maintenance-audit:" + operationId,
                operatorId, "3".repeat(64));
        jdbc.update("""
                INSERT INTO prv_maintenance_windows (
                    maintenance_window_id, tracking_key, title, summary, scope_type,
                    provider_tenant_id, impact_type, expected_impact_seconds,
                    lifecycle_state, starts_at, ends_at, customer_notice_at,
                    minimum_notice_hours, operation_id, created_by, updated_by)
                VALUES (?, ?, 'Maintenance audit attribution', 'Foreign tenant regression',
                        'TENANT', ?, 'NO_IMPACT', 0, 'DRAFT',
                        CURRENT_TIMESTAMP + INTERVAL '7 days',
                        CURRENT_TIMESTAMP + INTERVAL '7 days 1 hour',
                        CURRENT_TIMESTAMP, 120, ?, ?, ?)
                """, maintenanceId,
                "AUDIT-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase(),
                tenantId, operationId, operatorId, operatorId);
        assertThat(jdbc.update("""
                UPDATE prv_operations
                   SET lifecycle_state = 'EXECUTING', lease_owner = 'maintenance-audit-test',
                       lease_token = ?, lease_expires_at = clock_timestamp() + INTERVAL '5 minutes',
                       started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE operation_id = ? AND lifecycle_state = 'PREVIEWED'
                """, leaseToken, operationId)).isOne();
        return new MaintenanceFixture(operationId, maintenanceId, leaseToken);
    }

    private void assertForeignMaintenanceAuditRejected(
            MaintenanceFixture maintenance,
            UUID foreignTenantId,
            long operatorId,
            boolean success) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            bind(maintenance.operationId(), maintenance.leaseToken());
            insertMaintenanceAudit(maintenance, foreignTenantId, operatorId, success);
        }))
                .rootCause()
                .hasMessageContaining("does not match its bound operation");
    }

    private void insertMaintenanceAudit(
            MaintenanceFixture maintenance,
            UUID foreignTenantId,
            long operatorId,
            boolean success) {
        long actorId = jdbc.queryForObject("""
                SELECT auth_user_id FROM prv_operators WHERE provider_operator_id = ?
                """, Long.class, operatorId);
        UUID organizationId = jdbc.queryForObject("""
                SELECT organization_id FROM prv_tenants WHERE provider_tenant_id = ?
                """, UUID.class, foreignTenantId);
        jdbc.update("""
                INSERT INTO prv_audit_events (
                    actor_id, action, target_type, target_id, outcome, correlation_id,
                    redacted_snapshot, provider_operator_id, provider_tenant_id,
                    organization_id, event_category)
                VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?, ?, 'CHANGE_MANAGEMENT')
                """, actorId,
                success ? "provider.maintenance.scheduled" : "provider.maintenance.schedule-failed",
                success ? "MAINTENANCE_WINDOW" : "PROVIDER_OPERATION",
                success ? maintenance.maintenanceId().toString() : maintenance.operationId().toString(),
                success ? "SUCCESS" : "FAILED",
                "foreign-maintenance-audit:" + maintenance.operationId() + ':' + success,
                operatorId, foreignTenantId, organizationId);
    }

    private record MutationFixture(UUID mutationId, UUID commandId) {
    }

    private record MaintenanceFixture(
            UUID operationId,
            UUID maintenanceId,
            UUID leaseToken) {
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
