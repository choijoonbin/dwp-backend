package com.dwp.services.auth.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resolves the current, persisted tenant permission for an authenticated user.
 *
 * <p>Direct role membership and current tenant-scoped group assignments are evaluated together.
 * An exact DENY always wins over an ALLOW for the same resource and permission.</p>
 */
@Component
public class TenantPermissionAuthorization {

    public static final String IDENTITY_DIRECTORY = "ADMIN.IDENTITY_DIRECTORY";
    public static final String IDENTITY_PROVISIONING = "ADMIN.IDENTITY_PROVISIONING";
    public static final String ACCESS_GOVERNANCE = "ADMIN.ACCESS_GOVERNANCE";
    public static final String ACCESS_REVIEWS = "ADMIN.ACCESS_REVIEWS";
    public static final String PRIVILEGED_ACCESS = "ADMIN.PRIVILEGED_ACCESS";

    private final JdbcTemplate jdbc;

    public TenantPermissionAuthorization(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void require(
            Long tenantId,
            Long actorId,
            String resourceKey,
            String permissionCode) {
        if (!can(tenantId, actorId, resourceKey, permissionCode)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    public boolean can(
            Long tenantId,
            Long actorId,
            String resourceKey,
            String permissionCode) {
        if (tenantId == null || tenantId <= 0 || actorId == null || actorId <= 0
                || resourceKey == null || resourceKey.isBlank()
                || permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        Boolean result = jdbc.queryForObject("""
                WITH authorized_actor AS (
                    SELECT user_id
                      FROM com_users
                     WHERE tenant_id = ? AND user_id = ?
                       AND status = 'ACTIVE'
                       AND identity_plane = 'TENANT'
                ), effective_roles AS (
                    SELECT member.role_id
                      FROM authorized_actor actor
                      JOIN com_role_members member
                        ON member.user_id = actor.user_id
                      JOIN com_roles role
                        ON role.tenant_id = member.tenant_id
                       AND role.role_id = member.role_id
                       AND role.status = 'ACTIVE'
                     WHERE member.tenant_id = ? AND member.user_id = ?
                    UNION
                    SELECT assignment.role_id
                      FROM authorized_actor actor
                      JOIN com_group_members membership
                        ON membership.user_id = actor.user_id
                      JOIN com_groups access_group
                        ON access_group.tenant_id = membership.tenant_id
                       AND access_group.group_id = membership.group_id
                       AND access_group.status = 'ACTIVE'
                      JOIN com_group_role_assignments assignment
                        ON assignment.tenant_id = membership.tenant_id
                       AND assignment.group_id = membership.group_id
                      JOIN com_roles role
                        ON role.tenant_id = assignment.tenant_id
                       AND role.role_id = assignment.role_id
                       AND role.status = 'ACTIVE'
                     WHERE membership.tenant_id = ? AND membership.user_id = ?
                       AND assignment.lifecycle_state = 'ACTIVE'
                       AND assignment.assignment_type = 'ACTIVE'
                       AND assignment.scope_type = 'TENANT'
                       AND (assignment.valid_from IS NULL
                            OR assignment.valid_from <= CURRENT_TIMESTAMP)
                       AND (assignment.valid_to IS NULL
                            OR assignment.valid_to > CURRENT_TIMESTAMP)
                ), matching AS (
                    SELECT role_permission.effect
                      FROM effective_roles effective_role
                      JOIN com_role_permissions role_permission
                        ON role_permission.tenant_id = ?
                       AND role_permission.role_id = effective_role.role_id
                      JOIN com_resources resource
                        ON resource.tenant_id = role_permission.tenant_id
                       AND resource.resource_id = role_permission.resource_id
                       AND resource.enabled = TRUE
                      JOIN com_permissions permission
                        ON permission.permission_id = role_permission.permission_id
                     WHERE resource.key = ? AND permission.code = ?
                )
                SELECT EXISTS (SELECT 1 FROM matching WHERE effect = 'ALLOW')
                   AND NOT EXISTS (SELECT 1 FROM matching WHERE effect = 'DENY')
                """, Boolean.class,
                tenantId, actorId,
                tenantId, actorId, tenantId, actorId, tenantId,
                resourceKey.trim().toUpperCase(Locale.ROOT),
                permissionCode.trim().toUpperCase(Locale.ROOT));
        return Boolean.TRUE.equals(result);
    }
}
