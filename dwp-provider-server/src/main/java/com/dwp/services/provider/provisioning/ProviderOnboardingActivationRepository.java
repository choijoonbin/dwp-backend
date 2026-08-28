package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.provisioning.ProviderTenantCommand;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

@Repository
public class ProviderOnboardingActivationRepository {

    private final JdbcTemplate jdbc;
    private final TenantMutationRepository mutations;
    private final TransactionTemplate transactions;

    public ProviderOnboardingActivationRepository(
            JdbcTemplate jdbc,
            TenantMutationRepository mutations,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.mutations = mutations;
        this.transactions = transactions;
    }

    public TenantMutationRepository.Mutation byIdempotencyKey(String idempotencyKey) {
        return mutations.byIdempotencyKey(idempotencyKey);
    }

    public TenantMutationRepository.Mutation create(
            TenantMutationRepository.MutationRequest request,
            OperationLease operationLease) {
        return Objects.requireNonNull(transactions.execute(status -> {
            bind(operationLease);
            return mutations.create(request);
        }));
    }

    public TenantMutationRepository.CommandLease claimNext(
            UUID mutationId,
            String workerId,
            Duration commandLeaseDuration,
            OperationLease operationLease) {
        return transactions.execute(status -> {
            bind(operationLease);
            jdbc.update("""
                    UPDATE prv_tenant_command_outbox
                       SET lifecycle_state = CASE
                               WHEN compensation THEN 'COMPENSATION_PENDING'
                               ELSE 'RETRY_WAIT'
                           END,
                           lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                           next_attempt_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP,
                           last_error_code = 'LEASE_EXPIRED',
                           last_error_message = 'Command lease expired before acknowledgement.'
                     WHERE mutation_id = ?
                       AND lifecycle_state = 'LEASED'
                       AND lease_expires_at < CURRENT_TIMESTAMP
                    """, mutationId);
            return mutations.claimNext(mutationId, workerId, commandLeaseDuration);
        });
    }

    public void markApplied(
            TenantMutationRepository.CommandLease command,
            ProviderTenantCommand.Receipt receipt,
            OperationLease operationLease) {
        transactions.executeWithoutResult(status -> {
            bind(operationLease);
            mutations.markApplied(command, receipt);
        });
    }

    public TenantMutationRepository.FailureDisposition markFailed(
            TenantMutationRepository.CommandLease command,
            int maximumAttempts,
            boolean permanent,
            String errorCode,
            String message,
            OperationLease operationLease) {
        return Objects.requireNonNull(transactions.execute(status -> {
            bind(operationLease);
            return mutations.markFailed(
                    command, maximumAttempts, permanent, errorCode, message);
        }));
    }

    public TenantMutationRepository.Completion completeIfReady(
            UUID mutationId,
            OperationLease operationLease) {
        return Objects.requireNonNull(transactions.execute(status -> {
            bind(operationLease);
            return mutations.completeIfReady(mutationId);
        }));
    }

    public void completeProjection(
            UUID mutationId,
            UUID tenantId,
            long committedTenantVersion,
            OperationLease operationLease,
            long operatorId) {
        transactions.executeWithoutResult(status -> completeProjectionInTransaction(
                mutationId, tenantId, committedTenantVersion, operationLease, operatorId));
    }

    private void completeProjectionInTransaction(
            UUID mutationId,
            UUID tenantId,
            long committedTenantVersion,
            OperationLease operationLease,
            long operatorId) {
        bind(operationLease);
        TenantRow tenant = lockTenant(tenantId);
        if (tenant.version() != committedTenantVersion
                || !"ACTIVE".equals(tenant.lifecycleState())
                || !("PENDING_EXTERNAL".equals(tenant.onboardingState())
                || "FAILED".equals(tenant.onboardingState()))) {
            throw conflict("Tenant containment superseded the onboarding activation before commit.");
        }
        Integer activeMutations = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_tenant_mutations
                 WHERE provider_tenant_id = ?
                   AND lifecycle_state IN (
                       'PENDING', 'EXECUTING', 'RETRY_WAIT',
                       'COMPENSATING', 'RECONCILIATION_REQUIRED')
                """, Integer.class, tenantId);
        if (activeMutations != null && activeMutations > 0) {
            throw conflict("A tenant containment mutation superseded onboarding activation.");
        }
        Integer exactActivation = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_tenant_mutations
                 WHERE mutation_id = ?
                   AND provider_tenant_id = ?
                   AND mutation_type = 'LIFECYCLE'
                   AND lifecycle_state = 'SUCCEEDED'
                   AND expected_tenant_version + 1 = ?
                   AND desired_payload ->> 'lifecycleState' = 'ACTIVE'
                   AND desired_payload ->> 'providerOperationId' = ?
                """, Integer.class, mutationId, tenantId, committedTenantVersion,
                operationLease.operationId().toString());
        if (exactActivation == null || exactActivation != 1) {
            throw conflict("The durable onboarding activation evidence is incomplete.");
        }
        int updated = jdbc.update("""
                UPDATE prv_tenants
                   SET onboarding_state = 'READY', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE provider_tenant_id = ?
                   AND version = ?
                   AND lifecycle_state = 'ACTIVE'
                   AND onboarding_state IN ('PENDING_EXTERNAL', 'FAILED')
                """, operatorId, tenantId, committedTenantVersion);
        if (updated != 1) {
            throw conflict("Tenant containment superseded the onboarding activation before commit.");
        }
    }

    private TenantRow lockTenant(UUID tenantId) {
        return jdbc.query("""
                SELECT lifecycle_state, onboarding_state, version
                  FROM prv_tenants
                 WHERE provider_tenant_id = ?
                 FOR UPDATE
                """, (result, ignored) -> new TenantRow(
                result.getString("lifecycle_state"), result.getString("onboarding_state"),
                result.getLong("version")), tenantId)
                .stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void bind(OperationLease operationLease) {
        try {
            jdbc.queryForList(
                    "SELECT prv_bind_provider_operation_lease(CAST(? AS uuid), CAST(? AS uuid))",
                    operationLease.operationId(), operationLease.leaseToken());
        } catch (DataAccessException exception) {
            throw new ProviderOperationLeaseRepository.OperationLeaseLostException(exception);
        }
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    public record OperationLease(UUID operationId, UUID leaseToken) {
    }

    private record TenantRow(
            String lifecycleState,
            String onboardingState,
            long version) {
    }
}
