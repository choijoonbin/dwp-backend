-- Freeze the exact provider identity used by each safety dispatch. This keeps retries and
-- read-only reconciliation on the original provider configuration after an admin changes it.
ALTER TABLE wp_safety_dispatch_attempts
    ADD COLUMN provider_code VARCHAR(80),
    ADD COLUMN provider_configuration_version BIGINT,
    ADD COLUMN provider_credential_reference VARCHAR(180),
    ADD COLUMN dispatch_started_at TIMESTAMPTZ;

-- A pre-upgrade in-flight call has no trustworthy provider identity to query or replay. Preserve
-- that uncertainty explicitly; never infer failure or repeat a possibly accepted emergency send.
UPDATE wp_safety_dispatch_attempts
   SET attempt_state='RESULT_UNKNOWN',
       result_code='LEGACY_DISPATCH_OUTCOME_UNKNOWN',
       next_reconcile_at=CURRENT_TIMESTAMP,
       updated_at=CURRENT_TIMESTAMP
 WHERE attempt_state='DISPATCHING';

ALTER TABLE wp_safety_dispatch_attempts
    ADD CONSTRAINT ck_wp_safety_attempt_provider_snapshot CHECK (
        (provider_code IS NULL
         AND provider_configuration_version IS NULL
         AND provider_credential_reference IS NULL)
        OR
        (provider_code ~ '^[A-Za-z0-9._-]{1,80}$'
         AND provider_configuration_version > 0
         AND provider_credential_reference ~ '^secret-manager://[A-Za-z0-9._/-]{1,143}$')
    ),
    ADD CONSTRAINT ck_wp_safety_attempt_dispatch_started CHECK (
        dispatch_started_at IS NULL OR provider_code IS NOT NULL
    );

CREATE INDEX idx_wp_safety_attempt_dispatch_recovery
    ON wp_safety_dispatch_attempts(attempt_state, dispatch_started_at, dispatch_attempt_id)
    WHERE attempt_state='DISPATCHING';

CREATE INDEX idx_wp_safety_attempt_result_recovery
    ON wp_safety_dispatch_attempts(attempt_state, next_reconcile_at, dispatch_attempt_id)
    WHERE attempt_state='RESULT_UNKNOWN';
