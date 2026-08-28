package com.dwp.services.provider.provisioning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

final class ProviderOnboardingOperationTestFixture {

    private static final Duration OPERATION_LEASE = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final long operatorId;
    private final UUID tenantId;

    ProviderOnboardingOperationTestFixture(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            long operatorId,
            UUID tenantId) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.operatorId = operatorId;
        this.tenantId = tenantId;
    }

    UUID claim(UUID operationId) {
        jdbc.update("""
                INSERT INTO prv_operations (
                    operation_id, provider_tenant_id, operation_type, idempotency_key,
                    lifecycle_state, risk_tier, requested_by, justification, plan_hash, plan)
                VALUES (?, ?, 'TENANT_ONBOARD', ?, 'PREVIEWED', 'L2', ?,
                        'Activation and containment race regression', ?, '{}'::jsonb)
                """, operationId, tenantId, "pg-onboarding-race:" + operationId,
                operatorId, "a".repeat(64));
        return new ProviderOperationLeaseRepository(jdbc).claim(
                operationId, 0L, false, "pg-race-worker", OPERATION_LEASE);
    }

    void finish(UUID operationId, UUID operationLeaseToken) {
        ProviderOperationLeaseRepository operationLeases =
                new ProviderOperationLeaseRepository(jdbc);
        transactions.executeWithoutResult(status -> {
            operationLeases.renewOwned(
                    operationId, operationLeaseToken, OPERATION_LEASE);
            jdbc.update("""
                    INSERT INTO prv_audit_events (
                        audit_event_id, actor_id, action, target_type, target_id,
                        outcome, correlation_id, redacted_snapshot, provider_operator_id,
                        provider_tenant_id, organization_id, event_category)
                    SELECT gen_random_uuid(), operator.auth_user_id,
                           'provider.tenant-onboarding.step-failed',
                           'PROVIDER_OPERATION', ?, 'FAILED', 'corr-race-cleanup',
                           '{"errorCode":"TEST_CLEANUP"}'::jsonb,
                           operator.provider_operator_id, tenant.provider_tenant_id,
                           tenant.organization_id, 'TENANT_LIFECYCLE'
                      FROM prv_operators operator
                      JOIN prv_tenants tenant ON tenant.provider_tenant_id = ?
                     WHERE operator.provider_operator_id = ?
                    """, operationId.toString(), tenantId, operatorId);
            operationLeases.markPartial(
                    operationId, operationLeaseToken,
                    "TEST_CLEANUP", "Race regression completed.");
        });
    }

    void cleanup(UUID operationId, UUID operationLeaseToken) {
        ProviderOperationLeaseRepository operationLeases =
                new ProviderOperationLeaseRepository(jdbc);
        transactions.executeWithoutResult(status -> {
            operationLeases.renewOwned(
                    operationId, operationLeaseToken, OPERATION_LEASE);
            jdbc.update("""
                    DELETE FROM prv_tenant_command_outbox command
                     USING prv_tenant_mutations mutation
                     WHERE mutation.mutation_id = command.mutation_id
                       AND mutation.desired_payload ->> 'providerOperationId' = ?
                    """, operationId.toString());
            jdbc.update("""
                    DELETE FROM prv_tenant_mutations
                     WHERE desired_payload ->> 'providerOperationId' = ?
                    """, operationId.toString());
            jdbc.update("""
                    UPDATE prv_tenants
                       SET lifecycle_state = 'SUSPENDED', onboarding_state = 'READY',
                           entitlement_revision = 0, version = 0
                     WHERE provider_tenant_id = ?
                    """, tenantId);
        });
        finish(operationId, operationLeaseToken);
    }
}
