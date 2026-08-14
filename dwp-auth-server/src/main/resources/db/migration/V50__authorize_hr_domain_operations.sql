-- HRIS is one product surface with separately delegated duties. Workspace
-- membership grants employee self-service only; these roles authorize domain
-- administration and never follow from tenant administration implicitly.
INSERT INTO sys_builtin_role_catalog (
    role_code, display_name, description, role_family, label_i18n,
    privileged, assignable_to_groups, sort_order, lifecycle_state,
    assignment_class)
VALUES
    ('TIME_ADMIN', 'Time administrator',
     'Maintains work schedules, time cards, exceptions, and time close readiness.',
     'PEOPLE', '{"ko":"근태 관리자","en":"Time administrator"}',
     TRUE, FALSE, 52, 'ACTIVE', 'DELEGATED'),
    ('ABSENCE_ADMIN', 'Absence administrator',
     'Maintains leave plans, balances, requests, and absence reconciliation.',
     'PEOPLE', '{"ko":"휴가 관리자","en":"Absence administrator"}',
     TRUE, FALSE, 53, 'ACTIVE', 'DELEGATED'),
    ('BENEFITS_ADMIN', 'Benefits administrator',
     'Maintains benefit programs, enrollment windows, and life-event processing.',
     'PEOPLE', '{"ko":"복리후생 관리자","en":"Benefits administrator"}',
     TRUE, FALSE, 54, 'ACTIVE', 'DELEGATED'),
    ('PAYROLL_ADMIN', 'Payroll administrator',
     'Controls payroll-cycle readiness and governed pay-statement references.',
     'PEOPLE', '{"ko":"급여 관리자","en":"Payroll administrator"}',
     TRUE, FALSE, 55, 'ACTIVE', 'DELEGATED'),
    ('TALENT_ADMIN', 'Talent administrator',
     'Maintains employee journeys, goals, learning assignments, and review cycles.',
     'PEOPLE', '{"ko":"인재 관리자","en":"Talent administrator"}',
     TRUE, FALSE, 56, 'ACTIVE', 'DELEGATED')
ON CONFLICT (role_code) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    role_family = EXCLUDED.role_family,
    label_i18n = EXCLUDED.label_i18n,
    privileged = EXCLUDED.privileged,
    assignable_to_groups = EXCLUDED.assignable_to_groups,
    sort_order = EXCLUDED.sort_order,
    lifecycle_state = 'ACTIVE',
    assignment_class = EXCLUDED.assignment_class,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_assignment_policies (
    grantor_role_code, target_role_code, assignment_mode, lifecycle_state)
SELECT grantor.role_code, target.role_code, 'DIRECT', 'ACTIVE'
  FROM (VALUES ('ADMIN'), ('PLATFORM_ADMIN'), ('TENANT_ADMIN'), ('HR_ADMIN')) grantor(role_code)
 CROSS JOIN (VALUES
    ('TIME_ADMIN'), ('ABSENCE_ADMIN'), ('BENEFITS_ADMIN'),
    ('PAYROLL_ADMIN'), ('TALENT_ADMIN')) target(role_code)
 WHERE grantor.role_code <> target.role_code
ON CONFLICT (grantor_role_code, target_role_code, assignment_mode) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_role_conflict_policies (
    left_role_code, right_role_code, reason_code, lifecycle_state)
SELECT LEAST('AUDITOR', role_code), GREATEST('AUDITOR', role_code),
       'AUDIT_INDEPENDENCE', 'ACTIVE'
  FROM (VALUES
    ('TIME_ADMIN'), ('ABSENCE_ADMIN'), ('BENEFITS_ADMIN'),
    ('PAYROLL_ADMIN'), ('TALENT_ADMIN')) roles(role_code)
ON CONFLICT (left_role_code, right_role_code) DO UPDATE SET
    reason_code = EXCLUDED.reason_code,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_resource_templates (
    resource_key, resource_type, display_name, required_entitlement)
VALUES
    ('DATA.HR_TIME', 'DATA', 'Time and attendance operations', 'core.people'),
    ('DATA.HR_ABSENCE', 'DATA', 'Absence and leave operations', 'core.people'),
    ('DATA.HR_BENEFITS', 'DATA', 'Benefits operations', 'core.people'),
    ('DATA.HR_PAY', 'DATA', 'Payroll readiness and pay statement references', 'core.people'),
    ('DATA.HR_TALENT', 'DATA', 'Talent, learning, and employee journeys', 'core.people')
ON CONFLICT (resource_key) DO UPDATE SET
    resource_type = EXCLUDED.resource_type,
    display_name = EXCLUDED.display_name,
    required_entitlement = EXCLUDED.required_entitlement,
    lifecycle_state = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
SELECT role_code, resource_key, permission_code
  FROM (VALUES
    ('TIME_ADMIN', 'APP.HRIS', 'VIEW'),
    ('TIME_ADMIN', 'DATA.HR_TIME', 'VIEW'),
    ('TIME_ADMIN', 'DATA.HR_TIME', 'CREATE'),
    ('TIME_ADMIN', 'DATA.HR_TIME', 'UPDATE'),
    ('TIME_ADMIN', 'DATA.HR_TIME', 'APPROVE'),
    ('TIME_ADMIN', 'DATA.HR_TIME', 'MANAGE'),
    ('ABSENCE_ADMIN', 'APP.HRIS', 'VIEW'),
    ('ABSENCE_ADMIN', 'DATA.HR_ABSENCE', 'VIEW'),
    ('ABSENCE_ADMIN', 'DATA.HR_ABSENCE', 'CREATE'),
    ('ABSENCE_ADMIN', 'DATA.HR_ABSENCE', 'UPDATE'),
    ('ABSENCE_ADMIN', 'DATA.HR_ABSENCE', 'APPROVE'),
    ('ABSENCE_ADMIN', 'DATA.HR_ABSENCE', 'MANAGE'),
    ('BENEFITS_ADMIN', 'APP.HRIS', 'VIEW'),
    ('BENEFITS_ADMIN', 'DATA.HR_BENEFITS', 'VIEW'),
    ('BENEFITS_ADMIN', 'DATA.HR_BENEFITS', 'CREATE'),
    ('BENEFITS_ADMIN', 'DATA.HR_BENEFITS', 'UPDATE'),
    ('BENEFITS_ADMIN', 'DATA.HR_BENEFITS', 'APPROVE'),
    ('BENEFITS_ADMIN', 'DATA.HR_BENEFITS', 'MANAGE'),
    ('PAYROLL_ADMIN', 'APP.HRIS', 'VIEW'),
    ('PAYROLL_ADMIN', 'DATA.HR_PAY', 'VIEW'),
    ('PAYROLL_ADMIN', 'DATA.HR_PAY', 'CREATE'),
    ('PAYROLL_ADMIN', 'DATA.HR_PAY', 'UPDATE'),
    ('PAYROLL_ADMIN', 'DATA.HR_PAY', 'APPROVE'),
    ('PAYROLL_ADMIN', 'DATA.HR_PAY', 'MANAGE'),
    ('TALENT_ADMIN', 'APP.HRIS', 'VIEW'),
    ('TALENT_ADMIN', 'DATA.HR_TALENT', 'VIEW'),
    ('TALENT_ADMIN', 'DATA.HR_TALENT', 'CREATE'),
    ('TALENT_ADMIN', 'DATA.HR_TALENT', 'UPDATE'),
    ('TALENT_ADMIN', 'DATA.HR_TALENT', 'APPROVE'),
    ('TALENT_ADMIN', 'DATA.HR_TALENT', 'MANAGE'))
       permission_matrix(role_code, resource_key, permission_code)
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO sys_tenant_role_permission_templates (
    role_code, resource_key, permission_code)
SELECT 'HR_ADMIN', resource_key, permission_code
  FROM (VALUES
    ('DATA.HR_TIME'), ('DATA.HR_ABSENCE'), ('DATA.HR_BENEFITS'),
    ('DATA.HR_PAY'), ('DATA.HR_TALENT')) resource(resource_key)
 CROSS JOIN (VALUES
    ('VIEW'), ('CREATE'), ('UPDATE'), ('APPROVE'), ('MANAGE')) permission(permission_code)
ON CONFLICT (role_code, resource_key, permission_code) DO UPDATE SET
    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name,
       catalog.description, 'ACTIVE', 'SYSTEM', catalog.privileged,
       catalog.assignable_to_groups, catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE catalog.role_code IN (
    'TIME_ADMIN', 'ABSENCE_ADMIN', 'BENEFITS_ADMIN',
    'PAYROLL_ADMIN', 'TALENT_ADMIN')
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    role_type = 'SYSTEM',
    privileged = EXCLUDED.privileged,
    assignable_to_groups = EXCLUDED.assignable_to_groups,
    builtin_role_code = EXCLUDED.builtin_role_code,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_resources (
    tenant_id, type, key, name, enabled, created_by, updated_by)
SELECT tenant.tenant_id, template.resource_type, template.resource_key,
       template.display_name, TRUE, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_tenant_resource_templates template
 WHERE template.resource_key LIKE 'DATA.HR_%'
ON CONFLICT (tenant_id, type, key) DO UPDATE SET
    name = EXCLUDED.name,
    enabled = TRUE,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

INSERT INTO com_role_permissions (
    tenant_id, role_id, resource_id, permission_id,
    effect, created_by, updated_by)
SELECT role.tenant_id, role.role_id, resource.resource_id,
       permission.permission_id, 'ALLOW', 1, 1
  FROM sys_tenant_role_permission_templates template
  JOIN com_roles role
    ON role.code = template.role_code
   AND role.status = 'ACTIVE'
  JOIN com_resources resource
    ON resource.tenant_id = role.tenant_id
   AND resource.key = template.resource_key
   AND resource.enabled = TRUE
  JOIN com_permissions permission
    ON permission.code = template.permission_code
 WHERE template.lifecycle_state = 'ACTIVE'
   AND (template.resource_key LIKE 'DATA.HR_%'
        OR template.role_code IN (
            'TIME_ADMIN', 'ABSENCE_ADMIN', 'BENEFITS_ADMIN',
            'PAYROLL_ADMIN', 'TALENT_ADMIN'))
ON CONFLICT (tenant_id, role_id, resource_id, permission_id) DO UPDATE SET
    effect = 'ALLOW',
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 1;

COMMENT ON TABLE sys_tenant_role_permission_templates IS
    'Provider-governed tenant authorization baseline, including independent HR domain duties.';
