ALTER TABLE wp_connector_replay_jobs
    ADD COLUMN credential_reference VARCHAR(160),
    ADD COLUMN reconcile_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_reconcile_at TIMESTAMPTZ,
    ADD COLUMN sensitive_payload_expires_at TIMESTAMPTZ,
    ADD COLUMN sensitive_payload_purged_at TIMESTAMPTZ;

-- A legacy job may have been accepted against an older provider configuration.  The mutable
-- configuration row is proof of that binding only when provider and version still match exactly;
-- otherwise leave the credential unset so recovery fails closed instead of querying a new owner.
UPDATE wp_connector_replay_jobs job
   SET credential_reference = configuration.configuration_reference,
       sensitive_payload_expires_at = job.requested_at + INTERVAL '90 days'
  FROM wp_experience_connector_configurations configuration
 WHERE configuration.tenant_id = job.tenant_id
   AND configuration.connector_kind = job.connector_kind
   AND configuration.provider = job.provider
   AND configuration.version = job.configuration_version
   AND configuration.configuration_reference
       ~ '^secret-manager://[A-Za-z0-9._/-]{1,143}$';

UPDATE wp_connector_replay_jobs
   SET sensitive_payload_expires_at = requested_at + INTERVAL '90 days'
 WHERE sensitive_payload_expires_at IS NULL;

ALTER TABLE wp_connector_replay_jobs
    ALTER COLUMN sensitive_payload_expires_at SET NOT NULL,
    ALTER COLUMN sensitive_payload_expires_at
        SET DEFAULT (CURRENT_TIMESTAMP + INTERVAL '90 days');

ALTER TABLE wp_connector_replay_jobs
    ADD CONSTRAINT ck_wp_connector_replay_credential_reference
        CHECK (credential_reference IS NULL
            OR credential_reference ~ '^[A-Za-z0-9._:/-]{1,160}$'),
    ADD CONSTRAINT ck_wp_connector_replay_sensitive_retention
        CHECK (sensitive_payload_expires_at >= requested_at
            AND (sensitive_payload_purged_at IS NULL
                OR sensitive_payload_purged_at >= requested_at)),
    ADD CONSTRAINT ck_wp_connector_replay_reconcile_attempt
        CHECK (reconcile_attempt_count >= 0);

CREATE UNIQUE INDEX uq_wp_connector_replay_single_active
    ON wp_connector_replay_jobs (tenant_id, connector_kind)
    WHERE replay_state IN ('QUEUED', 'DISPATCHING', 'RUNNING', 'RESULT_UNKNOWN');

COMMENT ON INDEX uq_wp_connector_replay_single_active IS
    'Database fence: at most one non-terminal provider replay may exist per tenant connector.';
COMMENT ON COLUMN wp_connector_replay_jobs.credential_reference IS
    'Opaque secret-manager owner reference snapshotted at acceptance; never a raw credential.';
COMMENT ON COLUMN wp_connector_replay_jobs.sensitive_payload_expires_at IS
    'Deadline for redacting operator reason and provider result text after terminal completion.';
