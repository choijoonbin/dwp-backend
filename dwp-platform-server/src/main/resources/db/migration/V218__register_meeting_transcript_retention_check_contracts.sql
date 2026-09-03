-- Project the closed CHECK vocabularies introduced by Meeting V25 into
-- the central administrator-only registry. The Meeting schema remains the
-- source of truth; the executable code-contract audit compares these rows to it.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE tmp_v218_check_contract_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    source_reference VARCHAR(240) NOT NULL UNIQUE,
    contract_kind VARCHAR(24) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    CONSTRAINT ck_tmp_v218_contract_kind
        CHECK (contract_kind IN ('STATE_MACHINE', 'OBSERVABILITY')),
    CONSTRAINT ck_tmp_v218_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v218_check_contract_manifest VALUES
    ('MEETING.VM_MEETING_TRANSCRIPT_DELETION_COMMANDS.COMMAND_STATE',
     'vm_meeting_transcript_deletion_commands.command_state',
     'STATE_MACHINE',
     ARRAY['FAILED', 'RUNNING', 'SUCCEEDED']::VARCHAR[]),
    ('MEETING.VM_MEETING_TRANSCRIPT_DELETION_HEALTH.HEALTH_KEY',
     'vm_meeting_transcript_deletion_health.health_key',
     'OBSERVABILITY',
     ARRAY['TRANSCRIPT_RETENTION']::VARCHAR[]);

DO $v218_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v218_check_contract_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = 'dwp-meeting-server'
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = manifest.source_reference
           AND binding.enforcement_type = 'CHECK'
           AND binding.lifecycle_state = 'ACTIVE'
           AND binding.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V218 found a conflicting active Meeting transcript retention CHECK binding';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v218_check_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.owner_service <> 'dwp-meeting-server'
            OR code_set.source_reference <> manifest.source_reference
    ) THEN
        RAISE EXCEPTION
            'V218 Meeting transcript retention code-set key is owned by a different source';
    END IF;
END;
$v218_preflight$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key,
       'dwp-meeting-server',
       manifest.source_reference,
       'Database CHECK contract for ' || manifest.source_reference || '.',
       'SYSTEM', 'CHECK', manifest.source_reference, manifest.contract_kind,
       'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v218_check_contract_manifest manifest
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
  FROM tmp_v218_check_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE sys_code_values.lifecycle_state <> 'ACTIVE';

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v218_check_contract_manifest manifest
 WHERE code_value.code_set_key = manifest.code_set_key
   AND NOT (code_value.code = ANY (manifest.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key,
       'dwp-meeting-server',
       'DATABASE_COLUMN',
       manifest.source_reference,
       'CHECK',
       'ACTIVE'
  FROM tmp_v218_check_contract_manifest manifest
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

DO $v218_postcondition$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v218_check_contract_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.code_set_key IS NULL
            OR ROW(code_set.owner_service, code_set.validation_source,
                   code_set.source_reference, code_set.contract_kind,
                   code_set.configuration_level, code_set.runtime_visibility,
                   code_set.lifecycle_state)
               IS DISTINCT FROM
               ROW('dwp-meeting-server', 'CHECK',
                   manifest.source_reference, manifest.contract_kind,
                   'SYSTEM', 'ADMIN_ONLY', 'ACTIVE')
            OR (SELECT array_agg(code_value.code ORDER BY code_value.code)
                  FROM sys_code_values code_value
                 WHERE code_value.code_set_key = manifest.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM manifest.allowed_values
            OR (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = 'dwp-meeting-server'
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.source_reference = manifest.source_reference
                   AND binding.enforcement_type = 'CHECK'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
    ) THEN
        RAISE EXCEPTION
            'V218 Meeting transcript retention CHECK contract convergence failed';
    END IF;
END;
$v218_postcondition$;
