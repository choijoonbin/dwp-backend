package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Concurrency, SoD, and principal persistence boundary for control responsibilities. */
final class AppGovernanceAssignmentStore {

    private final JdbcTemplate jdbc;

    AppGovernanceAssignmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockAssignmentBoundary(
            Long tenantId,
            String principalType,
            String principalRef,
            AppGovernanceDtos.ResourceSet resourceSet) {
        lockResourceSet(tenantId, resourceSet.resourceSetId());
        resourceSet.resources().stream()
                .map(AppGovernanceDtos.ResourceMember::resourceKey)
                .sorted()
                .forEach(resourceKey -> lockTransactionKey(
                        tenantId,
                        "app-admin-duty:" + principalType + ":" + principalRef + ":" + resourceKey));
    }

    void lockResourceSet(Long tenantId, UUID resourceSetId) {
        jdbc.query("""
                SELECT resource_set_id FROM com_admin_resource_sets
                 WHERE tenant_id = ? AND resource_set_id = ?
                 FOR UPDATE
                """, ignored -> { }, tenantId, resourceSetId);
        lockTransactionKey(tenantId, "app-admin-resource-set:" + resourceSetId);
    }

    void requirePrincipal(Long tenantId, String type, String ref) {
        String sql = "USER".equals(type)
                ? "SELECT COUNT(*) FROM com_users WHERE tenant_id = ? AND user_id::text = ? AND status = 'ACTIVE'"
                : "SELECT COUNT(*) FROM com_groups WHERE tenant_id = ? AND group_id::text = ? AND status = 'ACTIVE'";
        Long count = jdbc.queryForObject(sql, Long.class, tenantId, ref);
        if (count == null || count == 0) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The principal is not active in this tenant.");
        }
    }

    void ensureNoDutyConflict(
            Long tenantId,
            String principalType,
            String principalRef,
            String responsibility,
            UUID resourceSetId,
            UUID ignoredAssignmentId) {
        Set<String> conflicts = switch (responsibility) {
            case "APP_ACCESS_MANAGER" -> Set.of("APP_ACCESS_APPROVER", "APP_ACCESS_REVIEWER");
            case "APP_ACCESS_APPROVER", "APP_ACCESS_REVIEWER" -> Set.of("APP_ACCESS_MANAGER");
            default -> Set.of();
        };
        if (conflicts.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(conflicts.size(), "?"));
        List<Object> arguments = new ArrayList<>(List.of(
                resourceSetId, tenantId, principalType, principalRef));
        arguments.addAll(conflicts);
        arguments.add(ignoredAssignmentId == null ? new UUID(0, 0) : ignoredAssignmentId);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM com_admin_role_assignments existing
                  JOIN com_admin_resource_set_members existing_member
                    ON existing_member.tenant_id = existing.tenant_id
                   AND existing_member.resource_set_id = existing.resource_set_id
                   AND existing_member.lifecycle_state = 'ACTIVE'
                  JOIN com_admin_resource_set_members requested_member
                    ON requested_member.tenant_id = existing_member.tenant_id
                   AND requested_member.resource_set_id = ?
                   AND requested_member.resource_type = existing_member.resource_type
                   AND requested_member.resource_key = existing_member.resource_key
                   AND requested_member.lifecycle_state = 'ACTIVE'
                 WHERE existing.tenant_id = ?
                   AND existing.principal_type = ? AND existing.principal_ref = ?
                   AND existing.responsibility_code IN (%s)
                   AND existing.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                   AND existing.admin_role_assignment_id <> ?
                """.formatted(placeholders), Long.class, arguments.toArray());
        if (count != null && count > 0) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Access fulfilment cannot overlap approval or review for the same application.");
        }
    }

    boolean hasOpenAssignment(
            Long tenantId, String principalType, String principalRef,
            String responsibility, UUID resourceSetId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND principal_type = ? AND principal_ref = ?
                   AND responsibility_code = ? AND resource_set_id = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                """, Long.class, tenantId, principalType, principalRef,
                responsibility, resourceSetId);
        return count != null && count > 0;
    }

    long activeOwnerCount(Long tenantId, UUID resourceSetId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND resource_set_id = ?
                   AND responsibility_code = 'APP_OWNER' AND lifecycle_state = 'ACTIVE'
                   AND (valid_to IS NULL OR valid_to > CURRENT_TIMESTAMP)
                """, Long.class, tenantId, resourceSetId);
        return count == null ? 0 : count;
    }

    long effectiveResponsibilityCount(
            Long tenantId, UUID resourceSetId, String responsibilityCode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND resource_set_id = ?
                   AND responsibility_code = ? AND lifecycle_state = 'ACTIVE'
                   AND (valid_from IS NULL OR valid_from <= statement_timestamp())
                   AND (valid_to IS NULL OR valid_to > statement_timestamp())
                   AND (
                       (principal_type = 'USER' AND EXISTS (
                           SELECT 1 FROM com_users user_record
                            WHERE user_record.tenant_id = com_admin_role_assignments.tenant_id
                              AND user_record.user_id::text =
                                  com_admin_role_assignments.principal_ref
                              AND user_record.status = 'ACTIVE'))
                       OR (principal_type = 'GROUP' AND EXISTS (
                           SELECT 1 FROM com_groups access_group
                           JOIN com_group_members membership
                             ON membership.tenant_id = access_group.tenant_id
                            AND membership.group_id = access_group.group_id
                           JOIN com_users member
                             ON member.tenant_id = membership.tenant_id
                            AND member.user_id = membership.user_id
                            AND member.status = 'ACTIVE'
                            WHERE access_group.tenant_id =
                                  com_admin_role_assignments.tenant_id
                              AND access_group.group_id::text =
                                  com_admin_role_assignments.principal_ref
                              AND access_group.status = 'ACTIVE')))
                """, Long.class, tenantId, resourceSetId, responsibilityCode);
        return count == null ? 0 : count;
    }

    boolean hasEffectiveResponsibilityForUser(
            Long tenantId,
            Long userId,
            UUID resourceSetId,
            String responsibilityCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM com_admin_role_assignments assignment
                     JOIN com_users actor
                       ON actor.tenant_id = assignment.tenant_id
                      AND actor.user_id = ?
                      AND actor.status = 'ACTIVE'
                     WHERE assignment.tenant_id = ?
                       AND assignment.resource_set_id = ?
                       AND assignment.responsibility_code = ?
                       AND assignment.lifecycle_state = 'ACTIVE'
                       AND (assignment.valid_from IS NULL
                            OR assignment.valid_from <= statement_timestamp())
                       AND (assignment.valid_to IS NULL
                            OR assignment.valid_to > statement_timestamp())
                       AND ((assignment.principal_type = 'USER'
                              AND assignment.principal_ref = actor.user_id::text)
                         OR (assignment.principal_type = 'GROUP' AND EXISTS (
                             SELECT 1 FROM com_groups access_group
                             JOIN com_group_members membership
                               ON membership.tenant_id = access_group.tenant_id
                              AND membership.group_id = access_group.group_id
                              AND membership.user_id = actor.user_id
                            WHERE access_group.tenant_id = assignment.tenant_id
                              AND access_group.group_id::text = assignment.principal_ref
                              AND access_group.status = 'ACTIVE'))))
                """, Boolean.class, userId, tenantId, resourceSetId,
                responsibilityCode));
    }

    boolean isActiveUser(Long tenantId, Long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM com_users
                     WHERE tenant_id = ? AND user_id = ? AND status = 'ACTIVE')
                """, Boolean.class, tenantId, userId));
    }

    boolean isActivePrincipal(Long tenantId, String principalType, String principalRef) {
        String sql = "USER".equals(principalType)
                ? """
                  SELECT EXISTS (
                      SELECT 1 FROM com_users
                       WHERE tenant_id = ? AND user_id::text = ? AND status = 'ACTIVE')
                  """
                : """
                  SELECT EXISTS (
                      SELECT 1 FROM com_groups access_group
                      JOIN com_group_members membership
                        ON membership.tenant_id = access_group.tenant_id
                       AND membership.group_id = access_group.group_id
                      JOIN com_users member
                        ON member.tenant_id = membership.tenant_id
                       AND member.user_id = membership.user_id
                       AND member.status = 'ACTIVE'
                       WHERE access_group.tenant_id = ?
                         AND access_group.group_id::text = ?
                         AND access_group.status = 'ACTIVE')
                  """;
        return Boolean.TRUE.equals(
                jdbc.queryForObject(sql, Boolean.class, tenantId, principalRef));
    }

    boolean principalIncludesUser(
            Long tenantId, String principalType, String principalRef, Long userId) {
        if ("USER".equals(principalType)) {
            return userId.toString().equals(principalRef);
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM com_group_members membership
                    JOIN com_groups access_group
                      ON access_group.tenant_id = membership.tenant_id
                     AND access_group.group_id = membership.group_id
                     AND access_group.status = 'ACTIVE'
                   WHERE membership.tenant_id = ?
                     AND membership.group_id::text = ?
                     AND membership.user_id = ?)
                """, Boolean.class, tenantId, principalRef, userId));
    }

    void invalidatePrincipal(
            Long tenantId, String principalType, String principalRef, Long actorId) {
        if ("USER".equals(principalType)) {
            jdbc.update("""
                    UPDATE com_users
                       SET access_revision = access_revision + 1, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND user_id::text = ?
                    """, actorId, tenantId, principalRef);
        } else if ("GROUP".equals(principalType)) {
            jdbc.update("""
                    UPDATE com_users user_record
                       SET access_revision = access_revision + 1, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                      FROM com_group_members membership
                     WHERE membership.tenant_id = ? AND membership.group_id::text = ?
                       AND user_record.tenant_id = membership.tenant_id
                       AND user_record.user_id = membership.user_id
                    """, actorId, tenantId, principalRef);
        }
    }

    private void lockTransactionKey(Long tenantId, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", ignored -> { },
                tenantId.intValue(), key.hashCode());
    }
}
