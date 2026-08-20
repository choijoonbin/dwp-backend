package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarTypes.*;

@Repository
public class CalendarRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CalendarRowMapper rows;

    public CalendarRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rows = new CalendarRowMapper(objectMapper);
    }

    PolicyRow policy(Long tenantId) {
        return jdbc.query(CalendarSql01.POLICY_SELECT_CAL_TENANT_POLICIES, (result, ignored) -> rows.policy(result), tenantId).stream()
                .findFirst()
                .orElseGet(CalendarRowMapper::defaultPolicy);
    }

    int updatePolicy(Long tenantId, Long actorId, CalendarDtos.PolicyRequest value) {
        return jdbc.update(CalendarSql01.UPDATE_POLICY_UPDATE_CAL_TENANT_POLICIES, value.weekStart(), value.workingDayStart(), value.workingDayEnd(),
                value.defaultEventMinutes(), value.minimumEventMinutes(),
                value.maximumEventMinutes(), value.maximumAdvanceDays(),
                value.defaultBufferMinutes(), value.weeklyFocusTargetMinutes(),
                value.dailyMeetingLimitMinutes(), value.enforceMeetingAgenda(),
                value.allowExternalAttendees(), actorId, tenantId, value.version());
    }

    List<CalendarRow> calendars(
            Long tenantId, Long userId, UUID personPublicId, boolean korean) {
        return jdbc.query(CalendarSql01.CALENDARS_SELECT_CAL_CALENDARS, (result, ignored) -> new CalendarRow(
                result.getObject("calendar_id", UUID.class),
                result.getString("calendar_key"),
                result.getString("name"),
                result.getString("color_hex"),
                CalendarType.valueOf(result.getString("calendar_type")),
                result.getString("visibility"),
                nullableLong(result, "owner_user_id")),
                korean, tenantId, personPublicId, userId);
    }

    UUID ensurePersonalCalendar(Long tenantId, Long userId, UUID personPublicId) {
        if (personPublicId != null) {
            return jdbc.queryForObject(CalendarSql01.ENSURE_PERSONAL_CALENDAR_INSERT_CAL_CALENDARS, UUID.class, tenantId, personPublicId, userId,
                    personPublicId, userId, userId);
        }
        return jdbc.queryForObject(CalendarSql01.CONFLICT_INSERT_CAL_CALENDARS, UUID.class, tenantId, userId, userId, userId, userId);
    }

    List<EventRow> visibleEvents(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean) {
        return jdbc.query(CalendarSql01.VISIBLE_EVENTS_SELECT_CAL_EVENTS, (result, ignored) -> rows.event(result), korean, korean,
                personPublicId, userId, personPublicId, tenantId,
                to, from, to, from, personPublicId, userId);
    }

    Optional<EventRow> event(
            Long tenantId, Long userId, UUID personPublicId, UUID eventId, boolean korean) {
        OffsetDateTime farPast = OffsetDateTime.now().minusYears(20);
        OffsetDateTime farFuture = OffsetDateTime.now().plusYears(20);
        return visibleEvents(tenantId, userId, personPublicId, farPast, farFuture, korean).stream()
                .filter(value -> value.eventId().equals(eventId))
                .findFirst();
    }

    void lockEventIdempotency(Long tenantId, Long userId, UUID idempotencyKey) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(
                        1, "calendar:" + tenantId + ":" + userId + ":" + idempotencyKey),
                result -> null);
    }

    Optional<IdempotencyRow> eventIdempotency(
            Long tenantId, Long userId, UUID idempotencyKey) {
        return jdbc.query(CalendarSql01.EVENT_IDEMPOTENCY_SELECT_CAL_EVENTS, (result, ignored) -> new IdempotencyRow(
                result.getObject("event_id", UUID.class),
                result.getString("request_fingerprint")),
                tenantId, userId, idempotencyKey).stream().findFirst();
    }

    List<AttendeeRow> attendees(Long tenantId, UUID eventId) {
        return jdbc.query(CalendarSql01.ATTENDEES_SELECT_CAL_EVENT_ATTENDEES, (result, ignored) -> new AttendeeRow(
                nullableLong(result, "attendee_user_id"),
                result.getObject("attendee_person_public_id", UUID.class),
                result.getString("attendee_email"),
                result.getString("attendee_name"),
                AttendeeType.valueOf(result.getString("attendee_type")),
                ResponseStatus.valueOf(result.getString("response_status"))), tenantId, eventId);
    }

    UUID insertEvent(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String organizerName,
            UUID calendarId,
            String requestFingerprint,
            CalendarDtos.CreateEventRequest value) {
        UUID eventId = UUID.randomUUID();
        jdbc.update(CalendarSql01.INSERT_EVENT_INSERT_CAL_EVENTS, eventId, tenantId, calendarId, userId, personPublicId,
                organizerName == null || organizerName.isBlank()
                        ? "User " + userId : organizerName.trim(), value.title().trim(),
                blankToNull(value.description()), value.type().name(), value.startsAt(),
                value.endsAt(), value.timeZone(), value.allDay(), blankToNull(value.location()),
                blankToNull(value.conferenceUrl()), value.visibility().name(),
                value.recurrence().name(), value.recurrenceInterval(), value.recurrenceUntil(),
                value.responseRequired(), value.idempotencyKey(), requestFingerprint, userId, userId);
        upsertAttendees(tenantId, eventId, value.attendees(), false);
        return eventId;
    }

    void replaceAttendees(
            Long tenantId,
            UUID eventId,
            List<CalendarDtos.AttendeeInput> attendees) {
        upsertAttendees(tenantId, eventId, attendees, true);
    }

    private void upsertAttendees(
            Long tenantId,
            UUID eventId,
            List<CalendarDtos.AttendeeInput> attendees,
            boolean removeMissing) {
        Set<String> retainedEmails = new HashSet<>();
        attendees.forEach(attendee -> {
            String email = attendee.email().trim().toLowerCase(java.util.Locale.ROOT);
            retainedEmails.add(email);
            jdbc.update(CalendarSql01.UPSERT_ATTENDEES_INSERT_CAL_EVENT_ATTENDEES, tenantId, eventId, attendee.userId(), attendee.personPublicId(), email,
                    attendee.name().trim(), attendee.type().name());
        });
        if (!removeMissing) return;
        attendees(tenantId, eventId).stream()
                .map(AttendeeRow::email)
                .filter(email -> !retainedEmails.contains(email.toLowerCase(java.util.Locale.ROOT)))
                .forEach(email -> jdbc.update(CalendarSql01.CONFLICT_DELETE_CAL_EVENT_ATTENDEES, tenantId, eventId, email));
    }

    List<BusyRow> busySlots(
            Long tenantId,
            List<UUID> personPublicIds,
            OffsetDateTime from,
            OffsetDateTime to) {
        if (personPublicIds.isEmpty()) return List.of();
        UUID[] ids = personPublicIds.toArray(UUID[]::new);
        return jdbc.query(CalendarSql01.BUSY_SLOTS_WITH_CAL_EVENTS, (result, ignored) -> new BusyRow(
                result.getObject("person_public_id", UUID.class),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class)),
                ids, ids, tenantId, to, to, to, to, from);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void linkIdentity(Long tenantId, Long userId, UUID personPublicId) {
        if (userId == null || personPublicId == null) return;
        jdbc.update(CalendarSql01.LINK_IDENTITY_DELETE_CAL_IDENTITY_LINKS, tenantId, personPublicId, userId);
        jdbc.update(CalendarSql01.LINK_IDENTITY_INSERT_CAL_IDENTITY_LINKS, tenantId, userId, personPublicId);
    }

    int updateEvent(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            CalendarDtos.UpdateEventRequest value) {
        return jdbc.update(CalendarSql01.UPDATE_EVENT_UPDATE_CAL_EVENTS, value.title().trim(), blankToNull(value.description()), value.type().name(),
                value.startsAt(), value.endsAt(), value.timeZone(), value.allDay(),
                blankToNull(value.location()), blankToNull(value.conferenceUrl()),
                value.visibility().name(), value.recurrence().name(), value.recurrenceInterval(),
                value.recurrenceUntil(), value.responseRequired(), userId, tenantId, eventId,
                personPublicId, userId, value.version());
    }

    int cancelEvent(
            Long tenantId, Long userId, UUID personPublicId, UUID eventId, long version) {
        return jdbc.update(CalendarSql01.CANCEL_EVENT_UPDATE_CAL_EVENTS, userId, tenantId, eventId, personPublicId, userId, version);
    }

    int respond(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            ResponseStatus response) {
        return jdbc.update(CalendarSql01.RESPOND_UPDATE_CAL_EVENT_ATTENDEES, response.name(), tenantId, eventId, personPublicId, userId);
    }

    List<ResourceRow> resources(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean,
            boolean includeRetired) {
        return jdbc.query(CalendarSql01.RESOURCES_SELECT_CAL_RESOURCE_BOOKINGS, (result, ignored) -> rows.resource(result), korean,
                to, to, to, from, tenantId, includeRetired);
    }

    Optional<ResourceRow> resource(Long tenantId, UUID resourceId, boolean korean) {
        OffsetDateTime now = OffsetDateTime.now();
        return resources(tenantId, now, now.plusMinutes(1), korean, true).stream()
                .filter(value -> value.resourceId().equals(resourceId))
                .findFirst();
    }

    boolean resourceConflict(
            Long tenantId,
            UUID resourceId,
            OffsetDateTime from,
            OffsetDateTime to,
            UUID excludingEventId) {
        Long count = jdbc.queryForObject(CalendarSql01.RESOURCE_CONFLICT_SELECT_CAL_RESOURCE_BOOKINGS, Long.class, to, to, tenantId, resourceId,
                excludingEventId, excludingEventId, to, from);
        return count != null && count > 0;
    }

    void lockResource(Long tenantId, UUID resourceId) {
        jdbc.queryForObject(
                "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                Integer.class,
                tenantId + ":" + resourceId);
    }

    void insertBooking(
            Long tenantId,
            Long userId,
            UUID eventId,
            ResourceRow resource,
            OffsetDateTime from,
            OffsetDateTime to) {
        jdbc.update(CalendarSql01.INSERT_BOOKING_INSERT_CAL_RESOURCE_BOOKINGS, tenantId, eventId, resource.resourceId(), from, to,
                resource.approvalRequired() ? "PENDING" : "CONFIRMED", userId, userId, userId);
    }

    List<BookingRow> pendingBookings(Long tenantId, boolean korean) {
        return bookingRows(CalendarSql01.PENDING_BOOKINGS_SQL_STATEMENT, korean, tenantId);
    }

    Optional<BookingRow> booking(Long tenantId, UUID bookingId, boolean korean) {
        return bookingRows(CalendarSql01.BOOKING_SQL_STATEMENT, korean, tenantId, bookingId).stream().findFirst();
    }

    BookingRow decideBooking(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String status,
            String note,
            long version,
            boolean korean) {
        int updated = jdbc.update(CalendarSql01.DECIDE_BOOKING_UPDATE_CAL_RESOURCE_BOOKINGS, status, blankToNull(note), actorId, actorId, tenantId, bookingId, version);
        return updated == 0 ? null : booking(tenantId, bookingId, korean).orElse(null);
    }

    private List<BookingRow> bookingRows(
            String predicate,
            boolean korean,
            Object... parameters) {
        Object[] values = new Object[parameters.length + 1];
        values[0] = korean;
        System.arraycopy(parameters, 0, values, 1, parameters.length);
        return jdbc.query(CalendarSql01.BOOKING_ROWS_SELECT_CAL_RESOURCE_BOOKINGS + predicate, (result, ignored) -> new BookingRow(
                result.getObject("booking_id", UUID.class),
                result.getObject("event_id", UUID.class),
                result.getObject("resource_id", UUID.class),
                result.getString("resource_name"), result.getString("title"),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getString("organizer_name"), result.getString("organizer_email"),
                result.getString("booking_status"), result.getLong("requested_by"),
                result.getString("decision_note"),
                result.getObject("decided_at", OffsetDateTime.class),
                nullableLong(result, "decided_by"), result.getLong("version")), values);
    }

    void rescheduleBooking(
            Long tenantId,
            Long userId,
            UUID eventId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean approvalRequired) {
        jdbc.update(CalendarSql01.RESCHEDULE_BOOKING_UPDATE_CAL_RESOURCE_BOOKINGS, from, to, approvalRequired ? "PENDING" : "CONFIRMED", userId,
                userId, tenantId, eventId);
    }

    void cancelBookings(Long tenantId, Long userId, UUID eventId) {
        jdbc.update(CalendarSql01.CANCEL_BOOKINGS_UPDATE_CAL_RESOURCE_BOOKINGS, userId, tenantId, eventId);
    }

    ResourceRow saveResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            CalendarDtos.ResourceRequest value,
            boolean korean) {
        UUID id = resourceId == null ? UUID.randomUUID() : resourceId;
        if (value.version() == null) {
            jdbc.update(CalendarSql01.SAVE_RESOURCE_INSERT_CAL_RESOURCES, id, tenantId, value.code(), value.nameKo(), value.nameEn(),
                    value.type().name(), value.site(), blankToNull(value.floor()), value.capacity(),
                    json(value.features()), value.timeZone(), value.approvalRequired(),
                    value.state().name(), actorId, actorId);
        } else {
            int updated = jdbc.update(CalendarSql01.CAL_RESOURCES_UPDATE_CAL_RESOURCES, value.code(), value.nameKo(), value.nameEn(), value.type().name(),
                    value.site(), blankToNull(value.floor()), value.capacity(), json(value.features()),
                    value.timeZone(), value.approvalRequired(), value.state().name(), actorId,
                    tenantId, id, value.version());
            if (updated == 0) return null;
        }
        return resource(tenantId, id, korean).orElse(null);
    }

    boolean isWorkplaceManagedResource(Long tenantId, UUID resourceId) {
        Boolean managed = jdbc.queryForObject(CalendarSql01.IS_WORKPLACE_MANAGED_RESOURCE_SELECT_WP_RESOURCES, Boolean.class, tenantId, resourceId);
        return Boolean.TRUE.equals(managed);
    }

    boolean isWorkplaceResourceBookable(Long tenantId, UUID resourceId) {
        Boolean bookable = jdbc.queryForObject(CalendarSql01.IS_WORKPLACE_RESOURCE_BOOKABLE_SELECT_WP_RESOURCES, Boolean.class, tenantId, resourceId, tenantId, resourceId);
        return Boolean.TRUE.equals(bookable);
    }

    AdminStats adminStats(Long tenantId, OffsetDateTime weekStart, OffsetDateTime weekEnd) {
        return jdbc.queryForObject(CalendarSql01.ADMIN_STATS_WITH_CAL_EVENTS, (result, ignored) -> new AdminStats(
                result.getLong("active_resources"),
                result.getLong("maintenance_resources"),
                result.getLong("bookings_this_week"),
                result.getLong("pending_bookings"),
                result.getLong("events_this_week"),
                result.getLong("conflicted_users")),
                weekEnd, weekEnd, tenantId, weekEnd, tenantId,
                tenantId, tenantId, weekEnd, weekStart, tenantId,
                weekEnd, weekStart, weekEnd, weekStart);
    }

    void audit(
            Long tenantId,
            Long actorId,
            UUID eventId,
            String action,
            String correlationId,
            Map<String, Object> before,
            Map<String, Object> after) {
        jdbc.update(CalendarSql01.AUDIT_INSERT_CAL_AUDIT_EVENTS, tenantId, eventId, action, actorId, correlationId,
                json(before), json(after));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Calendar data could not be serialized.", exception);
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    record CalendarRow(
            UUID calendarId,
            String calendarKey,
            String name,
            String color,
            CalendarType type,
            String visibility,
            Long ownerUserId) {
    }

    record EventRow(
            UUID eventId,
            UUID calendarId,
            String calendarName,
            String calendarColor,
            Long organizerUserId,
            UUID organizerPersonPublicId,
            String organizerName,
            String organizerEmail,
            String title,
            String description,
            EventType type,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String timeZone,
            boolean allDay,
            String location,
            String conferenceUrl,
            EventStatus status,
            EventVisibility visibility,
            RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            boolean responseRequired,
            ResponseStatus myResponse,
            ResourceRow resource,
            long version) {
    }

    record AttendeeRow(
            Long userId,
            UUID personPublicId,
            String email,
            String name,
            AttendeeType type,
            ResponseStatus response) {
    }

    record IdempotencyRow(UUID eventId, String requestFingerprint) {
    }

    record ResourceRow(
            UUID resourceId,
            String code,
            String name,
            String nameKo,
            String nameEn,
            ResourceType type,
            String site,
            String floor,
            int capacity,
            List<String> features,
            String timeZone,
            boolean approvalRequired,
            ResourceState state,
            boolean available,
            long version) {
    }

    record BookingRow(
            UUID bookingId,
            UUID eventId,
            UUID resourceId,
            String resourceName,
            String eventTitle,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String organizerName,
            String organizerEmail,
            String status,
            Long requestedBy,
            String decisionNote,
            OffsetDateTime decidedAt,
            Long decidedBy,
            long version) {
    }

    record PolicyRow(
            int weekStart,
            LocalTime workingDayStart,
            LocalTime workingDayEnd,
            int defaultEventMinutes,
            int minimumEventMinutes,
            int maximumEventMinutes,
            int maximumAdvanceDays,
            int defaultBufferMinutes,
            int weeklyFocusTargetMinutes,
            int dailyMeetingLimitMinutes,
            boolean enforceMeetingAgenda,
            boolean allowExternalAttendees,
            long version) {
    }

    record AdminStats(
            long activeResources,
            long resourcesInMaintenance,
            long bookingsThisWeek,
            long pendingBookings,
            long eventsThisWeek,
            long conflictedUsers) {
    }

    record BusyRow(UUID personPublicId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }
}
