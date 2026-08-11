INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ROLE_FAMILY', 'dwp-auth-server',
     'Built-in role family',
     'Product domains that own built-in authorization roles.',
     'SYSTEM', 'CHECK', 'sys_builtin_role_catalog.role_family', 'SECURITY'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.LIFECYCLE_STATE', 'dwp-auth-server',
     'Built-in role lifecycle',
     'Availability lifecycle for built-in authorization roles.',
     'SYSTEM', 'CHECK', 'sys_builtin_role_catalog.lifecycle_state', 'STATE_MACHINE'),
    ('PEOPLE.PPL_APPROVAL_ROLE_CATALOG.LIFECYCLE_STATE', 'dwp-people-server',
     'Approval role lifecycle',
     'Availability lifecycle for organization scenario approval roles.',
     'SYSTEM', 'CHECK', 'ppl_approval_role_catalog.lifecycle_state', 'STATE_MACHINE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, sort_order, behavior_metadata)
VALUES
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ROLE_FAMILY', 'PLATFORM', 'Platform', 10, '{}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ROLE_FAMILY', 'TENANT', 'Tenant', 20, '{}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ROLE_FAMILY', 'WORKSPACE', 'Workspace', 30, '{}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ROLE_FAMILY', 'PEOPLE', 'People', 40, '{}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ROLE_FAMILY', 'AUDIT', 'Audit', 50, '{}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ROLE_FAMILY', 'PROVIDER', 'Provider', 60, '{}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.LIFECYCLE_STATE', 'ACTIVE', 'Active', 10, '{}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.LIFECYCLE_STATE', 'RETIRED', 'Retired', 20, '{}'),
    ('PEOPLE.PPL_APPROVAL_ROLE_CATALOG.LIFECYCLE_STATE', 'ACTIVE', 'Active', 10, '{}'),
    ('PEOPLE.PPL_APPROVAL_ROLE_CATALOG.LIFECYCLE_STATE', 'RETIRED', 'Retired', 20, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ROLE_FAMILY', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_builtin_role_catalog.role_family', 'CHECK'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.LIFECYCLE_STATE', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_builtin_role_catalog.lifecycle_state', 'CHECK'),
    ('PEOPLE.PPL_APPROVAL_ROLE_CATALOG.LIFECYCLE_STATE', 'dwp-people-server',
     'DATABASE_COLUMN', 'ppl_approval_role_catalog.lifecycle_state', 'CHECK');
