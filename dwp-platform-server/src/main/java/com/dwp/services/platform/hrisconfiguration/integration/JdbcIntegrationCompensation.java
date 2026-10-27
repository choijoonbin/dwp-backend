package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal jdbc integration compensation operations shared by the owner facade. */
final class JdbcIntegrationCompensation {

    private final JdbcIntegrationRepositoryOperations owner;
    private final JdbcTemplate jdbc;

    JdbcIntegrationCompensation(
            JdbcIntegrationRepositoryOperations owner,
            JdbcTemplate jdbc) {
        this.owner = owner;
        this.jdbc = jdbc;
    }


    public CompensationClaim claimCompensation(
            ExecutionRecord current,
            String claimDigest,
            Long expectedRevision,
            OffsetDateTime now) {
        if (claimDigest == null || !claimDigest.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException(
                    "SYS008_COMPENSATION_CLAIM_DIGEST_INVALID");
        }
        if (Set.of(ExecutionState.RECONCILED, ExecutionState.DEAD_LETTER,
                ExecutionState.CANCELLED).contains(current.state())) {
            throw conflict("SYS008_COMPENSATION_CLAIM_STATE_INVALID");
        }
        String originalPrincipal = jdbc.queryForObject(
                "SELECT current_setting('dwp.principal_public_id', true)",
                String.class);
        if (originalPrincipal == null || originalPrincipal.isBlank()) {
            throw new IllegalStateException(
                    "SYS008_TRUSTED_PRINCIPAL_CONTEXT_REQUIRED");
        }
        String claimKey = "sys008-comp-claim-" + current.executionId();
        try {
            jdbc.queryForObject(
                    "SELECT set_config('dwp.principal_public_id', ?, true)",
                    String.class, COMPENSATION_CLAIM_PRINCIPAL.toString());
            List<String> existing = jdbc.query("""
                    SELECT request_digest
                      FROM sys_hris_command_receipts
                     WHERE tenant_id = ?
                       AND subject_principal_public_id = ?
                       AND originating_action = ?
                       AND idempotency_key = ?
                     FOR UPDATE
                    """, (rs, row) -> rs.getString(1), current.tenantId(),
                    COMPENSATION_CLAIM_PRINCIPAL,
                    IntegrationService.CANCEL_EXECUTION, claimKey);
            if (!existing.isEmpty()) {
                if (!claimDigest.equals(existing.getFirst())) {
                    throw conflict(
                            "SYS008_COMPENSATION_CLAIM_DIGEST_CONFLICT");
                }
                return new CompensationClaim(current, claimDigest, false);
            }
            if (expectedRevision == null
                    || expectedRevision != current.aggregateRevision()) {
                throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                        "SYS008_EXECUTION_REVISION_CONFLICT");
            }
            UUID claimReceiptId = UUID.randomUUID();
            long claimedRevision = current.aggregateRevision() + 1;
            int inserted = jdbc.update("""
                    INSERT INTO sys_hris_command_receipts (
                        public_id, tenant_id, command_type, originating_action,
                        subject_principal_public_id, population_scope_digest,
                        authority_population_scope_digest,
                        field_policy_revision, authority_field_policy_revision,
                        purpose_code, authorization_revision,
                        authority_authorization_revision,
                        origin_route_contract_key, origin_context_key,
                        origin_context_scope_key, origin_decision_revision,
                        idempotency_key, request_digest, status, correlation_id,
                        created_at)
                    VALUES (?, ?, 'COMPENSATION_EFFECT_CLAIM', ?, ?, ?, ?,
                            1, 1, ?, ?, ?, 'SYS-API-026',
                            'sys008.compensation-claim',
                            'sys008.compensation-claim', ?, ?, ?, 'ACCEPTED',
                            ?, ?)
                    """, claimReceiptId, current.tenantId(),
                    IntegrationService.CANCEL_EXECUTION,
                    COMPENSATION_CLAIM_PRINCIPAL,
                    current.requestedScopeDigest(),
                    current.requestedScopeDigest(),
                    IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE,
                    current.authorizationRevision(),
                    current.authorizationRevision(),
                    "execution:" + current.executionId(), claimKey,
                    claimDigest, current.correlationId(), now);
            if (inserted != 1) {
                throw conflict("SYS008_COMPENSATION_CLAIM_CAS_CONFLICT");
            }
            int changed = jdbc.update("""
                    UPDATE sys_connector_executions
                       SET object_revision = object_revision + 1, updated_at = ?
                     WHERE tenant_id = ? AND execution_id = ?
                       AND lifecycle_state = ? AND object_revision = ?
                    """, now, current.tenantId(), current.internalId(),
                    current.state().name(), current.aggregateRevision());
            if (changed != 1) {
                throw conflict("SYS008_COMPENSATION_CLAIM_CAS_CONFLICT");
            }
            changed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = 'RUNNING', aggregate_public_id = ?,
                           aggregate_revision = ?, result_ref = ?
                     WHERE tenant_id = ? AND public_id = ?
                       AND status = 'ACCEPTED'
                    """, current.executionId(), claimedRevision,
                    "/v1/admin/hris/connector-executions/"
                            + current.executionId(), current.tenantId(),
                    claimReceiptId);
            if (changed != 1) {
                throw conflict("SYS008_COMPENSATION_CLAIM_CAS_CONFLICT");
            }
            jdbc.queryForObject(
                    "SELECT set_config('dwp.principal_public_id', ?, true)",
                    String.class, originalPrincipal);
            ExecutionRecord claimed = owner.lockExecution(
                    current.tenantId(), current.executionId());
            return new CompensationClaim(claimed, claimDigest, true);
        } finally {
            jdbc.queryForObject(
                    "SELECT set_config('dwp.principal_public_id', ?, true)",
                    String.class, originalPrincipal);
        }
    }

    public ExecutionRecord markCompensationResultUnknown(
            ExecutionRecord current,
            String operationsExceptionRef,
            OffsetDateTime now) {
        if (operationsExceptionRef == null || operationsExceptionRef.isBlank()) {
            throw new IllegalArgumentException(
                    "SYS008_OPERATIONS_EXCEPTION_REF_REQUIRED");
        }
        if (Set.of(ExecutionState.RECONCILED, ExecutionState.DEAD_LETTER,
                ExecutionState.CANCELLED).contains(current.state())) {
            throw conflict("SYS008_COMPENSATION_UNKNOWN_STATE_INVALID");
        }
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET unresolved_external_effect = TRUE,
                       operations_exception_ref = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state = ? AND object_revision = ?
                """, operationsExceptionRef, now,
                current.tenantId(), current.internalId(),
                current.state().name(), current.aggregateRevision());
        if (changed != 1) {
            throw conflict("SYS008_COMPENSATION_UNKNOWN_CAS_CONFLICT");
        }
        return owner.lockExecution(current.tenantId(), current.executionId());
    }

    public ExecutionRecord beginReconciliation(
            ExecutionRecord current,
            String providerReceiptRef,
            String providerReceiptDigest,
            OffsetDateTime now) {
        IntegrationDomainPolicy.requireTransition(
                current.state(), ExecutionState.RECONCILING);
        int changed = jdbc.update("""
                UPDATE sys_connector_executions
                   SET lifecycle_state = 'RECONCILING',
                       provider_receipt_ref = ?,
                       provider_receipt_digest = ?,
                       reconciliation_receipt_ref = ?,
                       reconciliation_receipt_digest = ?,
                       object_revision = object_revision + 1, updated_at = ?
                 WHERE tenant_id = ? AND execution_id = ?
                   AND lifecycle_state IN ('RESULT_UNKNOWN', 'ACKNOWLEDGED')
                   AND object_revision = ?
                   AND provider_idempotency_key = ?
                """, providerReceiptRef, providerReceiptDigest,
                providerReceiptRef, providerReceiptDigest, now,
                current.tenantId(), current.internalId(),
                current.aggregateRevision(), current.providerIdempotencyKey());
        if (changed != 1) throw conflict("SYS008_RECONCILIATION_CAS_CONFLICT");
        return owner.lockExecution(current.tenantId(), current.executionId());
    }
}
