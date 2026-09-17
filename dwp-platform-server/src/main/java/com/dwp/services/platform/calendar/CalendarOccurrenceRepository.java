package com.dwp.services.platform.calendar;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.EventImportance;
import static com.dwp.services.platform.calendar.CalendarTypes.EventType;
import static com.dwp.services.platform.calendar.CalendarTypes.EventVisibility;

@Repository
class CalendarOccurrenceRepository {

    private final JdbcTemplate jdbc;

    CalendarOccurrenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<OverrideRow> overrides(
            long tenantId,
            List<UUID> eventIds,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit) {
        if (eventIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT event_id, original_starts_at, override_kind, starts_at, ends_at,
                       title, description, event_type, all_day, location, conference_url,
                       visibility, response_required, importance, version
                  FROM cal_event_occurrence_overrides
                 WHERE tenant_id = ?
                   AND event_id = ANY(?::uuid[])
                   AND (
                       (original_starts_at >= ?::timestamptz - INTERVAL '1 day'
                        AND original_starts_at < ?)
                       OR (override_kind = 'MODIFIED' AND starts_at < ? AND ends_at > ?)
                   )
                 ORDER BY event_id, original_starts_at
                 LIMIT ?
                """, (row, ignored) -> override(row), tenantId,
                eventIds.toArray(UUID[]::new), from, to, to, from, limit);
    }

    Optional<OverrideRow> override(long tenantId, UUID eventId, OffsetDateTime originalStartsAt) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT event_id, original_starts_at, override_kind, starts_at, ends_at,
                           title, description, event_type, all_day, location, conference_url,
                           visibility, response_required, importance, version
                      FROM cal_event_occurrence_overrides
                     WHERE tenant_id = ? AND event_id = ? AND original_starts_at = ?
                    """, (row, ignored) -> override(row), tenantId, eventId, originalStartsAt));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    void lockCommand(long tenantId, long actorId, UUID idempotencyKey) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(
                        1,
                        "calendar-occurrence:" + tenantId + ":" + actorId + ":"
                                + idempotencyKey),
                result -> null);
    }

    Optional<CommandReceipt> receipt(long tenantId, long actorId, UUID idempotencyKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT event_id, original_starts_at, request_fingerprint,
                           resulting_event_version, resulting_override_version
                      FROM cal_occurrence_command_receipts
                     WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                    """, (row, ignored) -> new CommandReceipt(
                    row.getObject("event_id", UUID.class),
                    row.getObject("original_starts_at", OffsetDateTime.class),
                    row.getString("request_fingerprint"),
                    row.getLong("resulting_event_version"),
                    row.getLong("resulting_override_version")),
                    tenantId, actorId, idempotencyKey));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    int advanceEventVersion(long tenantId, UUID eventId, long expectedVersion, long actorId) {
        return jdbc.update("""
                UPDATE cal_events
                   SET version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND event_id = ? AND version = ?
                   AND deleted_at IS NULL AND status <> 'CANCELLED'
                """, actorId, tenantId, eventId, expectedVersion);
    }

    long upsertModified(
            long tenantId,
            long actorId,
            UUID eventId,
            OffsetDateTime originalStartsAt,
            CalendarDtos.UpdateEventRequest request) {
        Long version = jdbc.queryForObject("""
                INSERT INTO cal_event_occurrence_overrides (
                    tenant_id, event_id, original_starts_at, override_kind,
                    starts_at, ends_at, title, description, event_type, all_day,
                    location, conference_url, visibility, response_required,
                    importance, created_by, updated_by)
                VALUES (?, ?, ?, 'MODIFIED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, event_id, original_starts_at) DO UPDATE SET
                    override_kind = 'MODIFIED', starts_at = EXCLUDED.starts_at,
                    ends_at = EXCLUDED.ends_at, title = EXCLUDED.title,
                    description = EXCLUDED.description, event_type = EXCLUDED.event_type,
                    all_day = EXCLUDED.all_day, location = EXCLUDED.location,
                    conference_url = EXCLUDED.conference_url,
                    visibility = EXCLUDED.visibility,
                    response_required = EXCLUDED.response_required,
                    importance = EXCLUDED.importance,
                    version = cal_event_occurrence_overrides.version + 1,
                    updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by
                RETURNING version
                """, Long.class,
                tenantId, eventId, originalStartsAt,
                request.startsAt(), request.endsAt(), request.title(), request.description(),
                request.type().name(), request.allDay(), request.location(),
                request.conferenceUrl(), request.visibility().name(), request.responseRequired(),
                (request.importance() == null ? EventImportance.NORMAL : request.importance()).name(),
                actorId, actorId);
        return version == null ? 0 : version;
    }

    int deleteOverrides(long tenantId, UUID eventId) {
        return jdbc.update("""
                DELETE FROM cal_event_occurrence_overrides
                 WHERE tenant_id = ? AND event_id = ?
                """, tenantId, eventId);
    }

    void saveReceipt(
            long tenantId,
            long actorId,
            UUID idempotencyKey,
            UUID eventId,
            OffsetDateTime originalStartsAt,
            String fingerprint,
            long eventVersion,
            long overrideVersion) {
        jdbc.update("""
                INSERT INTO cal_occurrence_command_receipts (
                    tenant_id, actor_user_id, idempotency_key, event_id,
                    original_starts_at, request_fingerprint,
                    resulting_event_version, resulting_override_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, actorId, idempotencyKey, eventId, originalStartsAt,
                fingerprint, eventVersion, overrideVersion);
    }

    private OverrideRow override(java.sql.ResultSet row) throws java.sql.SQLException {
        String eventType = row.getString("event_type");
        String visibility = row.getString("visibility");
        String importance = row.getString("importance");
        Boolean allDay = row.getObject("all_day", Boolean.class);
        Boolean responseRequired = row.getObject("response_required", Boolean.class);
        return new OverrideRow(
                row.getObject("event_id", UUID.class),
                row.getObject("original_starts_at", OffsetDateTime.class),
                row.getString("override_kind"),
                row.getObject("starts_at", OffsetDateTime.class),
                row.getObject("ends_at", OffsetDateTime.class),
                row.getString("title"),
                row.getString("description"),
                eventType == null ? null : EventType.valueOf(eventType),
                allDay,
                row.getString("location"),
                row.getString("conference_url"),
                visibility == null ? null : EventVisibility.valueOf(visibility),
                responseRequired,
                importance == null ? null : EventImportance.valueOf(importance),
                row.getLong("version"));
    }

    record OverrideRow(
            UUID eventId,
            OffsetDateTime originalStartsAt,
            String kind,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String title,
            String description,
            EventType type,
            Boolean allDay,
            String location,
            String conferenceUrl,
            EventVisibility visibility,
            Boolean responseRequired,
            EventImportance importance,
            long version) {
    }

    record CommandReceipt(
            UUID eventId,
            OffsetDateTime originalStartsAt,
            String fingerprint,
            long eventVersion,
            long overrideVersion) {
    }
}
