-- Catalog only. Neither resource registration nor a permission code grants actor authority.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES ('ACTION.APPROVAL_SIGNATURE', 'ACTION',
        'Approval internal self-attestation ceremony', 'core.approvals')
ON CONFLICT (resource_key) DO NOTHING;

INSERT INTO com_resources (tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key = 'ACTION.APPROVAL_SIGNATURE'
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, type, key) DO NOTHING;

INSERT INTO com_permissions (code, name, created_by, updated_by)
VALUES ('UPDATE', 'Update', 1, 1), ('SIGN', 'Internal self-attestation', 1, 1)
ON CONFLICT (code) DO NOTHING;

-- No role templates, user grants, scoped duties, resource-set grants or route activation.
