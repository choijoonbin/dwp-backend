package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Shared, fail-closed authorization boundary for application governance workflows. */
final class AppGovernanceAuthorization {

    private static final String CATALOG_ADMIN = "APP_CATALOG_ADMIN";
    private static final Set<String> HUB_RESPONSIBILITIES = Set.of(
            "APP_OWNER", "APP_ACCESS_APPROVER",
            "APP_ACCESS_MANAGER", "APP_ACCESS_REVIEWER");

    private final JdbcTemplate jdbc;

    AppGovernanceAuthorization(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Visibility requireVisibility(Long tenantId, Long actorId) {
        Visibility visibility = visibility(tenantId, actorId);
        if (!visibility.queueReader() && visibility.resourceSetIds().isEmpty()) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return visibility;
    }

    Visibility visibility(Long tenantId, Long actorId) {
        boolean queueReader = tenantRoles(tenantId, actorId).contains(CATALOG_ADMIN);
        Set<UUID> resourceSetIds = new LinkedHashSet<>();
        HUB_RESPONSIBILITIES.forEach(responsibility -> resourceSetIds.addAll(
                responsibilityScopes(tenantId, actorId, responsibility)));
        Set<UUID> reviewSetIds = responsibilityScopes(
                tenantId, actorId, "APP_ACCESS_REVIEWER");
        Set<String> appResourceKeys = new LinkedHashSet<>();
        jdbc.query("""
                SELECT member.resource_set_id, member.resource_key
                  FROM com_admin_resource_set_members member
                 WHERE member.tenant_id = ? AND member.resource_type = 'APP'
                   AND member.lifecycle_state = 'ACTIVE'
                 ORDER BY member.resource_key
                """, result -> {
            if (resourceSetIds.contains(result.getObject(1, UUID.class))) {
                appResourceKeys.add(result.getString(2));
            }
        }, tenantId);
        return new Visibility(
                queueReader, Set.copyOf(resourceSetIds), Set.copyOf(reviewSetIds),
                Set.copyOf(appResourceKeys));
    }

    void requirePresetRequester(
            Long tenantId, Long actorId, UUID resourceSetId, String correlationId) {
        if (!tenantRoles(tenantId, actorId).contains(CATALOG_ADMIN)
                && !hasResponsibility(
                        tenantId, actorId, "APP_OWNER", resourceSetId)) {
            denied(tenantId, actorId, correlationId, "APP_ADMIN_PRESET_ASSIGNMENT",
                    "REQUEST", "CATALOG_ADMIN_OR_APP_OWNER_REQUIRED");
        }
    }

    void requireCatalogAdmin(
            Long tenantId,
            Long actorId,
            String correlationId,
            String entityType,
            String targetId) {
        if (!tenantRoles(tenantId, actorId).contains(CATALOG_ADMIN)) {
            denied(tenantId, actorId, correlationId, entityType, targetId,
                    "APP_CATALOG_ADMIN_REQUIRED");
        }
    }

    void requireScopedResponsibility(
            Long tenantId,
            Long actorId,
            String responsibilityCode,
            UUID resourceSetId,
            String correlationId,
            String entityType,
            String targetId) {
        if (!hasResponsibility(tenantId, actorId, responsibilityCode, resourceSetId)) {
            denied(tenantId, actorId, correlationId, entityType, targetId,
                    responsibilityCode + "_EXACT_SCOPE_REQUIRED");
        }
    }

    void requireAnyScopedResponsibility(
            Long tenantId,
            Long actorId,
            String responsibilityCode,
            String correlationId,
            String entityType,
            String targetId) {
        if (responsibilityScopes(tenantId, actorId, responsibilityCode).isEmpty()) {
            denied(tenantId, actorId, correlationId, entityType, targetId,
                    responsibilityCode + "_REQUIRED");
        }
    }

    boolean hasResponsibility(
            Long tenantId, Long actorId, String responsibilityCode, UUID resourceSetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM com_admin_role_assignments assignment
                     WHERE assignment.tenant_id = ?
                       AND assignment.responsibility_code = ?
                       AND assignment.resource_set_id = ?
                       AND assignment.lifecycle_state = 'ACTIVE'
                       AND (assignment.valid_from IS NULL
                            OR assignment.valid_from <= CURRENT_TIMESTAMP)
                       AND (assignment.valid_to IS NULL
                            OR assignment.valid_to > CURRENT_TIMESTAMP)
                       AND ((assignment.principal_type = 'USER'
                              AND assignment.principal_ref = ?)
                         OR (assignment.principal_type = 'GROUP' AND EXISTS (
                             SELECT 1 FROM com_group_members membership
                              JOIN com_groups access_group
                                ON access_group.tenant_id = membership.tenant_id
                               AND access_group.group_id = membership.group_id
                               AND access_group.status = 'ACTIVE'
                             WHERE membership.tenant_id = assignment.tenant_id
                               AND membership.group_id::text = assignment.principal_ref
                               AND membership.user_id = ?))))
                """, Boolean.class, tenantId, responsibilityCode, resourceSetId,
                actorId.toString(), actorId));
    }

    private Set<UUID> responsibilityScopes(
            Long tenantId, Long actorId, String responsibilityCode) {
        String responsibilityFilter = responsibilityCode == null
                ? "" : " AND assignment.responsibility_code = ?";
        Object[] arguments = responsibilityCode == null
                ? new Object[] {tenantId, actorId.toString(), actorId}
                : new Object[] {tenantId, responsibilityCode, actorId.toString(), actorId};
        return new LinkedHashSet<>(jdbc.query("""
                SELECT DISTINCT assignment.resource_set_id
                  FROM com_admin_role_assignments assignment
                 WHERE assignment.tenant_id = ?
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND (assignment.valid_from IS NULL
                        OR assignment.valid_from <= CURRENT_TIMESTAMP)
                   AND (assignment.valid_to IS NULL
                        OR assignment.valid_to > CURRENT_TIMESTAMP)
                """ + responsibilityFilter + """
                   AND ((assignment.principal_type = 'USER'
                          AND assignment.principal_ref = ?)
                     OR (assignment.principal_type = 'GROUP' AND EXISTS (
                         SELECT 1 FROM com_group_members membership
                          JOIN com_groups access_group
                            ON access_group.tenant_id = membership.tenant_id
                           AND access_group.group_id = membership.group_id
                           AND access_group.status = 'ACTIVE'
                         WHERE membership.tenant_id = assignment.tenant_id
                           AND membership.group_id::text = assignment.principal_ref
                           AND membership.user_id = ?)))
                 ORDER BY assignment.resource_set_id
                """, (result, ignored) -> result.getObject(1, UUID.class), arguments));
    }

    private Set<String> tenantRoles(Long tenantId, Long actorId) {
        return new LinkedHashSet<>(jdbc.query("""
                SELECT role.code FROM com_roles role
                  JOIN com_role_members member ON member.tenant_id = role.tenant_id
                   AND member.role_id = role.role_id
                 WHERE role.tenant_id = ? AND member.user_id = ?
                   AND role.status = 'ACTIVE'
                UNION
                SELECT role.code FROM com_roles role
                  JOIN com_group_role_assignments assignment
                    ON assignment.tenant_id = role.tenant_id
                   AND assignment.role_id = role.role_id
                  JOIN com_group_members member
                    ON member.tenant_id = assignment.tenant_id
                   AND member.group_id = assignment.group_id
                  JOIN com_groups access_group
                    ON access_group.tenant_id = member.tenant_id
                   AND access_group.group_id = member.group_id
                   AND access_group.status = 'ACTIVE'
                 WHERE role.tenant_id = ? AND member.user_id = ?
                   AND role.status = 'ACTIVE'
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND assignment.assignment_type = 'ACTIVE'
                   AND assignment.scope_type = 'TENANT'
                   AND (assignment.valid_from IS NULL
                        OR assignment.valid_from <= CURRENT_TIMESTAMP)
                   AND (assignment.valid_to IS NULL
                        OR assignment.valid_to > CURRENT_TIMESTAMP)
                """, (result, ignored) -> result.getString(1),
                tenantId, actorId, tenantId, actorId));
    }

    private void denied(
            Long tenantId,
            Long actorId,
            String correlationId,
            String entityType,
            String targetId,
            String reason) {
        // The public exception handler still renders the localized FORBIDDEN message.
        // Keep this stable, non-sensitive machine reason on the exception so the
        // independent denial audit can distinguish failed authority boundaries.
        throw new BaseException(ErrorCode.FORBIDDEN, reason);
    }

    record Visibility(
            boolean queueReader,
            Set<UUID> resourceSetIds,
            Set<UUID> reviewSetIds,
            Set<String> appResourceKeys) {
    }
}
