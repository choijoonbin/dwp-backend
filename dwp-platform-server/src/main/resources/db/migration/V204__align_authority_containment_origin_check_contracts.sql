-- Align the central registry with the Provider authority-containment origin
-- literals introduced by Provider V53. Provider database CHECK constraints
-- remain authoritative; this migration only advances the administrator-only
-- projection created by Platform V202.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE tmp_v204_authority_origin_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    allowed_values VARCHAR[] NOT NULL,
    prior_values VARCHAR[] NOT NULL,
    CONSTRAINT ck_tmp_v204_allowed_values_nonempty
        CHECK (cardinality(allowed_values) > 0),
    CONSTRAINT ck_tmp_v204_prior_values_nonempty
        CHECK (cardinality(prior_values) > 0),
    CONSTRAINT ck_tmp_v204_prior_values_preserved
        CHECK (prior_values <@ allowed_values)
) ON COMMIT DROP;

INSERT INTO tmp_v204_authority_origin_manifest (
    code_set_key, owner_service, source_reference,
    allowed_values, prior_values)
VALUES
    ('PROVIDER.PRV_SUPPORT_ACCESS_REQUESTS.CANCELLATION_ORIGIN',
     'dwp-provider-server', 'prv_support_access_requests.cancellation_origin',
     ARRAY['AUTOMATIC_OPERATOR_CONTAINMENT',
           'AUTOMATIC_SCOPE_RETIREMENT',
           'AUTOMATIC_AUTHORITY_CONTAINMENT']::VARCHAR[],
     ARRAY['AUTOMATIC_OPERATOR_CONTAINMENT',
           'AUTOMATIC_SCOPE_RETIREMENT']::VARCHAR[]),
    ('PROVIDER.PRV_SUPPORT_SESSIONS.REVOCATION_ORIGIN',
     'dwp-provider-server', 'prv_support_sessions.revocation_origin',
     ARRAY['AUTOMATIC_OPERATOR_CONTAINMENT',
           'AUTOMATIC_SCOPE_RETIREMENT',
           'AUTOMATIC_TENANT_CONTAINMENT',
           'AUTOMATIC_AUTHORITY_CONTAINMENT']::VARCHAR[],
     ARRAY['AUTOMATIC_OPERATOR_CONTAINMENT',
           'AUTOMATIC_SCOPE_RETIREMENT',
           'AUTOMATIC_TENANT_CONTAINMENT']::VARCHAR[]);

-- Ownership, source identity, the V202 baseline values, and CHECK enforcement
-- are security boundaries. Do not silently adopt a missing or repurposed
-- registry object. Lifecycle and descriptive drift are repaired below only
-- after these immutable identities have been proven.
DO $v204_canonical_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v204_authority_origin_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.code_set_key IS NULL
    ) THEN
        RAISE EXCEPTION
            'V204 requires both authority-origin code sets from Platform V202';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v204_authority_origin_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.owner_service <> manifest.owner_service
            OR code_set.source_reference <> manifest.source_reference
    ) THEN
        RAISE EXCEPTION
            'V204 authority-origin code-set ownership or source is not canonical';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v204_authority_origin_manifest manifest
         WHERE EXISTS (
                   SELECT 1
                     FROM unnest(manifest.prior_values) prior(code)
                    WHERE NOT EXISTS (
                          SELECT 1
                            FROM sys_code_values code_value
                           WHERE code_value.code_set_key = manifest.code_set_key
                             AND code_value.code = prior.code))
    ) THEN
        RAISE EXCEPTION
            'V204 authority-origin V202 baseline values are incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v204_authority_origin_manifest manifest
         WHERE NOT EXISTS (
               SELECT 1
                 FROM sys_code_bindings binding
                WHERE binding.code_set_key = manifest.code_set_key
                  AND binding.consumer_service = manifest.owner_service
                  AND binding.usage_type = 'DATABASE_COLUMN'
                  AND binding.source_reference = manifest.source_reference
                  AND binding.enforcement_type = 'CHECK')
    ) THEN
        RAISE EXCEPTION
            'V204 authority-origin canonical CHECK binding is missing or altered';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v204_authority_origin_manifest manifest
          JOIN sys_code_bindings binding
            ON binding.consumer_service = manifest.owner_service
           AND binding.usage_type = 'DATABASE_COLUMN'
           AND binding.source_reference = manifest.source_reference
           AND binding.enforcement_type = 'CHECK'
           AND binding.lifecycle_state = 'ACTIVE'
         WHERE binding.code_set_key <> manifest.code_set_key
    ) THEN
        RAISE EXCEPTION
            'V204 found a conflicting active CHECK binding for an authority origin';
    END IF;
END;
$v204_canonical_preflight$;

UPDATE sys_code_sets code_set
   SET display_name = manifest.source_reference,
       description =
           'Database CHECK contract for ' || manifest.source_reference || '.',
       configuration_level = 'SYSTEM',
       validation_source = 'CHECK',
       contract_kind = 'SECURITY',
       runtime_visibility = 'ADMIN_ONLY',
       lifecycle_state = 'ACTIVE'
  FROM tmp_v204_authority_origin_manifest manifest
 WHERE code_set.code_set_key = manifest.code_set_key
   AND ROW(
           code_set.display_name,
           code_set.description,
           code_set.configuration_level,
           code_set.validation_source,
           code_set.contract_kind,
           code_set.runtime_visibility,
           code_set.lifecycle_state)
       IS DISTINCT FROM ROW(
           manifest.source_reference,
           'Database CHECK contract for ' || manifest.source_reference || '.',
           'SYSTEM', 'CHECK', 'SECURITY', 'ADMIN_ONLY', 'ACTIVE');

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
  FROM tmp_v204_authority_origin_manifest manifest
 CROSS JOIN LATERAL unnest(manifest.allowed_values)
     WITH ORDINALITY AS value_ref(code, ordinality)
ON CONFLICT (code_set_key, code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    label_i18n = EXCLUDED.label_i18n,
    behavior_metadata = EXCLUDED.behavior_metadata,
    sort_order = EXCLUDED.sort_order,
    predefined = TRUE,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE ROW(
          sys_code_values.display_name,
          sys_code_values.label_i18n,
          sys_code_values.behavior_metadata,
          sys_code_values.sort_order,
          sys_code_values.predefined,
          sys_code_values.lifecycle_state)
      IS DISTINCT FROM ROW(
          EXCLUDED.display_name,
          EXCLUDED.label_i18n,
          EXCLUDED.behavior_metadata,
          EXCLUDED.sort_order,
          TRUE,
          'ACTIVE');

UPDATE sys_code_values code_value
   SET lifecycle_state = 'RETIRED',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v204_authority_origin_manifest manifest
 WHERE code_value.code_set_key = manifest.code_set_key
   AND NOT (code_value.code = ANY (manifest.allowed_values))
   AND code_value.lifecycle_state <> 'RETIRED';

UPDATE sys_code_bindings binding
   SET lifecycle_state = 'ACTIVE',
       updated_at = CURRENT_TIMESTAMP
  FROM tmp_v204_authority_origin_manifest manifest
 WHERE binding.code_set_key = manifest.code_set_key
   AND binding.consumer_service = manifest.owner_service
   AND binding.usage_type = 'DATABASE_COLUMN'
   AND binding.source_reference = manifest.source_reference
   AND binding.enforcement_type = 'CHECK'
   AND binding.lifecycle_state <> 'ACTIVE';

DO $v204_postcondition$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v204_authority_origin_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE ROW(
                   code_set.owner_service,
                   code_set.source_reference,
                   code_set.configuration_level,
                   code_set.validation_source,
                   code_set.contract_kind,
                   code_set.runtime_visibility,
                   code_set.lifecycle_state)
               IS DISTINCT FROM ROW(
                   manifest.owner_service,
                   manifest.source_reference,
                   'SYSTEM', 'CHECK', 'SECURITY', 'ADMIN_ONLY', 'ACTIVE')
    ) OR EXISTS (
        SELECT 1
          FROM tmp_v204_authority_origin_manifest manifest
         WHERE (SELECT array_agg(code_value.code ORDER BY code_value.code)
                  FROM sys_code_values code_value
                 WHERE code_value.code_set_key = manifest.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM
               (SELECT array_agg(expected.code ORDER BY expected.code)
                  FROM unnest(manifest.allowed_values) expected(code))
    ) OR EXISTS (
        SELECT 1
          FROM tmp_v204_authority_origin_manifest manifest
         WHERE (SELECT COUNT(*)
                  FROM sys_code_bindings binding
                 WHERE binding.code_set_key = manifest.code_set_key
                   AND binding.consumer_service = manifest.owner_service
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.source_reference = manifest.source_reference
                   AND binding.enforcement_type = 'CHECK'
                   AND binding.lifecycle_state = 'ACTIVE') <> 1
    ) THEN
        RAISE EXCEPTION
            'V204 authority-origin registry convergence failed';
    END IF;
END;
$v204_postcondition$;
