-- Catalog only. Planning requires two independent explicitly approved same-resource-set duties.
INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES ('ADMIN.APPROVAL_WORKFLOW', 'ADMIN',
        'Approval workflow planning authority', 'core.approvals')
ON CONFLICT (resource_key) DO NOTHING;

INSERT INTO com_resources (tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key = 'ADMIN.APPROVAL_WORKFLOW'
   AND template.lifecycle_state = 'ACTIVE'
ON CONFLICT (tenant_id, type, key) DO NOTHING;

INSERT INTO sys_admin_scoped_duty_catalog (
    duty_code, product_key, legacy_role_code, product_resource_key, resource_key,
    audit_policy_exception, risk_tier)
VALUES
    ('APPROVAL_WORKFLOW_PLANNING', 'approvals', 'APPROVAL_DESIGNER', 'APP.APPROVALS',
     'ADMIN.APPROVAL_WORKFLOW', FALSE, 'LOW'),
    ('APPROVAL_FORM_REFERENCE_READ', 'approvals', 'APPROVAL_DESIGNER', 'APP.APPROVALS',
     'ACTION.APPROVAL_FORM', FALSE, 'LOW')
ON CONFLICT (duty_code) DO NOTHING;

INSERT INTO sys_admin_scoped_duty_capabilities (
    duty_code, capability_contract_key, permission_resource_key, permission_code)
VALUES
    ('APPROVAL_WORKFLOW_PLANNING', 'approvals.admin.workflow-planning-simulation.read',
     'ADMIN.APPROVAL_WORKFLOW', 'UPDATE'),
    ('APPROVAL_FORM_REFERENCE_READ', 'approvals.admin.workflow-planning-form.read',
     'ACTION.APPROVAL_FORM', 'VIEW')
ON CONFLICT (duty_code, capability_contract_key) DO NOTHING;
