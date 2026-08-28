package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Repository
public class ProviderOperationLeaseRepository {

    private final JdbcTemplate jdbc;

    public ProviderOperationLeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID claim(
            UUID operationId,
            long expectedVersion,
            boolean retry,
            String workerId,
            Duration leaseDuration) {
        UUID token = UUID.randomUUID();
        int updated = jdbc.update(retry ? """
                UPDATE prv_operations
                   SET lifecycle_state = 'EXECUTING',
                       failure_code = NULL,
                       failure_message = NULL,
                       completed_at = NULL,
                       started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                       lease_owner = ?,
                       lease_token = ?,
                       lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                       updated_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE operation_id = ?
                   AND version = ?
                   AND (
                       lifecycle_state IN ('PARTIAL', 'FAILED')
                       OR (
                           lifecycle_state = 'EXECUTING'
                           AND (lease_expires_at IS NULL OR lease_expires_at <= CURRENT_TIMESTAMP)
                       )
                   )
                   AND (lease_expires_at IS NULL OR lease_expires_at <= CURRENT_TIMESTAMP)
                """ : """
                UPDATE prv_operations
                   SET lifecycle_state = 'EXECUTING',
                       failure_code = NULL,
                       failure_message = NULL,
                       completed_at = NULL,
                       started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                       lease_owner = ?,
                       lease_token = ?,
                       lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                       updated_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE operation_id = ?
                   AND version = ?
                   AND lifecycle_state = 'PREVIEWED'
                   AND lease_token IS NULL
                """, workerId, token, leaseDuration.toMillis(), operationId, expectedVersion);
        if (updated != 1) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The provider operation is already executing or changed before its lease was claimed.");
        }
        return token;
    }

    @Transactional
    public void complete(UUID operationId, UUID leaseToken) {
        bindOwnedExecution(operationId, leaseToken);
        int updated = jdbc.update("""
                UPDATE prv_operations
                   SET lifecycle_state = 'SUCCEEDED',
                       failure_code = NULL,
                       failure_message = NULL,
                       completed_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL,
                       lease_token = NULL,
                       lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE operation_id = ?
                   AND lifecycle_state = 'EXECUTING'
                   AND lease_token = ?
                   AND lease_expires_at > CURRENT_TIMESTAMP
                """, operationId, leaseToken);
        if (updated != 1) throw new OperationLeaseLostException();
    }

    @Transactional
    public void renewOwned(UUID operationId, UUID leaseToken, Duration leaseDuration) {
        bindOwnedExecution(operationId, leaseToken);
        int updated = jdbc.update("""
                UPDATE prv_operations
                   SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE operation_id = ?
                   AND lifecycle_state = 'EXECUTING'
                   AND lease_token = ?
                   AND lease_expires_at > CURRENT_TIMESTAMP
                """, leaseDuration.toMillis(), operationId, leaseToken);
        if (updated != 1) throw new OperationLeaseLostException();
    }

    @Transactional
    public void markPartial(
            UUID operationId,
            UUID leaseToken,
            String failureCode,
            String failureMessage) {
        bindOwnedExecution(operationId, leaseToken);
        int updated = jdbc.update("""
                UPDATE prv_operations
                   SET lifecycle_state = 'PARTIAL',
                       failure_code = ?,
                       failure_message = ?,
                       lease_owner = NULL,
                       lease_token = NULL,
                       lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE operation_id = ?
                   AND lifecycle_state = 'EXECUTING'
                   AND lease_token = ?
                   AND lease_expires_at > CURRENT_TIMESTAMP
                """, bounded(failureCode, 80), bounded(failureMessage, 1000), operationId, leaseToken);
        if (updated != 1) throw new OperationLeaseLostException();
    }

    private void bindOwnedExecution(UUID operationId, UUID leaseToken) {
        try {
            jdbc.queryForList(
                    "SELECT prv_bind_provider_operation_lease(CAST(? AS uuid), CAST(? AS uuid))",
                    operationId, leaseToken);
        } catch (org.springframework.dao.DataAccessException exception) {
            throw new OperationLeaseLostException(exception);
        }
    }

    private String bounded(String value, int limit) {
        if (value == null) return null;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    static final class OperationLeaseLostException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        OperationLeaseLostException() {
            super("The provider operation lease was lost before fenced state could be persisted.");
        }

        OperationLeaseLostException(Throwable cause) {
            super("The provider operation lease was lost before fenced state could be persisted.", cause);
        }
    }
}
