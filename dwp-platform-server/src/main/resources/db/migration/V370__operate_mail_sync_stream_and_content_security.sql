-- Durable Mail synchronization, provider subscription evidence, event-stream cursors,
-- and account cursor fencing. Provider secrets and callback client-state values are
-- deliberately represented only by SHA-256 receipts.

CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_connection_tenant_identity
    ON mail_provider_connections (tenant_id, connection_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_connection_operation_tenant_identity
    ON mail_connection_operations (tenant_id, operation_id);

CREATE SEQUENCE mail_system_principal_actor_id_seq AS BIGINT
    START WITH -1 INCREMENT BY -1 MAXVALUE -1 MINVALUE -9223372036854775807 NO CYCLE;

CREATE TABLE mail_system_principals (
    tenant_id BIGINT NOT NULL,
    principal_key VARCHAR(80) NOT NULL,
    actor_id BIGINT NOT NULL DEFAULT nextval('mail_system_principal_actor_id_seq'),
    principal_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, principal_key),
    CONSTRAINT uk_mail_system_principal_actor UNIQUE (actor_id),
    CONSTRAINT ck_mail_system_principal_key CHECK (
        principal_key IN (
            'MAIL_WEBHOOK', 'MAIL_SYNC_RECOVERY', 'MAIL_SUBSCRIPTION_RENEWAL')),
    CONSTRAINT ck_mail_system_principal_actor CHECK (actor_id < 0),
    CONSTRAINT ck_mail_system_principal_evidence CHECK (
        jsonb_typeof(principal_evidence) = 'object')
);

INSERT INTO mail_system_principals (
    tenant_id, principal_key, principal_evidence)
SELECT tenant_id, principal_key,
       jsonb_build_object('kind', 'SYSTEM', 'owner', 'dwp-platform-server')
  FROM sys_service_tenants
 CROSS JOIN (VALUES ('MAIL_WEBHOOK'), ('MAIL_SYNC_RECOVERY'),
                    ('MAIL_SUBSCRIPTION_RENEWAL')) principal(principal_key)
ON CONFLICT (tenant_id, principal_key) DO NOTHING;

ALTER TABLE mail_connection_operations
    ADD CONSTRAINT fk_mail_connection_operation_tenant_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES mail_provider_connections (tenant_id, connection_id)
        ON DELETE CASCADE;

ALTER TABLE mail_accounts
    ADD COLUMN synchronization_cursor_generation BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_mail_account_cursor_generation
        CHECK (synchronization_cursor_generation >= 0);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_account_tenant_connection_identity
    ON mail_accounts (tenant_id, connection_id, account_id);

CREATE TABLE mail_sync_jobs (
    job_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    operation_id UUID NOT NULL,
    connection_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    trigger_kind VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    job_state VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    requested_connection_version BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    maximum_attempts INTEGER NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(200),
    lease_expires_at TIMESTAMPTZ,
    last_error_code VARCHAR(160),
    worker_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    cursor_reset_receipt JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_mail_sync_job_operation UNIQUE (operation_id),
    CONSTRAINT uk_mail_sync_job_tenant_identity UNIQUE (tenant_id, job_id),
    CONSTRAINT fk_mail_sync_job_operation
        FOREIGN KEY (tenant_id, operation_id)
        REFERENCES mail_connection_operations (tenant_id, operation_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_mail_sync_job_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES mail_provider_connections (tenant_id, connection_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_mail_sync_job_trigger CHECK (
        trigger_kind IN ('MANUAL', 'WEBHOOK', 'SUBSCRIPTION', 'RECOVERY')),
    CONSTRAINT ck_mail_sync_job_state CHECK (
        job_state IN ('QUEUED', 'LEASED', 'RETRY_WAIT',
                      'SUCCEEDED', 'FAILED', 'DEAD_LETTER')),
    CONSTRAINT ck_mail_sync_job_attempts CHECK (
        attempt_count >= 0 AND maximum_attempts BETWEEN 1 AND 100),
    CONSTRAINT ck_mail_sync_job_version CHECK (requested_connection_version >= 0),
    CONSTRAINT ck_mail_sync_job_evidence CHECK (
        jsonb_typeof(worker_evidence) = 'object'
        AND (cursor_reset_receipt IS NULL
             OR jsonb_typeof(cursor_reset_receipt) = 'object')),
    CONSTRAINT ck_mail_sync_job_lease CHECK (
        (job_state = 'LEASED' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (job_state <> 'LEASED' AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT ck_mail_sync_job_completion CHECK (
        (job_state IN ('SUCCEEDED', 'FAILED', 'DEAD_LETTER') AND completed_at IS NOT NULL)
        OR (job_state IN ('QUEUED', 'LEASED', 'RETRY_WAIT') AND completed_at IS NULL))
);

CREATE INDEX idx_mail_sync_job_claim
    ON mail_sync_jobs (next_attempt_at, created_at, job_id)
    WHERE job_state IN ('QUEUED', 'RETRY_WAIT');

CREATE INDEX idx_mail_sync_job_lease
    ON mail_sync_jobs (lease_expires_at)
    WHERE job_state = 'LEASED';

CREATE INDEX idx_mail_sync_job_connection
    ON mail_sync_jobs (tenant_id, connection_id, created_at DESC);

CREATE TABLE mail_sync_cursor_reset_receipts (
    reset_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    source_job_id UUID NOT NULL,
    source_operation_id UUID NOT NULL,
    account_id UUID NOT NULL,
    prior_cursor_hash CHAR(64) NOT NULL,
    reset_cursor_hash CHAR(64) NOT NULL,
    prior_generation BIGINT NOT NULL,
    reset_generation BIGINT NOT NULL,
    recovery_operation_id UUID NOT NULL,
    recovery_job_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_sync_cursor_reset_source UNIQUE (source_job_id, account_id),
    CONSTRAINT fk_mail_sync_cursor_reset_source_job
        FOREIGN KEY (tenant_id, source_job_id)
        REFERENCES mail_sync_jobs (tenant_id, job_id) ON DELETE CASCADE,
    CONSTRAINT fk_mail_sync_cursor_reset_source_operation
        FOREIGN KEY (tenant_id, source_operation_id)
        REFERENCES mail_connection_operations (tenant_id, operation_id) ON DELETE CASCADE,
    CONSTRAINT fk_mail_sync_cursor_reset_account
        FOREIGN KEY (tenant_id, account_id)
        REFERENCES mail_accounts (tenant_id, account_id) ON DELETE CASCADE,
    CONSTRAINT fk_mail_sync_cursor_reset_recovery_operation
        FOREIGN KEY (tenant_id, recovery_operation_id)
        REFERENCES mail_connection_operations (tenant_id, operation_id) ON DELETE CASCADE,
    CONSTRAINT fk_mail_sync_cursor_reset_recovery_job
        FOREIGN KEY (tenant_id, recovery_job_id)
        REFERENCES mail_sync_jobs (tenant_id, job_id) ON DELETE CASCADE,
    CONSTRAINT ck_mail_sync_cursor_reset_hashes CHECK (
        prior_cursor_hash ~ '^[0-9a-f]{64}$'
        AND reset_cursor_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mail_sync_cursor_reset_generation CHECK (
        prior_generation >= 0 AND reset_generation = prior_generation + 1)
);

CREATE TABLE mail_provider_subscriptions (
    subscription_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    connection_id UUID NOT NULL,
    account_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL,
    provider_subscription_reference_hash CHAR(64),
    client_state_hash CHAR(64),
    subscription_state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ,
    renewal_due_at TIMESTAMPTZ NOT NULL,
    next_renewal_attempt_at TIMESTAMPTZ,
    lease_owner VARCHAR(200),
    lease_expires_at TIMESTAMPTZ,
    last_renewed_at TIMESTAMPTZ,
    last_verified_at TIMESTAMPTZ,
    renewal_attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(160),
    verification_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    worker_evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mail_provider_subscription UNIQUE (
        tenant_id, connection_id, provider_subscription_reference_hash),
    CONSTRAINT fk_mail_provider_subscription_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES mail_provider_connections (tenant_id, connection_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_mail_provider_subscription_account
        FOREIGN KEY (tenant_id, connection_id, account_id)
        REFERENCES mail_accounts (tenant_id, connection_id, account_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_mail_provider_subscription_hashes CHECK (
        (provider_subscription_reference_hash IS NULL
         OR provider_subscription_reference_hash ~ '^[0-9a-f]{64}$')
        AND (client_state_hash IS NULL
             OR client_state_hash ~ '^[0-9a-f]{64}$')
        AND ((provider_subscription_reference_hash IS NULL
              AND client_state_hash IS NULL AND expires_at IS NULL
              AND subscription_state IN ('PENDING', 'RENEWING', 'FAILED'))
             OR (provider_subscription_reference_hash IS NOT NULL
                 AND expires_at IS NOT NULL))),
    CONSTRAINT ck_mail_provider_subscription_state CHECK (
        subscription_state IN ('PENDING', 'ACTIVE', 'RENEWAL_DUE', 'RENEWING',
                               'EXPIRED', 'FAILED', 'REVOKED')),
    CONSTRAINT ck_mail_provider_subscription_attempt CHECK (
        renewal_attempt_count >= 0),
    CONSTRAINT ck_mail_provider_subscription_window CHECK (
        expires_at IS NULL OR renewal_due_at <= expires_at),
    CONSTRAINT ck_mail_provider_subscription_lease CHECK (
        (subscription_state = 'RENEWING'
         AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (subscription_state <> 'RENEWING'
            AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT ck_mail_provider_subscription_evidence CHECK (
        jsonb_typeof(verification_evidence) = 'object'
        AND jsonb_typeof(worker_evidence) = 'object')
);

CREATE UNIQUE INDEX uk_mail_provider_subscription_owner
    ON mail_provider_subscriptions (tenant_id, connection_id, account_id)
    WHERE subscription_state NOT IN ('EXPIRED', 'REVOKED');

CREATE INDEX idx_mail_provider_subscription_renewal
    ON mail_provider_subscriptions (
        next_renewal_attempt_at, renewal_due_at, expires_at, subscription_id)
    WHERE subscription_state IN ('PENDING', 'ACTIVE', 'RENEWAL_DUE', 'FAILED');

CREATE INDEX idx_mail_provider_subscription_lease
    ON mail_provider_subscriptions (lease_expires_at)
    WHERE subscription_state = 'RENEWING';

ALTER TABLE mail_domain_events
    ADD COLUMN event_sequence BIGSERIAL;

ALTER TABLE mail_domain_events
    ALTER COLUMN event_sequence SET NOT NULL;

CREATE UNIQUE INDEX uk_mail_domain_event_sequence
    ON mail_domain_events (event_sequence);

CREATE INDEX idx_mail_domain_event_tenant_sequence
    ON mail_domain_events (tenant_id, event_sequence);

CREATE TEMP TABLE tmp_v370_mail_code_contracts (
    code_set_key VARCHAR(100) PRIMARY KEY,
    source_reference VARCHAR(240) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_v370_mail_code_contracts VALUES
    ('PLATFORM.MAIL_SYSTEM_PRINCIPALS.PRINCIPAL_KEY',
     'mail_system_principals.principal_key', 'STATE_MACHINE',
     ARRAY['MAIL_WEBHOOK', 'MAIL_SYNC_RECOVERY',
           'MAIL_SUBSCRIPTION_RENEWAL']::VARCHAR[]),
    ('PLATFORM.MAIL_SYNC_JOBS.JOB_STATE', 'mail_sync_jobs.job_state', 'STATE_MACHINE',
     ARRAY['QUEUED', 'LEASED', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'DEAD_LETTER']::VARCHAR[]),
    ('PLATFORM.MAIL_SYNC_JOBS.TRIGGER_KIND', 'mail_sync_jobs.trigger_kind', 'STATE_MACHINE',
     ARRAY['MANUAL', 'WEBHOOK', 'SUBSCRIPTION', 'RECOVERY']::VARCHAR[]),
    ('PLATFORM.MAIL_PROVIDER_SUBSCRIPTIONS.SUBSCRIPTION_STATE',
     'mail_provider_subscriptions.subscription_state', 'STATE_MACHINE',
     ARRAY['PENDING', 'ACTIVE', 'RENEWAL_DUE', 'RENEWING',
           'EXPIRED', 'FAILED', 'REVOKED']::VARCHAR[]);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT contract.code_set_key, 'dwp-platform-server', contract.source_reference,
       'Database CHECK contract for ' || contract.source_reference || '.',
       'SYSTEM', 'CHECK', contract.source_reference, contract.contract_kind,
       'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v370_mail_code_contracts contract
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    configuration_level = 'SYSTEM',
    validation_source = 'CHECK',
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    runtime_visibility = 'ADMIN_ONLY',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT contract.code_set_key, value_ref.code, value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb, value_ref.ordinality::INTEGER * 10, TRUE, 'ACTIVE'
  FROM tmp_v370_mail_code_contracts contract
 CROSS JOIN LATERAL unnest(contract.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
  FROM tmp_v370_mail_code_contracts contract
 WHERE code_value.code_set_key = contract.code_set_key
   AND NOT (code_value.code = ANY (contract.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT contract.code_set_key, 'dwp-platform-server', 'DATABASE_COLUMN',
       contract.source_reference, 'CHECK', 'ACTIVE'
  FROM tmp_v370_mail_code_contracts contract
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET enforcement_type = 'CHECK', lifecycle_state = 'ACTIVE',
              updated_at = CURRENT_TIMESTAMP;

COMMENT ON TABLE mail_sync_jobs IS
    'Lease-based durable work for manual, verified webhook, and subscription-triggered synchronization.';
COMMENT ON COLUMN mail_sync_jobs.cursor_reset_receipt IS
    'Safe reset evidence only; provider cursors and credentials must never be included.';
COMMENT ON TABLE mail_system_principals IS
    'Tenant-bound non-user principals for verified provider callbacks and automatic recovery.';
COMMENT ON TABLE mail_sync_cursor_reset_receipts IS
    'Hash-only evidence for generation-fenced cursor reset and its single recovery job.';
COMMENT ON TABLE mail_provider_subscriptions IS
    'Provider subscription lifecycle ledger. Client-state and provider references are stored only as SHA-256 receipts.';
COMMENT ON COLUMN mail_domain_events.event_sequence IS
    'Monotonic reconnect cursor for tenant-filtered Mail event catch-up.';
