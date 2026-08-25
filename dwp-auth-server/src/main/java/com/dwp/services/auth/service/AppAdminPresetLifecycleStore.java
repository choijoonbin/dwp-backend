package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/** Locked lifecycle mutations and exact-scope evidence queries for preset aggregates. */
final class AppAdminPresetLifecycleStore {

    private final JdbcTemplate jdbc;

    AppAdminPresetLifecycleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void decideResponsibility(
            Long tenantId, UUID id, Long actorId, String state, String reason) {
        int changed = jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET lifecycle_state = ?, approved_by = ?, approved_at = CURRENT_TIMESTAMP,
                       decision_reason = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND admin_role_assignment_id = ?
                   AND lifecycle_state = 'PENDING_APPROVAL'
                """, state, actorId, reason.strip(), actorId, tenantId, id);
        requireChanged(changed);
    }

    void activateResponsibility(Long tenantId, UUID id, Long actorId) {
        int changed = jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET lifecycle_state = 'ACTIVE', valid_from = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND admin_role_assignment_id = ?
                   AND lifecycle_state = 'APPROVED'
                """, actorId, tenantId, id);
        requireChanged(changed);
    }

    void revokeResponsibility(Long tenantId, UUID id, Long actorId, String reason) {
        int changed = jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET lifecycle_state = 'REVOKED', revoked_by = ?,
                       revoked_at = CURRENT_TIMESTAMP, revocation_reason = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND admin_role_assignment_id = ?
                   AND lifecycle_state IN ('APPROVED', 'ACTIVE')
                """, actorId, reason.strip(), actorId, tenantId, id);
        requireChanged(changed);
    }

    void requireNotSubject(
            Long tenantId, Long actorId, String principalType,
            String principalRef, String message) {
        boolean self = "USER".equals(principalType)
                ? actorId.toString().equals(principalRef)
                : Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM com_group_members
                     WHERE tenant_id = ? AND group_id::text = ? AND user_id = ?)
                    """, Boolean.class, tenantId, principalRef, actorId));
        if (self) throw new BaseException(ErrorCode.SOD_CONFLICT, message);
    }

    boolean hasGovernedDutyEvidence(
            Long tenantId, AppGovernanceDtos.AppAdminPresetReview review) {
        UUID resourceSetId = review.resourceSetId();
        if (resourceSetId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM auth_effective_scoped_duties effective
                    JOIN com_admin_scoped_duty_assignments assignment
                      ON assignment.scoped_duty_assignment_id =
                         effective.scoped_duty_assignment_id
                     AND assignment.app_preset_assignment_id IS NOT NULL
                   WHERE effective.tenant_id = ? AND effective.user_id = ?
                     AND effective.duty_code = ? AND effective.resource_set_id = ?)
                """, Boolean.class, tenantId, review.userId(), review.dutyCode(),
                resourceSetId));
    }

    void lockResourceBoundary(Long tenantId, UUID resourceSetId) {
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", ignored -> { },
                tenantId.intValue(), ("app-admin-resource-set:" + resourceSetId).hashCode());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", ignored -> { },
                "dwp-app-admin-preset:" + tenantId);
    }

    void lockAggregate(Long tenantId, UUID assignmentId) {
        List<UUID> values = jdbc.query("""
                SELECT app_preset_assignment_id FROM com_admin_app_preset_assignments
                 WHERE tenant_id = ? AND app_preset_assignment_id = ? FOR UPDATE
                """, (result, ignored) -> result.getObject(1, UUID.class), tenantId, assignmentId);
        if (values.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
    }

    void assertDeferredGuards() {
        jdbc.execute("SET CONSTRAINTS trg_scoped_duty_assignment_sod IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS trg_app_preset_aggregate_consistency IMMEDIATE");
        jdbc.execute("SET CONSTRAINTS trg_app_preset_duty_consistency IMMEDIATE");
    }

    long eventSequence(Long tenantId, UUID assignmentId) {
        Long value = jdbc.queryForObject("""
                SELECT event_sequence FROM com_admin_app_preset_assignments
                 WHERE tenant_id = ? AND app_preset_assignment_id = ?
                """, Long.class, tenantId, assignmentId);
        if (value == null || value <= 0) throw new IllegalStateException("Invalid event sequence.");
        return value;
    }

    void invalidatePrincipal(
            Long tenantId, String principalType, String principalRef, Long actorId) {
        if ("USER".equals(principalType)) {
            jdbc.update("""
                    UPDATE com_users SET access_revision = access_revision + 1,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP,
                           updated_by = ? WHERE tenant_id = ? AND user_id::text = ?
                    """, actorId, tenantId, principalRef);
            return;
        }
        jdbc.update("""
                UPDATE com_users user_record
                   SET access_revision = access_revision + 1,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                  FROM com_group_members membership
                 WHERE membership.tenant_id = ? AND membership.group_id::text = ?
                   AND user_record.tenant_id = membership.tenant_id
                   AND user_record.user_id = membership.user_id
                """, actorId, tenantId, principalRef);
    }

    private void requireChanged(int changed) {
        if (changed != 1) throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
    }
}
