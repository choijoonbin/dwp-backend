-- Make exhausted safety delivery explicit and extend durable administrator booking receipts.

ALTER TABLE wp_safety_dispatch_attempts
    DROP CONSTRAINT ck_wp_safety_attempt_state,
    DROP CONSTRAINT ck_wp_safety_attempt_recovery,
    ADD CONSTRAINT ck_wp_safety_attempt_state CHECK (attempt_state IN
        ('QUEUED','DISPATCHING','DELIVERED','DELIVERY_FAILED','OFFLINE_QUEUED',
         'MANUAL_REVIEW_REQUIRED','RESULT_UNKNOWN')),
    ADD CONSTRAINT ck_wp_safety_attempt_recovery CHECK
        ((attempt_state='OFFLINE_QUEUED' AND next_attempt_at IS NOT NULL)
         OR (attempt_state='MANUAL_REVIEW_REQUIRED' AND next_attempt_at IS NULL)
         OR attempt_state NOT IN ('OFFLINE_QUEUED','MANUAL_REVIEW_REQUIRED'));

CREATE TEMP TABLE tmp_v343_exhausted_safety_batches (
    tenant_id BIGINT NOT NULL,
    dispatch_batch_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, dispatch_batch_id)
) ON COMMIT DROP;

INSERT INTO tmp_v343_exhausted_safety_batches(tenant_id, dispatch_batch_id)
SELECT DISTINCT tenant_id, dispatch_batch_id
  FROM wp_safety_dispatch_attempts
 WHERE attempt_state='OFFLINE_QUEUED' AND offline_retry_count>=3;

UPDATE wp_safety_dispatch_attempts
   SET attempt_state='MANUAL_REVIEW_REQUIRED',
       next_attempt_at=NULL,
       result_code=COALESCE(result_code, 'OFFLINE_RETRY_EXHAUSTED'),
       updated_at=CURRENT_TIMESTAMP
 WHERE attempt_state='OFFLINE_QUEUED' AND offline_retry_count>=3;

WITH counts AS (
    SELECT attempt.tenant_id,attempt.dispatch_batch_id,
           COUNT(*) FILTER (WHERE attempt_state='DELIVERED')::INTEGER delivered,
           COUNT(*) FILTER (WHERE attempt_state IN
               ('DELIVERY_FAILED','MANUAL_REVIEW_REQUIRED'))::INTEGER failed,
           COUNT(*) FILTER (WHERE attempt_state='RESULT_UNKNOWN')::INTEGER unknown,
           COUNT(*) FILTER (WHERE attempt_state IN
               ('QUEUED','DISPATCHING','OFFLINE_QUEUED'))::INTEGER pending
      FROM wp_safety_dispatch_attempts attempt
      JOIN tmp_v343_exhausted_safety_batches affected
        ON affected.tenant_id=attempt.tenant_id
       AND affected.dispatch_batch_id=attempt.dispatch_batch_id
     GROUP BY attempt.tenant_id,attempt.dispatch_batch_id
)
UPDATE wp_safety_dispatch_batches batch
   SET delivered_count=counts.delivered,
       failed_count=counts.failed,
       unknown_count=counts.unknown,
       dispatch_state=CASE
           WHEN counts.pending>0 THEN 'DISPATCHING'
           WHEN counts.unknown>0 THEN 'RESULT_UNKNOWN'
           WHEN counts.delivered>0 AND counts.failed>0 THEN 'PARTIAL'
           WHEN counts.failed>0 THEN 'FAILED'
           ELSE 'SUCCEEDED' END,
       version=batch.version+1,
       updated_at=CURRENT_TIMESTAMP
  FROM counts
 WHERE batch.tenant_id=counts.tenant_id
   AND batch.dispatch_batch_id=counts.dispatch_batch_id;

UPDATE wp_safety_commands command
   SET command_state=CASE
           WHEN batch.dispatch_state='SUCCEEDED' THEN 'SUCCEEDED'
           WHEN batch.dispatch_state IN ('FAILED','PARTIAL') THEN 'FAILED'
           WHEN batch.dispatch_state='RESULT_UNKNOWN' THEN 'RESULT_UNKNOWN'
           ELSE command.command_state END,
       completed_at=CASE WHEN batch.dispatch_state IN
           ('SUCCEEDED','FAILED','PARTIAL','RESULT_UNKNOWN')
           THEN CURRENT_TIMESTAMP ELSE NULL END,
       version=command.version+1,
       updated_at=CURRENT_TIMESTAMP
  FROM wp_safety_dispatch_batches batch
  JOIN tmp_v343_exhausted_safety_batches affected
    ON affected.tenant_id=batch.tenant_id
   AND affected.dispatch_batch_id=batch.dispatch_batch_id
 WHERE command.tenant_id=batch.tenant_id
   AND command.command_id=batch.command_id;

CREATE INDEX idx_wp_safety_attempt_manual_review
    ON wp_safety_dispatch_attempts(tenant_id,updated_at,dispatch_attempt_id)
    WHERE attempt_state='MANUAL_REVIEW_REQUIRED';

ALTER TABLE wp_booking_commands
    DROP CONSTRAINT ck_wp_booking_command_type,
    ADD CONSTRAINT ck_wp_booking_command_type CHECK (command_type IN
        ('CHECK_IN','CANCEL','RELEASE','RELOCATE','FORCE_CANCEL','LEGAL_HOLD'));

-- V322 registered both CHECK-backed code sets. Activate the additive values so the
-- administrator catalog remains aligned with the database constraints.
INSERT INTO sys_code_values (
    code_set_key,code,display_name,label_i18n,behavior_metadata,
    sort_order,predefined,lifecycle_state)
SELECT code_set.code_set_key,value_ref.code,value_ref.code,
       jsonb_build_object('ko',value_ref.code,'en',value_ref.code),
       '{}'::jsonb,value_ref.sort_order,TRUE,'ACTIVE'
  FROM sys_code_sets code_set
  JOIN (VALUES
      ('wp_safety_dispatch_attempts.attempt_state',
       'MANUAL_REVIEW_REQUIRED',70),
      ('wp_booking_commands.command_type','FORCE_CANCEL',50),
      ('wp_booking_commands.command_type','LEGAL_HOLD',60)
  ) value_ref(source_reference,code,sort_order)
    ON value_ref.source_reference=code_set.source_reference
 WHERE code_set.owner_service='dwp-platform-server'
ON CONFLICT (code_set_key,code) DO UPDATE SET
    lifecycle_state='ACTIVE',updated_at=CURRENT_TIMESTAMP;

COMMENT ON COLUMN wp_safety_dispatch_attempts.attempt_state IS
    'MANUAL_REVIEW_REQUIRED is terminal after the third offline retry; administrator resend creates a new attempt.';
COMMENT ON TABLE wp_booking_commands IS
    'Durable receipts for member and administrator booking commands; replay snapshots are deleted on governed anonymization.';
