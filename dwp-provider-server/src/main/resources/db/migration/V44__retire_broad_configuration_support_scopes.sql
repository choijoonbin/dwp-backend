-- Prefix-wide configuration access includes assets, revision history, and
-- future child routes. Retire both broad scopes until exact method/template
-- catalogs and field-masked projections are executable end to end.
UPDATE prv_support_scope_catalog
   SET lifecycle_state = 'RETIRED'
 WHERE scope_code IN ('TENANT_CONFIGURATION_READ', 'TENANT_CONFIGURATION_WRITE')
   AND lifecycle_state <> 'RETIRED';
