INSERT INTO com_roles (
    tenant_id, code, name, description, role_type,
    privileged, assignable_to_groups, status)
VALUES (
    1, 'PROVIDER_ADMIN', 'Provider administrator',
    'Operator role for the isolated provider control plane',
    'SYSTEM', TRUE, FALSE, 'ACTIVE')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO com_role_members (tenant_id, role_id, user_id)
SELECT 1, role.role_id, 1
  FROM com_roles role
 WHERE role.tenant_id = 1 AND role.code = 'PROVIDER_ADMIN'
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;
