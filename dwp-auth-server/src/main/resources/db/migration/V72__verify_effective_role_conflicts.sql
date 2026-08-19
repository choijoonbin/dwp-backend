-- Keep a queryable preflight surface for direct, group-derived, and active
-- privileged roles. Runtime assignment APIs already evaluate the same deny
-- policies; this migration prevents legacy or out-of-band conflicts from
-- silently surviving an upgrade.
CREATE OR REPLACE VIEW v_sys_effective_role_conflicts AS
WITH effective_roles AS (
    SELECT membership.tenant_id, membership.user_id, role.code AS role_code
      FROM com_role_members membership
      JOIN com_roles role
        ON role.tenant_id = membership.tenant_id
       AND role.role_id = membership.role_id
     WHERE role.status = 'ACTIVE'
    UNION
    SELECT membership.tenant_id, membership.user_id, role.code AS role_code
      FROM com_group_role_assignments assignment
      JOIN com_group_members membership
        ON membership.tenant_id = assignment.tenant_id
       AND membership.group_id = assignment.group_id
      JOIN com_groups access_group
        ON access_group.tenant_id = membership.tenant_id
       AND access_group.group_id = membership.group_id
      JOIN com_roles role
        ON role.tenant_id = assignment.tenant_id
       AND role.role_id = assignment.role_id
     WHERE access_group.status = 'ACTIVE'
       AND role.status = 'ACTIVE'
       AND assignment.lifecycle_state = 'ACTIVE'
       AND assignment.assignment_type = 'ACTIVE'
       AND assignment.scope_type = 'TENANT'
       AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
       AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
    UNION
    SELECT active_grant.tenant_id, active_grant.user_id, role.code AS role_code
      FROM com_active_privileged_grants active_grant
      JOIN com_roles role
        ON role.tenant_id = active_grant.tenant_id
       AND role.role_id = active_grant.role_id
     WHERE role.status = 'ACTIVE'
       AND active_grant.scope_type = 'TENANT'
       AND active_grant.revoked_at IS NULL
       AND active_grant.activated_at <= CURRENT_TIMESTAMP
       AND active_grant.expires_at > CURRENT_TIMESTAMP
)
SELECT left_role.tenant_id,
       left_role.user_id,
       policy.left_role_code,
       policy.right_role_code,
       policy.reason_code,
       policy.enforcement,
       policy.risk_level
  FROM sys_role_conflict_policies policy
  JOIN effective_roles left_role
    ON left_role.role_code = policy.left_role_code
  JOIN effective_roles right_role
    ON right_role.tenant_id = left_role.tenant_id
   AND right_role.user_id = left_role.user_id
   AND right_role.role_code = policy.right_role_code
 WHERE policy.lifecycle_state = 'ACTIVE'
   AND policy.enforcement = 'DENY';

COMMENT ON VIEW v_sys_effective_role_conflicts IS
    'Fail-closed preflight evidence for active deny-policy conflicts across effective tenant roles.';

DO $$
DECLARE
    conflict_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO conflict_count FROM v_sys_effective_role_conflicts;
    IF conflict_count > 0 THEN
        RAISE EXCEPTION
            'Detected % active effective-role conflicts. Resolve v_sys_effective_role_conflicts before upgrading.',
            conflict_count;
    END IF;
END $$;
