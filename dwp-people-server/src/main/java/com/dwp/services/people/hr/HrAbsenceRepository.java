package com.dwp.services.people.hr;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class HrAbsenceRepository {

    private final JdbcTemplate jdbc;

    HrAbsenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<HrDtos.LeaveBalance> leaveBalances(
            Long tenantId, long workerId, LocalDate asOf) {
        return jdbc.query("""
                SELECT plan.public_id, plan.plan_key, plan.name,
                       balance.granted_minutes, balance.used_minutes,
                       balance.pending_minutes,
                       balance.granted_minutes + balance.adjustment_minutes
                         - balance.used_minutes - balance.pending_minutes AS available_minutes,
                       balance.as_of_date, balance.data_origin
                  FROM abs_leave_balances balance
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = balance.tenant_id
                   AND plan.leave_plan_id = balance.leave_plan_id
                 WHERE balance.tenant_id = ? AND balance.worker_id = ?
                   AND balance.balance_year = EXTRACT(YEAR FROM ?::DATE)::INTEGER
                 ORDER BY plan.name
                """, (result, ignored) -> new HrDtos.LeaveBalance(
                result.getObject("public_id", UUID.class), result.getString("plan_key"),
                result.getString("name"), result.getInt("granted_minutes"),
                result.getInt("used_minutes"), result.getInt("pending_minutes"),
                result.getInt("available_minutes"),
                result.getObject("as_of_date", LocalDate.class), result.getString("data_origin")),
                tenantId, workerId, asOf);
    }

    List<HrDtos.LeaveRequest> leaveRequests(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT request.public_id, plan.public_id AS plan_public_id, plan.name,
                       request.start_at, request.end_at, request.requested_minutes,
                       request.status, request.reason, request.submitted_at,
                       request.decision_note, request.cancelled_at,
                       request.cancellation_note, request.version
                  FROM abs_leave_requests request
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = request.tenant_id
                   AND plan.leave_plan_id = request.leave_plan_id
                 WHERE request.tenant_id = ? AND request.worker_id = ?
                 ORDER BY request.start_at DESC
                 LIMIT 50
                """, (result, ignored) -> new HrDtos.LeaveRequest(
                result.getObject("public_id", UUID.class),
                result.getObject("plan_public_id", UUID.class), result.getString("name"),
                instant(result.getTimestamp("start_at")), instant(result.getTimestamp("end_at")),
                result.getInt("requested_minutes"), result.getString("status"),
                result.getString("reason"), instant(result.getTimestamp("submitted_at")),
                result.getString("decision_note"), instant(result.getTimestamp("cancelled_at")),
                result.getString("cancellation_note"), result.getLong("version")),
                tenantId, workerId);
    }

    boolean hasOverlappingLeaveRequest(
            Long tenantId, long workerId, Instant startAt, Instant endAt) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM abs_leave_requests request
                 WHERE request.tenant_id = ?
                   AND request.worker_id = ?
                   AND request.status IN ('SUBMITTED', 'APPROVED')
                   AND tstzrange(request.start_at, request.end_at, '[)')
                       && tstzrange(?, ?, '[)')
                """, Integer.class, tenantId, workerId,
                Timestamp.from(startAt), Timestamp.from(endAt));
        return count != null && count > 0;
    }

    Optional<HrDtos.LeaveRequest> createLeaveRequest(
            Long tenantId, long workerId, HrDtos.CreateLeaveRequest request, Long actorId) {
        return jdbc.query("""
                WITH eligible_plan AS (
                    SELECT plan.leave_plan_id
                      FROM abs_leave_plans plan
                      JOIN abs_leave_balances balance
                        ON balance.tenant_id = plan.tenant_id
                       AND balance.leave_plan_id = plan.leave_plan_id
                       AND balance.worker_id = ?
                       AND balance.balance_year = EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER
                     WHERE plan.tenant_id = ? AND plan.public_id = ?
                       AND plan.lifecycle_state = 'ACTIVE'
                       AND (plan.negative_balance_allowed
                            OR balance.granted_minutes + balance.adjustment_minutes
                               - balance.used_minutes - balance.pending_minutes >= ?)
                       FOR UPDATE OF balance
                )
                INSERT INTO abs_leave_requests (
                    tenant_id, worker_id, leave_plan_id, start_at, end_at,
                    requested_minutes, reason, status, submitted_at,
                    created_by, updated_by)
                SELECT ?, ?, eligible_plan.leave_plan_id, ?, ?, ?, ?, 'SUBMITTED',
                       CURRENT_TIMESTAMP, ?, ?
                  FROM eligible_plan
                RETURNING public_id
                """, (result, ignored) -> result.getObject("public_id", UUID.class),
                workerId, tenantId, request.planId(), request.requestedMinutes(),
                tenantId, workerId, Timestamp.from(request.startAt()), Timestamp.from(request.endAt()),
                request.requestedMinutes(), request.reason(), actorId, actorId)
                .stream().findFirst()
                .flatMap(publicId -> {
                    jdbc.update("""
                            UPDATE abs_leave_balances balance
                               SET pending_minutes = pending_minutes + ?,
                                   as_of_date = CURRENT_DATE,
                                   version = version + 1,
                                   updated_at = CURRENT_TIMESTAMP, updated_by = ?
                              FROM abs_leave_plans plan
                             WHERE balance.tenant_id = ? AND balance.worker_id = ?
                               AND plan.tenant_id = balance.tenant_id
                               AND plan.leave_plan_id = balance.leave_plan_id
                               AND plan.public_id = ?
                               AND balance.balance_year = EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER
                            """, request.requestedMinutes(), actorId, tenantId, workerId, request.planId());
                    return leaveRequest(tenantId, publicId);
                });
    }

    Optional<HrRepository.LeaveRequestTarget> leaveRequestTarget(
            Long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT request.leave_request_id, request.worker_id, request.leave_plan_id,
                       request.requested_minutes, request.status, request.version,
                       person.public_id, person.display_name, assignment.business_title,
                       plan.name AS plan_name
                  FROM abs_leave_requests request
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = request.tenant_id
                   AND plan.leave_plan_id = request.leave_plan_id
                  JOIN ppl_workers worker
                    ON worker.tenant_id = request.tenant_id AND worker.worker_id = request.worker_id
                  JOIN ppl_persons person
                    ON person.tenant_id = worker.tenant_id AND person.person_id = worker.person_id
                  LEFT JOIN LATERAL (
                      SELECT candidate.business_title
                        FROM ppl_work_relationships relationship
                        JOIN ppl_assignments candidate
                          ON candidate.tenant_id = relationship.tenant_id
                         AND candidate.work_relationship_id = relationship.work_relationship_id
                       WHERE relationship.tenant_id = worker.tenant_id
                         AND relationship.worker_id = worker.worker_id
                         AND candidate.assignment_status = 'ACTIVE'
                       ORDER BY candidate.primary_assignment DESC, candidate.effective_start_date DESC
                       LIMIT 1
                  ) assignment ON TRUE
                 WHERE request.tenant_id = ? AND request.public_id = ?
                """, (result, ignored) -> new HrRepository.LeaveRequestTarget(
                result.getLong("leave_request_id"), result.getLong("worker_id"),
                result.getLong("leave_plan_id"), result.getInt("requested_minutes"),
                result.getString("status"), result.getLong("version"),
                result.getObject("public_id", UUID.class), result.getString("display_name"),
                result.getString("business_title"), result.getString("plan_name")),
                tenantId, requestId).stream().findFirst();
    }

    boolean decideLeaveRequest(
            Long tenantId, UUID requestId, HrRepository.LeaveRequestTarget target,
            String status, String note, Long actorId) {
        int changed = jdbc.update("""
                UPDATE abs_leave_requests
                   SET status = ?, decision_note = ?, decided_at = CURRENT_TIMESTAMP,
                       decided_by = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND public_id = ?
                   AND status = 'SUBMITTED' AND version = ?
                """, status, note, actorId, actorId, tenantId, requestId, target.version());
        if (changed == 1) {
            jdbc.update("""
                    UPDATE abs_leave_balances
                       SET pending_minutes = GREATEST(0, pending_minutes - ?),
                           used_minutes = used_minutes + CASE WHEN ? = 'APPROVED' THEN ? ELSE 0 END,
                           as_of_date = CURRENT_DATE, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND worker_id = ? AND leave_plan_id = ?
                       AND balance_year = EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER
                    """, target.requestedMinutes(), status, target.requestedMinutes(), actorId,
                    tenantId, target.workerId(), target.leavePlanId());
        }
        return changed == 1;
    }

    boolean withdrawLeaveRequest(
            Long tenantId, UUID requestId, HrRepository.LeaveRequestTarget target,
            String note, Long actorId) {
        int changed = jdbc.update("""
                UPDATE abs_leave_requests
                   SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP,
                       cancelled_by = ?, cancellation_note = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND public_id = ? AND worker_id = ?
                   AND leave_plan_id = ? AND status = 'SUBMITTED' AND version = ?
                """, actorId, note, actorId, tenantId, requestId, target.workerId(),
                target.leavePlanId(), target.version());
        if (changed == 1) {
            jdbc.update("""
                    UPDATE abs_leave_balances
                       SET pending_minutes = GREATEST(0, pending_minutes - ?),
                           as_of_date = CURRENT_DATE, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND worker_id = ? AND leave_plan_id = ?
                       AND balance_year = EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER
                    """, target.requestedMinutes(), actorId, tenantId,
                    target.workerId(), target.leavePlanId());
        }
        return changed == 1;
    }

    private Optional<HrDtos.LeaveRequest> leaveRequest(Long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT request.public_id, plan.public_id AS plan_public_id, plan.name,
                       request.start_at, request.end_at, request.requested_minutes,
                       request.status, request.reason, request.submitted_at,
                       request.decision_note, request.cancelled_at,
                       request.cancellation_note, request.version
                  FROM abs_leave_requests request
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = request.tenant_id
                   AND plan.leave_plan_id = request.leave_plan_id
                 WHERE request.tenant_id = ? AND request.public_id = ?
                """, (result, ignored) -> new HrDtos.LeaveRequest(
                result.getObject("public_id", UUID.class),
                result.getObject("plan_public_id", UUID.class), result.getString("name"),
                instant(result.getTimestamp("start_at")), instant(result.getTimestamp("end_at")),
                result.getInt("requested_minutes"), result.getString("status"),
                result.getString("reason"), instant(result.getTimestamp("submitted_at")),
                result.getString("decision_note"), instant(result.getTimestamp("cancelled_at")),
                result.getString("cancellation_note"), result.getLong("version")),
                tenantId, requestId).stream().findFirst();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
