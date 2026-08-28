package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Repository
public class ProviderOperationEvidenceRepository {

    private static final String RECOVERY_CODE = "OPERATION_LEASE_EXPIRED";
    private static final String RECOVERY_MESSAGE =
            "The prior provider worker lease expired before recording a terminal result.";

    private final JdbcTemplate jdbc;

    public ProviderOperationEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void abandonRunning(UUID operationId, UUID leaseToken, Duration leaseDuration) {
        renewOwned(operationId, leaseToken, leaseDuration);
        jdbc.update("""
                UPDATE prv_operation_step_attempts attempt
                   SET lifecycle_state = 'ABANDONED',
                       error_code = ?,
                       error_message = ?,
                       completed_at = COALESCE(attempt.completed_at, CURRENT_TIMESTAMP)
                  FROM prv_operation_steps step
                 WHERE step.operation_step_id = attempt.operation_step_id
                   AND step.operation_id = ?
                   AND attempt.lifecycle_state = 'RUNNING'
                """, RECOVERY_CODE, RECOVERY_MESSAGE, operationId);
        jdbc.update("""
                UPDATE prv_operation_steps
                   SET lifecycle_state = 'FAILED',
                       last_error_code = ?,
                       last_error_message = ?,
                       completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP)
                 WHERE operation_id = ?
                   AND lifecycle_state = 'RUNNING'
                """, RECOVERY_CODE, RECOVERY_MESSAGE, operationId);
    }

    @Transactional
    public int startAttempt(
            UUID operationId,
            UUID leaseToken,
            Duration leaseDuration,
            Long operationStepId,
            String requestFingerprint) {
        renewOwned(operationId, leaseToken, leaseDuration);
        List<Integer> attempts = jdbc.queryForList("""
                UPDATE prv_operation_steps
                   SET lifecycle_state = 'RUNNING',
                       attempt_count = attempt_count + 1,
                       started_at = CURRENT_TIMESTAMP,
                       completed_at = NULL,
                       last_error_code = NULL,
                       last_error_message = NULL,
                       next_retry_at = NULL
                 WHERE operation_step_id = ?
                   AND operation_id = ?
                   AND lifecycle_state <> 'SUCCEEDED'
                 RETURNING attempt_count
                """, Integer.class, operationStepId, operationId);
        if (attempts.size() != 1) {
            throw invalidEvidence("The provider operation step cannot start in its current state.");
        }
        int attemptNumber = attempts.get(0);
        int inserted = jdbc.update("""
                INSERT INTO prv_operation_step_attempts (
                    operation_step_id, attempt_number, lifecycle_state, request_fingerprint)
                VALUES (?, ?, 'RUNNING', ?)
                """, operationStepId, attemptNumber, requestFingerprint);
        if (inserted != 1) {
            throw invalidEvidence("The provider operation attempt evidence was not created.");
        }
        return attemptNumber;
    }

    @Transactional
    public void succeedAttempt(
            UUID operationId,
            UUID leaseToken,
            Duration leaseDuration,
            Long operationStepId,
            int attemptNumber,
            String externalReference,
            String redactedResult) {
        renewOwned(operationId, leaseToken, leaseDuration);
        int stepUpdated = jdbc.update("""
                UPDATE prv_operation_steps
                   SET lifecycle_state = 'SUCCEEDED',
                       external_reference = ?,
                       redacted_result = CAST(? AS jsonb),
                       completed_at = CURRENT_TIMESTAMP
                 WHERE operation_step_id = ?
                   AND operation_id = ?
                   AND lifecycle_state = 'RUNNING'
                   AND attempt_count = ?
                """, externalReference, redactedResult, operationStepId, operationId, attemptNumber);
        int attemptUpdated = jdbc.update("""
                UPDATE prv_operation_step_attempts
                   SET lifecycle_state = 'SUCCEEDED',
                       redacted_result = CAST(? AS jsonb),
                       completed_at = CURRENT_TIMESTAMP
                 WHERE operation_step_id = ?
                   AND attempt_number = ?
                   AND lifecycle_state = 'RUNNING'
                """, redactedResult, operationStepId, attemptNumber);
        requireExactEvidence(stepUpdated, attemptUpdated);
    }

    @Transactional
    public void failAttempt(
            UUID operationId,
            UUID leaseToken,
            Duration leaseDuration,
            Long operationStepId,
            int attemptNumber,
            String errorCode,
            String errorMessage) {
        renewOwned(operationId, leaseToken, leaseDuration);
        int stepUpdated = jdbc.update("""
                UPDATE prv_operation_steps
                   SET lifecycle_state = 'FAILED',
                       last_error_code = ?,
                       last_error_message = ?,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE operation_step_id = ?
                   AND operation_id = ?
                   AND lifecycle_state = 'RUNNING'
                   AND attempt_count = ?
                """, errorCode, errorMessage, operationStepId, operationId, attemptNumber);
        int attemptUpdated = jdbc.update("""
                UPDATE prv_operation_step_attempts
                   SET lifecycle_state = 'FAILED',
                       error_code = ?,
                       error_message = ?,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE operation_step_id = ?
                   AND attempt_number = ?
                   AND lifecycle_state = 'RUNNING'
                """, errorCode, errorMessage, operationStepId, attemptNumber);
        requireExactEvidence(stepUpdated, attemptUpdated);
    }

    private void renewOwned(UUID operationId, UUID leaseToken, Duration leaseDuration) {
        try {
            jdbc.queryForList(
                    "SELECT prv_bind_provider_operation_lease(CAST(? AS uuid), CAST(? AS uuid))",
                    operationId, leaseToken);
        } catch (org.springframework.dao.DataAccessException exception) {
            throw new ProviderOperationLeaseRepository.OperationLeaseLostException(exception);
        }
        int updated = jdbc.update("""
                UPDATE prv_operations
                   SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE operation_id = ?
                   AND lifecycle_state = 'EXECUTING'
                   AND lease_token = ?
                   AND lease_expires_at > CURRENT_TIMESTAMP
                """, leaseDuration.toMillis(), operationId, leaseToken);
        if (updated != 1) throw new ProviderOperationLeaseRepository.OperationLeaseLostException();
    }

    private void requireExactEvidence(int stepUpdated, int attemptUpdated) {
        if (stepUpdated != 1 || attemptUpdated != 1) {
            throw invalidEvidence("Provider operation evidence changed while its result was recorded.");
        }
    }

    private BaseException invalidEvidence(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }
}
