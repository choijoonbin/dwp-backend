-- Fence governed personal-mail backfill execution. Automatic incoming rules
-- remain disabled; this contract only covers explicit preview-bound commands.

CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_account_tenant_identity
    ON mail_accounts (tenant_id, account_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_rule_tenant_account_identity
    ON mail_rules (tenant_id, account_id, rule_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mail_thread_tenant_account_identity
    ON mail_threads (tenant_id, account_id, thread_id);

CREATE TABLE mail_rule_backfill_executions (
    execution_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    account_id UUID NOT NULL,
    owner_user_id BIGINT NOT NULL,
    request_id UUID NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    preview_fingerprint CHAR(64) NOT NULL,
    execution_status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    generation BIGINT NOT NULL DEFAULT 1,
    lease_token UUID NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    scanned_count INTEGER NOT NULL DEFAULT 0,
    matched_thread_count INTEGER NOT NULL DEFAULT 0,
    application_count INTEGER NOT NULL DEFAULT 0,
    changed_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(120),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mail_backfill_account
        FOREIGN KEY (tenant_id, account_id)
        REFERENCES mail_accounts (tenant_id, account_id) ON DELETE RESTRICT,
    CONSTRAINT uk_mail_backfill_request
        UNIQUE (tenant_id, account_id, owner_user_id, request_id),
    CONSTRAINT ck_mail_backfill_status CHECK (
        execution_status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_mail_backfill_generation CHECK (generation >= 1),
    CONSTRAINT ck_mail_backfill_fingerprints CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'
        AND preview_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mail_backfill_counts CHECK (
        scanned_count >= 0 AND matched_thread_count >= 0
        AND application_count >= 0 AND changed_count >= 0
        AND matched_thread_count <= scanned_count
        AND changed_count <= application_count),
    CONSTRAINT ck_mail_backfill_completion CHECK (
        (execution_status = 'RUNNING' AND completed_at IS NULL)
        OR (execution_status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL))
);

CREATE INDEX idx_mail_backfill_active_lease
    ON mail_rule_backfill_executions (
        tenant_id, account_id, execution_status, lease_expires_at)
    WHERE execution_status = 'RUNNING';

CREATE TABLE mail_rule_backfill_applications (
    execution_id UUID NOT NULL
        REFERENCES mail_rule_backfill_executions(execution_id) ON DELETE RESTRICT,
    tenant_id BIGINT NOT NULL,
    account_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    rule_version BIGINT NOT NULL,
    before_thread_version BIGINT NOT NULL,
    after_thread_version BIGINT NOT NULL,
    changed BOOLEAN NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (execution_id, thread_id, rule_id),
    CONSTRAINT fk_mail_backfill_application_thread
        FOREIGN KEY (tenant_id, account_id, thread_id)
        REFERENCES mail_threads (tenant_id, account_id, thread_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mail_backfill_application_rule
        FOREIGN KEY (tenant_id, account_id, rule_id)
        REFERENCES mail_rules (tenant_id, account_id, rule_id) ON DELETE RESTRICT,
    CONSTRAINT ck_mail_backfill_application_versions CHECK (
        rule_version >= 0 AND before_thread_version >= 0
        AND after_thread_version = before_thread_version + CASE WHEN changed THEN 1 ELSE 0 END)
);

CREATE OR REPLACE FUNCTION validate_mail_backfill_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM mail_accounts account
         WHERE account.tenant_id = NEW.tenant_id
           AND account.account_id = NEW.account_id
           AND account.owner_user_id = NEW.owner_user_id
           AND account.account_kind = 'PERSONAL'
           AND account.connection_state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Mail backfill requires an active personal account owner';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mail_backfill_owner
BEFORE INSERT OR UPDATE OF tenant_id, account_id, owner_user_id
ON mail_rule_backfill_executions
FOR EACH ROW
EXECUTE FUNCTION validate_mail_backfill_owner();

CREATE TEMP TABLE tmp_v208_mail_check_contracts (
    code_set_key VARCHAR(100) PRIMARY KEY,
    source_reference VARCHAR(240) NOT NULL UNIQUE,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_v208_mail_check_contracts VALUES
    ('PLATFORM.MAIL_RULE_BACKFILL_EXECUTIONS.EXECUTION_STATUS',
     'mail_rule_backfill_executions.execution_status', 'STATE_MACHINE',
     ARRAY['RUNNING', 'SUCCEEDED', 'FAILED']::VARCHAR[]);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT code_set_key, 'dwp-platform-server', source_reference,
       'Database CHECK contract for ' || source_reference || '.',
       'SYSTEM', 'CHECK', source_reference, contract_kind, 'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v208_mail_check_contracts
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    validation_source = 'CHECK',
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT contract.code_set_key, value_ref.code, value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb, value_ref.ordinality::INTEGER * 10, TRUE, 'ACTIVE'
  FROM tmp_v208_mail_check_contracts contract
 CROSS JOIN LATERAL unnest(contract.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT code_set_key, 'dwp-platform-server', 'DATABASE_COLUMN',
       source_reference, 'CHECK', 'ACTIVE'
  FROM tmp_v208_mail_check_contracts
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET enforcement_type = 'CHECK', lifecycle_state = 'ACTIVE',
              updated_at = CURRENT_TIMESTAMP;

COMMENT ON TABLE mail_rule_backfill_executions IS
    'Preview-bound, idempotent and lease-fenced manual mail-rule backfill commands.';
COMMENT ON TABLE mail_rule_backfill_applications IS
    'Exact rule/thread/version application ledger for governed mail-rule backfills.';
