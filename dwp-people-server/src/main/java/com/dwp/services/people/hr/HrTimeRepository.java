package com.dwp.services.people.hr;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class HrTimeRepository {

    private final JdbcTemplate jdbc;

    HrTimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<HrRepository.WorkerSchedule> workerSchedule(
            Long tenantId, long workerId, LocalDate asOf) {
        return jdbc.query("""
                SELECT profile.time_zone,
                       COALESCE((
                           SELECT ROUND(AVG((day.value #>> '{}')::NUMERIC))::INTEGER
                             FROM jsonb_each(profile.daily_pattern) day
                            WHERE jsonb_typeof(day.value) = 'number'
                              AND (day.value #>> '{}')::INTEGER > 0
                       ), NULLIF(profile.weekly_minutes / 5, 0)) AS standard_day_minutes,
                       assignment.data_origin
                  FROM tme_worker_schedule_assignments assignment
                  JOIN tme_work_schedule_profiles profile
                    ON profile.tenant_id = assignment.tenant_id
                   AND profile.schedule_profile_id = assignment.schedule_profile_id
                 WHERE assignment.tenant_id = ? AND assignment.worker_id = ?
                   AND assignment.effective_start_date <= ?
                   AND (assignment.effective_end_date IS NULL OR assignment.effective_end_date >= ?)
                   AND profile.lifecycle_state = 'ACTIVE'
                 ORDER BY assignment.effective_start_date DESC
                 LIMIT 1
                """, (result, ignored) -> new HrRepository.WorkerSchedule(
                result.getString("time_zone"),
                result.getObject("standard_day_minutes", Integer.class),
                result.getString("data_origin")), tenantId, workerId, asOf, asOf)
                .stream().findFirst();
    }

    HrDtos.TimeCard currentTimeCard(Long tenantId, long workerId, LocalDate asOf) {
        return jdbc.query("""
                SELECT public_id, period_start_date, period_end_date, status,
                       scheduled_minutes, recorded_minutes, exception_count,
                       data_origin, version
                  FROM tme_time_cards
                 WHERE tenant_id = ? AND worker_id = ?
                   AND period_start_date <= ? AND period_end_date >= ?
                 ORDER BY period_start_date DESC
                 LIMIT 1
                """, (result, ignored) -> new HrDtos.TimeCard(
                result.getObject("public_id", UUID.class),
                result.getObject("period_start_date", LocalDate.class),
                result.getObject("period_end_date", LocalDate.class),
                result.getString("status"), result.getInt("scheduled_minutes"),
                result.getInt("recorded_minutes"), result.getInt("exception_count"),
                result.getString("data_origin"), result.getLong("version")),
                tenantId, workerId, asOf, asOf).stream().findFirst().orElse(null);
    }

    List<HrDtos.TimeEntry> timeEntries(Long tenantId, long workerId, UUID cardId) {
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

    List<HrDtos.TimeException> timeExceptions(Long tenantId, long workerId, UUID cardId) {
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

    boolean upsertTimeEntry(
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

    boolean submitTimeCard(
            Long tenantId, long workerId, UUID cardId, long version, Long actorId) {
        return jdbc.update("""
                UPDATE tme_time_cards
                   SET status = 'SUBMITTED', submitted_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND worker_id = ? AND public_id = ?
                   AND status = 'OPEN' AND version = ?
                   AND recorded_minutes > 0 AND exception_count = 0
                """, actorId, tenantId, workerId, cardId, version) == 1;
    }

    Optional<HrRepository.TimeCardTarget> timeCardTarget(Long tenantId, UUID cardId) {
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
                """, (result, ignored) -> new HrRepository.TimeCardTarget(
                result.getLong("time_card_id"), result.getLong("worker_id"),
                result.getString("status"), result.getLong("version"),
                result.getObject("public_id", UUID.class), result.getString("display_name"),
                result.getString("business_title"), result.getInt("recorded_minutes")),
                tenantId, cardId).stream().findFirst();
    }

    boolean decideTimeCard(
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
}
