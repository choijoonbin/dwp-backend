INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference,
    contract_kind, runtime_visibility)
VALUES
    ('AUTH.TENANT_RESOURCE_TEMPLATE.RESOURCE_TYPE', 'dwp-auth-server',
     'Tenant resource template type',
     'Authorization resource classes materialized for each entitled tenant.',
     'SYSTEM', 'CHECK', 'sys_tenant_resource_templates.resource_type',
     'SECURITY', 'ADMIN_ONLY'),
    ('AUTH.TENANT_AUTHORIZATION_TEMPLATE.LIFECYCLE_STATE', 'dwp-auth-server',
     'Tenant authorization template lifecycle',
     'Lifecycle shared by tenant resource and role-permission templates.',
     'SYSTEM', 'CHECK', 'sys_tenant_authorization_templates.lifecycle_state',
     'SECURITY', 'ADMIN_ONLY');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n,
    sort_order, behavior_metadata)
VALUES
    ('AUTH.TENANT_RESOURCE_TEMPLATE.RESOURCE_TYPE', 'APP', 'Application',
     '{"ko":"애플리케이션","en":"Application"}', 10,
     '{"scope":"tenant-application"}'),
    ('AUTH.TENANT_RESOURCE_TEMPLATE.RESOURCE_TYPE', 'ADMIN', 'Administration',
     '{"ko":"관리 기능","en":"Administration"}', 20,
     '{"scope":"tenant-administration"}'),
    ('AUTH.TENANT_RESOURCE_TEMPLATE.RESOURCE_TYPE', 'DATA', 'Data',
     '{"ko":"데이터","en":"Data"}', 30,
     '{"scope":"tenant-data"}'),
    ('AUTH.TENANT_RESOURCE_TEMPLATE.RESOURCE_TYPE', 'ACTION', 'Action',
     '{"ko":"업무 작업","en":"Action"}', 40,
     '{"scope":"tenant-action"}'),
    ('AUTH.TENANT_AUTHORIZATION_TEMPLATE.LIFECYCLE_STATE', 'ACTIVE', 'Active',
     '{"ko":"활성","en":"Active"}', 10,
     '{"materialized":true}'),
    ('AUTH.TENANT_AUTHORIZATION_TEMPLATE.LIFECYCLE_STATE', 'RETIRED', 'Retired',
     '{"ko":"폐기","en":"Retired"}', 20,
     '{"materialized":false}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type,
    source_reference, enforcement_type)
VALUES
    ('AUTH.TENANT_RESOURCE_TEMPLATE.RESOURCE_TYPE', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_tenant_resource_templates.resource_type', 'CHECK'),
    ('AUTH.TENANT_AUTHORIZATION_TEMPLATE.LIFECYCLE_STATE', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_tenant_resource_templates.lifecycle_state', 'CHECK'),
    ('AUTH.TENANT_AUTHORIZATION_TEMPLATE.LIFECYCLE_STATE', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_tenant_role_permission_templates.lifecycle_state', 'CHECK');
