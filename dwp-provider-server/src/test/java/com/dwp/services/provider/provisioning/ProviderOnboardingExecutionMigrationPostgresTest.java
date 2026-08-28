package com.dwp.services.provider.provisioning;

import com.dwp.services.provider.ProviderOperationsRepository;
import com.dwp.services.provider.operation.ProviderOperation;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderOnboardingExecutionMigrationPostgresTest {

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
    void v53ThroughV55CorrectsRollingWindowRowsAndRejectsStaleApprovalWriters() {
        flyway("53").migrate();
        long operatorId = jdbc.queryForObject(
                "SELECT MIN(provider_operator_id) FROM prv_operators", Long.class);
        long deciderId = jdbc.queryForObject(
                """
                SELECT MIN(provider_operator_id)
                  FROM prv_operators
                 WHERE role_code = 'PROVIDER_CHANGE_APPROVER'
                """, Long.class);
        UUID corrected = operation("L3", "PREVIEWED", 0, operatorId);
        UUID lowRisk = operation("L2", "PREVIEWED", 0, operatorId);
        UUID wrongGate = operation("L3", "PREVIEWED", 0, operatorId);
        UUID decided = operation("L3", "PREVIEWED", 0, operatorId);
        UUID executing = operation("L2", "EXECUTING", 7, operatorId);
        UUID recoveredTenant = onboardingTenant("recovered");
        associateTenant(executing, recoveredTenant);
        jdbc.update("""
                INSERT INTO prv_tenant_service_instances (
                    provider_tenant_id, service_key, lifecycle_state)
                VALUES (?, 'auth', 'PROVISIONING')
                """, recoveredTenant);
        UUID succeededTenant = onboardingTenant("succeeded");
        UUID succeededWithoutAudit = operation("L2", "SUCCEEDED", 3, operatorId);
        associateTenant(succeededWithoutAudit, succeededTenant);
        jdbc.update("""
                UPDATE prv_operations
                   SET completed_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                 WHERE operation_id = ?
                """, succeededWithoutAudit);
        successfulStep(succeededWithoutAudit);
        UUID partialTenant = onboardingTenant("partial");
        UUID partialWithoutAudit = operation("L2", "PARTIAL", 4, operatorId);
        associateTenant(partialWithoutAudit, partialTenant);
        jdbc.update("""
                UPDATE prv_operations
                   SET failure_code = 'OLD_WORKER_FAILURE',
                       failure_message = 'Bounded old worker failure.',
                       updated_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                 WHERE operation_id = ?
                """, partialWithoutAudit);
        failedStep(partialWithoutAudit, "AUTH_TENANT", "OLD_WORKER_FAILURE");
        approval(corrected, "RISK_REVIEW", "PENDING", "PROVIDER_ADMIN", operatorId, null);
        approval(lowRisk, "RISK_REVIEW", "PENDING", "PROVIDER_ADMIN", operatorId, null);
        approval(wrongGate, "SECURITY_REVIEW", "PENDING", "PROVIDER_ADMIN", operatorId, null);
        approval(decided, "RISK_REVIEW", "APPROVED", "PROVIDER_ADMIN", operatorId, deciderId);
        long stepId = jdbc.queryForObject("""
                INSERT INTO prv_operation_steps (
                    operation_id, step_order, step_key, lifecycle_state, target_service,
                    attempt_count)
                VALUES (?, 1, 'CONTROL_RECORD', 'RUNNING', 'dwp-provider-server', 3)
                RETURNING operation_step_id
                """, Long.class, executing);
        jdbc.update("""
                INSERT INTO prv_operation_step_attempts (
                    operation_step_id, attempt_number, lifecycle_state, request_fingerprint)
                VALUES (?, 3, 'RUNNING', ?)
                """, stepId, "e".repeat(64));
        UUID activationGap = operation("L2", "EXECUTING", 5, operatorId);
        UUID activationGapTenant = onboardingTenant("activation-gap");
        associateTenant(activationGap, activationGapTenant);
        jdbc.update("""
                UPDATE prv_tenants
                   SET onboarding_state = 'PENDING_EXTERNAL'
                 WHERE provider_tenant_id = ?
                """, activationGapTenant);
        long activationGapStep = runningStep(activationGap, "ACTIVATE_TENANT", 2);
        runningAttempt(activationGapStep, 2, "c".repeat(64));
        UUID maintenanceGap = typedOperation(
                "MAINTENANCE_SCHEDULE", "L2", "EXECUTING", 6, operatorId);
        UUID maintenanceGapWindow = maintenanceWindow(maintenanceGap, operatorId);
        long maintenanceGapStep = runningStep(
                maintenanceGap, "SCHEDULE_MAINTENANCE", 2);
        runningAttempt(maintenanceGapStep, 2, "d".repeat(64));

        flyway("54").migrate();
        jdbc.update("""
                UPDATE prv_tenants
                   SET lifecycle_state = 'ACTIVE', onboarding_state = 'READY'
                 WHERE provider_tenant_id = ?
                """, activationGapTenant);
        jdbc.update("""
                UPDATE prv_maintenance_windows
                   SET lifecycle_state = 'SCHEDULED', updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?, version = version + 1
                 WHERE maintenance_window_id = ?
                """, operatorId, maintenanceGapWindow);
        jdbc.update("""
                UPDATE prv_operation_steps
                   SET lifecycle_state = 'SUCCEEDED', external_reference = 'stale-projection',
                       redacted_result = '{"status":"ready"}'::jsonb,
                       last_error_code = NULL, last_error_message = NULL,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE operation_step_id IN (?, ?)
                """, activationGapStep, maintenanceGapStep);
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, onboarding_state
                  FROM prv_tenants
                 WHERE provider_tenant_id = ?
                """, activationGapTenant))
                .containsEntry("lifecycle_state", "ACTIVE")
                .containsEntry("onboarding_state", "READY");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM prv_maintenance_windows
                 WHERE maintenance_window_id = ?
                """, String.class, maintenanceGapWindow)).isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForList("""
                SELECT lifecycle_state
                  FROM prv_operation_steps
                 WHERE operation_step_id IN (?, ?)
                 ORDER BY operation_step_id
                """, String.class, activationGapStep, maintenanceGapStep))
                .containsOnly("SUCCEEDED");
        UUID rollingRole = operation("L3", "PREVIEWED", 0, operatorId);
        oldShapeApproval(rollingRole, operatorId);
        UUID rollingSeparation = operation("L3", "PREVIEWED", 0, operatorId);
        nonSeparatedApproval(rollingSeparation, operatorId);
        assertThat(approvalState(rollingRole))
                .containsEntry("required_role_code", "PROVIDER_ADMIN")
                .containsEntry("separation_of_duties", true)
                .containsEntry("version", 0L);
        assertThat(approvalState(rollingSeparation))
                .containsEntry("required_role_code", "PROVIDER_CHANGE_APPROVER")
                .containsEntry("separation_of_duties", false)
                .containsEntry("version", 0L);

        flyway(null).migrate();

        assertThat(approvalState(rollingRole))
                .containsEntry("required_role_code", "PROVIDER_CHANGE_APPROVER")
                .containsEntry("separation_of_duties", true)
                .containsEntry("version", 1L);
        assertThat(approvalState(rollingSeparation))
                .containsEntry("required_role_code", "PROVIDER_CHANGE_APPROVER")
                .containsEntry("separation_of_duties", true)
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, onboarding_state
                  FROM prv_tenants
                 WHERE provider_tenant_id = ?
                """, activationGapTenant))
                .containsEntry("lifecycle_state", "PROVISIONING")
                .containsEntry("onboarding_state", "PENDING_EXTERNAL");
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM prv_maintenance_windows
                 WHERE maintenance_window_id = ?
                """, String.class, maintenanceGapWindow)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForList("""
                SELECT lifecycle_state
                  FROM prv_operation_steps
                 WHERE operation_step_id IN (?, ?)
                 ORDER BY operation_step_id
                """, String.class, activationGapStep, maintenanceGapStep))
                .containsOnly("FAILED");

        UUID staleInsert = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            insertOperation(staleInsert, "L3", "PREVIEWED", 0, operatorId);
            oldShapeApproval(staleInsert, operatorId);
        }))
                .rootCause()
                .hasMessageContaining(
                        "require PROVIDER_CHANGE_APPROVER with separation_of_duties");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_operation_approvals WHERE operation_id = ?
                """, Integer.class, staleInsert)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_operations WHERE operation_id = ?
                """, Integer.class, staleInsert)).isZero();

        UUID nonSeparatedInsert = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            insertOperation(nonSeparatedInsert, "L3", "PREVIEWED", 0, operatorId);
            nonSeparatedApproval(nonSeparatedInsert, operatorId);
        }))
                .rootCause()
                .hasMessageContaining(
                        "require PROVIDER_CHANGE_APPROVER with separation_of_duties");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_operations WHERE operation_id = ?
                """, Integer.class, nonSeparatedInsert)).isZero();

        UUID staleUpdate = operation("L3", "PREVIEWED", 0, operatorId);
        approval(
                staleUpdate,
                "RISK_REVIEW",
                "PENDING",
                "PROVIDER_CHANGE_APPROVER",
                operatorId,
                null);
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> jdbc.update("""
                UPDATE prv_operation_approvals
                   SET required_role_code = 'PROVIDER_ADMIN', version = version + 1
                 WHERE operation_id = ?
                """, staleUpdate)))
                .rootCause()
                .hasMessageContaining(
                        "require PROVIDER_CHANGE_APPROVER with separation_of_duties");
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> jdbc.update("""
                UPDATE prv_operation_approvals
                   SET separation_of_duties = FALSE, version = version + 1
                 WHERE operation_id = ?
                """, staleUpdate)))
                .rootCause()
                .hasMessageContaining(
                        "require PROVIDER_CHANGE_APPROVER with separation_of_duties");
        assertThat(approvalState(staleUpdate))
                .containsEntry("required_role_code", "PROVIDER_CHANGE_APPROVER")
                .containsEntry("separation_of_duties", true)
                .containsEntry("version", 0L);

        assertThat(approvalState(corrected))
                .containsEntry("required_role_code", "PROVIDER_CHANGE_APPROVER")
                .containsEntry("version", 1L);
        assertThat(approvalState(lowRisk)).containsEntry("required_role_code", "PROVIDER_ADMIN");
        assertThat(approvalState(wrongGate)).containsEntry("required_role_code", "PROVIDER_ADMIN");
        assertThat(approvalState(decided))
                .containsEntry("lifecycle_state", "APPROVED")
                .containsEntry("required_role_code", "PROVIDER_ADMIN")
                .containsEntry("version", 0L);
        assertThat(new ProviderOperationsRepository(jdbc).operationApproved(decided)).isFalse();
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, failure_code, version
                  FROM prv_operations
                 WHERE operation_id = ?
                """, executing))
                .containsEntry("lifecycle_state", "PARTIAL")
                .containsEntry("failure_code", "DEPLOYMENT_RECOVERY_REQUIRED")
                .containsEntry("version", 8L);
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, last_error_code, attempt_count
                  FROM prv_operation_steps
                 WHERE operation_step_id = ?
                """, stepId))
                .containsEntry("lifecycle_state", "FAILED")
                .containsEntry("last_error_code", "DEPLOYMENT_RECOVERY_REQUIRED")
                .containsEntry("attempt_count", 3);
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, error_code
                  FROM prv_operation_step_attempts
                 WHERE operation_step_id = ? AND attempt_number = 3
                """, stepId))
                .containsEntry("lifecycle_state", "ABANDONED")
                .containsEntry("error_code", "DEPLOYMENT_RECOVERY_REQUIRED");
        assertThat(jdbc.queryForMap("""
                SELECT action, target_type, target_id, outcome, provider_tenant_id,
                       provider_operation_id, provider_operation_lease_token,
                       event_category,
                       redacted_snapshot ->> 'tenantKey' AS tenant_key,
                       redacted_snapshot ->> 'authTenantId' AS auth_tenant_id
                  FROM prv_audit_events
                 WHERE action = 'provider.tenant-onboarding.succeeded'
                   AND target_id = ?
                """, succeededTenant.toString()))
                .containsEntry("target_type", "PROVIDER_TENANT")
                .containsEntry("target_id", succeededTenant.toString())
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("provider_tenant_id", succeededTenant)
                .containsEntry("provider_operation_id", null)
                .containsEntry("provider_operation_lease_token", null)
                .containsEntry("event_category", "TENANT_LIFECYCLE")
                .containsEntry("tenant_key", "migration-succeeded-" + succeededTenant)
                .containsEntry("auth_tenant_id", null);
        assertFailureAudit(partialWithoutAudit, "AUTH_TENANT", "OLD_WORKER_FAILURE", 1);
        assertFailureAudit(executing, "CONTROL_RECORD", "DEPLOYMENT_RECOVERY_REQUIRED", 3);
        assertFailureAudit(
                activationGap, "ACTIVATE_TENANT", "DEPLOYMENT_RECOVERY_REQUIRED", 2);
        assertFailureAudit(
                maintenanceGap,
                "provider.maintenance.schedule-failed",
                "CHANGE_MANAGEMENT",
                "SCHEDULE_MAINTENANCE",
                "DEPLOYMENT_RECOVERY_REQUIRED",
                2);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_operations SET lifecycle_state = 'EXECUTING'
                 WHERE operation_id = ?
                """, corrected)).hasMessageContaining("ck_prv_operations_execution_lease");
    }

    @Test
    void v1ToLatestEnforcesLeaseAndDedicatedApprovalContracts() {
        flyway("1").migrate();
        flyway(null).migrate();

        assertThat(jdbc.queryForList("""
                SELECT version
                  FROM flyway_schema_history
                 WHERE success
                   AND version IN ('1', '55')
                 ORDER BY installed_rank
                """, String.class)).containsExactly("1", "55");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_name = 'prv_operations'
                   AND column_name IN ('lease_owner', 'lease_token', 'lease_expires_at')
                 ORDER BY column_name
                """, String.class)).containsExactlyElementsOf(List.of(
                "lease_expires_at", "lease_owner", "lease_token"));
        long operatorId = jdbc.queryForObject(
                "SELECT MIN(provider_operator_id) FROM prv_operators", Long.class);
        long deciderId = jdbc.queryForObject(
                """
                SELECT MIN(provider_operator_id)
                  FROM prv_operators
                 WHERE role_code = 'PROVIDER_CHANGE_APPROVER'
                """, Long.class);
        ProviderOperationsRepository approvals = new ProviderOperationsRepository(jdbc);
        UUID missingApproval = operation("L3", "PREVIEWED", 0, operatorId);
        assertThat(approvals.operationApproved(missingApproval)).isFalse();

        UUID operationId = operation("L3", "PREVIEWED", 0, operatorId);
        approvals.ensureOperationApproval(ProviderOperation.builder()
                .operationId(operationId)
                .riskTier("L3")
                .requestedBy(operatorId)
                .justification("Dedicated approval role regression")
                .build());
        assertThat(approvalState(operationId))
                .containsEntry("required_role_code", "PROVIDER_CHANGE_APPROVER")
                .containsEntry("separation_of_duties", true)
                .containsEntry("version", 0L);
        approval(
                operationId,
                "SECURITY_REVIEW",
                "PENDING",
                "PROVIDER_CHANGE_APPROVER",
                operatorId,
                null);
        approve(operationId, "RISK_REVIEW", deciderId);
        assertThat(approvals.operationApproved(operationId)).isFalse();
        approve(operationId, "SECURITY_REVIEW", deciderId);
        assertThat(approvals.operationApproved(operationId)).isTrue();
    }

    private UUID operation(
            String riskTier,
            String lifecycleState,
            long version,
            long operatorId) {
        return typedOperation("TENANT_ONBOARD", riskTier, lifecycleState, version, operatorId);
    }

    private UUID typedOperation(
            String operationType,
            String riskTier,
            String lifecycleState,
            long version,
            long operatorId) {
        UUID operationId = UUID.randomUUID();
        insertOperation(
                operationId, operationType, riskTier, lifecycleState, version, operatorId);
        return operationId;
    }

    private void insertOperation(
            UUID operationId,
            String riskTier,
            String lifecycleState,
            long version,
            long operatorId) {
        insertOperation(
                operationId, "TENANT_ONBOARD", riskTier, lifecycleState, version, operatorId);
    }

    private void insertOperation(
            UUID operationId,
            String operationType,
            String riskTier,
            String lifecycleState,
            long version,
            long operatorId) {
        jdbc.update("""
                INSERT INTO prv_operations (
                    operation_id, operation_type, idempotency_key, lifecycle_state, risk_tier,
                    requested_by, justification, plan_hash, plan, version)
                VALUES (?, ?, ?, ?, ?, ?, 'migration regression', ?, '{}'::jsonb, ?)
                """, operationId, operationType, "migration:" + UUID.randomUUID(), lifecycleState,
                riskTier, operatorId, UUID.randomUUID().toString().replace("-", "").repeat(2), version);
    }

    private UUID onboardingTenant(String marker) {
        UUID organizationId = jdbc.queryForObject("""
                INSERT INTO prv_organizations (organization_key, display_name)
                VALUES (?, 'Provider migration organization')
                RETURNING organization_id
                """, UUID.class, "migration-org-" + marker + "-"
                + UUID.randomUUID().toString().substring(0, 8));
        UUID tenantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_tenants (
                    provider_tenant_id, tenant_key, organization_id, display_name,
                    service_tier, data_region, isolation_model, lifecycle_state,
                    onboarding_state, environment_key)
                VALUES (?, ?, ?, 'Provider migration tenant', 'ENTERPRISE',
                        'ap-northeast-2', 'POOL', 'PROVISIONING',
                        'CONTROL_PLANE_READY', 'production')
                """, tenantId, "migration-" + marker + "-" + tenantId, organizationId);
        return tenantId;
    }

    private void associateTenant(UUID operationId, UUID tenantId) {
        jdbc.update("""
                UPDATE prv_operations SET provider_tenant_id = ? WHERE operation_id = ?
                """, tenantId, operationId);
    }

    private long runningStep(UUID operationId, String stepKey, int attemptCount) {
        return jdbc.queryForObject("""
                INSERT INTO prv_operation_steps (
                    operation_id, step_order, step_key, lifecycle_state,
                    target_service, attempt_count)
                VALUES (?, 1, ?, 'RUNNING', 'dwp-provider-server', ?)
                RETURNING operation_step_id
                """, Long.class, operationId, stepKey, attemptCount);
    }

    private void runningAttempt(long stepId, int attemptNumber, String fingerprint) {
        jdbc.update("""
                INSERT INTO prv_operation_step_attempts (
                    operation_step_id, attempt_number, lifecycle_state, request_fingerprint)
                VALUES (?, ?, 'RUNNING', ?)
                """, stepId, attemptNumber, fingerprint);
    }

    private UUID maintenanceWindow(UUID operationId, long operatorId) {
        UUID maintenanceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_maintenance_windows (
                    maintenance_window_id, tracking_key, title, summary, scope_type,
                    impact_type, expected_impact_seconds, lifecycle_state,
                    starts_at, ends_at, customer_notice_at, minimum_notice_hours,
                    operation_id, created_by, updated_by)
                VALUES (?, ?, 'Migration maintenance', 'V54 to V55 gap regression', 'GLOBAL',
                        'NO_IMPACT', 0, 'DRAFT',
                        CURRENT_TIMESTAMP + INTERVAL '7 days',
                        CURRENT_TIMESTAMP + INTERVAL '7 days 1 hour',
                        CURRENT_TIMESTAMP, 120, ?, ?, ?)
                """, maintenanceId,
                "V55-GAP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                operationId, operatorId, operatorId);
        return maintenanceId;
    }

    private void successfulStep(UUID operationId) {
        long stepId = jdbc.queryForObject("""
                INSERT INTO prv_operation_steps (
                    operation_id, step_order, step_key, lifecycle_state,
                    target_service, external_reference, redacted_result,
                    started_at, completed_at, attempt_count)
                VALUES (?, 1, 'ACTIVATE_TENANT', 'SUCCEEDED', 'dwp-provider-server',
                        ?, '{"status":"ready"}'::jsonb,
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute', 1)
                RETURNING operation_step_id
                """, Long.class, operationId, operationId.toString());
        jdbc.update("""
                INSERT INTO prv_operation_step_attempts (
                    operation_step_id, attempt_number, lifecycle_state,
                    request_fingerprint, redacted_result, started_at, completed_at)
                VALUES (?, 1, 'SUCCEEDED', ?, '{"status":"ready"}'::jsonb,
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute')
                """, stepId, "a".repeat(64));
    }

    private void failedStep(UUID operationId, String stepKey, String errorCode) {
        long stepId = jdbc.queryForObject("""
                INSERT INTO prv_operation_steps (
                    operation_id, step_order, step_key, lifecycle_state,
                    target_service, started_at, completed_at, attempt_count,
                    last_error_code, last_error_message)
                VALUES (?, 1, ?, 'FAILED', 'dwp-provider-server',
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute', 1, ?,
                        'Bounded old worker failure.')
                RETURNING operation_step_id
                """, Long.class, operationId, stepKey, errorCode);
        jdbc.update("""
                INSERT INTO prv_operation_step_attempts (
                    operation_step_id, attempt_number, lifecycle_state,
                    request_fingerprint, error_code, error_message,
                    started_at, completed_at)
                VALUES (?, 1, 'FAILED', ?, ?, 'Bounded old worker failure.',
                        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute')
                """, stepId, "b".repeat(64), errorCode);
    }

    private void assertFailureAudit(
            UUID operationId,
            String stepKey,
            String errorCode,
            int attempt) {
        assertFailureAudit(
                operationId,
                "provider.tenant-onboarding.step-failed",
                "TENANT_LIFECYCLE",
                stepKey,
                errorCode,
                attempt);
    }

    private void assertFailureAudit(
            UUID operationId,
            String action,
            String eventCategory,
            String stepKey,
            String errorCode,
            int attempt) {
        assertThat(jdbc.queryForMap("""
                SELECT action, target_type, target_id, outcome, event_category,
                       provider_operation_id, provider_operation_lease_token,
                       redacted_snapshot ->> 'step' AS step_key,
                       redacted_snapshot ->> 'errorCode' AS error_code,
                       (redacted_snapshot ->> 'attempt')::INTEGER AS attempt
                  FROM prv_audit_events
                 WHERE action = ?
                   AND target_id = ?
                """, action, operationId.toString()))
                .containsEntry("target_type", "PROVIDER_OPERATION")
                .containsEntry("target_id", operationId.toString())
                .containsEntry("outcome", "FAILED")
                .containsEntry("event_category", eventCategory)
                .containsEntry("provider_operation_id", null)
                .containsEntry("provider_operation_lease_token", null)
                .containsEntry("step_key", stepKey)
                .containsEntry("error_code", errorCode)
                .containsEntry("attempt", attempt);
    }

    private void approval(
            UUID operationId,
            String gateKey,
            String lifecycleState,
            String requiredRole,
            long operatorId,
            Long deciderId) {
        jdbc.update("""
                INSERT INTO prv_operation_approvals (
                    operation_id, gate_key, lifecycle_state, required_role_code,
                    requested_by, decided_by, request_reason, decision_reason, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, 'migration regression', ?, ?)
                """, operationId, gateKey, lifecycleState, requiredRole, operatorId,
                deciderId,
                deciderId == null ? null : "approved before migration",
                deciderId == null ? null : java.sql.Timestamp.from(java.time.Instant.now()));
    }

    private void oldShapeApproval(UUID operationId, long operatorId) {
        jdbc.update("""
                INSERT INTO prv_operation_approvals (
                    operation_id, gate_key, lifecycle_state, required_role_code,
                    separation_of_duties, requested_by, request_reason)
                VALUES (?, 'RISK_REVIEW', 'PENDING', 'PROVIDER_ADMIN', TRUE, ?,
                        'old provider node regression')
                ON CONFLICT (operation_id, gate_key) DO NOTHING
                """, operationId, operatorId);
    }

    private void nonSeparatedApproval(UUID operationId, long operatorId) {
        jdbc.update("""
                INSERT INTO prv_operation_approvals (
                    operation_id, gate_key, lifecycle_state, required_role_code,
                    separation_of_duties, requested_by, request_reason)
                VALUES (?, 'RISK_REVIEW', 'PENDING', 'PROVIDER_CHANGE_APPROVER', FALSE, ?,
                        'non-separated provider approval regression')
                """, operationId, operatorId);
    }

    private void approve(UUID operationId, String gateKey, long deciderId) {
        jdbc.update("""
                UPDATE prv_operation_approvals
                   SET lifecycle_state = 'APPROVED',
                       decided_by = ?,
                       decision_reason = 'migration regression approved',
                       decided_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE operation_id = ?
                   AND gate_key = ?
                """, deciderId, operationId, gateKey);
    }

    private Map<String, Object> approvalState(UUID operationId) {
        return jdbc.queryForMap("""
                SELECT lifecycle_state, required_role_code, separation_of_duties, version
                  FROM prv_operation_approvals
                 WHERE operation_id = ?
                """, operationId);
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
