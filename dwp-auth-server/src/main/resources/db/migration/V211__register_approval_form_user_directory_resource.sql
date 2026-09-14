-- Catalog registration only. Source VIEW must be granted explicitly, never by CREATE or HCM access.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES ('ACTION.APPROVAL_FORM_USER_DIRECTORY', 'ACTION',
        'Purpose-bound approval form people source', 'core.approvals')
ON CONFLICT (resource_key) DO NOTHING;

INSERT INTO com_resources (tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key = 'ACTION.APPROVAL_FORM_USER_DIRECTORY'
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, type, key) DO NOTHING;
