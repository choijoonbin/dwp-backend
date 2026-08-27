package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
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
        // The database invariant uses the same tenant-wide lock. Taking it before
        // the narrower row/resource locks makes cross-principal USER/GROUP races
        // observable by the service preflight instead of only at transaction commit.
        lockTransactionTextKey("dwp-app-responsibility-sod:" + tenantId);
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
            OffsetDateTime validFrom,
            OffsetDateTime validTo,
            UUID ignoredAssignmentId) {
        Set<String> conflicts = conflictingResponsibilities(responsibility);
        if (conflicts.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(conflicts.size(), "?"));
        List<Object> arguments = new ArrayList<>(List.of(
                principalType, tenantId, principalRef,
                principalType, tenantId, principalRef,
                resourceSetId, tenantId));
        arguments.addAll(conflicts);
        arguments.add(ignoredAssignmentId == null ? new UUID(0, 0) : ignoredAssignmentId);
        arguments.add(validTo);
        arguments.add(validFrom);
        Long count = jdbc.queryForObject("""
                WITH requested_users AS (
                    SELECT user_record.user_id
                      FROM com_users user_record
                     WHERE ? = 'USER'
                       AND user_record.tenant_id = ?
                       AND user_record.user_id::text = ?
                       AND user_record.status = 'ACTIVE'
                    UNION
                    SELECT membership.user_id
                      FROM com_groups access_group
                      JOIN com_group_members membership
                        ON membership.tenant_id = access_group.tenant_id
                       AND membership.group_id = access_group.group_id
                      JOIN com_users member
                        ON member.tenant_id = membership.tenant_id
                       AND member.user_id = membership.user_id
                       AND member.status = 'ACTIVE'
                     WHERE ? = 'GROUP'
                       AND access_group.tenant_id = ?
                       AND access_group.group_id::text = ?
                       AND access_group.status = 'ACTIVE'
                )
                SELECT COUNT(DISTINCT existing.admin_role_assignment_id)
                  FROM com_admin_role_assignments existing
                  JOIN com_admin_resource_sets existing_set
                    ON existing_set.tenant_id = existing.tenant_id
                   AND existing_set.resource_set_id = existing.resource_set_id
                   AND existing_set.lifecycle_state = 'ACTIVE'
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
                  JOIN requested_users requested_user ON (
                       (existing.principal_type = 'USER'
                        AND existing.principal_ref = requested_user.user_id::text
                        AND EXISTS (
                            SELECT 1 FROM com_users existing_user
                             WHERE existing_user.tenant_id = existing.tenant_id
                               AND existing_user.user_id = requested_user.user_id
                               AND existing_user.status = 'ACTIVE'))
                    OR (existing.principal_type = 'GROUP' AND EXISTS (
                            SELECT 1
                              FROM com_groups existing_group
                              JOIN com_group_members existing_membership
                                ON existing_membership.tenant_id = existing_group.tenant_id
                               AND existing_membership.group_id = existing_group.group_id
                              JOIN com_users existing_user
                                ON existing_user.tenant_id = existing_membership.tenant_id
                               AND existing_user.user_id = existing_membership.user_id
                               AND existing_user.status = 'ACTIVE'
                             WHERE existing_group.tenant_id = existing.tenant_id
                               AND existing_group.group_id::text = existing.principal_ref
                               AND existing_group.status = 'ACTIVE'
                               AND existing_membership.user_id = requested_user.user_id)))
                 WHERE existing.tenant_id = ?
                   AND existing.responsibility_code IN (%s)
                   AND existing.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                   AND existing.admin_role_assignment_id <> ?
                   AND (existing.valid_to IS NULL
                        OR existing.valid_to > statement_timestamp())
                   AND COALESCE(existing.valid_from, '-infinity'::TIMESTAMPTZ)
                       < COALESCE(CAST(? AS TIMESTAMPTZ), 'infinity'::TIMESTAMPTZ)
                   AND COALESCE(CAST(? AS TIMESTAMPTZ), '-infinity'::TIMESTAMPTZ)
                       < COALESCE(existing.valid_to, 'infinity'::TIMESTAMPTZ)
                """.formatted(placeholders), Long.class, arguments.toArray());
        if (count != null && count > 0) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Access fulfilment cannot overlap approval or review for the same application and effective user.");
        }
    }

    static Set<String> conflictingResponsibilities(String responsibility) {
        return switch (responsibility) {
            case "APP_ACCESS_MANAGER" -> Set.of("APP_ACCESS_APPROVER", "APP_ACCESS_REVIEWER");
            case "APP_ACCESS_APPROVER", "APP_ACCESS_REVIEWER" -> Set.of("APP_ACCESS_MANAGER");
            default -> Set.of();
        };
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

    boolean wouldRemoveFinalEffectiveOwner(
            Long tenantId, UUID resourceSetId, UUID assignmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH effective_owners AS (
                    SELECT assignment.admin_role_assignment_id
                      FROM com_admin_role_assignments assignment
                     WHERE assignment.tenant_id = ?
                       AND assignment.resource_set_id = ?
                       AND assignment.responsibility_code = 'APP_OWNER'
                       AND assignment.lifecycle_state = 'ACTIVE'
                       AND (assignment.valid_from IS NULL
                            OR assignment.valid_from <= statement_timestamp())
                       AND (assignment.valid_to IS NULL
                            OR assignment.valid_to > statement_timestamp())
                       AND (
                           (assignment.principal_type = 'USER' AND EXISTS (
                               SELECT 1 FROM com_users owner_user
                                WHERE owner_user.tenant_id = assignment.tenant_id
                                  AND owner_user.user_id::text = assignment.principal_ref
                                  AND owner_user.status = 'ACTIVE'))
                           OR (assignment.principal_type = 'GROUP' AND EXISTS (
                               SELECT 1
                                 FROM com_groups owner_group
                                 JOIN com_group_members membership
                                   ON membership.tenant_id = owner_group.tenant_id
                                  AND membership.group_id = owner_group.group_id
                                 JOIN com_users owner_user
                                   ON owner_user.tenant_id = membership.tenant_id
                                  AND owner_user.user_id = membership.user_id
                                  AND owner_user.status = 'ACTIVE'
                                WHERE owner_group.tenant_id = assignment.tenant_id
                                  AND owner_group.group_id::text = assignment.principal_ref
                                  AND owner_group.status = 'ACTIVE')))
                )
                SELECT EXISTS (
                           SELECT 1 FROM effective_owners
                            WHERE admin_role_assignment_id = ?)
                   AND NOT EXISTS (
                           SELECT 1 FROM effective_owners
                            WHERE admin_role_assignment_id <> ?)
                """, Boolean.class, tenantId, resourceSetId,
                assignmentId, assignmentId));
    }

    Set<UUID> effectiveOwnerResourceSetIds(Long tenantId) {
        return Set.copyOf(jdbc.query("""
                SELECT DISTINCT assignment.resource_set_id
                  FROM com_admin_role_assignments assignment
                  JOIN com_admin_resource_sets resource_set
                    ON resource_set.tenant_id = assignment.tenant_id
                   AND resource_set.resource_set_id = assignment.resource_set_id
                   AND resource_set.lifecycle_state = 'ACTIVE'
                 WHERE assignment.tenant_id = ?
                   AND assignment.responsibility_code = 'APP_OWNER'
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND (assignment.valid_from IS NULL
                        OR assignment.valid_from <= statement_timestamp())
                   AND (assignment.valid_to IS NULL
                        OR assignment.valid_to > statement_timestamp())
                   AND (
                       (assignment.principal_type = 'USER' AND EXISTS (
                           SELECT 1 FROM com_users owner_user
                            WHERE owner_user.tenant_id = assignment.tenant_id
                              AND owner_user.user_id::text = assignment.principal_ref
                              AND owner_user.status = 'ACTIVE'))
                       OR (assignment.principal_type = 'GROUP' AND EXISTS (
                           SELECT 1
                             FROM com_groups owner_group
                             JOIN com_group_members membership
                               ON membership.tenant_id = owner_group.tenant_id
                              AND membership.group_id = owner_group.group_id
                             JOIN com_users owner_user
                               ON owner_user.tenant_id = membership.tenant_id
                              AND owner_user.user_id = membership.user_id
                              AND owner_user.status = 'ACTIVE'
                            WHERE owner_group.tenant_id = assignment.tenant_id
                              AND owner_group.group_id::text = assignment.principal_ref
                              AND owner_group.status = 'ACTIVE')))
                """, (result, ignored) ->
                result.getObject("resource_set_id", UUID.class), tenantId));
    }

    Set<UUID> firstApproverBootstrapEligibleAssignmentIds(
            Long tenantId,
            Long actorId,
            boolean catalogAdmin) {
        return firstApproverBootstrapEligibleAssignmentIds(
                tenantId, actorId, catalogAdmin, null);
    }

    boolean isFirstApproverBootstrapEligible(
            Long tenantId,
            Long actorId,
            boolean catalogAdmin,
            UUID assignmentId) {
        return firstApproverBootstrapEligibleAssignmentIds(
                tenantId, actorId, catalogAdmin, assignmentId)
                .contains(assignmentId);
    }

    private Set<UUID> firstApproverBootstrapEligibleAssignmentIds(
            Long tenantId,
            Long actorId,
            boolean catalogAdmin,
            UUID assignmentId) {
        if (!catalogAdmin) return Set.of();
        String assignmentFilter = assignmentId == null
                ? "" : " AND candidate.admin_role_assignment_id = ?";
        List<Object> arguments = new ArrayList<>(List.of(actorId, tenantId));
        if (assignmentId != null) arguments.add(assignmentId);
        return Set.copyOf(jdbc.query("""
                SELECT candidate.admin_role_assignment_id
                  FROM com_admin_role_assignments candidate
                  JOIN com_users actor
                    ON actor.tenant_id = candidate.tenant_id
                   AND actor.user_id = ?
                   AND actor.status = 'ACTIVE'
                  JOIN com_users target_user
                    ON target_user.tenant_id = candidate.tenant_id
                   AND target_user.user_id::text = candidate.principal_ref
                   AND target_user.status = 'ACTIVE'
                  JOIN com_admin_resource_sets resource_set
                    ON resource_set.tenant_id = candidate.tenant_id
                   AND resource_set.resource_set_id = candidate.resource_set_id
                   AND resource_set.lifecycle_state = 'ACTIVE'
                 WHERE candidate.tenant_id = ?
                   AND candidate.responsibility_code = 'APP_ACCESS_APPROVER'
                   AND candidate.lifecycle_state = 'PENDING_APPROVAL'
                   AND candidate.assignment_source = 'MANUAL'
                   AND candidate.principal_type = 'USER'
                   AND candidate.created_by IS NOT NULL
                   AND candidate.created_by <> actor.user_id
                   AND target_user.user_id <> actor.user_id
                   AND (candidate.valid_to IS NULL
                        OR candidate.valid_to > statement_timestamp())
                   AND EXISTS (
                       SELECT 1
                         FROM com_admin_role_assignments owner_assignment
                        WHERE owner_assignment.tenant_id = candidate.tenant_id
                          AND owner_assignment.resource_set_id = candidate.resource_set_id
                          AND owner_assignment.responsibility_code = 'APP_OWNER'
                          AND owner_assignment.lifecycle_state = 'ACTIVE'
                          AND (owner_assignment.valid_from IS NULL
                               OR owner_assignment.valid_from <= statement_timestamp())
                          AND (owner_assignment.valid_to IS NULL
                               OR owner_assignment.valid_to > statement_timestamp())
                          AND (
                              (owner_assignment.principal_type = 'USER' AND EXISTS (
                                  SELECT 1 FROM com_users owner_user
                                   WHERE owner_user.tenant_id = owner_assignment.tenant_id
                                     AND owner_user.user_id = candidate.created_by
                                     AND owner_user.user_id::text =
                                         owner_assignment.principal_ref
                                     AND owner_user.status = 'ACTIVE'))
                              OR (owner_assignment.principal_type = 'GROUP' AND EXISTS (
                                  SELECT 1
                                    FROM com_groups owner_group
                                    JOIN com_group_members owner_membership
                                      ON owner_membership.tenant_id = owner_group.tenant_id
                                     AND owner_membership.group_id = owner_group.group_id
                                    JOIN com_users owner_user
                                      ON owner_user.tenant_id = owner_membership.tenant_id
                                     AND owner_user.user_id = owner_membership.user_id
                                     AND owner_user.status = 'ACTIVE'
                                   WHERE owner_group.tenant_id = owner_assignment.tenant_id
                                     AND owner_group.group_id::text =
                                         owner_assignment.principal_ref
                                     AND owner_group.status = 'ACTIVE'
                                     AND owner_membership.user_id = candidate.created_by)))
                   )
                   AND NOT EXISTS (
                       SELECT 1
                         FROM com_admin_role_assignments approver_assignment
                        WHERE approver_assignment.tenant_id = candidate.tenant_id
                          AND approver_assignment.resource_set_id = candidate.resource_set_id
                          AND approver_assignment.responsibility_code =
                              'APP_ACCESS_APPROVER'
                          AND approver_assignment.lifecycle_state = 'ACTIVE'
                          AND (approver_assignment.valid_from IS NULL
                               OR approver_assignment.valid_from <= statement_timestamp())
                          AND (approver_assignment.valid_to IS NULL
                               OR approver_assignment.valid_to > statement_timestamp())
                          AND (
                              (approver_assignment.principal_type = 'USER' AND EXISTS (
                                  SELECT 1 FROM com_users approver_user
                                   WHERE approver_user.tenant_id =
                                         approver_assignment.tenant_id
                                     AND approver_user.user_id::text =
                                         approver_assignment.principal_ref
                                     AND approver_user.status = 'ACTIVE'))
                              OR (approver_assignment.principal_type = 'GROUP' AND EXISTS (
                                  SELECT 1
                                    FROM com_groups approver_group
                                    JOIN com_group_members approver_membership
                                      ON approver_membership.tenant_id =
                                         approver_group.tenant_id
                                     AND approver_membership.group_id =
                                         approver_group.group_id
                                    JOIN com_users approver_user
                                      ON approver_user.tenant_id =
                                         approver_membership.tenant_id
                                     AND approver_user.user_id =
                                         approver_membership.user_id
                                     AND approver_user.status = 'ACTIVE'
                                   WHERE approver_group.tenant_id =
                                         approver_assignment.tenant_id
                                     AND approver_group.group_id::text =
                                         approver_assignment.principal_ref
                                     AND approver_group.status = 'ACTIVE')))
                   )
                """ + assignmentFilter, (result, ignored) ->
                result.getObject("admin_role_assignment_id", UUID.class),
                arguments.toArray()));
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

    private void lockTransactionTextKey(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, key);
    }
}
