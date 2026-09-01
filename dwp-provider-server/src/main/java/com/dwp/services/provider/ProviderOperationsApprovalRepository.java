package com.dwp.services.provider;

import com.dwp.services.provider.operation.ProviderOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class ProviderOperationsApprovalRepository {

    private final JdbcTemplate jdbc;
    private final ProviderOperationApprovalPolicy approvalPolicy;

    ProviderOperationsApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.approvalPolicy = new ProviderOperationApprovalPolicy(jdbc);
    }

    List<ProviderDtos.OperationApprovalSummary> operationApprovals(String state) {
        expireApprovals();
        String clause = state == null || state.isBlank() ? "" : " WHERE approval.lifecycle_state = ?";
        Object[] arguments = clause.isEmpty() ? new Object[0] : new Object[]{state};
        return jdbc.query("""
                SELECT approval.operation_approval_id,
                       approval.operation_id,
                       operation.provider_tenant_id,
                       tenant.display_name AS tenant_name,
                       operation.operation_type,
                       operation.risk_tier,
                       approval.gate_key,
                       approval.gate_order,
                       approval.lifecycle_state,
                       approval.required_role_code,
                       approval.separation_of_duties,
                       approval.requested_by,
                       requester.display_name AS requested_by_name,
                       approval.decided_by,
                       decider.display_name AS decided_by_name,
                       approval.request_reason,
                       approval.decision_reason,
                       approval.requested_at,
                       approval.decided_at,
                       approval.expires_at,
                       approval.version
                  FROM prv_operation_approvals approval
                  JOIN prv_operations operation ON operation.operation_id = approval.operation_id
                  LEFT JOIN prv_tenants tenant ON tenant.provider_tenant_id = operation.provider_tenant_id
                  JOIN prv_operators requester ON requester.provider_operator_id = approval.requested_by
                  LEFT JOIN prv_operators decider ON decider.provider_operator_id = approval.decided_by
                """ + clause + " ORDER BY approval.requested_at DESC LIMIT 200",
                (RowMapper<ProviderDtos.OperationApprovalSummary>) this::operationApproval, arguments);
    }

    void ensureOperationApproval(ProviderOperation operation) {
        approvalPolicy.ensureRequiredApproval(operation);
    }

    Optional<ProviderOperationsRepository.ApprovalRecord> approval(UUID approvalId) {
        expireApprovals();
        return jdbc.query("""
                SELECT operation_approval_id, operation_id, lifecycle_state,
                       required_role_code, separation_of_duties, requested_by,
                       expires_at, version
                  FROM prv_operation_approvals
                 WHERE operation_approval_id = ?
                """, (result, ignored) -> new ProviderOperationsRepository.ApprovalRecord(
                        result.getObject("operation_approval_id", UUID.class),
                        result.getObject("operation_id", UUID.class),
                        result.getString("lifecycle_state"),
                        result.getString("required_role_code"),
                        result.getBoolean("separation_of_duties"),
                        result.getLong("requested_by"),
                        instant(result, "expires_at"),
                        result.getLong("version")), approvalId).stream().findFirst();
    }

    boolean operationApproved(UUID operationId) {
        expireApprovals();
        return approvalPolicy.allRequiredApprovalsPassed(operationId);
    }

    boolean decideApproval(
            UUID approvalId,
            String decision,
            String reason,
            Long operatorId,
            long version) {
        return jdbc.update("""
                UPDATE prv_operation_approvals
                   SET lifecycle_state = ?,
                       decision_reason = ?,
                       decided_by = ?,
                       decided_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE operation_approval_id = ?
                   AND lifecycle_state = 'PENDING'
                   AND expires_at > CURRENT_TIMESTAMP
                   AND version = ?
                """, decision, reason, operatorId, approvalId, version) == 1;
    }

    private ProviderDtos.OperationApprovalSummary operationApproval(ResultSet result, int ignored)
            throws SQLException {
        return new ProviderDtos.OperationApprovalSummary(
                result.getObject("operation_approval_id", UUID.class),
                result.getObject("operation_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_name"), result.getString("operation_type"),
                result.getString("risk_tier"), result.getString("gate_key"),
                result.getInt("gate_order"), result.getString("lifecycle_state"),
                result.getString("required_role_code"), result.getBoolean("separation_of_duties"),
                result.getLong("requested_by"), result.getString("requested_by_name"),
                nullableLong(result, "decided_by"), result.getString("decided_by_name"),
                result.getString("request_reason"), result.getString("decision_reason"),
                instant(result, "requested_at"), instant(result, "decided_at"),
                instant(result, "expires_at"), result.getLong("version"));
    }

    private void expireApprovals() {
        jdbc.update("""
                UPDATE prv_operation_approvals
                   SET lifecycle_state = 'EXPIRED', version = version + 1
                 WHERE lifecycle_state = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP
                """);
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
