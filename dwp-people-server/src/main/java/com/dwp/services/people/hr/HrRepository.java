package com.dwp.services.people.hr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class HrRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public HrRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<WorkerIdentity> worker(Long tenantId, UUID personPublicId) {
        if (personPublicId == null) return Optional.empty();
        return jdbc.query("""
                SELECT worker.worker_id, person.public_id, person.display_name,
                       assignment.assignment_key, assignment.business_title,
                       organization.name AS organization_name,
                       manager_person.public_id AS manager_person_id,
                       manager_person.display_name AS manager_display_name,
                       (SELECT COUNT(*)
                          FROM ppl_assignments report_assignment
                          JOIN ppl_work_relationships report_relationship
                            ON report_relationship.tenant_id = report_assignment.tenant_id
                           AND report_relationship.work_relationship_id = report_assignment.work_relationship_id
                          JOIN ppl_workers report_worker
                            ON report_worker.tenant_id = report_relationship.tenant_id
                           AND report_worker.worker_id = report_relationship.worker_id
                         WHERE report_assignment.tenant_id = assignment.tenant_id
                           AND report_assignment.manager_assignment_key = assignment.assignment_key
                           AND report_assignment.assignment_status = 'ACTIVE'
                           AND report_worker.worker_status IN ('ACTIVE', 'LEAVE')) AS direct_reports
                  FROM ppl_persons person
                  JOIN ppl_workers worker
                    ON worker.tenant_id = person.tenant_id
                   AND worker.person_id = person.person_id
                  LEFT JOIN LATERAL (
                      SELECT candidate.*
                        FROM ppl_work_relationships relationship
                        JOIN ppl_assignments candidate
                          ON candidate.tenant_id = relationship.tenant_id
                         AND candidate.work_relationship_id = relationship.work_relationship_id
                       WHERE relationship.tenant_id = worker.tenant_id
                         AND relationship.worker_id = worker.worker_id
                         AND candidate.assignment_status = 'ACTIVE'
                         AND candidate.effective_start_date <= CURRENT_DATE
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= CURRENT_DATE)
                       ORDER BY candidate.primary_assignment DESC,
                                candidate.effective_start_date DESC,
                                candidate.effective_sequence DESC
                       LIMIT 1
                  ) assignment ON TRUE
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id = assignment.tenant_id
                   AND organization.organization_id = assignment.organization_id
                  LEFT JOIN ppl_assignments manager_assignment
                    ON manager_assignment.tenant_id = assignment.tenant_id
                   AND manager_assignment.assignment_key = assignment.manager_assignment_key
                   AND manager_assignment.assignment_status = 'ACTIVE'
                   AND manager_assignment.effective_start_date <= CURRENT_DATE
                   AND (manager_assignment.effective_end_date IS NULL
                        OR manager_assignment.effective_end_date >= CURRENT_DATE)
                  LEFT JOIN ppl_work_relationships manager_relationship
                    ON manager_relationship.tenant_id = manager_assignment.tenant_id
                   AND manager_relationship.work_relationship_id = manager_assignment.work_relationship_id
                  LEFT JOIN ppl_workers manager_worker
                    ON manager_worker.tenant_id = manager_relationship.tenant_id
                   AND manager_worker.worker_id = manager_relationship.worker_id
                  LEFT JOIN ppl_persons manager_person
                    ON manager_person.tenant_id = manager_worker.tenant_id
                   AND manager_person.person_id = manager_worker.person_id
                 WHERE person.tenant_id = ?
                   AND person.public_id = ?
                   AND person.lifecycle_state = 'ACTIVE'
                   AND worker.worker_status IN ('ACTIVE', 'LEAVE')
                 LIMIT 1
                """, (result, ignored) -> new WorkerIdentity(
                result.getLong("worker_id"),
                result.getObject("public_id", UUID.class),
                result.getString("display_name"),
                result.getString("assignment_key"),
                result.getString("business_title"),
                result.getString("organization_name"),
                result.getObject("manager_person_id", UUID.class),
                result.getString("manager_display_name"),
                result.getInt("direct_reports")), tenantId, personPublicId).stream().findFirst();
    }

    public boolean manages(Long tenantId, String managerAssignmentKey, long workerId) {
        if (managerAssignmentKey == null) return false;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM ppl_assignments assignment
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id = assignment.tenant_id
                   AND relationship.work_relationship_id = assignment.work_relationship_id
                 WHERE assignment.tenant_id = ?
                   AND relationship.worker_id = ?
                   AND assignment.manager_assignment_key = ?
                   AND assignment.assignment_status = 'ACTIVE'
                   AND assignment.effective_start_date <= CURRENT_DATE
                   AND (assignment.effective_end_date IS NULL
                        OR assignment.effective_end_date >= CURRENT_DATE)
                """, Integer.class, tenantId, workerId, managerAssignmentKey);
        return count != null && count > 0;
    }

    public HrDtos.TimeCard currentTimeCard(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT public_id, period_start_date, period_end_date, status,
                       scheduled_minutes, recorded_minutes, exception_count,
                       data_origin, version
                  FROM tme_time_cards
                 WHERE tenant_id = ? AND worker_id = ?
                 ORDER BY period_start_date DESC
                 LIMIT 1
                """, (result, ignored) -> new HrDtos.TimeCard(
                result.getObject("public_id", UUID.class),
                result.getObject("period_start_date", LocalDate.class),
                result.getObject("period_end_date", LocalDate.class),
                result.getString("status"), result.getInt("scheduled_minutes"),
                result.getInt("recorded_minutes"), result.getInt("exception_count"),
                result.getString("data_origin"), result.getLong("version")),
                tenantId, workerId).stream().findFirst().orElse(null);
    }

    public List<HrDtos.TimeEntry> timeEntries(Long tenantId, long workerId, UUID cardId) {
        if (cardId == null) return List.of();
        return jdbc.query("""
                SELECT entry.public_id, entry.work_date, entry.entry_type,
                       entry.minutes, entry.work_mode, entry.note, entry.version
                  FROM tme_time_entries entry
                  JOIN tme_time_cards card
                    ON card.tenant_id = entry.tenant_id
                   AND card.time_card_id = entry.time_card_id
                 WHERE entry.tenant_id = ? AND entry.worker_id = ?
                   AND card.public_id = ? AND entry.lifecycle_state = 'ACTIVE'
                 ORDER BY entry.work_date, entry.created_at
                """, (result, ignored) -> new HrDtos.TimeEntry(
                result.getObject("public_id", UUID.class),
                result.getObject("work_date", LocalDate.class),
                result.getString("entry_type"), result.getInt("minutes"),
                result.getString("work_mode"), result.getString("note"),
                result.getLong("version")), tenantId, workerId, cardId);
    }

    public List<HrDtos.TimeException> timeExceptions(Long tenantId, long workerId, UUID cardId) {
        if (cardId == null) return List.of();
        return jdbc.query("""
                SELECT exception.public_id, exception.exception_code, exception.severity,
                       exception.occurred_on, exception.message,
                       exception.lifecycle_state, exception.resolution_note
                  FROM tme_time_exceptions exception
                  JOIN tme_time_cards card
                    ON card.tenant_id = exception.tenant_id
                   AND card.time_card_id = exception.time_card_id
                 WHERE exception.tenant_id = ? AND exception.worker_id = ?
                   AND card.public_id = ?
                 ORDER BY CASE exception.lifecycle_state WHEN 'OPEN' THEN 0 ELSE 1 END,
                          CASE exception.severity
                              WHEN 'BLOCKING' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
                          exception.occurred_on DESC, exception.created_at DESC
                """, (result, ignored) -> new HrDtos.TimeException(
                result.getObject("public_id", UUID.class), result.getString("exception_code"),
                result.getString("severity"), result.getObject("occurred_on", LocalDate.class),
                result.getString("message"), result.getString("lifecycle_state"),
                result.getString("resolution_note")), tenantId, workerId, cardId);
    }

    public boolean upsertTimeEntry(
            Long tenantId, long workerId, UUID cardId, LocalDate workDate,
            HrDtos.UpsertTimeEntryRequest request, Long actorId) {
        int changed = jdbc.update("""
                INSERT INTO tme_time_entries (
                    tenant_id, time_card_id, worker_id, work_date, entry_type,
                    minutes, work_mode, note, source_reference, created_by, updated_by)
                SELECT ?, card.time_card_id, ?, ?, 'WORK', ?, ?, ?,
                       'self-service', ?, ?
                  FROM tme_time_cards card
                 WHERE card.tenant_id = ? AND card.worker_id = ?
                   AND card.public_id = ? AND card.status = 'OPEN'
                   AND card.version = ?
                   AND ? BETWEEN card.period_start_date AND card.period_end_date
                ON CONFLICT (tenant_id, time_card_id, work_date, entry_type)
                    WHERE lifecycle_state = 'ACTIVE'
                DO UPDATE SET
                    minutes = EXCLUDED.minutes,
                    work_mode = EXCLUDED.work_mode,
                    note = EXCLUDED.note,
                    source_reference = EXCLUDED.source_reference,
                    version = tme_time_entries.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """, tenantId, workerId, workDate, request.minutes(), request.workMode(),
                request.note(), actorId, actorId, tenantId, workerId, cardId,
                request.cardVersion(), workDate);
        if (changed > 0) refreshTimeCardTotals(tenantId, cardId, actorId);
        return changed > 0;
    }

    public boolean submitTimeCard(Long tenantId, long workerId, UUID cardId, long version, Long actorId) {
        return jdbc.update("""
                UPDATE tme_time_cards
                   SET status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND worker_id = ? AND public_id = ?
                   AND status = 'OPEN' AND version = ?
                   AND recorded_minutes > 0 AND exception_count = 0
                """, actorId, tenantId, workerId, cardId, version) == 1;
    }

    public Optional<TimeCardTarget> timeCardTarget(Long tenantId, UUID cardId) {
        return jdbc.query("""
                SELECT card.time_card_id, card.worker_id, card.status, card.version,
                       person.public_id, person.display_name,
                       assignment.business_title, card.recorded_minutes
                  FROM tme_time_cards card
                  JOIN ppl_workers worker
                    ON worker.tenant_id = card.tenant_id AND worker.worker_id = card.worker_id
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
                 WHERE card.tenant_id = ? AND card.public_id = ?
                """, (result, ignored) -> new TimeCardTarget(
                result.getLong("time_card_id"), result.getLong("worker_id"),
                result.getString("status"), result.getLong("version"),
                result.getObject("public_id", UUID.class), result.getString("display_name"),
                result.getString("business_title"), result.getInt("recorded_minutes")),
                tenantId, cardId).stream().findFirst();
    }

    public boolean decideTimeCard(
            Long tenantId, UUID cardId, String status, String note, long version, Long actorId) {
        return jdbc.update("""
                UPDATE tme_time_cards
                   SET status = ?, decision_note = ?, decided_at = CURRENT_TIMESTAMP,
                       decided_by = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND public_id = ?
                   AND status = 'SUBMITTED' AND version = ?
                """, status, note, actorId, actorId, tenantId, cardId, version) == 1;
    }

    public List<HrDtos.LeaveBalance> leaveBalances(Long tenantId, long workerId) {
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
                   AND balance.balance_year = EXTRACT(YEAR FROM CURRENT_DATE)::INTEGER
                 ORDER BY plan.name
                """, (result, ignored) -> new HrDtos.LeaveBalance(
                result.getObject("public_id", UUID.class), result.getString("plan_key"),
                result.getString("name"), result.getInt("granted_minutes"),
                result.getInt("used_minutes"), result.getInt("pending_minutes"),
                result.getInt("available_minutes"),
                result.getObject("as_of_date", LocalDate.class), result.getString("data_origin")),
                tenantId, workerId);
    }

    public List<HrDtos.LeaveRequest> leaveRequests(Long tenantId, long workerId) {
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

    public boolean hasOverlappingLeaveRequest(
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

    public Optional<HrDtos.LeaveRequest> createLeaveRequest(
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

    public Optional<LeaveRequestTarget> leaveRequestTarget(Long tenantId, UUID requestId) {
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
                """, (result, ignored) -> new LeaveRequestTarget(
                result.getLong("leave_request_id"), result.getLong("worker_id"),
                result.getLong("leave_plan_id"), result.getInt("requested_minutes"),
                result.getString("status"), result.getLong("version"),
                result.getObject("public_id", UUID.class), result.getString("display_name"),
                result.getString("business_title"), result.getString("plan_name")),
                tenantId, requestId).stream().findFirst();
    }

    public boolean decideLeaveRequest(
            Long tenantId, UUID requestId, LeaveRequestTarget target,
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

    public boolean withdrawLeaveRequest(
            Long tenantId, UUID requestId, LeaveRequestTarget target,
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

    public List<HrDtos.BenefitPlan> benefitPlans(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT plan.public_id, plan.plan_type, plan.name, plan.provider_name,
                       enrollment.coverage_level, enrollment.status,
                       enrollment.effective_start_date, enrollment.effective_end_date
                  FROM bnf_enrollments enrollment
                  JOIN bnf_benefit_plans plan
                    ON plan.tenant_id = enrollment.tenant_id
                   AND plan.benefit_plan_id = enrollment.benefit_plan_id
                 WHERE enrollment.tenant_id = ? AND enrollment.worker_id = ?
                   AND enrollment.status IN ('ELECTED', 'ACTIVE', 'WAIVED')
                 ORDER BY plan.plan_type, plan.name
                """, (result, ignored) -> new HrDtos.BenefitPlan(
                result.getObject("public_id", UUID.class), result.getString("plan_type"),
                result.getString("name"), result.getString("provider_name"),
                result.getString("coverage_level"), result.getString("status"),
                result.getObject("effective_start_date", LocalDate.class),
                result.getObject("effective_end_date", LocalDate.class)), tenantId, workerId);
    }

    public List<HrDtos.EnrollmentWindow> enrollmentWindows(Long tenantId) {
        return jdbc.query("""
                SELECT public_id, name, window_type, opens_at, closes_at, lifecycle_state
                  FROM bnf_enrollment_windows
                 WHERE tenant_id = ? AND lifecycle_state IN ('SCHEDULED', 'OPEN')
                 ORDER BY opens_at
                """, (result, ignored) -> new HrDtos.EnrollmentWindow(
                result.getObject("public_id", UUID.class), result.getString("name"),
                result.getString("window_type"), instant(result.getTimestamp("opens_at")),
                instant(result.getTimestamp("closes_at")), result.getString("lifecycle_state")),
                tenantId);
    }

    public HrDtos.PayCycle nextPayCycle(Long tenantId) {
        return jdbc.query("""
                SELECT public_id, name, period_start_date, period_end_date,
                       pay_date, status, readiness
                  FROM pay_pay_cycles
                 WHERE tenant_id = ? AND status NOT IN ('PAID', 'CANCELLED')
                 ORDER BY pay_date
                 LIMIT 1
                """, (result, ignored) -> {
            Map<String, Object> readiness = json(result.getString("readiness"));
            return new HrDtos.PayCycle(
                    result.getObject("public_id", UUID.class), result.getString("name"),
                    result.getObject("period_start_date", LocalDate.class),
                    result.getObject("period_end_date", LocalDate.class),
                    result.getObject("pay_date", LocalDate.class), result.getString("status"),
                    Boolean.TRUE.equals(readiness.get("timeValidated")),
                    Boolean.TRUE.equals(readiness.get("absenceValidated")),
                    Boolean.TRUE.equals(readiness.get("sourceConfirmed")));
        }, tenantId).stream().findFirst().orElse(null);
    }

    public List<HrDtos.PayStatement> payStatements(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT public_id, statement_period_label, availability_state,
                       published_at, document_reference
                  FROM pay_statement_references
                 WHERE tenant_id = ? AND worker_id = ?
                 ORDER BY created_at DESC
                 LIMIT 24
                """, (result, ignored) -> new HrDtos.PayStatement(
                result.getObject("public_id", UUID.class),
                result.getString("statement_period_label"),
                result.getString("availability_state"),
                instant(result.getTimestamp("published_at")),
                "AVAILABLE".equals(result.getString("availability_state"))
                        && !result.getString("document_reference").startsWith("reference://")),
                tenantId, workerId);
    }

    public List<HrDtos.Journey> journeys(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT instance.public_id, template.name, template.journey_type,
                       instance.progress_percent, instance.target_date, instance.status
                  FROM tal_journey_instances instance
                  JOIN tal_journey_templates template
                    ON template.tenant_id = instance.tenant_id
                   AND template.journey_template_id = instance.journey_template_id
                 WHERE instance.tenant_id = ? AND instance.worker_id = ?
                 ORDER BY instance.status, instance.target_date NULLS LAST
                """, (result, ignored) -> new HrDtos.Journey(
                result.getObject("public_id", UUID.class), result.getString("name"),
                result.getString("journey_type"), result.getInt("progress_percent"),
                result.getObject("target_date", LocalDate.class), result.getString("status")),
                tenantId, workerId);
    }

    public List<HrDtos.Goal> goals(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT public_id, title, goal_type, progress_percent,
                       due_date, status, version
                  FROM tal_goals
                 WHERE tenant_id = ? AND worker_id = ?
                   AND status <> 'CANCELLED'
                 ORDER BY due_date NULLS LAST, created_at DESC
                """, (result, ignored) -> new HrDtos.Goal(
                result.getObject("public_id", UUID.class), result.getString("title"),
                result.getString("goal_type"), result.getInt("progress_percent"),
                result.getObject("due_date", LocalDate.class), result.getString("status"),
                result.getLong("version")), tenantId, workerId);
    }

    public List<HrDtos.Learning> learning(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT public_id, title, provider_name, required,
                       progress_percent, due_date, status
                  FROM tal_learning_assignments
                 WHERE tenant_id = ? AND worker_id = ?
                   AND status NOT IN ('COMPLETED', 'WAIVED', 'EXPIRED')
                 ORDER BY required DESC, due_date NULLS LAST
                """, (result, ignored) -> new HrDtos.Learning(
                result.getObject("public_id", UUID.class), result.getString("title"),
                result.getString("provider_name"), result.getBoolean("required"),
                result.getInt("progress_percent"),
                result.getObject("due_date", LocalDate.class), result.getString("status")),
                tenantId, workerId);
    }

    public boolean updateGoal(
            Long tenantId, long workerId, UUID goalId,
            HrDtos.UpdateGoalRequest request, Long actorId) {
        return jdbc.update("""
                UPDATE tal_goals
                   SET progress_percent = ?, status = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND worker_id = ? AND public_id = ?
                   AND version = ? AND status NOT IN ('COMPLETED', 'CANCELLED')
                """, request.progressPercent(), request.status(), actorId,
                tenantId, workerId, goalId, request.version()) == 1;
    }

    public List<HrDtos.ApprovalItem> teamQueue(
            Long tenantId, String managerAssignmentKey, String domain) {
        if (managerAssignmentKey == null) return List.of();
        if ("TIME".equals(domain)) {
            return jdbc.query("""
                    SELECT card.public_id, person.public_id AS person_public_id,
                           person.display_name, assignment.business_title,
                           card.recorded_minutes || ' minutes recorded' AS summary,
                           card.status, card.submitted_at, card.version
                      FROM tme_time_cards card
                      JOIN ppl_workers worker
                        ON worker.tenant_id = card.tenant_id AND worker.worker_id = card.worker_id
                      JOIN ppl_persons person
                        ON person.tenant_id = worker.tenant_id AND person.person_id = worker.person_id
                      JOIN ppl_work_relationships relationship
                        ON relationship.tenant_id = worker.tenant_id AND relationship.worker_id = worker.worker_id
                      JOIN ppl_assignments assignment
                        ON assignment.tenant_id = relationship.tenant_id
                       AND assignment.work_relationship_id = relationship.work_relationship_id
                     WHERE card.tenant_id = ? AND card.status = 'SUBMITTED'
                       AND assignment.assignment_status = 'ACTIVE'
                       AND assignment.manager_assignment_key = ?
                     ORDER BY card.submitted_at
                    """, (result, ignored) -> approval(result, "TIME"),
                    tenantId, managerAssignmentKey);
        }
        return jdbc.query("""
                SELECT request.public_id, person.public_id AS person_public_id,
                       person.display_name, assignment.business_title,
                       plan.name || ' · ' || request.requested_minutes || ' minutes' AS summary,
                       request.status, request.submitted_at, request.version
                  FROM abs_leave_requests request
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = request.tenant_id AND plan.leave_plan_id = request.leave_plan_id
                  JOIN ppl_workers worker
                    ON worker.tenant_id = request.tenant_id AND worker.worker_id = request.worker_id
                  JOIN ppl_persons person
                    ON person.tenant_id = worker.tenant_id AND person.person_id = worker.person_id
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id = worker.tenant_id AND relationship.worker_id = worker.worker_id
                  JOIN ppl_assignments assignment
                    ON assignment.tenant_id = relationship.tenant_id
                   AND assignment.work_relationship_id = relationship.work_relationship_id
                 WHERE request.tenant_id = ? AND request.status = 'SUBMITTED'
                   AND assignment.assignment_status = 'ACTIVE'
                   AND assignment.manager_assignment_key = ?
                 ORDER BY request.submitted_at
                """, (result, ignored) -> approval(result, "ABSENCE"),
                tenantId, managerAssignmentKey);
    }

    public List<HrDtos.TeamAbsence> teamAbsences(
            Long tenantId, String managerAssignmentKey) {
        if (managerAssignmentKey == null) return List.of();
        return jdbc.query("""
                SELECT request.public_id, person.public_id AS person_public_id,
                       person.display_name, assignment.business_title,
                       plan.name AS plan_name, request.start_at, request.end_at,
                       request.status
                  FROM abs_leave_requests request
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = request.tenant_id
                   AND plan.leave_plan_id = request.leave_plan_id
                  JOIN ppl_workers worker
                    ON worker.tenant_id = request.tenant_id
                   AND worker.worker_id = request.worker_id
                  JOIN ppl_persons person
                    ON person.tenant_id = worker.tenant_id
                   AND person.person_id = worker.person_id
                  JOIN LATERAL (
                      SELECT candidate.business_title, candidate.manager_assignment_key
                        FROM ppl_work_relationships relationship
                        JOIN ppl_assignments candidate
                          ON candidate.tenant_id = relationship.tenant_id
                         AND candidate.work_relationship_id = relationship.work_relationship_id
                       WHERE relationship.tenant_id = worker.tenant_id
                         AND relationship.worker_id = worker.worker_id
                         AND relationship.start_date <= CURRENT_DATE
                         AND (relationship.end_date IS NULL
                              OR relationship.end_date >= CURRENT_DATE)
                         AND candidate.assignment_status = 'ACTIVE'
                         AND candidate.effective_start_date <= CURRENT_DATE
                         AND (candidate.effective_end_date IS NULL
                              OR candidate.effective_end_date >= CURRENT_DATE)
                       ORDER BY candidate.primary_assignment DESC,
                                candidate.effective_start_date DESC,
                                candidate.effective_sequence DESC
                       LIMIT 1
                  ) assignment ON TRUE
                 WHERE request.tenant_id = ?
                   AND assignment.manager_assignment_key = ?
                   AND request.status IN ('SUBMITTED', 'APPROVED')
                   AND request.end_at >= CURRENT_TIMESTAMP
                   AND request.start_at < CURRENT_TIMESTAMP + INTERVAL '60 days'
                 ORDER BY request.start_at, person.display_name
                 LIMIT 100
                """, (result, ignored) -> new HrDtos.TeamAbsence(
                result.getObject("public_id", UUID.class),
                result.getObject("person_public_id", UUID.class),
                result.getString("display_name"), result.getString("business_title"),
                result.getString("plan_name"), instant(result.getTimestamp("start_at")),
                instant(result.getTimestamp("end_at")), result.getString("status")),
                tenantId, managerAssignmentKey);
    }

    public List<HrDtos.ApprovalItem> submittedQueue(Long tenantId, String domain) {
        if ("TIME".equals(domain)) {
            return jdbc.query("""
                    SELECT card.public_id, person.public_id AS person_public_id,
                           person.display_name, assignment.business_title,
                           card.recorded_minutes || ' minutes recorded' AS summary,
                           card.status, card.submitted_at, card.version
                      FROM tme_time_cards card
                      JOIN ppl_workers worker
                        ON worker.tenant_id = card.tenant_id AND worker.worker_id = card.worker_id
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
                     WHERE card.tenant_id = ? AND card.status = 'SUBMITTED'
                     ORDER BY card.submitted_at
                     LIMIT 200
                    """, (result, ignored) -> approval(result, "TIME"), tenantId);
        }
        return jdbc.query("""
                SELECT request.public_id, person.public_id AS person_public_id,
                       person.display_name, assignment.business_title,
                       plan.name || ' · ' || request.requested_minutes || ' minutes' AS summary,
                       request.status, request.submitted_at, request.version
                  FROM abs_leave_requests request
                  JOIN abs_leave_plans plan
                    ON plan.tenant_id = request.tenant_id AND plan.leave_plan_id = request.leave_plan_id
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
                 WHERE request.tenant_id = ? AND request.status = 'SUBMITTED'
                 ORDER BY request.submitted_at
                 LIMIT 200
                """, (result, ignored) -> approval(result, "ABSENCE"), tenantId);
    }

    public List<HrDtos.DomainMetric> metrics(Long tenantId, String domain) {
        return switch (domain) {
            case "TIME" -> List.of(
                    metric("submitted", count("SELECT COUNT(*) FROM tme_time_cards WHERE tenant_id = ? AND status = 'SUBMITTED'", tenantId), "ATTENTION"),
                    metric("openExceptions", count("SELECT COUNT(*) FROM tme_time_exceptions WHERE tenant_id = ? AND lifecycle_state = 'OPEN'", tenantId), "CRITICAL"),
                    metric("openCards", count("SELECT COUNT(*) FROM tme_time_cards WHERE tenant_id = ? AND status = 'OPEN'", tenantId), "INFO"));
            case "ABSENCE" -> List.of(
                    metric("submitted", count("SELECT COUNT(*) FROM abs_leave_requests WHERE tenant_id = ? AND status = 'SUBMITTED'", tenantId), "ATTENTION"),
                    metric("activePlans", count("SELECT COUNT(*) FROM abs_leave_plans WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'", tenantId), "INFO"),
                    metric("activeEnrollments", count("SELECT COUNT(*) FROM abs_worker_plan_enrollments WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'", tenantId), "INFO"));
            case "BENEFITS" -> List.of(
                    metric("activePlans", count("SELECT COUNT(*) FROM bnf_benefit_plans WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'", tenantId), "INFO"),
                    metric("openWindows", count("SELECT COUNT(*) FROM bnf_enrollment_windows WHERE tenant_id = ? AND lifecycle_state = 'OPEN'", tenantId), "ATTENTION"),
                    metric("activeEnrollments", count("SELECT COUNT(*) FROM bnf_enrollments WHERE tenant_id = ? AND status = 'ACTIVE'", tenantId), "INFO"));
            case "PAY" -> List.of(
                    metric("openCycles", count("SELECT COUNT(*) FROM pay_pay_cycles WHERE tenant_id = ? AND status NOT IN ('PAID','CANCELLED')", tenantId), "ATTENTION"),
                    metric("pendingStatements", count("SELECT COUNT(*) FROM pay_statement_references WHERE tenant_id = ? AND availability_state = 'PENDING'", tenantId), "ATTENTION"),
                    metric("availableStatements", count("SELECT COUNT(*) FROM pay_statement_references WHERE tenant_id = ? AND availability_state = 'AVAILABLE'", tenantId), "INFO"));
            case "TALENT" -> List.of(
                    metric("activeJourneys", count("SELECT COUNT(*) FROM tal_journey_instances WHERE tenant_id = ? AND status = 'ACTIVE'", tenantId), "INFO"),
                    metric("atRiskGoals", count("SELECT COUNT(*) FROM tal_goals WHERE tenant_id = ? AND status = 'AT_RISK'", tenantId), "ATTENTION"),
                    metric("requiredLearning", count("SELECT COUNT(*) FROM tal_learning_assignments WHERE tenant_id = ? AND required AND status IN ('ASSIGNED','IN_PROGRESS')", tenantId), "ATTENTION"));
            default -> List.of();
        };
    }

    public long activeBenefits(Long tenantId, long workerId) {
        return count("SELECT COUNT(*) FROM bnf_enrollments WHERE tenant_id = ? AND worker_id = ? AND status = 'ACTIVE'", tenantId, workerId);
    }

    public long openBenefitWindows(Long tenantId) {
        return count("SELECT COUNT(*) FROM bnf_enrollment_windows WHERE tenant_id = ? AND lifecycle_state = 'OPEN'", tenantId);
    }

    public long activeGoals(Long tenantId, long workerId) {
        return count("SELECT COUNT(*) FROM tal_goals WHERE tenant_id = ? AND worker_id = ? AND status IN ('ACTIVE','AT_RISK')", tenantId, workerId);
    }

    public long requiredLearning(Long tenantId, long workerId) {
        return count("SELECT COUNT(*) FROM tal_learning_assignments WHERE tenant_id = ? AND worker_id = ? AND required AND status IN ('ASSIGNED','IN_PROGRESS')", tenantId, workerId);
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

    private void refreshTimeCardTotals(Long tenantId, UUID cardId, Long actorId) {
        jdbc.update("""
                UPDATE tme_time_cards card
                   SET recorded_minutes = COALESCE((
                       SELECT SUM(entry.minutes)::INTEGER
                         FROM tme_time_entries entry
                        WHERE entry.tenant_id = card.tenant_id
                          AND entry.time_card_id = card.time_card_id
                          AND entry.lifecycle_state = 'ACTIVE'), 0),
                       exception_count = COALESCE((
                       SELECT COUNT(*)::INTEGER
                         FROM tme_time_exceptions exception
                        WHERE exception.tenant_id = card.tenant_id
                          AND exception.time_card_id = card.time_card_id
                          AND exception.lifecycle_state = 'OPEN'), 0),
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE card.tenant_id = ? AND card.public_id = ?
                """, actorId, tenantId, cardId);
    }

    private HrDtos.ApprovalItem approval(java.sql.ResultSet result, String domain)
            throws java.sql.SQLException {
        return new HrDtos.ApprovalItem(
                result.getObject("public_id", UUID.class), domain,
                result.getObject("person_public_id", UUID.class),
                result.getString("display_name"), result.getString("business_title"),
                result.getString("summary"), result.getString("status"),
                instant(result.getTimestamp("submitted_at")), result.getLong("version"));
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private HrDtos.DomainMetric metric(String key, long value, String severity) {
        return new HrDtos.DomainMetric(key, value, severity);
    }

    private Map<String, Object> json(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record WorkerIdentity(
            long workerId,
            UUID personId,
            String displayName,
            String assignmentKey,
            String businessTitle,
            String organizationName,
            UUID managerPersonId,
            String managerDisplayName,
            int directReportCount) {
    }

    public record TimeCardTarget(
            long timeCardId,
            long workerId,
            String status,
            long version,
            UUID personId,
            String displayName,
            String businessTitle,
            int recordedMinutes) {
    }

    public record LeaveRequestTarget(
            long leaveRequestId,
            long workerId,
            long leavePlanId,
            int requestedMinutes,
            String status,
            long version,
            UUID personId,
            String displayName,
            String businessTitle,
            String planName) {
    }
}
