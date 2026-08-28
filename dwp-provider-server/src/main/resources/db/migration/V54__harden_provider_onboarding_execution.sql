-- Forward-only hardening for provider onboarding approval, crash recovery,
-- and multi-instance operation ownership.

UPDATE prv_operation_approvals
   SET required_role_code = 'PROVIDER_CHANGE_APPROVER',
       version = version + 1
 WHERE gate_key = 'RISK_REVIEW'
   AND lifecycle_state = 'PENDING'
   AND decided_at IS NULL
   AND required_role_code = 'PROVIDER_ADMIN'
   AND EXISTS (
       SELECT 1
         FROM prv_operations operation
        WHERE operation.operation_id = prv_operation_approvals.operation_id
          AND operation.risk_tier = 'L3'
   );

ALTER TABLE prv_operations
    ADD COLUMN lease_owner VARCHAR(120),
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

ALTER TABLE prv_operation_step_attempts
    DROP CONSTRAINT ck_prv_operation_step_attempts_state;

ALTER TABLE prv_operation_step_attempts
    ADD CONSTRAINT ck_prv_operation_step_attempts_state
        CHECK (lifecycle_state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'ABANDONED'));

UPDATE prv_operation_step_attempts attempt
   SET lifecycle_state = 'ABANDONED',
       error_code = 'DEPLOYMENT_RECOVERY_REQUIRED',
       error_message = 'The prior provider worker stopped before recording a terminal result.',
       completed_at = COALESCE(attempt.completed_at, CURRENT_TIMESTAMP)
  FROM prv_operation_steps step
 WHERE step.operation_step_id = attempt.operation_step_id
   AND attempt.lifecycle_state = 'RUNNING';

UPDATE prv_operation_steps
   SET lifecycle_state = 'FAILED',
       last_error_code = 'DEPLOYMENT_RECOVERY_REQUIRED',
       last_error_message = 'The prior provider worker stopped before recording a terminal result.',
       completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP)
 WHERE lifecycle_state = 'RUNNING';

UPDATE prv_operations
   SET lifecycle_state = 'PARTIAL',
       failure_code = 'DEPLOYMENT_RECOVERY_REQUIRED',
       failure_message = 'The prior provider worker stopped before releasing the operation.',
       updated_at = CURRENT_TIMESTAMP,
       version = version + 1
 WHERE lifecycle_state = 'EXECUTING';

ALTER TABLE prv_operations
    ADD CONSTRAINT ck_prv_operations_execution_lease
        CHECK (
            (lifecycle_state = 'EXECUTING'
             AND lease_owner IS NOT NULL
             AND lease_token IS NOT NULL
             AND lease_expires_at IS NOT NULL)
            OR
            (lifecycle_state <> 'EXECUTING'
             AND lease_owner IS NULL
             AND lease_token IS NULL
             AND lease_expires_at IS NULL)
        );

CREATE INDEX idx_prv_operations_execution_lease
    ON prv_operations(lease_expires_at)
    WHERE lifecycle_state = 'EXECUTING';

COMMENT ON COLUMN prv_operations.lease_token IS
    'Opaque fencing token for the single provider worker allowed to persist onboarding evidence.';
COMMENT ON COLUMN prv_operations.lease_expires_at IS
    'Bounded operation lease expiry; an expired EXECUTING operation may be reclaimed by explicit retry.';
