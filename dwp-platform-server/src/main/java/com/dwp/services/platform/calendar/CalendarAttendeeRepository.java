package com.dwp.services.platform.calendar;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.AttendeeType;
import static com.dwp.services.platform.calendar.CalendarTypes.ResponseStatus;

/** Owns attendee persistence while {@link CalendarRepository} remains the aggregate facade. */
final class CalendarAttendeeRepository {

    private final JdbcTemplate jdbc;

    CalendarAttendeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<CalendarRepositoryRows.AttendeeRow> attendees(Long tenantId, UUID eventId) {
        return jdbc.query(
                CalendarSql01.ATTENDEES_SELECT_CAL_EVENT_ATTENDEES,
                (result, ignored) -> attendee(result),
                tenantId,
                eventId);
    }

    Map<UUID, List<CalendarRepositoryRows.AttendeeRow>> attendeesByEvents(
            Long tenantId, List<UUID> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        Map<UUID, List<CalendarRepositoryRows.AttendeeRow>> result = new LinkedHashMap<>();
        jdbc.query(CalendarSql01.ATTENDEES_BY_EVENTS_SELECT_CAL_EVENT_ATTENDEES, row -> {
            UUID eventId = row.getObject("event_id", UUID.class);
            result.computeIfAbsent(eventId, ignored -> new java.util.ArrayList<>())
                    .add(attendee(row));
        }, tenantId, eventIds.toArray(UUID[]::new));
        result.replaceAll((ignored, values) -> List.copyOf(values));
        return Map.copyOf(result);
    }

    void upsertAttendees(
            Long tenantId,
            UUID eventId,
            List<CalendarDtos.AttendeeInput> attendees,
            boolean removeMissing) {
        Set<String> retainedEmails = new HashSet<>();
        attendees.forEach(attendee -> {
            String email = attendee.email().trim().toLowerCase(java.util.Locale.ROOT);
            retainedEmails.add(email);
            jdbc.update(
                    CalendarSql01.UPSERT_ATTENDEES_INSERT_CAL_EVENT_ATTENDEES,
                    tenantId,
                    eventId,
                    attendee.userId(),
                    attendee.personPublicId(),
                    email,
                    attendee.name().trim(),
                    attendee.type().name());
        });
        if (!removeMissing) return;
        attendees(tenantId, eventId).stream()
                .map(CalendarRepositoryRows.AttendeeRow::email)
                .filter(email -> !retainedEmails.contains(email.toLowerCase(java.util.Locale.ROOT)))
                .forEach(email -> jdbc.update(
                        CalendarSql01.CONFLICT_DELETE_CAL_EVENT_ATTENDEES,
                        tenantId,
                        eventId,
                        email));
    }

    private static CalendarRepositoryRows.AttendeeRow attendee(ResultSet result) throws SQLException {
        return new CalendarRepositoryRows.AttendeeRow(
                nullableLong(result, "attendee_user_id"),
                result.getObject("attendee_person_public_id", UUID.class),
                result.getString("attendee_email"),
                result.getString("attendee_name"),
                AttendeeType.valueOf(result.getString("attendee_type")),
                ResponseStatus.valueOf(result.getString("response_status")));
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }
}
