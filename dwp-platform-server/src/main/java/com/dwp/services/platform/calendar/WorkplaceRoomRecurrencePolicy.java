package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.RecurrencePattern;

/** Bridges a Workplace-managed Calendar room to its authoritative recurrence policy. */
@Repository
class WorkplaceRoomRecurrencePolicy {

    private final JdbcTemplate jdbc;

    WorkplaceRoomRecurrencePolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean allowsRecurring(Long tenantId, UUID calendarResourceId) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT CASE
                         WHEN NOT EXISTS (
                             SELECT 1
                               FROM wp_resources resource
                              WHERE resource.tenant_id=?
                                AND resource.calendar_resource_id=?
                         ) THEN TRUE
                         ELSE COALESCE((
                             SELECT policy.allow_recurring
                               FROM wp_tenant_policies policy
                              WHERE policy.tenant_id=?
                         ), FALSE)
                       END
                """, Boolean.class, tenantId, calendarResourceId, tenantId);
        return Boolean.TRUE.equals(allowed);
    }

    Optional<ExistingSeries> existingSeries(Long tenantId, UUID eventId) {
        return jdbc.query("""
                SELECT event.recurrence_pattern,event.recurrence_interval,
                       event.recurrence_until,event.recurrence_days,event.recurrence_count,
                       event.starts_at,event.ends_at,event.time_zone,
                       booking.resource_id
                  FROM cal_events event
                  LEFT JOIN LATERAL (
                      SELECT resource_id
                        FROM cal_resource_bookings
                       WHERE tenant_id=event.tenant_id AND event_id=event.event_id
                         AND booking_status IN ('PENDING','CONFIRMED')
                       ORDER BY updated_at DESC,booking_id DESC
                       LIMIT 1
                  ) booking ON TRUE
                 WHERE event.tenant_id=? AND event.event_id=?
                """, (result, ignored) -> new ExistingSeries(
                        RecurrencePattern.valueOf(result.getString("recurrence_pattern")),
                        result.getInt("recurrence_interval"),
                        result.getObject("recurrence_until", LocalDate.class),
                        recurrenceDays(result),
                        nullableInteger(result, "recurrence_count"),
                        result.getObject("starts_at", OffsetDateTime.class),
                        result.getObject("ends_at", OffsetDateTime.class),
                        result.getString("time_zone"),
                        result.getObject("resource_id", UUID.class)),
                tenantId, eventId).stream().findFirst();
    }

    record ExistingSeries(
            RecurrencePattern recurrence,
            int interval,
            LocalDate until,
            List<DayOfWeek> days,
            Integer count,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            UUID calendarResourceId) {

        ExistingSeries(
                RecurrencePattern recurrence,
                int interval,
                LocalDate until,
                OffsetDateTime startsAt,
                OffsetDateTime endsAt,
                String timeZone,
                UUID calendarResourceId) {
            this(recurrence, interval, until, List.of(), null,
                    startsAt, endsAt, timeZone, calendarResourceId);
        }
    }

    private static List<DayOfWeek> recurrenceDays(java.sql.ResultSet result)
            throws java.sql.SQLException {
        java.sql.Array array = result.getArray("recurrence_days");
        if (array == null) return List.of();
        return java.util.Arrays.stream((Object[]) array.getArray())
                .map(value -> DayOfWeek.of(((Number) value).intValue()))
                .distinct().sorted().toList();
    }

    private static Integer nullableInteger(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }
}
