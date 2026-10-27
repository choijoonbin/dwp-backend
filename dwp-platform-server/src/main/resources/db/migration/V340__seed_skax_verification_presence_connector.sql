-- Connect only the bundled SKAX verification tenant to the in-process presence adapter.
-- Existing connector ownership always wins; production/customer tenants remain fail-closed.
INSERT INTO wp_experience_connector_configurations (
    tenant_id,
    connector_kind,
    provider,
    enabled,
    configuration_reference,
    version,
    updated_by)
SELECT tenant.tenant_id,
       'ACTUAL_PRESENCE',
       'DWP_LOCAL_VERIFICATION',
       TRUE,
       'local://skax-verification/presence',
       0,
       identity_link.user_id
  FROM sys_service_tenants tenant
  JOIN cal_identity_links identity_link
    ON identity_link.tenant_id = tenant.tenant_id
   AND identity_link.user_id = 900018
   AND identity_link.person_public_id =
       '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'::uuid
  JOIN wp_tenant_policies policy
    ON policy.tenant_id = tenant.tenant_id
 WHERE tenant.tenant_key = 'default'
ON CONFLICT DO NOTHING;
