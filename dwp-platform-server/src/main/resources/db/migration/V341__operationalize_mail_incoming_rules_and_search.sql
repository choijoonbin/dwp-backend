-- Operate incoming Mail rules as durable, retryable work and maintain an indexed
-- full-text projection that includes every visible message body in a thread.

ALTER TABLE mail_rule_runs
    ADD COLUMN account_id UUID,
    ADD COLUMN thread_id UUID,
    ADD COLUMN message_id UUID,
    ADD COLUMN rule_version BIGINT,
    ADD COLUMN rule_priority INTEGER,
    ADD COLUMN rule_match_mode VARCHAR(8),
    ADD COLUMN rule_conditions JSONB,
    ADD COLUMN rule_actions JSONB,
    ADD COLUMN stop_processing BOOLEAN,
    ADD COLUMN matched BOOLEAN,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN lease_owner VARCHAR(200),
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

ALTER TABLE mail_rule_runs
    DROP CONSTRAINT ck_mail_rule_run_status,
    DROP CONSTRAINT ck_mail_rule_run_completion;

ALTER TABLE mail_rule_runs
    ADD CONSTRAINT ck_mail_rule_run_status CHECK (run_status IN (
        'RUNNING', 'QUEUED', 'LEASED', 'RETRY_WAIT',
        'SUCCEEDED', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT ck_mail_rule_run_completion CHECK (
        (run_status IN ('RUNNING', 'QUEUED', 'LEASED', 'RETRY_WAIT')
            AND completed_at IS NULL)
        OR (run_status IN ('SUCCEEDED', 'FAILED', 'SKIPPED')
            AND completed_at IS NOT NULL)),
    ADD CONSTRAINT ck_mail_rule_run_attempt CHECK (attempt_count >= 0),
    ADD CONSTRAINT ck_mail_rule_run_incoming_identity CHECK (
        trigger_kind <> 'INCOMING'
        OR (account_id IS NOT NULL
            AND thread_id IS NOT NULL
            AND message_id IS NOT NULL
            AND rule_version IS NOT NULL
            AND rule_version >= 0
            AND rule_priority BETWEEN 1 AND 10000
            AND rule_match_mode IN ('ALL', 'ANY')
            AND jsonb_typeof(rule_conditions) = 'array'
            AND jsonb_array_length(rule_conditions) BETWEEN 1 AND 10
            AND jsonb_typeof(rule_actions) = 'array'
            AND jsonb_array_length(rule_actions) BETWEEN 1 AND 8
            AND stop_processing IS NOT NULL)),
    ADD CONSTRAINT fk_mail_rule_run_account
        FOREIGN KEY (tenant_id, account_id)
        REFERENCES mail_accounts (tenant_id, account_id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_mail_rule_run_thread
        FOREIGN KEY (tenant_id, account_id, thread_id)
        REFERENCES mail_threads (tenant_id, account_id, thread_id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uk_mail_message_tenant_thread_identity
    ON mail_messages (tenant_id, thread_id, message_id);

ALTER TABLE mail_rule_runs
    ADD CONSTRAINT fk_mail_rule_run_message
        FOREIGN KEY (tenant_id, thread_id, message_id)
        REFERENCES mail_messages (tenant_id, thread_id, message_id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uk_mail_rule_incoming_message
    ON mail_rule_runs (tenant_id, message_id, rule_id)
    WHERE trigger_kind = 'INCOMING';

CREATE INDEX idx_mail_rule_incoming_claim
    ON mail_rule_runs (next_attempt_at, started_at, message_id, rule_priority, rule_id)
    WHERE trigger_kind = 'INCOMING'
      AND run_status IN ('QUEUED', 'RETRY_WAIT');

CREATE INDEX idx_mail_rule_incoming_lease
    ON mail_rule_runs (lease_expires_at)
    WHERE trigger_kind = 'INCOMING' AND run_status = 'LEASED';

CREATE TEMP TABLE tmp_v341_mail_rule_run_contract (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(100) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_v341_mail_rule_run_contract VALUES (
    'PLATFORM.MAIL_RULE_RUNS.RUN_STATUS',
    'dwp-platform-server',
    'mail_rule_runs.run_status',
    'STATE_MACHINE',
    ARRAY[
        'RUNNING', 'QUEUED', 'LEASED', 'RETRY_WAIT',
        'SUCCEEDED', 'FAILED', 'SKIPPED'
    ]::VARCHAR[]
);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT contract.code_set_key, contract.owner_service, contract.source_reference,
       'Database CHECK contract for ' || contract.source_reference || '.',
       'SYSTEM', 'CHECK', contract.source_reference, contract.contract_kind,
       'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v341_mail_rule_run_contract contract
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
  FROM tmp_v341_mail_rule_run_contract contract
 CROSS JOIN LATERAL unnest(contract.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP
  FROM tmp_v341_mail_rule_run_contract contract
 WHERE code_value.code_set_key = contract.code_set_key
   AND NOT (code_value.code = ANY (contract.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT contract.code_set_key, contract.owner_service, 'DATABASE_COLUMN',
       contract.source_reference, 'CHECK', 'ACTIVE'
  FROM tmp_v341_mail_rule_run_contract contract
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET enforcement_type = 'CHECK', lifecycle_state = 'ACTIVE',
              updated_at = CURRENT_TIMESTAMP;

ALTER TABLE mail_threads
    ADD COLUMN search_document TSVECTOR NOT NULL DEFAULT ''::tsvector;

CREATE OR REPLACE FUNCTION refresh_mail_thread_search_document(
    target_tenant_id BIGINT,
    target_thread_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE mail_threads thread
       SET search_document =
           setweight(to_tsvector('simple', COALESCE(thread.subject, '')), 'A')
           || setweight(to_tsvector('simple', COALESCE(thread.preview, '')), 'B')
           || setweight(to_tsvector('simple', COALESCE((
                SELECT string_agg(
                    COALESCE(participant.value ->> 'name', '') || ' '
                    || COALESCE(participant.value ->> 'email', ''), ' ')
                  FROM jsonb_array_elements(thread.participants) participant(value)
                 WHERE UPPER(TRIM(COALESCE(participant.value ->> 'type', 'TO'))) <> 'BCC'
           ), '')), 'C')
           || setweight(to_tsvector('simple', COALESCE((
                SELECT string_agg(message.body_content, ' ' ORDER BY message.sent_at, message.message_id)
                  FROM mail_messages message
                 WHERE message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
           ), '')), 'D')
     WHERE thread.tenant_id = target_tenant_id
       AND thread.thread_id = target_thread_id;
END;
$$;

CREATE OR REPLACE FUNCTION refresh_mail_thread_search_from_thread()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM refresh_mail_thread_search_document(NEW.tenant_id, NEW.thread_id);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION refresh_mail_thread_search_from_message()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM refresh_mail_thread_search_document(OLD.tenant_id, OLD.thread_id);
        RETURN OLD;
    END IF;
    PERFORM refresh_mail_thread_search_document(NEW.tenant_id, NEW.thread_id);
    IF TG_OP = 'UPDATE'
       AND (OLD.tenant_id, OLD.thread_id) IS DISTINCT FROM (NEW.tenant_id, NEW.thread_id) THEN
        PERFORM refresh_mail_thread_search_document(OLD.tenant_id, OLD.thread_id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mail_thread_search_from_thread
AFTER INSERT OR UPDATE OF subject, preview, participants
ON mail_threads
FOR EACH ROW EXECUTE FUNCTION refresh_mail_thread_search_from_thread();

CREATE TRIGGER trg_mail_thread_search_from_message
AFTER INSERT OR UPDATE OF body_content, recipients, sender_email, thread_id OR DELETE
ON mail_messages
FOR EACH ROW EXECUTE FUNCTION refresh_mail_thread_search_from_message();

SELECT refresh_mail_thread_search_document(thread.tenant_id, thread.thread_id)
  FROM mail_threads thread;

CREATE INDEX idx_mail_thread_search_document
    ON mail_threads USING GIN (search_document);

COMMENT ON COLUMN mail_threads.search_document IS
    'Server-maintained FTS projection of subject, preview, non-BCC participants and message bodies.';
