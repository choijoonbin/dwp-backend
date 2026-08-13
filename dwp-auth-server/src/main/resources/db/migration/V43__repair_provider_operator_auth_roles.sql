-- V40 exposed that the provider catalog roles introduced in V25 did not have
-- matching com_roles rows. ProviderSecurityFilter requires the authenticated
-- role and the provider operator assignment to agree, so repair that contract.

INSERT INTO com_roles (
    tenant_id, code, name, description, status, role_type,
    privileged, assignable_to_groups, builtin_role_code, created_by, updated_by)
SELECT tenant.tenant_id, catalog.role_code, catalog.display_name, catalog.description,
       'ACTIVE', 'SYSTEM', TRUE, FALSE, catalog.role_code, 1, 1
  FROM com_tenants tenant
 CROSS JOIN sys_builtin_role_catalog catalog
 WHERE tenant.code = 'default'
   AND catalog.role_code IN (
       'PROVIDER_OPERATOR', 'PROVIDER_SUPPORT', 'PROVIDER_AUDITOR')
ON CONFLICT (tenant_id, code) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    status = 'ACTIVE',
    role_type = 'SYSTEM',
    privileged = TRUE,
    assignable_to_groups = FALSE,
    builtin_role_code = EXCLUDED.builtin_role_code,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = EXCLUDED.updated_by;

CREATE TEMP TABLE tmp_provider_auth_role_repairs (
    user_id BIGINT PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL
) ON COMMIT DROP;

INSERT INTO tmp_provider_auth_role_repairs VALUES
    (900013, 'PROVIDER_OPERATOR'),
    (900014, 'PROVIDER_SUPPORT'),
    (900015, 'PROVIDER_AUDITOR');

DELETE FROM com_role_members membership
USING tmp_provider_auth_role_repairs seed, com_tenants tenant
WHERE tenant.code = 'default'
  AND membership.tenant_id = tenant.tenant_id
  AND membership.user_id = seed.user_id;

INSERT INTO com_role_members (
    tenant_id, role_id, user_id, created_by, updated_by)
SELECT role.tenant_id, role.role_id, seed.user_id, 1, 1
  FROM tmp_provider_auth_role_repairs seed
  JOIN com_tenants tenant ON tenant.code = 'default'
  JOIN com_roles role
    ON role.tenant_id = tenant.tenant_id
   AND role.code = seed.role_code
  JOIN com_users user_record
    ON user_record.tenant_id = tenant.tenant_id
   AND user_record.user_id = seed.user_id
ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING;

UPDATE sys_auth_sessions session
   SET revoked_at = COALESCE(session.revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = NULL
  FROM tmp_provider_auth_role_repairs seed, com_tenants tenant
 WHERE tenant.code = 'default'
   AND session.tenant_id = tenant.tenant_id
   AND session.user_id = seed.user_id
   AND session.revoked_at IS NULL;
