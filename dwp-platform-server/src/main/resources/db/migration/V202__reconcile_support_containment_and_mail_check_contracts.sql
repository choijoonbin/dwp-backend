-- Project the enum-like CHECK literals introduced by Provider V51/V52 and
-- Platform V199 into the central administrator-only registry. If the optional
-- Platform mail lifecycle migration preceded this migration, project its eight
-- contracts as well. The source database constraints remain authoritative;
-- previously applied migrations are intentionally left immutable.

CREATE TEMP TABLE tmp_v202_check_contract_manifest (
    code_set_key VARCHAR(100) NOT NULL,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    PRIMARY KEY (owner_service, source_reference),
    UNIQUE (code_set_key),
    CONSTRAINT ck_tmp_v202_contract_kind
        CHECK (contract_kind IN (
            'REFERENCE', 'STATE_MACHINE', 'SECURITY',
            'PROTOCOL', 'OBSERVABILITY', 'REGISTRY_META')),
    CONSTRAINT ck_tmp_v202_allowed_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v202_check_contract_manifest (
    code_set_key, owner_service, source_reference,
    contract_kind, allowed_values)
VALUES
    ('PROVIDER.PRV_OPERATORS.DISPLAY_NAME',
     'dwp-provider-server', 'prv_operators.display_name', 'SECURITY',
     ARRAY['Provider support containment system']::VARCHAR[]),
    ('PROVIDER.PRV_OPERATORS.ROLE_CODE',
     'dwp-provider-server', 'prv_operators.role_code', 'SECURITY',
     ARRAY['PROVIDER_SYSTEM_CONTAINMENT']::VARCHAR[]),
    ('PROVIDER.PRV_SUPPORT_ACCESS_REQUESTS.CANCELLATION_ORIGIN',
     'dwp-provider-server', 'prv_support_access_requests.cancellation_origin',
     'SECURITY',
     ARRAY['AUTOMATIC_OPERATOR_CONTAINMENT',
           'AUTOMATIC_SCOPE_RETIREMENT']::VARCHAR[]),
    ('PROVIDER.PRV_SUPPORT_SESSIONS.REVOCATION_ORIGIN',
     'dwp-provider-server', 'prv_support_sessions.revocation_origin',
     'SECURITY',
     ARRAY['AUTOMATIC_OPERATOR_CONTAINMENT',
           'AUTOMATIC_SCOPE_RETIREMENT',
           'AUTOMATIC_TENANT_CONTAINMENT']::VARCHAR[]),

    ('PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.SCOPE',
     'dwp-platform-server', 'usr_saved_view_lifecycle_commands.scope',
     'SECURITY', ARRAY['PERSONAL', 'TEAM', 'TENANT']::VARCHAR[]);

DO $v202_optional_mail_preflight$
DECLARE
    expected_constraint_count INTEGER;
BEGIN
    IF to_regclass('public.mail_rules') IS NOT NULL THEN
        SELECT COUNT(*) INTO expected_constraint_count
          FROM (VALUES
                    ('mail_folders', 'ck_mail_folder_color'),
                    ('mail_folders', 'ck_mail_folder_sync_state'),
                    ('mail_threads', 'ck_mail_thread_workflow'),
                    ('mail_rules', 'ck_mail_rule_match_mode'),
                    ('mail_rules', 'ck_mail_rule_sync_state'),
                    ('mail_rules', 'ck_mail_rule_state'),
                    ('mail_rule_runs', 'ck_mail_rule_run_trigger'),
                    ('mail_rule_runs', 'ck_mail_rule_run_status')
               ) expected(table_name, constraint_name)
          JOIN pg_class relation_ref
            ON relation_ref.relname = expected.table_name
          JOIN pg_namespace namespace_ref
            ON namespace_ref.oid = relation_ref.relnamespace
           AND namespace_ref.nspname = 'public'
          JOIN pg_constraint constraint_ref
            ON constraint_ref.conrelid = relation_ref.oid
           AND constraint_ref.conname = expected.constraint_name
           AND constraint_ref.contype = 'c';

        IF expected_constraint_count <> 8 THEN
            RAISE EXCEPTION
                'V202 found a partial optional mail lifecycle CHECK schema';
        END IF;
    ELSIF to_regclass('public.mail_rule_runs') IS NOT NULL
       OR EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name = 'mail_folders'
               AND column_name IN ('color_token', 'provider_sync_state')) THEN
        RAISE EXCEPTION
            'V202 found optional mail lifecycle columns without mail_rules';
    END IF;
END;
$v202_optional_mail_preflight$;

INSERT INTO tmp_v202_check_contract_manifest (
    code_set_key, owner_service, source_reference,
    contract_kind, allowed_values)
SELECT optional_contract.*
  FROM (VALUES
            ('PLATFORM.MAIL_FOLDERS.COLOR_TOKEN',
             'dwp-platform-server', 'mail_folders.color_token',
             'REFERENCE',
             ARRAY['NEUTRAL', 'BLUE', 'TEAL', 'GREEN',
                   'AMBER', 'CORAL', 'VIOLET']::VARCHAR[]),
            ('PLATFORM.MAIL_FOLDERS.PROVIDER_SYNC_STATE',
             'dwp-platform-server', 'mail_folders.provider_sync_state',
             'STATE_MACHINE',
             ARRAY['LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR']::VARCHAR[]),
            ('PLATFORM.MAIL_THREADS.WORKFLOW_STATE',
             'dwp-platform-server', 'mail_threads.workflow_state',
             'STATE_MACHINE',
             ARRAY['OPEN', 'DONE', 'SNOOZED', 'ARCHIVED',
                   'DRAFT', 'TRASHED', 'SPAM']::VARCHAR[]),
            ('PLATFORM.MAIL_RULES.MATCH_MODE',
             'dwp-platform-server', 'mail_rules.match_mode',
             'REFERENCE', ARRAY['ALL', 'ANY']::VARCHAR[]),
            ('PLATFORM.MAIL_RULES.SYNCHRONIZATION_STATE',
             'dwp-platform-server', 'mail_rules.synchronization_state',
             'STATE_MACHINE',
             ARRAY['LOCAL_ONLY', 'PENDING', 'SYNCED', 'ERROR']::VARCHAR[]),
            ('PLATFORM.MAIL_RULES.LIFECYCLE_STATE',
             'dwp-platform-server', 'mail_rules.lifecycle_state',
             'STATE_MACHINE', ARRAY['ACTIVE', 'ARCHIVED']::VARCHAR[]),
            ('PLATFORM.MAIL_RULE_RUNS.TRIGGER_KIND',
             'dwp-platform-server', 'mail_rule_runs.trigger_kind',
             'PROTOCOL', ARRAY['MANUAL', 'INCOMING', 'BACKFILL']::VARCHAR[]),
            ('PLATFORM.MAIL_RULE_RUNS.RUN_STATUS',
             'dwp-platform-server', 'mail_rule_runs.run_status',
             'STATE_MACHINE', ARRAY['RUNNING', 'SUCCEEDED', 'FAILED']::VARCHAR[])
       ) optional_contract(
           code_set_key, owner_service, source_reference,
           contract_kind, allowed_values)
 WHERE to_regclass('public.mail_rules') IS NOT NULL;

-- A source column has one canonical active CHECK projection. An existing
-- foreign-key or domain-catalog binding may coexist because it represents a
-- separate enforcement mechanism (notably prv_operators.role_code).
DO $v202_binding_guard$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v202_check_contract_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.owner_service
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = manifest.source_reference
           AND binding.enforcement_type = 'CHECK'
           AND binding.lifecycle_state = 'ACTIVE'
           AND binding.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V202 found a conflicting active CHECK binding for a manifest source';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v202_check_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.owner_service <> manifest.owner_service
            OR code_set.source_reference <> manifest.source_reference
    ) THEN
        RAISE EXCEPTION
            'V202 code-set key is already owned by a different source';
    END IF;
END;
$v202_binding_guard$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.owner_service,
       manifest.source_reference,
       'Database CHECK contract for ' || manifest.source_reference || '.',
       'SYSTEM', 'CHECK', manifest.source_reference, manifest.contract_kind,
       'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v202_check_contract_manifest manifest
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    configuration_level = 'SYSTEM',
    validation_source = 'CHECK',
    source_reference = EXCLUDED.source_reference,
    contract_kind = EXCLUDED.contract_kind,
    runtime_visibility = 'ADMIN_ONLY',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_sets.owner_service,
          sys_code_sets.configuration_level,
          sys_code_sets.validation_source,
          sys_code_sets.source_reference,
          sys_code_sets.contract_kind,
          sys_code_sets.runtime_visibility,
          sys_code_sets.lifecycle_state)
      IS DISTINCT FROM ROW(
          EXCLUDED.owner_service,
          'SYSTEM',
          'CHECK',
          EXCLUDED.source_reference,
          EXCLUDED.contract_kind,
          'ADMIN_ONLY',
          'ACTIVE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT manifest.code_set_key,
       value_ref.code,
       value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb,
       value_ref.ordinality::INTEGER * 10,
       TRUE,
       'ACTIVE'
  FROM tmp_v202_check_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v202_check_contract_manifest manifest
 WHERE code_value.code_set_key = manifest.code_set_key
   AND NOT (code_value.code = ANY (manifest.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.owner_service,
       'DATABASE_COLUMN',
       manifest.source_reference,
       'CHECK',
       'ACTIVE'
  FROM tmp_v202_check_contract_manifest manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO UPDATE SET
    enforcement_type = 'CHECK',
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_bindings.enforcement_type,
          sys_code_bindings.lifecycle_state)
      IS DISTINCT FROM ROW('CHECK', 'ACTIVE');
