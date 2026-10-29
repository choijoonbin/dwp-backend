-- Distinguish provider acceptance from recipient delivery and retain durable provider-purge
-- reconciliation evidence without claiming completion for unsupported or ambiguous outcomes.

ALTER TABLE mail_delivery_outbox
    DROP CONSTRAINT ck_mail_delivery_status,
    DROP CONSTRAINT ck_mail_delivery_acceptance;

ALTER TABLE mail_delivery_outbox
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_state VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED';

UPDATE mail_delivery_outbox delivery
   SET delivery_status = 'PROVIDER_ACCEPTED',
       reconciliation_state = 'PENDING'
  FROM mail_threads thread
  JOIN mail_accounts account
    ON account.tenant_id = thread.tenant_id
   AND account.account_id = thread.account_id
  JOIN mail_provider_connections connection
    ON connection.tenant_id = account.tenant_id
   AND connection.connection_id = account.connection_id
 WHERE delivery.tenant_id = thread.tenant_id
   AND delivery.thread_id = thread.thread_id
   AND delivery.delivery_status = 'DELIVERED'
   AND connection.provider_type <> 'DWP_SANDBOX';

UPDATE mail_delivery_outbox
   SET delivered_at = accepted_at
 WHERE delivery_status = 'DELIVERED';

ALTER TABLE mail_delivery_outbox
    ADD CONSTRAINT ck_mail_delivery_status CHECK (delivery_status IN (
        'QUEUED', 'LEASED', 'RETRY_WAIT', 'PROVIDER_ACCEPTED',
        'DELIVERED', 'RESULT_UNKNOWN', 'FAILED', 'CANCELLED')),
    ADD CONSTRAINT ck_mail_delivery_reconciliation CHECK (reconciliation_state IN (
        'NOT_REQUIRED', 'PENDING', 'CONFIRMED', 'FAILED')),
    ADD CONSTRAINT ck_mail_delivery_acceptance CHECK (
        (delivery_status = 'DELIVERED'
            AND accepted_at IS NOT NULL AND delivered_at IS NOT NULL
            AND reconciliation_state IN ('NOT_REQUIRED', 'CONFIRMED'))
        OR (delivery_status = 'PROVIDER_ACCEPTED'
            AND accepted_at IS NOT NULL AND delivered_at IS NULL
            AND reconciliation_state = 'PENDING')
        OR (delivery_status = 'RESULT_UNKNOWN'
            AND accepted_at IS NULL AND delivered_at IS NULL
            AND reconciliation_state = 'PENDING')
        OR (delivery_status NOT IN ('DELIVERED', 'PROVIDER_ACCEPTED', 'RESULT_UNKNOWN')
            AND accepted_at IS NULL AND delivered_at IS NULL
            AND reconciliation_state = 'NOT_REQUIRED'));

CREATE TABLE mail_provider_purge_commands (
    command_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    job_id UUID NOT NULL REFERENCES mail_purge_jobs(job_id) ON DELETE RESTRICT,
    connection_id UUID NOT NULL,
    account_id UUID NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    provider_account_reference VARCHAR(500) NOT NULL,
    provider_message_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    provider_thread_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    command_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    idempotency_key UUID NOT NULL,
    legal_hold_checked_at TIMESTAMPTZ NOT NULL,
    legal_hold_evidence_hash CHAR(64) NOT NULL,
    provider_receipt_hash CHAR(64),
    purged_items INTEGER NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(160),
    references_purged_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_mail_provider_purge_job_account UNIQUE (job_id, account_id),
    CONSTRAINT uk_mail_provider_purge_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_mail_provider_purge_connection
        FOREIGN KEY (tenant_id, connection_id)
        REFERENCES mail_provider_connections (tenant_id, connection_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mail_provider_purge_account
        FOREIGN KEY (account_id) REFERENCES mail_accounts(account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_mail_provider_purge_provider CHECK (provider_type IN (
        'DWP_SANDBOX', 'MICROSOFT_GRAPH', 'GOOGLE_GMAIL',
        'NAVER_WORKS', 'JMAP', 'IMAP_SMTP')),
    CONSTRAINT ck_mail_provider_purge_state CHECK (command_state IN (
        'PENDING', 'PURGED', 'NOT_SUPPORTED',
        'RECONCILIATION_REQUIRED', 'FAILED')),
    CONSTRAINT ck_mail_provider_purge_references CHECK (
        jsonb_typeof(provider_message_references) = 'array'
        AND jsonb_typeof(provider_thread_references) = 'array'),
    CONSTRAINT ck_mail_provider_purge_hashes CHECK (
        legal_hold_evidence_hash ~ '^[0-9a-f]{64}$'
        AND (provider_receipt_hash IS NULL
             OR provider_receipt_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_mail_provider_purge_counts CHECK (
        purged_items >= 0 AND attempt_count >= 0),
    CONSTRAINT ck_mail_provider_purge_terminal CHECK (
        (command_state = 'PENDING' AND completed_at IS NULL)
        OR (command_state <> 'PENDING' AND completed_at IS NOT NULL)),
    CONSTRAINT ck_mail_provider_purge_reference_erasure CHECK (
        references_purged_at IS NULL
        OR (provider_message_references = '[]'::jsonb
            AND provider_thread_references = '[]'::jsonb))
);

CREATE INDEX idx_mail_provider_purge_job
    ON mail_provider_purge_commands (tenant_id, job_id, command_state, command_id);

COMMENT ON TABLE mail_provider_purge_commands IS
    'Durable provider-delete commands staged only after legal-hold revalidation. Unsupported and ambiguous outcomes remain non-complete reconciliation evidence.';
COMMENT ON COLUMN mail_provider_purge_commands.legal_hold_evidence_hash IS
    'SHA-256 receipt of the job, candidate snapshot and legal-hold revalidation instant; raw hold evidence is not copied.';

CREATE TEMP TABLE tmp_v371_mail_code_contracts (
    source_reference VARCHAR(240) PRIMARY KEY,
    allowed_values VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_v371_mail_code_contracts VALUES
    ('mail_delivery_outbox.delivery_status',
     ARRAY['CANCELLED', 'DELIVERED', 'FAILED', 'LEASED', 'PROVIDER_ACCEPTED',
           'QUEUED', 'RESULT_UNKNOWN', 'RETRY_WAIT']::VARCHAR[]),
    ('mail_delivery_outbox.reconciliation_state',
     ARRAY['CONFIRMED', 'FAILED', 'NOT_REQUIRED', 'PENDING']::VARCHAR[]),
    ('mail_provider_purge_commands.command_state',
     ARRAY['FAILED', 'NOT_SUPPORTED', 'PENDING', 'PURGED',
           'RECONCILIATION_REQUIRED']::VARCHAR[]);

CREATE TEMP TABLE tmp_v371_resolved_mail_code_contracts ON COMMIT DROP AS
SELECT COALESCE(
           (SELECT code_set_key FROM sys_code_sets
             WHERE owner_service = 'dwp-platform-server'
               AND source_reference = contract.source_reference
             ORDER BY (lifecycle_state = 'ACTIVE') DESC, code_set_key LIMIT 1),
           'DBCHK.' || UPPER(SUBSTRING(MD5(
               'dwp-platform-server:' || contract.source_reference), 1, 32)))::VARCHAR(100)
           AS code_set_key,
       contract.source_reference,
       contract.allowed_values
  FROM tmp_v371_mail_code_contracts contract;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT code_set_key, 'dwp-platform-server', source_reference,
       'Database CHECK contract for ' || source_reference || '.',
       'SYSTEM', 'CHECK', source_reference, 'STATE_MACHINE',
       'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v371_resolved_mail_code_contracts
ON CONFLICT (code_set_key) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT contract.code_set_key, value_ref.code, value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb, value_ref.ordinality::INTEGER * 10, TRUE, 'ACTIVE'
  FROM tmp_v371_resolved_mail_code_contracts contract
 CROSS JOIN LATERAL unnest(contract.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
  FROM tmp_v371_resolved_mail_code_contracts contract
 WHERE code_value.code_set_key = contract.code_set_key
   AND NOT (code_value.code = ANY (contract.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT code_set_key, 'dwp-platform-server', 'DATABASE_COLUMN',
       source_reference, 'CHECK', 'ACTIVE'
  FROM tmp_v371_resolved_mail_code_contracts
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET enforcement_type = 'CHECK', lifecycle_state = 'ACTIVE',
              updated_at = CURRENT_TIMESTAMP;
