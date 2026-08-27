-- Register CHECK contracts introduced after the V190 reconciliation was applied.
-- The service database constraints remain authoritative; this migration only
-- projects their exact closed values into the central, administrator-only catalog.

CREATE TEMP TABLE tmp_v193_check_contract_manifest (
    code_set_key VARCHAR(100) NOT NULL,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    PRIMARY KEY (owner_service, source_reference),
    UNIQUE (code_set_key),
    CONSTRAINT ck_tmp_v193_allowed_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v193_check_contract_manifest (
    code_set_key, owner_service, source_reference, allowed_values)
VALUES
    ('PLATFORM.EXPERIENCE_REVISION.CHANGE_TYPE',
     'dwp-platform-server', 'adm_experience_revisions.change_type',
     ARRAY['ASSET_PUBLISHED', 'ASSET_RESET', 'BASELINE',
           'EXPERIENCE_PUBLISHED', 'ROLLBACK', 'SETTINGS_PUBLISHED']::VARCHAR[]),
    ('PLATFORM.ADM_HOME_EXPERIENCES.CONTENT_ALIGNMENT',
     'dwp-platform-server', 'adm_home_experiences.content_alignment',
     ARRAY['CENTER', 'LEFT', 'RIGHT']::VARCHAR[]),
    ('PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.ACTION',
     'dwp-platform-server', 'usr_saved_view_lifecycle_commands.action',
     ARRAY['ARCHIVE_NOW', 'EXTEND_RETENTION', 'REASSIGN']::VARCHAR[]),
    ('PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.NEW_LIFECYCLE_STATE',
     'dwp-platform-server', 'usr_saved_view_lifecycle_commands.new_lifecycle_state',
     ARRAY['ACTIVE', 'ARCHIVED', 'ORPHANED']::VARCHAR[]),
    ('PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.PREVIOUS_LIFECYCLE_STATE',
     'dwp-platform-server', 'usr_saved_view_lifecycle_commands.previous_lifecycle_state',
     ARRAY['ORPHANED']::VARCHAR[]),
    ('PLATFORM.USR_SAVED_VIEW_LIFECYCLE_COMMANDS.REASON_CODE',
     'dwp-platform-server', 'usr_saved_view_lifecycle_commands.reason_code',
     ARRAY['OFFBOARDING', 'OWNER_CORRECTION', 'TEAM_REORGANIZATION']::VARCHAR[]),
    ('PLATFORM.WP_FLOOR_PLAN_MEDIA_ASSETS.ASSET_STATUS',
     'dwp-platform-server', 'wp_floor_plan_media_assets.asset_status',
     ARRAY['DELETED', 'DELETING', 'PENDING_DELETE', 'REFERENCED', 'STAGED']::VARCHAR[]),
    ('PROVIDER.PRV_SUPPORT_ACTIVATION_CONTROL.CONTROL_KEY',
     'dwp-provider-server', 'prv_support_activation_control.control_key',
     ARRAY['STANDARD_JIT']::VARCHAR[]),

    ('AUTH.SYS_PROVIDER_TENANT_COMMAND_RECEIPTS.COMMAND_TYPE',
     'dwp-auth-server', 'sys_provider_tenant_command_receipts.command_type',
     ARRAY['ENTITLEMENTS', 'LIFECYCLE']::VARCHAR[]),
    ('PEOPLE.SYS_PROVIDER_TENANT_COMMAND_RECEIPTS.COMMAND_TYPE',
     'dwp-people-server', 'sys_provider_tenant_command_receipts.command_type',
     ARRAY['ENTITLEMENTS', 'LIFECYCLE']::VARCHAR[]),
    ('PLATFORM.SYS_PROVIDER_TENANT_COMMAND_RECEIPTS.COMMAND_TYPE',
     'dwp-platform-server', 'sys_provider_tenant_command_receipts.command_type',
     ARRAY['ENTITLEMENTS', 'LIFECYCLE']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_MUTATIONS.MUTATION_TYPE',
     'dwp-provider-server', 'prv_tenant_mutations.mutation_type',
     ARRAY['ENTITLEMENTS', 'LIFECYCLE']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_MUTATIONS.LIFECYCLE_STATE',
     'dwp-provider-server', 'prv_tenant_mutations.lifecycle_state',
     ARRAY['COMPENSATED', 'COMPENSATING', 'EXECUTING', 'PENDING',
           'RECONCILIATION_REQUIRED', 'RETRY_WAIT', 'SUCCEEDED']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_COMMAND_OUTBOX.TARGET_SERVICE',
     'dwp-provider-server', 'prv_tenant_command_outbox.target_service',
     ARRAY['AUTH', 'PEOPLE', 'PLATFORM']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_COMMAND_OUTBOX.COMMAND_TYPE',
     'dwp-provider-server', 'prv_tenant_command_outbox.command_type',
     ARRAY['ENTITLEMENTS', 'LIFECYCLE']::VARCHAR[]),
    ('PROVIDER.PRV_TENANT_COMMAND_OUTBOX.LIFECYCLE_STATE',
     'dwp-provider-server', 'prv_tenant_command_outbox.lifecycle_state',
     ARRAY['APPLIED', 'COMPENSATED', 'COMPENSATION_PENDING', 'LEASED',
           'PENDING', 'RECONCILIATION_REQUIRED', 'RETRY_WAIT']::VARCHAR[]);

DO $v193_binding_guard$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v193_check_contract_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.owner_service
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = manifest.source_reference
           AND binding.enforcement_type = 'CHECK'
           AND binding.lifecycle_state = 'ACTIVE'
           AND binding.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V193 found a conflicting active CHECK binding for a manifest source';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v193_check_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.owner_service <> manifest.owner_service
            OR code_set.source_reference <> manifest.source_reference
    ) THEN
        RAISE EXCEPTION
            'V193 code-set key is already owned by a different source';
    END IF;
END;
$v193_binding_guard$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key,
       manifest.owner_service,
       manifest.source_reference,
       'Closed database CHECK contract for ' || manifest.source_reference || '.',
       'SYSTEM', 'CHECK', manifest.source_reference, 'REFERENCE',
       'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v193_check_contract_manifest manifest
ON CONFLICT (code_set_key) DO UPDATE SET
    owner_service = EXCLUDED.owner_service,
    configuration_level = 'SYSTEM',
    validation_source = 'CHECK',
    source_reference = EXCLUDED.source_reference,
    runtime_visibility = 'ADMIN_ONLY',
    lifecycle_state = 'ACTIVE'
WHERE ROW(
          sys_code_sets.owner_service,
          sys_code_sets.configuration_level,
          sys_code_sets.validation_source,
          sys_code_sets.source_reference,
          sys_code_sets.runtime_visibility,
          sys_code_sets.lifecycle_state)
      IS DISTINCT FROM ROW(
          EXCLUDED.owner_service,
          'SYSTEM',
          'CHECK',
          EXCLUDED.source_reference,
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
  FROM tmp_v193_check_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v193_check_contract_manifest manifest
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
  FROM tmp_v193_check_contract_manifest manifest
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
