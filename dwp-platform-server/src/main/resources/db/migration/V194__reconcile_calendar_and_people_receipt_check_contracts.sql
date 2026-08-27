-- Reconcile the exact CHECK values that became authoritative in People V46
-- and Platform V192. V190 and V193 are already applied and remain immutable.

CREATE TEMP TABLE tmp_v194_check_contract_manifest (
    code_set_key VARCHAR(100) NOT NULL,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    PRIMARY KEY (owner_service, source_reference),
    UNIQUE (code_set_key),
    CONSTRAINT ck_tmp_v194_allowed_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v194_check_contract_manifest (
    code_set_key, owner_service, source_reference, allowed_values)
VALUES
    ('PEOPLE.SYS_PROVIDER_TENANT_COMMAND_RECEIPTS.COMMAND_TYPE',
     'dwp-people-server', 'sys_provider_tenant_command_receipts.command_type',
     ARRAY['LIFECYCLE']::VARCHAR[]),
    ('PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.ACCESS_LEVEL',
     'dwp-platform-server', 'cal_calendar_access_grants.access_level',
     ARRAY['EDIT', 'MANAGE', 'VIEW_DETAILS', 'VIEW_FREE_BUSY']::VARCHAR[]),
    ('PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.LIFECYCLE_STATE',
     'dwp-platform-server', 'cal_calendar_access_grants.lifecycle_state',
     ARRAY['ACTIVE', 'EXPIRED', 'REVOKED']::VARCHAR[]),
    ('PLATFORM.CAL_CALENDAR_ACCESS_GRANTS.PRINCIPAL_TYPE',
     'dwp-platform-server', 'cal_calendar_access_grants.principal_type',
     ARRAY['GROUP', 'PERSON', 'TENANT']::VARCHAR[]),
    ('PLATFORM.CAL_CALENDARS.SUBSCRIPTION_POLICY',
     'dwp-platform-server', 'cal_calendars.subscription_policy',
     ARRAY['DEFAULT_ON', 'OPTIONAL', 'REQUIRED']::VARCHAR[]),
    ('PLATFORM.CAL_EVENT_OCCURRENCE_OVERRIDES.IMPORTANCE',
     'dwp-platform-server', 'cal_event_occurrence_overrides.importance',
     ARRAY['HIGH', 'LOW', 'NORMAL']::VARCHAR[]),
    ('PLATFORM.CAL_EVENT_OCCURRENCE_OVERRIDES.OVERRIDE_KIND',
     'dwp-platform-server', 'cal_event_occurrence_overrides.override_kind',
     ARRAY['CANCELLED', 'MODIFIED']::VARCHAR[]),
    ('PLATFORM.CAL_EVENTS.IMPORTANCE',
     'dwp-platform-server', 'cal_events.importance',
     ARRAY['HIGH', 'LOW', 'NORMAL']::VARCHAR[]);

DO $v194_binding_guard$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v194_check_contract_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.owner_service
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = manifest.source_reference
           AND binding.enforcement_type = 'CHECK'
           AND binding.lifecycle_state = 'ACTIVE'
           AND binding.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V194 found a conflicting active CHECK binding for a manifest source';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v194_check_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.owner_service <> manifest.owner_service
            OR code_set.source_reference <> manifest.source_reference
    ) THEN
        RAISE EXCEPTION
            'V194 code-set key is already owned by a different source';
    END IF;
END;
$v194_binding_guard$;

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
  FROM tmp_v194_check_contract_manifest manifest
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
  FROM tmp_v194_check_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';

-- Preserve obsolete registry evidence while removing it from the active contract.
UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v194_check_contract_manifest manifest
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
  FROM tmp_v194_check_contract_manifest manifest
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
