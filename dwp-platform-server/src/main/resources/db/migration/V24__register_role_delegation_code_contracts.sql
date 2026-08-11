-- Register the authorization delegation constraints introduced by the Auth service.
INSERT INTO sys_code_sets (
    code_set_key, owner_service, display_name, description,
    configuration_level, validation_source, source_reference, contract_kind)
VALUES
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ASSIGNMENT_CLASS', 'dwp-auth-server',
     'Built-in role assignment class',
     'Governance tier that determines how a built-in role may be assigned.',
     'SYSTEM', 'CHECK', 'sys_builtin_role_catalog.assignment_class', 'SECURITY'),
    ('AUTH.SYS_IDENTITY_AUDIT_EVENTS.OUTCOME', 'dwp-auth-server',
     'Identity audit outcome',
     'Recorded outcome for identity governance and authorization audit events.',
     'SYSTEM', 'CHECK', 'sys_identity_audit_events.outcome', 'OBSERVABILITY'),
    ('AUTH.SYS_ROLE_ASSIGNMENT_POLICIES.ASSIGNMENT_MODE', 'dwp-auth-server',
     'Role assignment policy mode',
     'Governed path through which a role assignment may be issued.',
     'SYSTEM', 'CHECK', 'sys_role_assignment_policies.assignment_mode', 'SECURITY'),
    ('AUTH.SYS_ROLE_ASSIGNMENT_POLICIES.LIFECYCLE_STATE', 'dwp-auth-server',
     'Role assignment policy lifecycle',
     'Availability lifecycle for role assignment authority policies.',
     'SYSTEM', 'CHECK', 'sys_role_assignment_policies.lifecycle_state', 'STATE_MACHINE'),
    ('AUTH.SYS_ROLE_CONFLICT_POLICIES.LIFECYCLE_STATE', 'dwp-auth-server',
     'Role conflict policy lifecycle',
     'Availability lifecycle for separation-of-duties conflict policies.',
     'SYSTEM', 'CHECK', 'sys_role_conflict_policies.lifecycle_state', 'STATE_MACHINE');

INSERT INTO sys_code_values (
    code_set_key, code, display_name, label_i18n, sort_order, behavior_metadata)
VALUES
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ASSIGNMENT_CLASS', 'BASELINE', 'Baseline',
     '{"ko":"기본 할당","en":"Baseline"}', 10, '{"directAssignment":true}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ASSIGNMENT_CLASS', 'DELEGATED', 'Delegated',
     '{"ko":"위임 할당","en":"Delegated"}', 20, '{"directAssignment":true}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ASSIGNMENT_CLASS', 'GOVERNED', 'Governed',
     '{"ko":"통제 할당","en":"Governed"}', 30, '{"directAssignment":false}'),
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ASSIGNMENT_CLASS', 'CONTROL_PLANE', 'Control plane',
     '{"ko":"컨트롤 플레인 전용","en":"Control plane"}', 40,
     '{"directAssignment":false,"controlPlaneOnly":true}'),
    ('AUTH.SYS_IDENTITY_AUDIT_EVENTS.OUTCOME', 'SUCCESS', 'Success',
     '{"ko":"성공","en":"Success"}', 10, '{}'),
    ('AUTH.SYS_IDENTITY_AUDIT_EVENTS.OUTCOME', 'DENIED', 'Denied',
     '{"ko":"거부","en":"Denied"}', 20, '{}'),
    ('AUTH.SYS_IDENTITY_AUDIT_EVENTS.OUTCOME', 'FAILED', 'Failed',
     '{"ko":"실패","en":"Failed"}', 30, '{}'),
    ('AUTH.SYS_ROLE_ASSIGNMENT_POLICIES.ASSIGNMENT_MODE', 'DIRECT', 'Direct',
     '{"ko":"직접 할당","en":"Direct"}', 10, '{}'),
    ('AUTH.SYS_ROLE_ASSIGNMENT_POLICIES.ASSIGNMENT_MODE', 'APPROVAL', 'Approval',
     '{"ko":"승인 후 할당","en":"Approval"}', 20, '{}'),
    ('AUTH.SYS_ROLE_ASSIGNMENT_POLICIES.ASSIGNMENT_MODE', 'PROVISIONING', 'Provisioning',
     '{"ko":"프로비저닝 할당","en":"Provisioning"}', 30, '{}'),
    ('AUTH.SYS_ROLE_ASSIGNMENT_POLICIES.LIFECYCLE_STATE', 'ACTIVE', 'Active',
     '{"ko":"활성","en":"Active"}', 10, '{}'),
    ('AUTH.SYS_ROLE_ASSIGNMENT_POLICIES.LIFECYCLE_STATE', 'RETIRED', 'Retired',
     '{"ko":"종료","en":"Retired"}', 20, '{}'),
    ('AUTH.SYS_ROLE_CONFLICT_POLICIES.LIFECYCLE_STATE', 'ACTIVE', 'Active',
     '{"ko":"활성","en":"Active"}', 10, '{}'),
    ('AUTH.SYS_ROLE_CONFLICT_POLICIES.LIFECYCLE_STATE', 'RETIRED', 'Retired',
     '{"ko":"종료","en":"Retired"}', 20, '{}');

INSERT INTO sys_code_bindings (
    code_set_key, consumer_service, usage_type, source_reference, enforcement_type)
VALUES
    ('AUTH.SYS_BUILTIN_ROLE_CATALOG.ASSIGNMENT_CLASS', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_builtin_role_catalog.assignment_class', 'CHECK'),
    ('AUTH.SYS_IDENTITY_AUDIT_EVENTS.OUTCOME', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_identity_audit_events.outcome', 'CHECK'),
    ('AUTH.SYS_ROLE_ASSIGNMENT_POLICIES.ASSIGNMENT_MODE', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_role_assignment_policies.assignment_mode', 'CHECK'),
    ('AUTH.SYS_ROLE_ASSIGNMENT_POLICIES.LIFECYCLE_STATE', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_role_assignment_policies.lifecycle_state', 'CHECK'),
    ('AUTH.SYS_ROLE_CONFLICT_POLICIES.LIFECYCLE_STATE', 'dwp-auth-server',
     'DATABASE_COLUMN', 'sys_role_conflict_policies.lifecycle_state', 'CHECK');
