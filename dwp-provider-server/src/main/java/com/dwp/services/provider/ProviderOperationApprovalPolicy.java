package com.dwp.services.provider;

import com.dwp.services.provider.operation.ProviderOperation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

final class ProviderOperationApprovalPolicy {

    private final JdbcTemplate jdbc;

    ProviderOperationApprovalPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void ensureRequiredApproval(ProviderOperation operation) {
        if (!"L3".equals(operation.getRiskTier())) return;
        jdbc.update("""
                INSERT INTO prv_operation_approvals (
                    operation_id, gate_key, lifecycle_state, required_role_code,
                    separation_of_duties, requested_by, request_reason)
                VALUES (?, 'RISK_REVIEW', 'PENDING', 'PROVIDER_CHANGE_APPROVER', TRUE, ?, ?)
                ON CONFLICT (operation_id, gate_key) DO NOTHING
                """, operation.getOperationId(), operation.getRequestedBy(), operation.getJustification());
    }

    boolean allRequiredApprovalsPassed(UUID operationId) {
        Boolean approved = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM prv_operations operation
                     WHERE operation.operation_id = ?
                       AND NOT EXISTS (
                           SELECT 1
                             FROM prv_operation_approvals approval
                            WHERE approval.operation_id = operation.operation_id
                              AND approval.lifecycle_state <> 'APPROVED'
                       )
                       AND (
                           operation.risk_tier <> 'L3'
                           OR (
                               (
                                   SELECT COUNT(*)
                                     FROM prv_operation_approvals approval
                                    WHERE approval.operation_id = operation.operation_id
                                      AND approval.gate_key = 'RISK_REVIEW'
                               ) = 1
                               AND (
                                   SELECT COUNT(*)
                                     FROM prv_operation_approvals approval
                                    WHERE approval.operation_id = operation.operation_id
                                      AND approval.gate_key = 'RISK_REVIEW'
                                      AND approval.required_role_code = 'PROVIDER_CHANGE_APPROVER'
                                      AND approval.separation_of_duties
                               ) = 1
                           )
                       )
                )
                """, Boolean.class, operationId);
        return Boolean.TRUE.equals(approved);
    }
}
