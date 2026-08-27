-- Reconcile the Widget Registry ingress-failure contract after the receiver
-- began rejecting browser and gateway authority headers by default. V205 and
-- V206 are immutable; this forward-only projection accepts only the frozen
-- V205 value set or the exact final enum set and fails closed on all drift.

SELECT pg_advisory_xact_lock(
    hashtextextended('dwp-platform:system-code-registry', 0));

LOCK TABLE sys_code_sets, sys_code_values, sys_code_bindings
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE tmp_v207_ingress_failure_manifest (
    code_set_key VARCHAR(100) PRIMARY KEY,
    owner_service VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    prior_values VARCHAR[] NOT NULL,
    final_values VARCHAR[] NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_v207_ingress_failure_manifest VALUES (
    'PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE',
    'dwp-platform-server',
    'WidgetRegistryIngressFailure',
    ARRAY['ROUTE_NOT_FOUND', 'METHOD_NOT_ALLOWED', 'TLS_REQUIRED',
          'PROVISIONING_TOKEN_FORBIDDEN', 'DUAL_PROOF_REQUIRED',
          'SERVICE_TOKEN_INVALID', 'ASSERTION_INVALID',
          'REQUEST_BINDING_INVALID', 'PAYLOAD_TOO_LARGE',
          'ASSERTION_REPLAYED', 'TRUST_UNAVAILABLE']::VARCHAR[],
    ARRAY['ROUTE_NOT_FOUND', 'METHOD_NOT_ALLOWED', 'TLS_REQUIRED',
          'PROVISIONING_TOKEN_FORBIDDEN', 'AUTHORITY_HEADERS_FORBIDDEN',
          'DUAL_PROOF_REQUIRED', 'SERVICE_TOKEN_INVALID',
          'ASSERTION_INVALID', 'REQUEST_BINDING_INVALID',
          'PAYLOAD_TOO_LARGE', 'ASSERTION_REPLAYED',
          'TRUST_UNAVAILABLE']::VARCHAR[]
);

DO $v207_preflight$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM tmp_v207_ingress_failure_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE ROW(code_set.owner_service, code_set.source_reference,
                   code_set.display_name, code_set.description,
                   code_set.configuration_level, code_set.validation_source,
                   code_set.contract_kind, code_set.runtime_visibility,
                   code_set.lifecycle_state)
               IS NOT DISTINCT FROM
               ROW(manifest.owner_service, manifest.source_reference,
                   'WidgetRegistryIngressFailure',
                   'Exact Java enum contract for '
                       || 'WidgetRegistryIngressFailure.',
                   'SYSTEM', 'TYPED_CONTRACT', 'SECURITY', 'ADMIN_ONLY',
                   'ACTIVE')
    ) OR (
        SELECT COUNT(*)
          FROM sys_code_sets code_set
         WHERE code_set.owner_service = 'dwp-platform-server'
           AND code_set.source_reference = 'WidgetRegistryIngressFailure'
    ) <> 1 THEN
        RAISE EXCEPTION
            'V207 Widget ingress-failure code-set identity or metadata drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM tmp_v207_ingress_failure_manifest manifest
          JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE NOT (
             code_set.schema_version = 3
             AND (SELECT array_agg(code_value.code ORDER BY code_value.code)
                    FROM sys_code_values code_value
                   WHERE code_value.code_set_key = manifest.code_set_key
                     AND code_value.lifecycle_state = 'ACTIVE')
                 IS NOT DISTINCT FROM
                 (SELECT array_agg(expected.code ORDER BY expected.code)
                    FROM unnest(manifest.prior_values) expected(code))
         )
           AND NOT (
             code_set.schema_version = 4
             AND (SELECT array_agg(code_value.code ORDER BY code_value.code)
                    FROM sys_code_values code_value
                   WHERE code_value.code_set_key = manifest.code_set_key
                     AND code_value.lifecycle_state = 'ACTIVE')
                 IS NOT DISTINCT FROM
                 (SELECT array_agg(expected.code ORDER BY expected.code)
                    FROM unnest(manifest.final_values) expected(code))
         )
    ) THEN
        RAISE EXCEPTION
            'V207 Widget ingress-failure values or revision drifted';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM sys_code_values code_value
         WHERE code_value.code_set_key =
                   'PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE'
           AND code_value.code = 'AUTHORITY_HEADERS_FORBIDDEN'
           AND ROW(code_value.display_name, code_value.label_i18n,
                   code_value.behavior_metadata, code_value.sort_order,
                   code_value.predefined, code_value.lifecycle_state)
               IS DISTINCT FROM
               ROW('AUTHORITY_HEADERS_FORBIDDEN',
                   jsonb_build_object(
                       'ko', 'AUTHORITY_HEADERS_FORBIDDEN',
                       'en', 'AUTHORITY_HEADERS_FORBIDDEN'),
                   '{}'::jsonb, 45, TRUE, 'ACTIVE')
    ) THEN
        RAISE EXCEPTION
            'V207 Widget ingress-failure target value metadata drifted';
    END IF;

    IF (SELECT COUNT(*)
          FROM sys_code_bindings binding
         WHERE binding.code_set_key =
                   'PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE'
           AND binding.consumer_service = 'dwp-platform-server'
           AND binding.usage_type = 'API_CONTRACT'
           AND binding.source_reference = 'WidgetRegistryIngressFailure'
           AND binding.enforcement_type = 'TYPED_CONTRACT'
           AND binding.lifecycle_state = 'ACTIVE') <> 1
       OR (SELECT COUNT(*)
             FROM sys_code_bindings binding
            WHERE binding.consumer_service = 'dwp-platform-server'
              AND binding.source_reference =
                      'WidgetRegistryIngressFailure') <> 1 THEN
        RAISE EXCEPTION
            'V207 Widget ingress-failure binding drifted';
    END IF;
END;
$v207_preflight$;

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    behavior_metadata, sort_order, predefined, lifecycle_state)
VALUES (
    'PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE',
    'AUTHORITY_HEADERS_FORBIDDEN',
    'AUTHORITY_HEADERS_FORBIDDEN',
    jsonb_build_object(
        'ko', 'AUTHORITY_HEADERS_FORBIDDEN',
        'en', 'AUTHORITY_HEADERS_FORBIDDEN'),
    '{}'::jsonb, 45, TRUE, 'ACTIVE')
ON CONFLICT (code_set_key, code) DO NOTHING;

DO $v207_postcondition$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tmp_v207_ingress_failure_manifest manifest
          LEFT JOIN sys_code_sets code_set
            ON code_set.code_set_key = manifest.code_set_key
         WHERE code_set.code_set_key IS NULL
            OR ROW(code_set.owner_service, code_set.source_reference,
                   code_set.configuration_level, code_set.validation_source,
                   code_set.contract_kind, code_set.runtime_visibility,
                   code_set.lifecycle_state, code_set.schema_version)
               IS DISTINCT FROM
               ROW(manifest.owner_service, manifest.source_reference,
                   'SYSTEM', 'TYPED_CONTRACT', 'SECURITY', 'ADMIN_ONLY',
                   'ACTIVE', 4)
            OR (SELECT array_agg(code_value.code ORDER BY code_value.code)
                  FROM sys_code_values code_value
                 WHERE code_value.code_set_key = manifest.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE')
               IS DISTINCT FROM
               (SELECT array_agg(expected.code ORDER BY expected.code)
                  FROM unnest(manifest.final_values) expected(code))
    ) OR NOT EXISTS (
        SELECT 1
          FROM sys_code_values code_value
         WHERE code_value.code_set_key =
                   'PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE'
           AND code_value.code = 'AUTHORITY_HEADERS_FORBIDDEN'
           AND code_value.display_name = 'AUTHORITY_HEADERS_FORBIDDEN'
           AND code_value.label_i18n = jsonb_build_object(
                   'ko', 'AUTHORITY_HEADERS_FORBIDDEN',
                   'en', 'AUTHORITY_HEADERS_FORBIDDEN')
           AND code_value.behavior_metadata = '{}'::jsonb
           AND code_value.sort_order = 45
           AND code_value.predefined
           AND code_value.lifecycle_state = 'ACTIVE'
    ) OR (SELECT COUNT(*)
            FROM sys_code_bindings binding
           WHERE binding.code_set_key =
                     'PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE'
             AND binding.consumer_service = 'dwp-platform-server'
             AND binding.usage_type = 'API_CONTRACT'
             AND binding.source_reference = 'WidgetRegistryIngressFailure'
             AND binding.enforcement_type = 'TYPED_CONTRACT'
             AND binding.lifecycle_state = 'ACTIVE') <> 1
       OR (SELECT COUNT(*)
             FROM sys_code_bindings binding
            WHERE binding.consumer_service = 'dwp-platform-server'
              AND binding.source_reference =
                      'WidgetRegistryIngressFailure') <> 1 THEN
        RAISE EXCEPTION
            'V207 Widget ingress-failure contract convergence failed';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM sys_code_catalog_health health
         WHERE health.code_set_key =
                   'PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE'
           AND health.value_count = 12
           AND health.binding_count = 1
           AND health.enforced_binding_count = 1
           AND health.registration_state = 'REGISTERED'
    ) THEN
        RAISE EXCEPTION
            'V207 Widget ingress-failure catalog health is incomplete';
    END IF;
END;
$v207_postcondition$;
