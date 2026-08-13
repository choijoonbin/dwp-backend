ALTER TABLE ppl_workforce_export_requests
    ADD COLUMN retry_cycle_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN manual_retry_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE ppl_workforce_export_requests
    ADD CONSTRAINT ck_workforce_export_retry_cycle_attempt_count
        CHECK (retry_cycle_attempt_count >= 0),
    ADD CONSTRAINT ck_workforce_export_manual_retry_count
        CHECK (manual_retry_count >= 0);

UPDATE ppl_workforce_export_datasets
   SET allowed_selection_keys = ARRAY[
           'view', 'asOf', 'compareTo', 'scenarioId', 'rootOrganizationId'
       ]::VARCHAR[],
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP
 WHERE dataset_key = 'ORGANIZATION_INTELLIGENCE';

ALTER TABLE ppl_workforce_export_attempt_events
    DROP CONSTRAINT ck_workforce_export_attempt_event;

ALTER TABLE ppl_workforce_export_attempt_events
    ADD CONSTRAINT ck_workforce_export_attempt_event CHECK (event_type IN (
        'BLOCKED', 'QUEUED', 'CLAIMED', 'RETRY_SCHEDULED', 'FAILED', 'COMPLETED',
        'CANCELLED', 'EXPIRED'
    ));

CREATE INDEX idx_workforce_export_artifact_expiry
    ON ppl_workforce_export_requests (artifact_expires_at, workforce_export_request_id)
    WHERE lifecycle_state = 'COMPLETED';

COMMENT ON COLUMN ppl_workforce_export_requests.retry_cycle_attempt_count IS
    'Automatic worker attempts consumed in the current governed retry cycle.';

COMMENT ON COLUMN ppl_workforce_export_requests.manual_retry_count IS
    'Explicit governor-authorized retry cycles consumed after terminal failure.';
