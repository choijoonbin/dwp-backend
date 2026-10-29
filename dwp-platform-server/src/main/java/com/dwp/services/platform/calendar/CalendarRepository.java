package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarPersistenceValues.*;
import static com.dwp.services.platform.calendar.CalendarTypes.*;

@Repository
public class CalendarRepository extends CalendarRepositoryRows {
    private final JdbcTemplate jdbc;
    private final CalendarRowMapper rows;
    private final CalendarInvitationResponseRepository invitationResponses;
    private final CalendarAttendeeRepository attendeeRepository;
    private final CalendarAccessRepository accessRepository;
    private final CalendarResourceBookingRepository resourceBookings;
    private final CalendarAuditRepository auditRepository;
    public CalendarRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.rows = new CalendarRowMapper(objectMapper);
        this.invitationResponses = new CalendarInvitationResponseRepository(jdbc);
        this.attendeeRepository = new CalendarAttendeeRepository(jdbc);
        this.accessRepository = new CalendarAccessRepository(jdbc);
        this.resourceBookings = new CalendarResourceBookingRepository(jdbc, objectMapper, rows);
        this.auditRepository = new CalendarAuditRepository(jdbc, objectMapper);
    }
    PolicyRow policy(Long tenantId) {
        return jdbc.query(CalendarSql01.POLICY_SELECT_CAL_TENANT_POLICIES, (result, ignored) ->
                        rows.policy(result, PolicyRow::new), tenantId).stream()
                .findFirst()
                .orElseGet(() -> CalendarRowMapper.defaultPolicy(PolicyRow::new));
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
        return calendars(tenantId, userId, personPublicId, null, korean);
    }

    List<CalendarRow> calendars(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            boolean korean) {
        return jdbc.query(CalendarSql01.CALENDARS_SELECT_CAL_CALENDARS, (result, ignored) -> new CalendarRow(
                result.getObject("calendar_id", UUID.class),
                result.getString("calendar_key"),
                result.getString("name"),
                result.getString("color_override") == null
                        ? result.getString("color_hex") : result.getString("color_override"),
                CalendarType.valueOf(result.getString("calendar_type")),
                result.getString("visibility"),
                nullableLong(result, "owner_user_id"),
                result.getObject("owner_person_public_id", UUID.class),
                result.getString("owner_display_name"),
                CalendarSourceKind.valueOf(result.getString("source_kind")),
                CalendarAccessLevel.valueOf(result.getString("access_level")),
                CalendarSubscriptionPolicy.valueOf(result.getString("subscription_policy")),
                result.getBoolean("selected"), result.getBoolean("favorite"),
                result.getInt("display_order"), result.getLong("calendar_version"),
                result.getLong("subscription_version"), result.getBoolean("can_view_private")),
                userId, personPublicId, CalendarVerifiedGroups.databaseArray(verifiedGroupRefs),
                korean, tenantId);
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
        return visibleEvents(
                tenantId, userId, personPublicId, null, from, to, korean);
    }

    List<EventRow> visibleEvents(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean) {
        return jdbc.query(
                CalendarSql01.VISIBLE_EVENTS_SELECT_CAL_EVENTS,
                (result, ignored) -> rows.event(result, ResourceRow::new, EventRow::new),
                userId, personPublicId, CalendarVerifiedGroups.databaseArray(verifiedGroupRefs),
                korean, korean, tenantId, to, from, to, from, from);
    }

    List<EventRow> visibleEventCandidates(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean,
            OffsetDateTime afterStartsAt,
            UUID afterEventId,
            int limit) {
        return jdbc.query(
                CalendarAccessSql.VISIBLE_EVENT_CANDIDATES,
                (result, ignored) -> rows.event(result, ResourceRow::new, EventRow::new),
                userId, personPublicId, CalendarVerifiedGroups.databaseArray(verifiedGroupRefs),
                korean, korean, tenantId, to, from, to, from, from,
                afterStartsAt, afterStartsAt, afterStartsAt, afterEventId, limit);
    }

    Optional<EventRow> event(
            Long tenantId, Long userId, UUID personPublicId, UUID eventId, boolean korean) {
        return event(tenantId, userId, personPublicId, null, eventId, korean);
    }

    Optional<EventRow> event(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            boolean korean) {
        return jdbc.query(
                CalendarAccessSql.VISIBLE_EVENT_BY_ID,
                (result, ignored) -> rows.event(result, ResourceRow::new, EventRow::new),
                userId, personPublicId, CalendarVerifiedGroups.databaseArray(verifiedGroupRefs),
                korean, korean, tenantId, eventId).stream().findFirst();
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
        return attendeeRepository.attendees(tenantId, eventId);
    }

    Map<UUID, List<AttendeeRow>> attendeesByEvents(Long tenantId, List<UUID> eventIds) {
        return attendeeRepository.attendeesByEvents(tenantId, eventIds);
    }

    UUID insertEvent(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String organizerName,
            UUID calendarId,
            String requestFingerprint,
            CalendarDtos.CreateEventRequest value) {
        return insertEvent(
                tenantId, userId, personPublicId, organizerName, calendarId,
                requestFingerprint, value, UUID.randomUUID());
    }

    UUID insertEvent(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String organizerName,
            UUID calendarId,
            String requestFingerprint,
            CalendarDtos.CreateEventRequest value,
            UUID eventId) {
        jdbc.update(CalendarSql01.INSERT_EVENT_INSERT_CAL_EVENTS, eventId, tenantId, calendarId, userId, personPublicId,
                organizerName == null || organizerName.isBlank()
                        ? "User " + userId : organizerName.trim(), value.title().trim(),
                blankToNull(value.description()), value.type().name(), value.startsAt(),
                value.endsAt(), value.timeZone(), value.allDay(), blankToNull(value.location()),
                blankToNull(value.conferenceUrl()), value.visibility().name(),
                value.recurrence().name(), value.recurrenceInterval(), value.recurrenceUntil(),
                recurrenceDays(value.recurrenceDays()), value.recurrenceCount(),
                value.responseRequired(), importance(value.importance()).name(),
                value.idempotencyKey(), requestFingerprint, userId, userId);
        upsertAttendees(tenantId, eventId, value.attendees(), false);
        return eventId;
    }

    void insertSplitEvent(
            Long tenantId,
            Long actorId,
            CalendarRepository.EventRow source,
            String requestFingerprint,
            CalendarDtos.CreateEventRequest value,
            UUID eventId) {
        jdbc.update(CalendarSql01.INSERT_EVENT_INSERT_CAL_EVENTS,
                eventId, tenantId, source.calendarId(), source.organizerUserId(),
                source.organizerPersonPublicId(), source.organizerName(),
                value.title().trim(), blankToNull(value.description()), value.type().name(),
                value.startsAt(), value.endsAt(), value.timeZone(), value.allDay(),
                blankToNull(value.location()), blankToNull(value.conferenceUrl()),
                value.visibility().name(), value.recurrence().name(),
                value.recurrenceInterval(), value.recurrenceUntil(),
                recurrenceDays(value.recurrenceDays()), value.recurrenceCount(),
                value.responseRequired(), importance(value.importance()).name(),
                value.idempotencyKey(), requestFingerprint, actorId, actorId);
        upsertAttendees(tenantId, eventId, value.attendees(), false);
    }

    void replaceAttendees(
            Long tenantId,
            UUID eventId,
            List<CalendarDtos.AttendeeInput> attendees) {
        attendeeRepository.upsertAttendees(tenantId, eventId, attendees, true);
    }

    private void upsertAttendees(
            Long tenantId,
            UUID eventId,
            List<CalendarDtos.AttendeeInput> attendees,
            boolean removeMissing) {
        attendeeRepository.upsertAttendees(tenantId, eventId, attendees, removeMissing);
    }

    List<BusyRow> busySlots(
            Long tenantId,
            List<UUID> personPublicIds,
            OffsetDateTime from,
            OffsetDateTime to) {
        return accessRepository.busySlots(tenantId, personPublicIds, from, to);
    }

    Set<UUID> knownPersonPublicIds(Long tenantId, List<UUID> personPublicIds) {
        return accessRepository.knownPersonPublicIds(tenantId, personPublicIds);
    }

    Set<UUID> authorizedFreeBusyPersonIds(
            Long tenantId,
            UUID viewerPersonPublicId,
            String verifiedGroupRefs,
            List<UUID> requestedPersonPublicIds) {
        return accessRepository.authorizedFreeBusyPersonIds(
                tenantId, viewerPersonPublicId, verifiedGroupRefs, requestedPersonPublicIds);
    }

    Optional<CalendarAccessDecision> calendarAccess(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID calendarId) {
        return accessRepository.calendarAccess(
                tenantId, userId, personPublicId, verifiedGroupRefs, calendarId);
    }

    boolean lockActiveCreateDelegation(
            Long tenantId,
            UUID calendarId,
            UUID delegatePersonPublicId) {
        return accessRepository.lockActiveCreateDelegation(
                tenantId, calendarId, delegatePersonPublicId);
    }

    boolean hasDirectCreateAccess(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID calendarId) {
        return accessRepository.hasDirectCreateAccess(
                tenantId, userId, personPublicId, verifiedGroupRefs, calendarId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void linkIdentity(Long tenantId, Long userId, UUID personPublicId) {
        accessRepository.linkIdentity(tenantId, userId, personPublicId);
    }

    boolean identityMatches(Long tenantId, Long userId, UUID personPublicId) {
        return accessRepository.identityMatches(tenantId, userId, personPublicId);
    }

    int updateEvent(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            CalendarDtos.UpdateEventRequest value) {
        return updateEvent(tenantId, userId, personPublicId, null, eventId, value);
    }

    int updateEvent(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String verifiedGroupRefs,
            UUID eventId,
            CalendarDtos.UpdateEventRequest value) {
        return jdbc.update(CalendarSql01.UPDATE_EVENT_UPDATE_CAL_EVENTS, value.title().trim(), blankToNull(value.description()), value.type().name(),
                value.startsAt(), value.endsAt(), value.timeZone(), value.allDay(),
                blankToNull(value.location()), blankToNull(value.conferenceUrl()),
                value.visibility().name(), value.recurrence().name(), value.recurrenceInterval(),
                value.recurrenceUntil(), recurrenceDays(value.recurrenceDays()),
                value.recurrenceCount(), value.responseRequired(),
                importance(value.importance()).name(), userId, tenantId, eventId,
                personPublicId, userId, personPublicId,
                CalendarVerifiedGroups.databaseArray(verifiedGroupRefs), personPublicId,
                value.version());
    }

    int cancelEvent(
            Long tenantId, Long userId, UUID personPublicId, UUID eventId, long version) {
        return cancelEvent(tenantId, userId, personPublicId, null, eventId, version);
    }

    int cancelEvent(
            Long tenantId, Long userId, UUID personPublicId,
            String verifiedGroupRefs, UUID eventId, long version) {
        return jdbc.update(CalendarSql01.CANCEL_EVENT_UPDATE_CAL_EVENTS,
                userId, tenantId, eventId, personPublicId, userId, personPublicId,
                CalendarVerifiedGroups.databaseArray(verifiedGroupRefs), version);
    }

    CalendarInvitationResponseRepository invitationResponses() {
        return invitationResponses;
    }

    List<ResourceRow> resources(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean,
            boolean includeRetired) {
        return resourceBookings.resources(tenantId, from, to, korean, includeRetired);
    }
    Optional<ResourceRow> resource(Long tenantId, UUID resourceId, boolean korean) {
        return resourceBookings.resource(tenantId, resourceId, korean);
    }

    boolean resourceConflict(
            Long tenantId,
            UUID resourceId,
            OffsetDateTime from,
            OffsetDateTime to,
            UUID excludingEventId) {
        return resourceBookings.resourceConflict(
                tenantId, resourceId, from, to, excludingEventId);
    }

    void lockResource(Long tenantId, UUID resourceId) {
        resourceBookings.lockResource(tenantId, resourceId);
    }
    boolean facilityClosureConflict(Long tenant, UUID resource, OffsetDateTime from, OffsetDateTime to) {
        return resourceBookings.facilityClosureConflict(tenant, resource, from, to);
    }
    int lockedRoomCapacity(Long tenantId, UUID resourceId) {
        return resourceBookings.lockedRoomCapacity(tenantId, resourceId);
    }
    void insertBooking(
            Long tenantId,
            Long userId,
            UUID eventId,
            ResourceRow resource,
            OffsetDateTime from,
            OffsetDateTime to) {
        resourceBookings.insertBooking(tenantId, userId, eventId, resource, from, to);
    }

    List<BookingRow> pendingBookings(Long tenantId, boolean korean) {
        return resourceBookings.pendingBookings(tenantId, korean);
    }

    Optional<BookingRow> booking(Long tenantId, UUID bookingId, boolean korean) {
        return resourceBookings.booking(tenantId, bookingId, korean);
    }

    BookingRow decideBooking(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String status,
            String note,
            long version,
            boolean korean) {
        return resourceBookings.decideBooking(
                tenantId, actorId, bookingId, status, note, version, korean);
    }

    void rescheduleBooking(
            Long tenantId,
            Long userId,
            UUID eventId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean approvalRequired) {
        resourceBookings.rescheduleBooking(
                tenantId, userId, eventId, from, to, approvalRequired);
    }

    void cancelBookings(Long tenantId, Long userId, UUID eventId) {
        resourceBookings.cancelBookings(tenantId, userId, eventId);
    }

    ResourceRow saveResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            CalendarDtos.ResourceRequest value,
            boolean korean) {
        return resourceBookings.saveResource(tenantId, actorId, resourceId, value, korean);
    }

    boolean isWorkplaceManagedResource(Long tenantId, UUID resourceId) {
        return resourceBookings.isWorkplaceManagedResource(tenantId, resourceId);
    }

    boolean isWorkplaceResourceBookable(Long tenantId, UUID resourceId) {
        return resourceBookings.isWorkplaceResourceBookable(tenantId, resourceId);
    }

    AdminStats adminStats(Long tenantId, OffsetDateTime weekStart, OffsetDateTime weekEnd) {
        return resourceBookings.adminStats(tenantId, weekStart, weekEnd);
    }

    void audit(
            Long tenantId,
            Long actorId,
            UUID eventId,
            String action,
            String correlationId,
            Map<String, Object> before,
            Map<String, Object> after) {
        auditRepository.audit(
                tenantId, actorId, eventId, action, correlationId, before, after);
    }

}
