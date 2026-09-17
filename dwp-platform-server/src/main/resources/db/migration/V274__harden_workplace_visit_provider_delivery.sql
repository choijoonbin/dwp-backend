ALTER TABLE wp_visit_outbox
    ADD COLUMN provider_kind VARCHAR(16),
    ADD COLUMN provider_code VARCHAR(80),
    ADD COLUMN provider_configuration_version BIGINT;

-- Existing in-flight rows predate immutable binding capture.  The currently active binding might
-- have rotated after the provider accepted the mutation, so attributing those rows to it would
-- permit a cross-version redispatch.  Their NULL snapshot is intentional: the worker converts a
-- queued legacy mutation to RESULT_UNKNOWN without calling a provider and status lookup remains
-- fail-closed for manual recovery.  Only operations inserted after this migration receive a
-- binding snapshot at acceptance time.

UPDATE wp_visit_outbox status
   SET provider_kind = original.provider_kind,
       provider_code = original.provider_code,
       provider_configuration_version = original.provider_configuration_version
  FROM wp_visit_outbox original
 WHERE status.tenant_id = original.tenant_id
   AND status.related_outbox_id = original.outbox_id
   AND status.operation_type = 'CHECK_PROVIDER_STATUS'
   AND status.delivery_state IN ('PENDING', 'RETRY', 'PROCESSING', 'RESULT_UNKNOWN');

-- Earlier workers could terminally park an uncertain status lookup.  Lookup is read-only and may
-- safely resume with the same original operation id after this upgrade.
UPDATE wp_visit_outbox status
   SET delivery_state = 'RETRY',
       next_attempt_at = CURRENT_TIMESTAMP,
       updated_at = CURRENT_TIMESTAMP
 WHERE status.operation_type = 'CHECK_PROVIDER_STATUS'
   AND status.delivery_state = 'RESULT_UNKNOWN'
   AND EXISTS (
       SELECT 1 FROM wp_visit_outbox original
        WHERE original.tenant_id = status.tenant_id
          AND original.outbox_id = status.related_outbox_id
          AND original.delivery_state = 'RESULT_UNKNOWN'
   );

ALTER TABLE wp_visit_outbox
    ADD CONSTRAINT ck_wp_visit_outbox_provider_snapshot CHECK (
        (provider_kind IS NULL AND provider_code IS NULL
            AND provider_configuration_version IS NULL)
        OR
        (provider_kind IN ('VISITOR', 'ACCESS')
            AND provider_code IS NOT NULL
            AND provider_configuration_version > 0)
    );

CREATE INDEX idx_wp_visit_outbox_global_pending
    ON wp_visit_outbox(delivery_state, next_attempt_at, created_at, outbox_id)
    WHERE delivery_state IN ('PENDING', 'RETRY', 'PROCESSING');

COMMENT ON COLUMN wp_visit_outbox.provider_code IS
    'Immutable non-secret provider binding snapshot captured at acceptance; NULL on ambiguous legacy operations.';
