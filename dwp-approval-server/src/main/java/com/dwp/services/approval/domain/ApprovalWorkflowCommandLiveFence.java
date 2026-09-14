package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Commands lock the server-owned request before any task projection can acquire a shared lock. */
final class ApprovalWorkflowCommandLiveFence {
    static void task(NamedParameterJdbcTemplate jdbc, long tenant, UUID task) {
        if (jdbc == null || task == null || !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Approval commands require a current retention-fenced transaction.");
        }
        var requests = jdbc.query("SELECT request_id FROM apr_tasks WHERE tenant_id=:tenant AND task_id=:task",
                Map.of("tenant", tenant, "task", task), (row, index) -> row.getObject(1, UUID.class));
        if (requests.size() != 1) throw new BaseException(ErrorCode.NOT_FOUND, "Approval resource is unavailable.");
        new ApprovalRetentionLiveGuard(jdbc).writeRequest(tenant, requests.getFirst());
    }
    private ApprovalWorkflowCommandLiveFence() { }
}
