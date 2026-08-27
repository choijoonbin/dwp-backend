-- Complete the governed personal-mail organization model. Mail folders remain
-- account scoped, while provider synchronization is explicitly projected so a
-- local rule is never mistaken for a provider-side rule.

ALTER TABLE mail_folders
    ADD COLUMN parent_folder_id UUID REFERENCES mail_folders(folder_id) ON DELETE RESTRICT,
    ADD COLUMN color_token VARCHAR(16) NOT NULL DEFAULT 'NEUTRAL',
    ADD COLUMN provider_sync_state VARCHAR(20) NOT NULL DEFAULT 'LOCAL_ONLY',
    ADD COLUMN provider_sync_error VARCHAR(240),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN created_by BIGINT,
    ADD COLUMN updated_by BIGINT,
    ADD CONSTRAINT ck_mail_folder_color CHECK (color_token IN (
        'NEUTRAL', 'BLUE', 'TEAL', 'GREEN', 'AMBER', 'CORAL', 'VIOLET')),
    ADD CONSTRAINT ck_mail_folder_sync_state CHECK (provider_sync_state IN (
        'LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR')),
    ADD CONSTRAINT ck_mail_folder_parent_self CHECK (parent_folder_id IS NULL OR parent_folder_id <> folder_id);

UPDATE mail_folders
   SET color_token = CASE folder_type
       WHEN 'INBOX' THEN 'BLUE'
       WHEN 'SENT' THEN 'TEAL'
       WHEN 'DRAFTS' THEN 'AMBER'
       WHEN 'ARCHIVE' THEN 'NEUTRAL'
       WHEN 'SPAM' THEN 'CORAL'
       WHEN 'TRASH' THEN 'NEUTRAL'
       ELSE color_token
   END;

CREATE UNIQUE INDEX uk_mail_folder_active_display_name
    ON mail_folders (account_id, LOWER(display_name))
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX idx_mail_folder_owner_tree
    ON mail_folders (tenant_id, account_id, parent_folder_id, sort_order, display_name)
    WHERE lifecycle_state = 'ACTIVE';

CREATE OR REPLACE FUNCTION validate_mail_folder_parent()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_account_id UUID;
    parent_type VARCHAR(20);
    cycle_found BOOLEAN;
BEGIN
    IF NEW.parent_folder_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT account_id, folder_type
      INTO parent_account_id, parent_type
      FROM mail_folders
     WHERE folder_id = NEW.parent_folder_id
       AND lifecycle_state = 'ACTIVE';

    IF parent_account_id IS NULL OR parent_account_id <> NEW.account_id THEN
        RAISE EXCEPTION 'Mail folder parent must be active and belong to the same account';
    END IF;
    IF parent_type <> 'CUSTOM' OR NEW.folder_type <> 'CUSTOM' THEN
        RAISE EXCEPTION 'Only custom mail folders can participate in a hierarchy';
    END IF;

    WITH RECURSIVE ancestors(folder_id, parent_folder_id) AS (
        SELECT folder_id, parent_folder_id
          FROM mail_folders
         WHERE folder_id = NEW.parent_folder_id
        UNION ALL
        SELECT folder.folder_id, folder.parent_folder_id
          FROM mail_folders folder
          JOIN ancestors ancestor ON folder.folder_id = ancestor.parent_folder_id
    )
    SELECT EXISTS (
        SELECT 1 FROM ancestors WHERE folder_id = NEW.folder_id)
      INTO cycle_found;

    IF cycle_found THEN
        RAISE EXCEPTION 'Mail folder hierarchy cannot contain a cycle';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mail_folder_parent
BEFORE INSERT OR UPDATE OF parent_folder_id, account_id, folder_type, lifecycle_state
ON mail_folders
FOR EACH ROW
EXECUTE FUNCTION validate_mail_folder_parent();

ALTER TABLE mail_threads
    ADD COLUMN previous_folder_id UUID REFERENCES mail_folders(folder_id) ON DELETE SET NULL,
    ADD COLUMN trashed_at TIMESTAMPTZ,
    ADD COLUMN spam_reported_at TIMESTAMPTZ;

ALTER TABLE mail_threads DROP CONSTRAINT ck_mail_thread_workflow;
ALTER TABLE mail_threads
    ADD CONSTRAINT ck_mail_thread_workflow CHECK (workflow_state IN (
        'OPEN', 'DONE', 'SNOOZED', 'ARCHIVED', 'DRAFT', 'TRASHED', 'SPAM')),
    ADD CONSTRAINT ck_mail_thread_lifecycle_timestamps CHECK (
        (workflow_state = 'TRASHED' AND trashed_at IS NOT NULL)
        OR (workflow_state <> 'TRASHED' AND trashed_at IS NULL)
    );

CREATE INDEX idx_mail_thread_lifecycle
    ON mail_threads (tenant_id, workflow_state, latest_message_at DESC);

CREATE TABLE mail_rules (
    rule_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    account_id UUID NOT NULL REFERENCES mail_accounts(account_id) ON DELETE CASCADE,
    owner_user_id BIGINT NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 100,
    match_mode VARCHAR(8) NOT NULL DEFAULT 'ALL',
    conditions JSONB NOT NULL,
    actions JSONB NOT NULL,
    stop_processing BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    synchronization_state VARCHAR(20) NOT NULL DEFAULT 'LOCAL_ONLY',
    provider_rule_ref VARCHAR(500),
    last_error_code VARCHAR(120),
    lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_run_at TIMESTAMPTZ,
    last_match_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT NOT NULL,
    CONSTRAINT ck_mail_rule_match_mode CHECK (match_mode IN ('ALL', 'ANY')),
    CONSTRAINT ck_mail_rule_sync_state CHECK (synchronization_state IN (
        'LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR')),
    CONSTRAINT ck_mail_rule_state CHECK (lifecycle_state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_mail_rule_priority CHECK (priority BETWEEN 1 AND 10000),
    CONSTRAINT ck_mail_rule_conditions CHECK (
        jsonb_typeof(conditions) = 'array' AND jsonb_array_length(conditions) BETWEEN 1 AND 10),
    CONSTRAINT ck_mail_rule_actions CHECK (
        jsonb_typeof(actions) = 'array' AND jsonb_array_length(actions) BETWEEN 1 AND 8),
    CONSTRAINT ck_mail_rule_match_count CHECK (last_match_count >= 0)
);

CREATE UNIQUE INDEX uk_mail_rule_active_name
    ON mail_rules (account_id, LOWER(display_name))
    WHERE lifecycle_state = 'ACTIVE';

CREATE INDEX idx_mail_rule_execution
    ON mail_rules (tenant_id, account_id, enabled, priority, rule_id)
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE mail_rule_runs (
    run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id BIGINT NOT NULL,
    rule_id UUID NOT NULL REFERENCES mail_rules(rule_id) ON DELETE RESTRICT,
    trigger_kind VARCHAR(16) NOT NULL,
    run_status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    scanned_count INTEGER NOT NULL DEFAULT 0,
    matched_count INTEGER NOT NULL DEFAULT 0,
    changed_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(120),
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    initiated_by BIGINT NOT NULL,
    CONSTRAINT ck_mail_rule_run_trigger CHECK (trigger_kind IN (
        'MANUAL', 'INCOMING', 'BACKFILL')),
    CONSTRAINT ck_mail_rule_run_status CHECK (run_status IN (
        'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_mail_rule_run_counts CHECK (
        scanned_count >= 0 AND matched_count >= 0 AND changed_count >= 0
        AND matched_count <= scanned_count AND changed_count <= matched_count),
    CONSTRAINT ck_mail_rule_run_completion CHECK (
        (run_status = 'RUNNING' AND completed_at IS NULL)
        OR (run_status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL))
);

CREATE INDEX idx_mail_rule_run_history
    ON mail_rule_runs (tenant_id, rule_id, started_at DESC);

-- A move-based mailbox uses one exclusive primary folder. Inbox and Sent can
-- coexist only while a provider projects both sides of a conversation.
CREATE OR REPLACE FUNCTION synchronize_mail_thread_folder_membership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    new_folder_type VARCHAR(24);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        UPDATE mail_thread_folders
           SET is_primary = FALSE
         WHERE thread_id = NEW.thread_id AND is_primary = TRUE;
    END IF;

    INSERT INTO mail_thread_folders (tenant_id, thread_id, folder_id, is_primary)
    VALUES (NEW.tenant_id, NEW.thread_id, NEW.folder_id, TRUE)
    ON CONFLICT (thread_id, folder_id) DO UPDATE SET is_primary = TRUE;

    SELECT folder_type INTO new_folder_type
      FROM mail_folders WHERE folder_id = NEW.folder_id;

    IF new_folder_type IN ('DRAFTS', 'ARCHIVE', 'SPAM', 'TRASH', 'CUSTOM') THEN
        DELETE FROM mail_thread_folders
         WHERE thread_id = NEW.thread_id AND folder_id <> NEW.folder_id;
    ELSIF new_folder_type IN ('INBOX', 'SENT') THEN
        DELETE FROM mail_thread_folders membership
         USING mail_folders folder
         WHERE membership.thread_id = NEW.thread_id
           AND membership.folder_id = folder.folder_id
           AND membership.folder_id <> NEW.folder_id
           AND folder.folder_type IN ('DRAFTS', 'ARCHIVE', 'SPAM', 'TRASH', 'CUSTOM');
    END IF;

    RETURN NEW;
END;
$$;

-- Ensure every governed account has the complete lifecycle folder set.
INSERT INTO mail_folders (
    tenant_id, account_id, folder_key, display_name, folder_type,
    sort_order, color_token, created_by, updated_by)
SELECT account.tenant_id, account.account_id, system_folder.folder_key,
       system_folder.display_name, system_folder.folder_type,
       system_folder.sort_order, system_folder.color_token,
       COALESCE(account.owner_user_id, 1), COALESCE(account.owner_user_id, 1)
  FROM mail_accounts account
 CROSS JOIN (VALUES
      ('archive', '보관함', 'ARCHIVE', 40, 'NEUTRAL'),
      ('spam', '스팸', 'SPAM', 50, 'CORAL'),
      ('trash', '휴지통', 'TRASH', 60, 'NEUTRAL')
 ) system_folder(folder_key, display_name, folder_type, sort_order, color_token)
ON CONFLICT (account_id, folder_key) DO NOTHING;

-- Local-development fixtures are intentionally attached to real SKAX member
-- accounts. Delivery adapters can later project the same folders and rules to
-- Microsoft Graph, Gmail or another provider without changing the UI contract.
INSERT INTO mail_folders (
    tenant_id, account_id, folder_key, display_name, folder_type,
    sort_order, color_token, created_by, updated_by)
SELECT account.tenant_id, account.account_id, fixture.folder_key,
       fixture.display_name, 'CUSTOM', fixture.sort_order, fixture.color_token,
       account.owner_user_id, account.owner_user_id
  FROM mail_accounts account
 CROSS JOIN (VALUES
      ('projects', '프로젝트', 100, 'BLUE'),
      ('people-services', '인사와 복지', 110, 'GREEN'),
      ('external-partners', '외부 파트너', 120, 'VIOLET')
 ) fixture(folder_key, display_name, sort_order, color_token)
 WHERE account.account_kind = 'PERSONAL'
   AND account.owner_user_id IS NOT NULL
ON CONFLICT (account_id, folder_key) DO NOTHING;

INSERT INTO mail_rules (
    tenant_id, account_id, owner_user_id, display_name, priority,
    match_mode, conditions, actions, stop_processing, enabled,
    created_by, updated_by)
SELECT account.tenant_id, account.account_id, account.owner_user_id,
       fixture.display_name, fixture.priority, 'ANY',
       fixture.conditions, jsonb_build_array(jsonb_build_object(
           'type', 'MOVE_TO_FOLDER', 'folderId', folder.folder_id::text)),
       TRUE, TRUE, account.owner_user_id, account.owner_user_id
  FROM mail_accounts account
  JOIN (VALUES
      ('프로젝트 메일 자동 정리', 100,
       '[{"field":"SUBJECT","operator":"CONTAINS","value":"프로젝트"},
         {"field":"SUBJECT","operator":"CONTAINS","value":"킥오프"}]'::jsonb,
       'projects'),
      ('인사·복지 문의 모으기', 200,
       '[{"field":"SUBJECT","operator":"CONTAINS","value":"지원"},
         {"field":"SUBJECT","operator":"CONTAINS","value":"휴가"}]'::jsonb,
       'people-services'),
      ('외부 파트너 메일 분류', 300,
       '[{"field":"SENDER","operator":"ENDS_WITH","value":"@example.com"}]'::jsonb,
       'external-partners')
  ) fixture(display_name, priority, conditions, folder_key) ON TRUE
  JOIN mail_folders folder
    ON folder.account_id = account.account_id
   AND folder.folder_key = fixture.folder_key
 WHERE account.account_kind = 'PERSONAL'
   AND account.owner_user_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- Project the eight new CHECK contracts that V202 could only register when an
-- earlier optional mail migration existed.
CREATE TEMP TABLE tmp_v203_mail_check_contracts (
    code_set_key VARCHAR(100) PRIMARY KEY,
    source_reference VARCHAR(240) NOT NULL UNIQUE,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_v203_mail_check_contracts VALUES
    ('PLATFORM.MAIL_FOLDERS.COLOR_TOKEN', 'mail_folders.color_token', 'REFERENCE',
     ARRAY['NEUTRAL', 'BLUE', 'TEAL', 'GREEN', 'AMBER', 'CORAL', 'VIOLET']::VARCHAR[]),
    ('PLATFORM.MAIL_FOLDERS.PROVIDER_SYNC_STATE', 'mail_folders.provider_sync_state', 'STATE_MACHINE',
     ARRAY['LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR']::VARCHAR[]),
    ('PLATFORM.MAIL_THREADS.WORKFLOW_STATE', 'mail_threads.workflow_state', 'STATE_MACHINE',
     ARRAY['OPEN', 'DONE', 'SNOOZED', 'ARCHIVED', 'DRAFT', 'TRASHED', 'SPAM']::VARCHAR[]),
    ('PLATFORM.MAIL_RULES.MATCH_MODE', 'mail_rules.match_mode', 'REFERENCE',
     ARRAY['ALL', 'ANY']::VARCHAR[]),
    ('PLATFORM.MAIL_RULES.SYNCHRONIZATION_STATE', 'mail_rules.synchronization_state', 'STATE_MACHINE',
     ARRAY['LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR']::VARCHAR[]),
    ('PLATFORM.MAIL_RULES.LIFECYCLE_STATE', 'mail_rules.lifecycle_state', 'STATE_MACHINE',
     ARRAY['ACTIVE', 'ARCHIVED']::VARCHAR[]),
    ('PLATFORM.MAIL_RULE_RUNS.TRIGGER_KIND', 'mail_rule_runs.trigger_kind', 'PROTOCOL',
     ARRAY['MANUAL', 'INCOMING', 'BACKFILL']::VARCHAR[]),
    ('PLATFORM.MAIL_RULE_RUNS.RUN_STATUS', 'mail_rule_runs.run_status', 'STATE_MACHINE',
     ARRAY['RUNNING', 'SUCCEEDED', 'FAILED']::VARCHAR[]);

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT code_set_key, 'dwp-platform-server', source_reference,
       'Database CHECK contract for ' || source_reference || '.',
       'SYSTEM', 'CHECK', source_reference, contract_kind, 'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v203_mail_check_contracts
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
  FROM tmp_v203_mail_check_contracts contract
 CROSS JOIN LATERAL unnest(contract.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT code_set_key, 'dwp-platform-server', 'DATABASE_COLUMN',
       source_reference, 'CHECK', 'ACTIVE'
  FROM tmp_v203_mail_check_contracts
ON CONFLICT (code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET enforcement_type = 'CHECK', lifecycle_state = 'ACTIVE',
              updated_at = CURRENT_TIMESTAMP;

COMMENT ON TABLE mail_rules IS
    'Typed, user-owned mail organization rules. Provider projection is explicit and fail-closed.';
COMMENT ON TABLE mail_rule_runs IS
    'Auditable execution history for manual, incoming and backfill mail rule evaluation.';
