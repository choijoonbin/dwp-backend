-- Register the closed Widget Registry command-target protocol introduced after
-- V205. The private Java enum is authoritative; this projection is additive,
-- administrator-only, and fails closed on identity, value, or binding drift.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE tmp_v206_target_contract_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    CONSTRAINT ck_tmp_v206_target_contract_values_nonempty
        CHECK (cardinality(allowed_values) > 0)
) ON COMMIT DROP;

INSERT INTO tmp_v206_target_contract_manifest VALUES (
    'PLATFORM.WIDGET_REGISTRY.COMMAND_TARGET_CONTRACT',
    'dwp-platform-server',
    'WidgetRegistryCommandTrustPolicy.TargetContract',
    'Widget Registry command target contract',
    'Exact Java enum contract for '
        || 'WidgetRegistryCommandTrustPolicy.TargetContract.',
    ARRAY['DEFINITION_KEY_HASH', 'DEFINITION_SEMVER_HASH', 'VERSION',
          'EVIDENCE', 'DEFINITION', 'DEFINITION_CHANNEL_HASH',
          'RUNTIME_CONTROL_SCOPE_HASH', 'RUNTIME_CONTROL']::VARCHAR[]
);

DO $v206_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v206_target_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE ROW(code_set.owner_service, code_set.source_reference,
                   code_set.display_name, code_set.description,
                   code_set.configuration_level, code_set.validation_source,
                   code_set.contract_kind, code_set.runtime_visibility,
                   code_set.lifecycle_state)
               IS DISTINCT FROM
               ROW(manifest.owner_service, manifest.source_reference,
                   manifest.display_name, manifest.description,
                   'SYSTEM', 'TYPED_CONTRACT', 'SECURITY', 'ADMIN_ONLY',
                   'ACTIVE')
    ) OR EXISTS (
        SELECT 1
          FROM tmp_v206_target_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.owner_service = manifest.owner_service
           AND code_set.source_reference = manifest.source_reference
         WHERE code_set.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V206 command target code-set identity or metadata drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v206_target_contract_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE (SELECT array_agg(code_value.code ORDER BY code_value.code)
                  FROM sys_code_values code_value
                 WHERE code_value.code_set_key = manifest.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM
               (SELECT array_agg(expected.code ORDER BY expected.code)
                  FROM unnest(manifest.allowed_values) expected(code))
    ) THEN
        RAISE EXCEPTION 'V206 command target code-set values drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v206_target_contract_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.owner_service
           AND binding.source_reference = manifest.source_reference
         WHERE ROW(binding.code_set_key, binding.usage_type,
                   binding.enforcement_type, binding.lifecycle_state)
               IS DISTINCT FROM
               ROW(manifest.code_set_key, 'BEHAVIOR', 'TYPED_CONTRACT',
                   'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'V206 found a conflicting command target binding';
    END IF;
END;
$v206_preflight$;

INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind,
    runtime_visibility, lifecycle_state)
SELECT manifest.code_set_key, manifest.owner_service, manifest.display_name,
       manifest.description, 'SYSTEM', 'TYPED_CONTRACT',
       manifest.source_reference, 'SECURITY', 'ADMIN_ONLY', 'ACTIVE'
  FROM tmp_v206_target_contract_manifest manifest
ON CONFLICT (code_set_key) DO NOTHING;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
SELECT manifest.code_set_key, value_ref.code, value_ref.code,
       jsonb_build_object('ko', value_ref.code, 'en', value_ref.code),
       '{}'::jsonb, value_ref.ordinality::INTEGER * 10, TRUE, 'ACTIVE'
  FROM tmp_v206_target_contract_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO NOTHING;

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type, lifecycle_state)
SELECT manifest.code_set_key, manifest.owner_service, 'BEHAVIOR',
       manifest.source_reference, 'TYPED_CONTRACT', 'ACTIVE'
  FROM tmp_v206_target_contract_manifest manifest
ON CONFLICT (
    code_set_key, consumer_service, usage_type, source_reference)
DO NOTHING;

DO $v206_postcondition$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v206_target_contract_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.code_set_key IS NULL
            OR ROW(code_set.owner_service, code_set.source_reference,
                   code_set.display_name, code_set.description,
                   code_set.configuration_level, code_set.validation_source,
                   code_set.contract_kind, code_set.runtime_visibility,
                   code_set.lifecycle_state)
               IS DISTINCT FROM
               ROW(manifest.owner_service, manifest.source_reference,
                   manifest.display_name, manifest.description,
                   'SYSTEM', 'TYPED_CONTRACT', 'SECURITY', 'ADMIN_ONLY',
                   'ACTIVE')
            OR (SELECT array_agg(code_value.code ORDER BY code_value.code)
                  FROM sys_code_values code_value
                 WHERE code_value.code_set_key = manifest.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM
               (SELECT array_agg(expected.code ORDER BY expected.code)
                  FROM unnest(manifest.allowed_values) expected(code))
            OR (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = manifest.owner_service
                   AND binding.usage_type = 'BEHAVIOR'
                   AND binding.source_reference = manifest.source_reference
                   AND binding.enforcement_type = 'TYPED_CONTRACT'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
            OR (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.consumer_service = manifest.owner_service
                   AND binding.source_reference = manifest.source_reference)
               <> 1
    ) THEN
        RAISE EXCEPTION
            'V206 command target code-contract registry convergence failed';
    END IF;
END;
$v206_postcondition$;
