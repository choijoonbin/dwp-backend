package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

@Repository
class CompanyCalendarAdminRepository {

    private static final String COMPANY_CALENDARS = """
            SELECT calendar.calendar_id, calendar.calendar_key,
                   calendar.name_ko, calendar.name_en,
                   CASE WHEN ? THEN calendar.name_ko ELSE calendar.name_en END AS name,
                   calendar.color_hex, calendar.version,
                   COUNT(event.event_id) FILTER (
                       WHERE event.deleted_at IS NULL
                         AND event.status <> 'CANCELLED'
                         AND event.ends_at >= CURRENT_TIMESTAMP) AS upcoming_count,
                   COUNT(event.event_id) FILTER (
                       WHERE event.deleted_at IS NOT NULL) AS trashed_count
              FROM cal_calendars calendar
              LEFT JOIN cal_events event
                ON event.tenant_id = calendar.tenant_id
               AND event.calendar_id = calendar.calendar_id
             WHERE calendar.tenant_id = ?
               AND calendar.calendar_type = 'SYSTEM'
               AND calendar.lifecycle_state = 'ACTIVE'
             GROUP BY calendar.calendar_id
             ORDER BY calendar.calendar_key
            """;

    private static final String COMPANY_CALENDAR = """
            SELECT calendar_id, calendar_key, name_ko, name_en, color_hex, version
              FROM cal_calendars
             WHERE tenant_id = ? AND calendar_id = ?
               AND calendar_type = 'SYSTEM' AND lifecycle_state = 'ACTIVE'
            """;

    private static final String LOCK_COMPANY_CALENDAR =
            COMPANY_CALENDAR + " FOR UPDATE";

    private static final String INSERT_COMPANY_CALENDAR = """
            INSERT INTO cal_calendars (
                calendar_id, tenant_id, calendar_key, name_ko, name_en,
                color_hex, calendar_type, visibility, subscription_policy,
                lifecycle_state, created_by, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, 'SYSTEM', 'DETAILS', 'REQUIRED',
                    'ACTIVE', ?, ?)
            """;

    private static final String UPDATE_COMPANY_CALENDAR = """
            UPDATE cal_calendars
               SET calendar_key = ?, name_ko = ?, name_en = ?, color_hex = ?,
                   version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
             WHERE tenant_id = ? AND calendar_id = ?
               AND calendar_type = 'SYSTEM' AND lifecycle_state = 'ACTIVE'
               AND version = ?
            """;

    private static final String ENSURE_COMPANY_GRANT = """
            INSERT INTO cal_calendar_access_grants (
                tenant_id, calendar_id, principal_type, access_level,
                can_view_private, lifecycle_state, created_by, updated_by)
            VALUES (?, ?, 'TENANT', 'VIEW_DETAILS', FALSE, 'ACTIVE', ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private static final String NORMALIZE_COMPANY_GRANT = """
            UPDATE cal_calendar_access_grants
               SET access_level = 'VIEW_DETAILS', can_view_private = FALSE,
                   updated_at = CURRENT_TIMESTAMP, updated_by = ?
             WHERE tenant_id = ? AND calendar_id = ?
               AND principal_type = 'TENANT' AND lifecycle_state = 'ACTIVE'
               AND (access_level <> 'VIEW_DETAILS' OR can_view_private)
            """;

    private static final String EVENT_COLUMNS = """
            event.event_id, event.calendar_id, event.title, event.description,
            event.event_type, event.starts_at, event.ends_at, event.time_zone,
            event.all_day, event.location, event.conference_url, event.status,
            event.visibility, event.recurrence_pattern, event.recurrence_interval,
            event.recurrence_until, event.response_required, event.importance,
            event.deleted_at, event.purge_after, event.legal_hold, event.version,
            EXISTS (
                SELECT 1 FROM cal_resource_bookings booking
                 WHERE booking.tenant_id = event.tenant_id
                   AND booking.event_id = event.event_id
                   AND booking.booking_status IN ('PENDING', 'CONFIRMED')) AS has_resource
            """;

    private static final String COMPANY_EVENTS = """
            SELECT %s
              FROM cal_events event
              JOIN cal_calendars calendar
                ON calendar.tenant_id = event.tenant_id
               AND calendar.calendar_id = event.calendar_id
             WHERE event.tenant_id = ? AND event.calendar_id = ?
               AND calendar.calendar_type = 'SYSTEM'
               AND calendar.lifecycle_state = 'ACTIVE'
               AND (?::boolean = (event.deleted_at IS NOT NULL))
               AND (?::boolean OR (
                   event.starts_at < ?
                   AND (event.ends_at > ?
                        OR (event.recurrence_pattern <> 'NONE'
                            AND (event.recurrence_until IS NULL
                                 OR event.recurrence_until >= CAST(? AS date))))))
             ORDER BY CASE WHEN event.deleted_at IS NULL
                           THEN event.starts_at ELSE event.deleted_at END DESC,
                      event.event_id
             LIMIT 500
            """.formatted(EVENT_COLUMNS);

    private static final String LOCK_COMPANY_EVENT = """
            SELECT %s
              FROM cal_events event
              JOIN cal_calendars calendar
                ON calendar.tenant_id = event.tenant_id
               AND calendar.calendar_id = event.calendar_id
             WHERE event.tenant_id = ? AND event.calendar_id = ? AND event.event_id = ?
               AND calendar.calendar_type = 'SYSTEM'
               AND calendar.lifecycle_state = 'ACTIVE'
             FOR UPDATE OF event
            """.formatted(EVENT_COLUMNS);

    private static final String UPDATE_COMPANY_EVENT = """
            UPDATE cal_events event
               SET title = ?, description = ?, event_type = ?, starts_at = ?, ends_at = ?,
                   time_zone = ?, all_day = ?, location = ?, conference_url = ?,
                   visibility = ?, recurrence_pattern = ?, recurrence_interval = ?,
                   recurrence_until = ?, response_required = ?, importance = ?,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP, updated_by = ?
             WHERE event.tenant_id = ? AND event.calendar_id = ? AND event.event_id = ?
               AND event.deleted_at IS NULL AND event.status <> 'CANCELLED'
               AND event.version = ?
               AND EXISTS (
                   SELECT 1 FROM cal_calendars calendar
                    WHERE calendar.tenant_id = event.tenant_id
                      AND calendar.calendar_id = event.calendar_id
                      AND calendar.calendar_type = 'SYSTEM'
                      AND calendar.lifecycle_state = 'ACTIVE')
            """;

    private static final String TRASH_COMPANY_EVENT = """
            UPDATE cal_events event
               SET deleted_at = CURRENT_TIMESTAMP, deleted_by = ?, deletion_reason = ?,
                   purge_after = CASE WHEN legal_hold THEN NULL
                       ELSE CURRENT_TIMESTAMP + INTERVAL '30 days' END,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP, updated_by = ?
             WHERE event.tenant_id = ? AND event.calendar_id = ? AND event.event_id = ?
               AND event.deleted_at IS NULL AND event.status <> 'CANCELLED'
               AND event.version = ?
               AND EXISTS (
                   SELECT 1 FROM cal_calendars calendar
                    WHERE calendar.tenant_id = event.tenant_id
                      AND calendar.calendar_id = event.calendar_id
                      AND calendar.calendar_type = 'SYSTEM')
            """;

    private static final String RESTORE_COMPANY_EVENT = """
            UPDATE cal_events event
               SET deleted_at = NULL, deleted_by = NULL, deletion_reason = NULL,
                   purge_after = NULL, version = version + 1,
                   updated_at = CURRENT_TIMESTAMP, updated_by = ?
             WHERE event.tenant_id = ? AND event.calendar_id = ? AND event.event_id = ?
               AND event.deleted_at IS NOT NULL
               AND (event.legal_hold OR event.purge_after > CURRENT_TIMESTAMP)
               AND event.version = ?
               AND EXISTS (
                   SELECT 1 FROM cal_calendars calendar
                    WHERE calendar.tenant_id = event.tenant_id
                      AND calendar.calendar_id = event.calendar_id
                      AND calendar.calendar_type = 'SYSTEM')
            """;

    private final JdbcTemplate jdbc;

    CompanyCalendarAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<CompanyCalendarRow> calendars(Long tenantId, boolean korean) {
        return jdbc.query(COMPANY_CALENDARS, (result, ignored) -> new CompanyCalendarRow(
                result.getObject("calendar_id", UUID.class),
                result.getString("calendar_key"), result.getString("name"),
                result.getString("name_ko"), result.getString("name_en"),
                result.getString("color_hex"), result.getInt("upcoming_count"),
                result.getInt("trashed_count"), result.getLong("version")),
                korean, tenantId);
    }

    Optional<CompanyCalendarState> lockCalendar(Long tenantId, UUID calendarId) {
        return jdbc.query(LOCK_COMPANY_CALENDAR, (result, ignored) -> new CompanyCalendarState(
                result.getObject("calendar_id", UUID.class),
                result.getString("calendar_key"), result.getString("name_ko"),
                result.getString("name_en"), result.getString("color_hex"),
                result.getLong("version")), tenantId, calendarId).stream().findFirst();
    }

    Optional<CompanyCalendarState> calendar(Long tenantId, UUID calendarId) {
        return jdbc.query(COMPANY_CALENDAR, (result, ignored) -> new CompanyCalendarState(
                result.getObject("calendar_id", UUID.class),
                result.getString("calendar_key"), result.getString("name_ko"),
                result.getString("name_en"), result.getString("color_hex"),
                result.getLong("version")), tenantId, calendarId).stream().findFirst();
    }

    UUID insertCalendar(Long tenantId, Long actorId, CalendarDtos.CompanyCalendarRequest request) {
        UUID calendarId = UUID.randomUUID();
        jdbc.update(INSERT_COMPANY_CALENDAR, calendarId, tenantId, request.key().trim(),
                request.nameKo().trim(), request.nameEn().trim(), request.color(),
                actorId, actorId);
        ensureCompanyGrant(tenantId, calendarId, actorId);
        return calendarId;
    }

    int updateCalendar(
            Long tenantId, Long actorId, UUID calendarId,
            CalendarDtos.CompanyCalendarRequest request) {
        int updated = jdbc.update(UPDATE_COMPANY_CALENDAR,
                request.key().trim(), request.nameKo().trim(), request.nameEn().trim(),
                request.color(), actorId, tenantId, calendarId, request.version());
        if (updated > 0) ensureCompanyGrant(tenantId, calendarId, actorId);
        return updated;
    }

    private void ensureCompanyGrant(Long tenantId, UUID calendarId, Long actorId) {
        jdbc.update(ENSURE_COMPANY_GRANT, tenantId, calendarId, actorId, actorId);
        jdbc.update(NORMALIZE_COMPANY_GRANT, actorId, tenantId, calendarId);
    }

    List<CompanyEventRow> events(
            Long tenantId, UUID calendarId, OffsetDateTime from,
            OffsetDateTime to, boolean deleted) {
        return jdbc.query(COMPANY_EVENTS, (result, ignored) -> event(result),
                tenantId, calendarId, deleted, deleted, to, from, from.toLocalDate());
    }

    Optional<CompanyEventRow> lockEvent(Long tenantId, UUID calendarId, UUID eventId) {
        return jdbc.query(LOCK_COMPANY_EVENT, (result, ignored) -> event(result),
                tenantId, calendarId, eventId).stream().findFirst();
    }

    int updateEvent(
            Long tenantId, Long actorId, UUID calendarId, UUID eventId,
            CalendarDtos.UpdateEventRequest request) {
        return jdbc.update(UPDATE_COMPANY_EVENT,
                request.title().trim(), blankToNull(request.description()), request.type().name(),
                request.startsAt(), request.endsAt(), request.timeZone(), request.allDay(),
                blankToNull(request.location()), blankToNull(request.conferenceUrl()),
                request.visibility().name(), request.recurrence().name(),
                request.recurrenceInterval(), request.recurrenceUntil(),
                request.responseRequired(), importance(request.importance()).name(), actorId,
                tenantId, calendarId, eventId, request.version());
    }

    int trashEvent(
            Long tenantId, Long actorId, UUID calendarId, UUID eventId,
            String reason, long version) {
        return jdbc.update(TRASH_COMPANY_EVENT, actorId, reason, actorId,
                tenantId, calendarId, eventId, version);
    }

    int restoreEvent(
            Long tenantId, Long actorId, UUID calendarId, UUID eventId, long version) {
        return jdbc.update(RESTORE_COMPANY_EVENT, actorId,
                tenantId, calendarId, eventId, version);
    }

    private CompanyEventRow event(ResultSet result) throws SQLException {
        return new CompanyEventRow(
                result.getObject("event_id", UUID.class),
                result.getObject("calendar_id", UUID.class),
                result.getString("title"), result.getString("description"),
                EventType.valueOf(result.getString("event_type")),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getString("time_zone"), result.getBoolean("all_day"),
                result.getString("location"), result.getString("conference_url"),
                EventStatus.valueOf(result.getString("status")),
                EventVisibility.valueOf(result.getString("visibility")),
                RecurrencePattern.valueOf(result.getString("recurrence_pattern")),
                result.getInt("recurrence_interval"),
                result.getObject("recurrence_until", LocalDate.class),
                result.getBoolean("response_required"),
                EventImportance.valueOf(result.getString("importance")),
                result.getObject("deleted_at", OffsetDateTime.class),
                result.getObject("purge_after", OffsetDateTime.class),
                result.getBoolean("legal_hold"), result.getBoolean("has_resource"),
                result.getLong("version"));
    }

    private EventImportance importance(EventImportance value) {
        return value == null ? EventImportance.NORMAL : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record CompanyCalendarRow(
            UUID calendarId, String key, String name, String nameKo, String nameEn, String color,
            int upcomingEventCount, int trashedEventCount, long version) {
    }

    record CompanyCalendarState(
            UUID calendarId, String key, String nameKo,
            String nameEn, String color, long version) {
    }

    record CompanyEventRow(
            UUID eventId, UUID calendarId, String title, String description,
            EventType type, OffsetDateTime startsAt, OffsetDateTime endsAt,
            String timeZone, boolean allDay, String location, String conferenceUrl,
            EventStatus status, EventVisibility visibility, RecurrencePattern recurrence,
            int recurrenceInterval, LocalDate recurrenceUntil, boolean responseRequired,
            EventImportance importance, OffsetDateTime deletedAt, OffsetDateTime purgeAfter,
            boolean legalHold, boolean hasResource, long version) {
    }
}
