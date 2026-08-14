CREATE OR REPLACE VIEW sys_code_catalog_health AS
SELECT code_set.code_set_key,
       code_set.owner_service,
       code_set.contract_kind,
       code_set.configuration_level,
       code_set.validation_source,
       code_set.runtime_visibility,
       COUNT(DISTINCT code_value.code) AS value_count,
       COUNT(DISTINCT binding.code_binding_id) AS binding_count,
       COUNT(DISTINCT binding.code_binding_id) FILTER (
           WHERE binding.enforcement_type IN (
               'CHECK', 'FOREIGN_KEY', 'CATALOG_LOOKUP', 'TYPED_CONTRACT'))
           AS enforced_binding_count,
       CASE
           WHEN COUNT(DISTINCT code_value.code) > 0
            AND COUNT(DISTINCT binding.code_binding_id) > 0
            AND COUNT(DISTINCT binding.code_binding_id) =
                COUNT(DISTINCT binding.code_binding_id) FILTER (
                    WHERE binding.enforcement_type IN (
                        'CHECK', 'FOREIGN_KEY', 'CATALOG_LOOKUP', 'TYPED_CONTRACT'))
               THEN 'REGISTERED'
           ELSE 'INCOMPLETE'
       END AS registration_state
  FROM sys_code_sets code_set
  LEFT JOIN sys_code_values code_value
    ON code_value.code_set_key = code_set.code_set_key
   AND code_value.lifecycle_state = 'ACTIVE'
  LEFT JOIN sys_code_bindings binding
    ON binding.code_set_key = code_set.code_set_key
   AND binding.lifecycle_state = 'ACTIVE'
 WHERE code_set.lifecycle_state = 'ACTIVE'
 GROUP BY code_set.code_set_key,
          code_set.owner_service,
          code_set.contract_kind,
          code_set.configuration_level,
          code_set.validation_source,
          code_set.runtime_visibility;

COMMENT ON VIEW sys_code_catalog_health IS
    'Registration health for active governed code sets; retired catalogs remain auditable but do not fail release readiness.';
