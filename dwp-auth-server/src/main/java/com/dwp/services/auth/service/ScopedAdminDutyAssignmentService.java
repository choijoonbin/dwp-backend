package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Internal lifecycle boundary for versioned scoped specialist duties. */
@Service
public class ScopedAdminDutyAssignmentService {

    private static final Set<String> PRINCIPAL_TYPES = Set.of("USER", "GROUP");
    private static final Set<String> SOURCES = Set.of(
            "MANUAL", "GROUP", "IAM", "PROVISIONING", "AGENT");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ScopedAdminDutyAssignmentService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    ScopedAdminDutyAssignmentService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Assignment request(Request command) {
        validateRequest(command);
        lockTenant(command.tenantId());
        if (!principalExists(command)) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        UUID assignmentId = UUID.randomUUID();
        try {
            int inserted = jdbc.update("""
                    INSERT INTO com_admin_scoped_duty_assignments (
                        scoped_duty_assignment_id, tenant_id,
                        principal_type, principal_ref, duty_code,
                        resource_set_id, responsibility_assignment_id,
                        app_preset_assignment_id,
                        assignment_source, lifecycle_state, valid_from, valid_to,
                        review_due_at, justification, requested_by, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?, ?, ?, ?, ?, ?)
                    """,
                    assignmentId, command.tenantId(), command.principalType(),
                    command.principalRef(), command.dutyCode(), command.resourceSetId(),
                    command.responsibilityAssignmentId(), command.appPresetAssignmentId(),
                    command.assignmentSource(),
                    command.validFrom(), command.validTo(), command.reviewDueAt(),
                    command.justification().trim(), command.requestedBy(),
                    command.requestedBy(), command.requestedBy());
            if (inserted != 1) throw conflict("Scoped duty request was not persisted.");
            return find(command.tenantId(), assignmentId);
        } catch (DataIntegrityViolationException exception) {
            throw integrity(exception);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Assignment approve(
            Long tenantId,
            UUID assignmentId,
            Long approverId,
            long expectedVersion,
            String reason) {
        requireDecision(tenantId, assignmentId, approverId, expectedVersion, reason);
        lockTenant(tenantId);
        Assignment current = findForUpdate(tenantId, assignmentId);
        if (current.version() != expectedVersion) throw versionConflict();
        if (!"PENDING_APPROVAL".equals(current.lifecycleState())) {
            throw conflict("Only a pending scoped duty can be approved.");
        }
        if (Objects.equals(current.requestedBy(), approverId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "Scoped duty requester and approver must be different users.");
        }
        try {
            int updated = jdbc.update("""
                    UPDATE com_admin_scoped_duty_assignments
                       SET lifecycle_state = 'ACTIVE',
                           valid_from = COALESCE(valid_from, CURRENT_TIMESTAMP),
                           approved_by = ?,
                           approved_at = CURRENT_TIMESTAMP, decision_reason = ?,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP,
                           updated_by = ?
                     WHERE tenant_id = ? AND scoped_duty_assignment_id = ?
                       AND lifecycle_state = 'PENDING_APPROVAL' AND version = ?
                    """, approverId, reason.trim(), approverId, tenantId,
                    assignmentId, expectedVersion);
            if (updated != 1) throw versionConflict();
            if (!effectiveOrAuditException(tenantId, assignmentId)) {
                throw new BaseException(ErrorCode.SOD_CONFLICT,
                        "Scoped duty lacks matching active responsibility evidence.");
            }
            return find(tenantId, assignmentId);
        } catch (DataIntegrityViolationException exception) {
            throw integrity(exception);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Assignment approveForActivation(
            Long tenantId,
            UUID assignmentId,
            Long approverId,
            long expectedVersion,
            String reason) {
        requireDecision(tenantId, assignmentId, approverId, expectedVersion, reason);
        lockTenant(tenantId);
        Assignment current = findForUpdate(tenantId, assignmentId);
        if (current.version() != expectedVersion) throw versionConflict();
        if (!"PENDING_APPROVAL".equals(current.lifecycleState())) {
            throw conflict("Only a pending scoped duty can be approved.");
        }
        if (Objects.equals(current.requestedBy(), approverId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "Scoped duty requester and approver must be different users.");
        }
        try {
            int updated = jdbc.update("""
                    UPDATE com_admin_scoped_duty_assignments
                       SET lifecycle_state = 'APPROVED', approved_by = ?,
                           approved_at = CURRENT_TIMESTAMP, decision_reason = ?,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP,
                           updated_by = ?
                     WHERE tenant_id = ? AND scoped_duty_assignment_id = ?
                       AND lifecycle_state = 'PENDING_APPROVAL' AND version = ?
                    """, approverId, reason.trim(), approverId, tenantId,
                    assignmentId, expectedVersion);
            if (updated != 1) throw versionConflict();
            return find(tenantId, assignmentId);
        } catch (DataIntegrityViolationException exception) {
            throw integrity(exception);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Assignment activate(
            Long tenantId,
            UUID assignmentId,
            Long actorId,
            long expectedVersion,
            String reason) {
        requireDecision(tenantId, assignmentId, actorId, expectedVersion, reason);
        lockTenant(tenantId);
        Assignment current = findForUpdate(tenantId, assignmentId);
        if (current.version() != expectedVersion) throw versionConflict();
        if (!"APPROVED".equals(current.lifecycleState())) {
            throw conflict("Only an approved scoped duty can be activated.");
        }
        try {
            int updated = jdbc.update("""
                    UPDATE com_admin_scoped_duty_assignments
                       SET lifecycle_state = 'ACTIVE',
                           valid_from = COALESCE(valid_from, CURRENT_TIMESTAMP),
                           version = version + 1, updated_at = CURRENT_TIMESTAMP,
                           updated_by = ?
                     WHERE tenant_id = ? AND scoped_duty_assignment_id = ?
                       AND lifecycle_state = 'APPROVED' AND version = ?
                    """, actorId, tenantId, assignmentId, expectedVersion);
            if (updated != 1) throw versionConflict();
            if (!effectiveOrAuditException(tenantId, assignmentId)) {
                throw new BaseException(ErrorCode.SOD_CONFLICT,
                        "Scoped duty lacks matching active responsibility evidence.");
            }
            return find(tenantId, assignmentId);
        } catch (DataIntegrityViolationException exception) {
            throw integrity(exception);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Assignment deny(
            Long tenantId,
            UUID assignmentId,
            Long approverId,
            long expectedVersion,
            String reason) {
        requireDecision(tenantId, assignmentId, approverId, expectedVersion, reason);
        lockTenant(tenantId);
        Assignment current = findForUpdate(tenantId, assignmentId);
        if (current.version() != expectedVersion) throw versionConflict();
        if (!"PENDING_APPROVAL".equals(current.lifecycleState())) {
            throw conflict("Only a pending scoped duty can be denied.");
        }
        if (Objects.equals(current.requestedBy(), approverId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "Scoped duty requester and approver must be different users.");
        }
        int updated = jdbc.update("""
                UPDATE com_admin_scoped_duty_assignments
                   SET lifecycle_state = 'DENIED', approved_by = ?,
                       approved_at = CURRENT_TIMESTAMP, decision_reason = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND scoped_duty_assignment_id = ?
                   AND lifecycle_state = 'PENDING_APPROVAL' AND version = ?
                """, approverId, reason.trim(), approverId, tenantId,
                assignmentId, expectedVersion);
        if (updated != 1) throw versionConflict();
        return find(tenantId, assignmentId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Assignment revoke(
            Long tenantId,
            UUID assignmentId,
            Long actorId,
            long expectedVersion,
            String reason) {
        requireDecision(tenantId, assignmentId, actorId, expectedVersion, reason);
        lockTenant(tenantId);
        int updated = jdbc.update("""
                UPDATE com_admin_scoped_duty_assignments
                   SET lifecycle_state = 'REVOKED', revoked_by = ?,
                       revoked_at = CURRENT_TIMESTAMP, revocation_reason = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND scoped_duty_assignment_id = ?
                   AND lifecycle_state IN ('APPROVED', 'ACTIVE') AND version = ?
                """, actorId, reason.trim(), actorId, tenantId,
                assignmentId, expectedVersion);
        if (updated != 1) throw versionConflict();
        return find(tenantId, assignmentId);
    }

    @Transactional(readOnly = true)
    public Assignment find(Long tenantId, UUID assignmentId) {
        return query(tenantId, assignmentId, false);
    }

    private Assignment findForUpdate(Long tenantId, UUID assignmentId) {
        return query(tenantId, assignmentId, true);
    }

    private Assignment query(Long tenantId, UUID assignmentId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        java.util.List<Assignment> values = jdbc.query("""
                SELECT scoped_duty_assignment_id, tenant_id, principal_type,
                       principal_ref, duty_code, resource_set_id,
                       responsibility_assignment_id, app_preset_assignment_id,
                       assignment_source,
                       lifecycle_state, valid_from, valid_to, review_due_at,
                       justification, requested_by, approved_by, approved_at,
                       decision_reason, revoked_by, revoked_at,
                       revocation_reason, version
                  FROM com_admin_scoped_duty_assignments
                 WHERE tenant_id = ? AND scoped_duty_assignment_id = ?
                """ + suffix, this::assignment, tenantId, assignmentId);
        if (values.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return values.getFirst();
    }

    private Assignment assignment(java.sql.ResultSet result, int ignored)
            throws java.sql.SQLException {
        return new Assignment(
                result.getObject("scoped_duty_assignment_id", UUID.class),
                result.getLong("tenant_id"), result.getString("principal_type"),
                result.getString("principal_ref"), result.getString("duty_code"),
                result.getObject("resource_set_id", UUID.class),
                result.getObject("responsibility_assignment_id", UUID.class),
                result.getObject("app_preset_assignment_id", UUID.class),
                result.getString("assignment_source"), result.getString("lifecycle_state"),
                result.getObject("valid_from", OffsetDateTime.class),
                result.getObject("valid_to", OffsetDateTime.class),
                result.getObject("review_due_at", OffsetDateTime.class),
                result.getString("justification"), (Long) result.getObject("requested_by"),
                (Long) result.getObject("approved_by"),
                result.getObject("approved_at", OffsetDateTime.class),
                result.getString("decision_reason"), (Long) result.getObject("revoked_by"),
                result.getObject("revoked_at", OffsetDateTime.class),
                result.getString("revocation_reason"), result.getLong("version"));
    }

    private boolean effectiveOrAuditException(Long tenantId, UUID assignmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                           SELECT 1
                             FROM auth_effective_scoped_duties effective
                            WHERE effective.tenant_id = assignment.tenant_id
                              AND effective.scoped_duty_assignment_id =
                                  assignment.scoped_duty_assignment_id)
                  FROM com_admin_scoped_duty_assignments assignment
                 WHERE assignment.tenant_id = ?
                   AND assignment.scoped_duty_assignment_id = ?
                """, Boolean.class, tenantId, assignmentId));
    }

    private void lockTenant(Long tenantId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                result -> {
                    if (!result.next()) {
                        throw conflict("Scoped duty tenant lock was not acquired.");
                    }
                    return null;
                }, "dwp-scoped-duty:" + tenantId);
    }

    private void validateRequest(Request value) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (value == null || value.tenantId() == null || value.tenantId() <= 0
                || !PRINCIPAL_TYPES.contains(value.principalType())
                || value.principalRef() == null || value.principalRef().isBlank()
                || value.dutyCode() == null || value.dutyCode().isBlank()
                || value.resourceSetId() == null || !SOURCES.contains(value.assignmentSource())
                || value.reviewDueAt() == null || !value.reviewDueAt().isAfter(now)
                || value.justification() == null
                || value.justification().trim().length() < 10 || value.requestedBy() == null
                || value.requestedBy() <= 0
                || value.validTo() != null && value.validFrom() != null
                && !value.validTo().isAfter(value.validFrom())
                || value.validTo() != null && !value.validTo().isAfter(now)
                || value.validTo() != null && value.reviewDueAt().isAfter(value.validTo())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private boolean principalExists(Request command) {
        String table = "USER".equals(command.principalType()) ? "com_users" : "com_groups";
        String id = "USER".equals(command.principalType()) ? "user_id" : "group_id";
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM " + table
                        + " WHERE tenant_id = ? AND " + id
                        + "::text = ? AND status IN ('ACTIVE', 'INVITED'))",
                Boolean.class, command.tenantId(), command.principalRef()));
    }

    private void requireDecision(
            Long tenantId, UUID assignmentId, Long actorId,
            long version, String reason) {
        if (tenantId == null || tenantId <= 0 || assignmentId == null
                || actorId == null || actorId <= 0 || version < 0
                || reason == null || reason.trim().length() < 10) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private BaseException integrity(DataIntegrityViolationException exception) {
        String message = Objects.toString(exception.getMostSpecificCause().getMessage(), "");
        return message.contains("Scoped duty separation-of-duties conflict")
                ? new BaseException(ErrorCode.SOD_CONFLICT,
                        "Scoped duty overlaps a conflicting duty assignment.", exception)
                : new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "Scoped duty assignment conflicts with current state.", exception);
    }

    private BaseException versionConflict() {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    public record Request(
            Long tenantId, String principalType, String principalRef,
            String dutyCode, UUID resourceSetId, UUID responsibilityAssignmentId,
            UUID appPresetAssignmentId,
            String assignmentSource, OffsetDateTime validFrom, OffsetDateTime validTo,
            OffsetDateTime reviewDueAt, String justification, Long requestedBy) {

        public Request(
                Long tenantId, String principalType, String principalRef,
                String dutyCode, UUID resourceSetId, UUID responsibilityAssignmentId,
                String assignmentSource, OffsetDateTime validFrom, OffsetDateTime validTo,
                OffsetDateTime reviewDueAt, String justification, Long requestedBy) {
            this(tenantId, principalType, principalRef, dutyCode, resourceSetId,
                    responsibilityAssignmentId, null, assignmentSource, validFrom,
                    validTo, reviewDueAt, justification, requestedBy);
        }
    }

    public record Assignment(
            UUID assignmentId, Long tenantId, String principalType, String principalRef,
            String dutyCode, UUID resourceSetId, UUID responsibilityAssignmentId,
            UUID appPresetAssignmentId,
            String assignmentSource, String lifecycleState,
            OffsetDateTime validFrom, OffsetDateTime validTo,
            OffsetDateTime reviewDueAt, String justification,
            Long requestedBy, Long approvedBy, OffsetDateTime approvedAt,
            String decisionReason, Long revokedBy, OffsetDateTime revokedAt,
            String revocationReason, long version) {
    }
}
