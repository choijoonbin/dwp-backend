INSERT INTO adm_tenant_branding (
    tenant_id, organization_name, version, created_by, updated_by)
VALUES (1, 'SKAX', 0, 1, 1)
ON CONFLICT (tenant_id) DO UPDATE SET
    organization_name = EXCLUDED.organization_name,
    version = adm_tenant_branding.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

UPDATE sys_service_tenants
   SET display_name = 'SKAX',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE tenant_id = 1;
