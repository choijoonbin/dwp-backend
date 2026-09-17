ALTER TABLE wp_booking_batches
    ADD COLUMN execution_claim_token UUID,
    ADD COLUMN execution_lease_expires_at TIMESTAMPTZ,
    ADD COLUMN execution_attempt_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE wp_booking_batches
    ADD CONSTRAINT ck_wp_booking_batch_execution_attempts
        CHECK (execution_attempt_count >= 0),
    ADD CONSTRAINT ck_wp_booking_batch_execution_lease
        CHECK ((execution_claim_token IS NULL AND execution_lease_expires_at IS NULL)
            OR (execution_claim_token IS NOT NULL AND execution_lease_expires_at IS NOT NULL));

-- A PROCESSING item written by the pre-fencing executor may already have reached its
-- owner. It cannot be replayed safely, so preserve the uncertainty for operator requery.
UPDATE wp_booking_batch_items
   SET item_state = 'RESULT_UNKNOWN',
       error_code = 'INTERRUPTED_OWNER_RESULT_UNKNOWN',
       error_message = 'Execution was interrupted before the owner result was durably recorded.',
       compensation_available = FALSE,
       requery_required = TRUE,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP
 WHERE item_state = 'PROCESSING'
   AND updated_at <= CURRENT_TIMESTAMP - INTERVAL '30 seconds';

UPDATE wp_booking_batch_items
   SET item_state = 'COMPENSATION_FAILED',
       error_code = 'INTERRUPTED_COMPENSATION_RESULT_UNKNOWN',
       error_message = 'Compensation was interrupted before its owner result was durably recorded.',
       compensation_available = FALSE,
       requery_required = TRUE,
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP
 WHERE item_state = 'COMPENSATION_PENDING'
   AND updated_at <= CURRENT_TIMESTAMP - INTERVAL '30 seconds';

CREATE INDEX idx_wp_booking_batch_execution_recovery
    ON wp_booking_batches (batch_state, execution_lease_expires_at, updated_at)
    WHERE batch_state IN ('ACCEPTED', 'PROCESSING', 'COMPENSATING');
