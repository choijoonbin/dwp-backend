package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class TenantSettingRegistryAuthorization {

    private static final String RESOURCE = "ADMIN.IDENTITY_PROVISIONING";
    private final JdbcTemplate jdbc;

    TenantSettingRegistryAuthorization(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void require(Long tenantId, Long actorId, String permission) {
        if (!can(tenantId, actorId, permission)) throw new BaseException(ErrorCode.FORBIDDEN);
    }

    boolean can(Long tenantId, Long actorId, String permission) {
        Boolean allowed = jdbc.queryForObject("""
                WITH effective_roles AS (
                    SELECT member.role_id
                      FROM com_role_members member
                      JOIN com_roles role ON role.tenant_id = member.tenant_id
                                         AND role.role_id = member.role_id
                                         AND role.status = 'ACTIVE'
                     WHERE member.tenant_id = ? AND member.user_id = ?
                    UNION
                    SELECT assignment.role_id
                      FROM com_group_members membership
                      JOIN com_groups access_group
                        ON access_group.tenant_id = membership.tenant_id
                       AND access_group.group_id = membership.group_id
                       AND access_group.status = 'ACTIVE'
                      JOIN com_group_role_assignments assignment
                        ON assignment.tenant_id = membership.tenant_id
                       AND assignment.group_id = membership.group_id
                      JOIN com_roles role ON role.tenant_id = assignment.tenant_id
                                         AND role.role_id = assignment.role_id
                                         AND role.status = 'ACTIVE'
                     WHERE membership.tenant_id = ? AND membership.user_id = ?
                       AND assignment.lifecycle_state = 'ACTIVE'
                       AND assignment.assignment_type = 'ACTIVE'
                       AND assignment.scope_type = 'TENANT'
                       AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
                       AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                ), decisions AS (
                    SELECT role_permission.effect
                      FROM effective_roles effective_role
                      JOIN com_role_permissions role_permission
                        ON role_permission.tenant_id = ?
                       AND role_permission.role_id = effective_role.role_id
                      JOIN com_resources resource
                        ON resource.tenant_id = role_permission.tenant_id
                       AND resource.resource_id = role_permission.resource_id
                       AND resource.enabled = TRUE
                      JOIN com_permissions permission_record
                        ON permission_record.permission_id = role_permission.permission_id
                     WHERE resource.key = ? AND permission_record.code = ?
                )
                SELECT EXISTS (SELECT 1 FROM decisions WHERE effect = 'ALLOW')
                   AND NOT EXISTS (SELECT 1 FROM decisions WHERE effect = 'DENY')
                """, Boolean.class, tenantId, actorId, tenantId, actorId, tenantId,
                RESOURCE, permission);
        return Boolean.TRUE.equals(allowed);
    }
}
