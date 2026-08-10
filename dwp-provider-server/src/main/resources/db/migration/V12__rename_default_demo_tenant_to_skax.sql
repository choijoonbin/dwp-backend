UPDATE prv_tenants
   SET display_name = 'SKAX',
       version = version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE provider_tenant_id = '00000000-0000-0000-0000-000000000001';
