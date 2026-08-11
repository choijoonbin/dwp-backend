-- Tenant administration and audit investigation are independent duties.
-- V27 copied the legacy ADMIN permission set into TENANT_ADMIN for the default
-- tenant; remove audit capabilities so AUDITOR and AUDIT_ADMIN remain the only
-- tenant-scoped audit roles.
DELETE FROM com_role_permissions permission
USING com_roles role, com_resources resource
WHERE permission.tenant_id = role.tenant_id
  AND permission.role_id = role.role_id
  AND role.code = 'TENANT_ADMIN'
  AND resource.resource_id = permission.resource_id
  AND resource.key IN (
      'ADMIN.AUDIT_VIEW',
      'ADMIN.AUDIT_INVESTIGATE',
      'ADMIN.AUDIT_EXPORT',
      'ADMIN.AUDIT_CONFIGURE');

-- Existing access tokens contain a permission snapshot. Revoke active sessions
-- and advance the access revision so the corrected boundary takes effect now.
UPDATE sys_auth_sessions session
   SET revoked_at = COALESCE(session.revoked_at, CURRENT_TIMESTAMP),
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE session.revoked_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM com_role_members membership
         JOIN com_roles role
           ON role.tenant_id = membership.tenant_id
          AND role.role_id = membership.role_id
        WHERE membership.tenant_id = session.tenant_id
          AND membership.user_id = session.user_id
          AND role.code = 'TENANT_ADMIN');

UPDATE com_users user_record
   SET access_revision = user_record.access_revision + 1,
       version = user_record.version + 1,
       updated_at = CURRENT_TIMESTAMP,
       updated_by = 1
 WHERE EXISTS (
       SELECT 1
         FROM com_role_members membership
         JOIN com_roles role
           ON role.tenant_id = membership.tenant_id
          AND role.role_id = membership.role_id
        WHERE membership.tenant_id = user_record.tenant_id
          AND membership.user_id = user_record.user_id
          AND role.code = 'TENANT_ADMIN');

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM com_role_permissions permission
          JOIN com_roles role
            ON role.tenant_id = permission.tenant_id
           AND role.role_id = permission.role_id
          JOIN com_resources resource
            ON resource.resource_id = permission.resource_id
         WHERE role.code = 'TENANT_ADMIN'
           AND resource.key LIKE 'ADMIN.AUDIT_%') THEN
        RAISE EXCEPTION 'TENANT_ADMIN must not retain audit control permissions';
    END IF;
END
$$;
