package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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

    public CalendarRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    PolicyRow policy(Long tenantId) {
        return jdbc.query("""
                SELECT week_start, working_day_start, working_day_end,
                       default_event_minutes, minimum_event_minutes,
                       maximum_event_minutes, maximum_advance_days,
                       default_buffer_minutes, weekly_focus_target_minutes,
                       daily_meeting_limit_minutes, enforce_meeting_agenda,
                       allow_external_attendees, version
                  FROM cal_tenant_policies WHERE tenant_id = ?
                """, (result, ignored) -> policyRow(result), tenantId).stream()
                .findFirst()
                .orElseGet(CalendarRepository::defaultPolicy);
    }

    int updatePolicy(Long tenantId, Long actorId, CalendarDtos.PolicyRequest value) {
        return jdbc.update("""
                UPDATE cal_tenant_policies
                   SET week_start = ?, working_day_start = ?, working_day_end = ?,
                       default_event_minutes = ?, minimum_event_minutes = ?,
                       maximum_event_minutes = ?, maximum_advance_days = ?,
                       default_buffer_minutes = ?, weekly_focus_target_minutes = ?,
                       daily_meeting_limit_minutes = ?, enforce_meeting_agenda = ?,
                       allow_external_attendees = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND version = ?
                """, value.weekStart(), value.workingDayStart(), value.workingDayEnd(),
                value.defaultEventMinutes(), value.minimumEventMinutes(),
                value.maximumEventMinutes(), value.maximumAdvanceDays(),
                value.defaultBufferMinutes(), value.weeklyFocusTargetMinutes(),
                value.dailyMeetingLimitMinutes(), value.enforceMeetingAgenda(),
                value.allowExternalAttendees(), actorId, tenantId, value.version());
    }

    List<CalendarRow> calendars(
            Long tenantId, Long userId, UUID personPublicId, boolean korean) {
        return jdbc.query("""
                SELECT calendar_id, calendar_key,
                       CASE WHEN ? THEN name_ko ELSE name_en END AS name,
                       color_hex, calendar_type, visibility, owner_user_id
                  FROM cal_calendars
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                   AND (
                       owner_person_public_id = ?
                       OR (owner_person_public_id IS NULL AND owner_user_id = ?)
                       OR calendar_type IN ('TEAM', 'SYSTEM')
                   )
                 ORDER BY CASE calendar_type WHEN 'PERSONAL' THEN 0 WHEN 'TEAM' THEN 1 ELSE 2 END,
                          calendar_key
                """, (result, ignored) -> new CalendarRow(
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
            return jdbc.queryForObject("""
                    INSERT INTO cal_calendars (
                        calendar_id, tenant_id, calendar_key, owner_user_id,
                        owner_person_public_id, name_ko, name_en, color_hex,
                        calendar_type, visibility, created_by, updated_by)
                    VALUES (gen_random_uuid(), ?, 'personal-person-' || ?, ?, ?,
                            '내 캘린더', 'My calendar', '#2563EB',
                            'PERSONAL', 'PRIVATE', ?, ?)
                    ON CONFLICT (tenant_id, owner_person_public_id)
                        WHERE calendar_type = 'PERSONAL'
                          AND owner_person_public_id IS NOT NULL
                    DO UPDATE SET
                        owner_user_id = EXCLUDED.owner_user_id,
                        lifecycle_state = 'ACTIVE',
                        updated_at = CURRENT_TIMESTAMP,
                        updated_by = EXCLUDED.updated_by
                    RETURNING calendar_id
                    """, UUID.class, tenantId, personPublicId, userId,
                    personPublicId, userId, userId);
        }
        return jdbc.queryForObject("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    name_ko, name_en, color_hex, calendar_type, visibility,
                    created_by, updated_by)
                VALUES (gen_random_uuid(), ?, 'personal-' || ?, ?,
                        '내 캘린더', 'My calendar', '#2563EB', 'PERSONAL', 'PRIVATE', ?, ?)
                ON CONFLICT (tenant_id, calendar_key) DO UPDATE SET
                    lifecycle_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                RETURNING calendar_id
                """, UUID.class, tenantId, userId, userId, userId, userId);
    }

    List<EventRow> visibleEvents(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean) {
        return jdbc.query("""
                SELECT event.event_id, event.calendar_id,
                       CASE WHEN ? THEN calendar.name_ko ELSE calendar.name_en END AS calendar_name,
                       calendar.color_hex, event.organizer_user_id,
                       event.organizer_person_public_id, event.organizer_name,
                       event.organizer_email, event.title, event.description,
                       event.event_type, event.starts_at, event.ends_at, event.time_zone,
                       event.all_day, event.location, event.conference_url, event.status,
                       event.visibility, event.recurrence_pattern, event.recurrence_interval,
                       event.recurrence_until, event.response_required,
                       mine.response_status AS my_response, event.version,
                       resource.resource_id, resource.resource_code,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS resource_name,
                       resource.name_ko AS resource_name_ko,
                       resource.name_en AS resource_name_en,
                       resource.resource_type, resource.site_name, resource.floor_name,
                       resource.capacity, resource.features::text, resource.time_zone,
                       resource.approval_required,
                       resource.lifecycle_state AS resource_state
                  FROM cal_events event
                  JOIN cal_calendars calendar ON calendar.calendar_id = event.calendar_id
                  LEFT JOIN LATERAL (
                      SELECT attendee.event_id, attendee.response_status
                        FROM cal_event_attendees attendee
                       WHERE attendee.event_id = event.event_id
                         AND attendee.tenant_id = event.tenant_id
                         AND (
                             attendee.attendee_person_public_id = ?
                             OR (attendee.attendee_person_public_id IS NULL
                                 AND attendee.attendee_user_id = ?)
                         )
                       ORDER BY
                           CASE WHEN attendee.attendee_person_public_id = ? THEN 0 ELSE 1 END,
                           attendee.updated_at DESC
                       LIMIT 1
                  ) mine ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT booking.resource_id
                        FROM cal_resource_bookings booking
                       WHERE booking.event_id = event.event_id
                         AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                       ORDER BY booking.created_at LIMIT 1
                  ) booking ON TRUE
                  LEFT JOIN cal_resources resource ON resource.resource_id = booking.resource_id
                 WHERE event.tenant_id = ? AND event.status <> 'CANCELLED'
                   AND (
                       (event.starts_at < ? AND event.ends_at > ?)
                       OR (event.recurrence_pattern <> 'NONE'
                           AND event.starts_at < ?
                           AND (event.recurrence_until IS NULL OR event.recurrence_until >= CAST(? AS date)))
                   )
                   AND (
                       event.organizer_person_public_id = ?
                       OR (event.organizer_person_public_id IS NULL
                           AND event.organizer_user_id = ?)
                       OR mine.event_id IS NOT NULL
                       OR (calendar.calendar_type IN ('TEAM', 'SYSTEM')
                           AND calendar.visibility = 'DETAILS'
                           AND event.visibility IN ('DEFAULT', 'PUBLIC'))
                   )
                 ORDER BY event.starts_at, event.event_id
                """, (result, ignored) -> eventRow(result), korean, korean,
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

    Optional<EventRow> eventByIdempotency(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID idempotencyKey,
            boolean korean) {
        List<UUID> eventIds = jdbc.query("""
                SELECT event_id FROM cal_events
                 WHERE tenant_id = ? AND idempotency_key = ?
                   AND (
                       organizer_person_public_id = ?
                       OR (organizer_person_public_id IS NULL AND organizer_user_id = ?)
                   )
                """, (result, ignored) -> result.getObject("event_id", UUID.class),
                tenantId, idempotencyKey, personPublicId, userId);
        return eventIds.isEmpty()
                ? Optional.empty()
                : event(tenantId, userId, personPublicId, eventIds.get(0), korean);
    }

    List<AttendeeRow> attendees(Long tenantId, UUID eventId) {
        return jdbc.query("""
                SELECT attendee_user_id, attendee_person_public_id, attendee_email, attendee_name,
                       attendee_type, response_status
                  FROM cal_event_attendees
                 WHERE tenant_id = ? AND event_id = ?
                 ORDER BY attendee_type, attendee_name, attendee_email
                """, (result, ignored) -> new AttendeeRow(
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
            CalendarDtos.CreateEventRequest value) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_person_public_id, organizer_name, title, description, event_type,
                    starts_at, ends_at, time_zone, all_day, location,
                    conference_url, visibility, recurrence_pattern,
                    recurrence_interval, recurrence_until, response_required,
                    source_type, idempotency_key, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'NATIVE', ?, ?, ?)
                """, eventId, tenantId, calendarId, userId, personPublicId,
                organizerName == null || organizerName.isBlank()
                        ? "User " + userId : organizerName.trim(), value.title().trim(),
                blankToNull(value.description()), value.type().name(), value.startsAt(),
                value.endsAt(), value.timeZone(), value.allDay(), blankToNull(value.location()),
                blankToNull(value.conferenceUrl()), value.visibility().name(),
                value.recurrence().name(), value.recurrenceInterval(), value.recurrenceUntil(),
                value.responseRequired(), value.idempotencyKey(), userId, userId);
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
            jdbc.update("""
                INSERT INTO cal_event_attendees (
                    tenant_id, event_id, attendee_user_id, attendee_person_public_id, attendee_email,
                    attendee_name, attendee_type, response_status)
                VALUES (?, ?, ?, ?, LOWER(BTRIM(?)), ?, ?, 'NEEDS_ACTION')
                ON CONFLICT (event_id, attendee_email) DO UPDATE SET
                    attendee_user_id = EXCLUDED.attendee_user_id,
                    attendee_person_public_id = EXCLUDED.attendee_person_public_id,
                    attendee_name = EXCLUDED.attendee_name,
                    attendee_type = EXCLUDED.attendee_type,
                    updated_at = CURRENT_TIMESTAMP
                """, tenantId, eventId, attendee.userId(), attendee.personPublicId(), email,
                    attendee.name().trim(), attendee.type().name());
        });
        if (!removeMissing) return;
        attendees(tenantId, eventId).stream()
                .map(AttendeeRow::email)
                .filter(email -> !retainedEmails.contains(email.toLowerCase(java.util.Locale.ROOT)))
                .forEach(email -> jdbc.update("""
                        DELETE FROM cal_event_attendees
                         WHERE tenant_id = ? AND event_id = ? AND attendee_email = ?
                        """, tenantId, eventId, email));
    }

    List<BusyRow> busySlots(
            Long tenantId,
            List<UUID> personPublicIds,
            OffsetDateTime from,
            OffsetDateTime to) {
        if (personPublicIds.isEmpty()) return List.of();
        UUID[] ids = personPublicIds.toArray(UUID[]::new);
        return jdbc.query("""
                WITH relevant_events AS (
                    SELECT subject.person_public_id,
                           event.starts_at,
                           event.ends_at,
                           event.time_zone,
                           event.recurrence_pattern,
                           event.recurrence_interval,
                           event.recurrence_until
                  FROM cal_events event
                  LEFT JOIN cal_identity_links organizer_identity
                    ON organizer_identity.tenant_id = event.tenant_id
                   AND organizer_identity.user_id = event.organizer_user_id
                  JOIN LATERAL (
                      SELECT COALESCE(
                                 event.organizer_person_public_id,
                                 organizer_identity.person_public_id) AS person_public_id
                      WHERE COALESCE(
                                event.organizer_person_public_id,
                                organizer_identity.person_public_id) = ANY (?::uuid[])
                      UNION
                      SELECT attendee.attendee_person_public_id
                        FROM cal_event_attendees attendee
                       WHERE attendee.event_id = event.event_id
                         AND attendee.response_status <> 'DECLINED'
                         AND attendee.attendee_person_public_id = ANY (?::uuid[])
                  ) subject ON TRUE
                 WHERE event.tenant_id = ? AND event.status <> 'CANCELLED'
                   AND event.starts_at < ?
                ), occurrences AS (
                    SELECT event.person_public_id,
                           occurrence.local_starts_at AT TIME ZONE event.time_zone AS starts_at,
                           (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                               + (event.ends_at - event.starts_at) AS ends_at
                      FROM relevant_events event
                      CROSS JOIN LATERAL generate_series(
                          event.starts_at AT TIME ZONE event.time_zone,
                          LEAST(
                              ?::timestamptz AT TIME ZONE event.time_zone,
                              COALESCE(
                                  event.recurrence_until + TIME '23:59:59',
                                  ?::timestamptz AT TIME ZONE event.time_zone)),
                          CASE event.recurrence_pattern
                              WHEN 'DAILY' THEN make_interval(days => event.recurrence_interval)
                              WHEN 'WEEKLY' THEN make_interval(days => 7 * event.recurrence_interval)
                              WHEN 'MONTHLY' THEN make_interval(months => event.recurrence_interval)
                              ELSE INTERVAL '100 years'
                          END
                      ) occurrence(local_starts_at)
                )
                SELECT person_public_id, starts_at, ends_at
                  FROM occurrences
                 WHERE starts_at < ? AND ends_at > ?
                 ORDER BY person_public_id, starts_at
                """, (result, ignored) -> new BusyRow(
                result.getObject("person_public_id", UUID.class),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class)),
                ids, ids, tenantId, to, to, to, to, from);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void linkIdentity(Long tenantId, Long userId, UUID personPublicId) {
        if (userId == null || personPublicId == null) return;
        jdbc.update("""
                DELETE FROM cal_identity_links
                 WHERE tenant_id = ? AND person_public_id = ? AND user_id <> ?
                """, tenantId, personPublicId, userId);
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO UPDATE SET
                    person_public_id = EXCLUDED.person_public_id,
                    last_seen_at = CURRENT_TIMESTAMP
                WHERE cal_identity_links.person_public_id IS DISTINCT FROM EXCLUDED.person_public_id
                   OR cal_identity_links.last_seen_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                """, tenantId, userId, personPublicId);
    }

    int updateEvent(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            CalendarDtos.UpdateEventRequest value) {
        return jdbc.update("""
                UPDATE cal_events
                   SET title = ?, description = ?, event_type = ?, starts_at = ?, ends_at = ?,
                       time_zone = ?, all_day = ?, location = ?, conference_url = ?,
                       visibility = ?, recurrence_pattern = ?, recurrence_interval = ?,
                       recurrence_until = ?, response_required = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND event_id = ?
                   AND (
                       organizer_person_public_id = ?
                       OR (organizer_person_public_id IS NULL AND organizer_user_id = ?)
                   )
                   AND status <> 'CANCELLED' AND version = ?
                """, value.title().trim(), blankToNull(value.description()), value.type().name(),
                value.startsAt(), value.endsAt(), value.timeZone(), value.allDay(),
                blankToNull(value.location()), blankToNull(value.conferenceUrl()),
                value.visibility().name(), value.recurrence().name(), value.recurrenceInterval(),
                value.recurrenceUntil(), value.responseRequired(), userId, tenantId, eventId,
                personPublicId, userId, value.version());
    }

    int cancelEvent(
            Long tenantId, Long userId, UUID personPublicId, UUID eventId, long version) {
        return jdbc.update("""
                UPDATE cal_events SET status = 'CANCELLED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND event_id = ?
                   AND (
                       organizer_person_public_id = ?
                       OR (organizer_person_public_id IS NULL AND organizer_user_id = ?)
                   )
                   AND status <> 'CANCELLED' AND version = ?
                """, userId, tenantId, eventId, personPublicId, userId, version);
    }

    int respond(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID eventId,
            ResponseStatus response) {
        return jdbc.update("""
                UPDATE cal_event_attendees
                   SET response_status = ?, responded_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND event_id = ?
                   AND (
                       attendee_person_public_id = ?
                       OR (attendee_person_public_id IS NULL AND attendee_user_id = ?)
                   )
                """, response.name(), tenantId, eventId, personPublicId, userId);
    }

    List<ResourceRow> resources(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean korean,
            boolean includeRetired) {
        return jdbc.query("""
                SELECT resource.resource_id, resource.resource_code,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS name,
                       resource.name_ko, resource.name_en,
                       resource.resource_type, resource.site_name, resource.floor_name,
                       resource.capacity, resource.features::text,
                       resource.time_zone,
                       resource.approval_required, resource.lifecycle_state, resource.version,
                       NOT EXISTS (
                           SELECT 1
                             FROM cal_resource_bookings booking
                             JOIN cal_events event ON event.event_id = booking.event_id
                             CROSS JOIN LATERAL generate_series(
                                 booking.starts_at AT TIME ZONE event.time_zone,
                                 LEAST(
                                     ?::timestamptz AT TIME ZONE event.time_zone,
                                     COALESCE(
                                         event.recurrence_until + TIME '23:59:59',
                                         ?::timestamptz AT TIME ZONE event.time_zone)),
                                 CASE event.recurrence_pattern
                                     WHEN 'DAILY' THEN make_interval(days => event.recurrence_interval)
                                     WHEN 'WEEKLY' THEN make_interval(
                                         days => 7 * event.recurrence_interval)
                                     WHEN 'MONTHLY' THEN make_interval(
                                         months => event.recurrence_interval)
                                     ELSE INTERVAL '100 years'
                                 END
                             ) occurrence(local_starts_at)
                            WHERE booking.tenant_id = resource.tenant_id
                              AND booking.resource_id = resource.resource_id
                              AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                              AND event.status <> 'CANCELLED'
                              AND (occurrence.local_starts_at AT TIME ZONE event.time_zone) < ?
                              AND (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                                  + (booking.ends_at - booking.starts_at) > ?
                       ) AS available
                  FROM cal_resources resource
                 WHERE resource.tenant_id = ?
                   AND (? OR resource.lifecycle_state <> 'RETIRED')
                 ORDER BY resource.site_name, resource.floor_name, resource.capacity, resource.resource_code
                """, (result, ignored) -> resourceRow(result), korean,
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
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM cal_resource_bookings booking
                  JOIN cal_events event ON event.event_id = booking.event_id
                  CROSS JOIN LATERAL generate_series(
                      booking.starts_at AT TIME ZONE event.time_zone,
                      LEAST(
                          ?::timestamptz AT TIME ZONE event.time_zone,
                          COALESCE(
                              event.recurrence_until + TIME '23:59:59',
                              ?::timestamptz AT TIME ZONE event.time_zone)),
                      CASE event.recurrence_pattern
                          WHEN 'DAILY' THEN make_interval(days => event.recurrence_interval)
                          WHEN 'WEEKLY' THEN make_interval(days => 7 * event.recurrence_interval)
                          WHEN 'MONTHLY' THEN make_interval(months => event.recurrence_interval)
                          ELSE INTERVAL '100 years'
                      END
                  ) occurrence(local_starts_at)
                 WHERE booking.tenant_id = ? AND booking.resource_id = ?
                   AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                   AND event.status <> 'CANCELLED'
                   AND (?::uuid IS NULL OR booking.event_id <> ?::uuid)
                   AND (occurrence.local_starts_at AT TIME ZONE event.time_zone) < ?
                   AND (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                       + (booking.ends_at - booking.starts_at) > ?
                """, Long.class, to, to, tenantId, resourceId,
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
        jdbc.update("""
                INSERT INTO cal_resource_bookings (
                    tenant_id, event_id, resource_id, starts_at, ends_at,
                    booking_status, requested_by, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id, resource_id) DO UPDATE SET
                    starts_at = EXCLUDED.starts_at, ends_at = EXCLUDED.ends_at,
                    booking_status = EXCLUDED.booking_status,
                    requested_by = EXCLUDED.requested_by,
                    decision_note = NULL, decided_at = NULL, decided_by = NULL,
                    version = cal_resource_bookings.version + 1,
                    updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by
                """, tenantId, eventId, resource.resourceId(), from, to,
                resource.approvalRequired() ? "PENDING" : "CONFIRMED", userId, userId, userId);
    }

    List<BookingRow> pendingBookings(Long tenantId, boolean korean) {
        return bookingRows("""
                WHERE booking.tenant_id = ? AND booking.booking_status = 'PENDING'
                ORDER BY event.starts_at, booking.created_at
                """, korean, tenantId);
    }

    Optional<BookingRow> booking(Long tenantId, UUID bookingId, boolean korean) {
        return bookingRows("""
                WHERE booking.tenant_id = ? AND booking.booking_id = ?
                """, korean, tenantId, bookingId).stream().findFirst();
    }

    BookingRow decideBooking(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String status,
            String note,
            long version,
            boolean korean) {
        int updated = jdbc.update("""
                UPDATE cal_resource_bookings
                   SET booking_status = ?, decision_note = ?, decided_at = CURRENT_TIMESTAMP,
                       decided_by = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND booking_id = ?
                   AND booking_status = 'PENDING' AND version = ?
                """, status, blankToNull(note), actorId, actorId, tenantId, bookingId, version);
        return updated == 0 ? null : booking(tenantId, bookingId, korean).orElse(null);
    }

    private List<BookingRow> bookingRows(
            String predicate,
            boolean korean,
            Object... parameters) {
        Object[] values = new Object[parameters.length + 1];
        values[0] = korean;
        System.arraycopy(parameters, 0, values, 1, parameters.length);
        return jdbc.query("""
                SELECT booking.booking_id, booking.event_id, booking.resource_id,
                       CASE WHEN ? THEN resource.name_ko ELSE resource.name_en END AS resource_name,
                       event.title, event.starts_at, event.ends_at,
                       event.organizer_name, event.organizer_email,
                       booking.booking_status, booking.requested_by,
                       booking.decision_note, booking.decided_at, booking.decided_by,
                       booking.version
                  FROM cal_resource_bookings booking
                  JOIN cal_events event ON event.event_id = booking.event_id
                  JOIN cal_resources resource ON resource.resource_id = booking.resource_id
                """ + predicate, (result, ignored) -> new BookingRow(
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
        jdbc.update("""
                UPDATE cal_resource_bookings
                   SET starts_at = ?, ends_at = ?, booking_status = ?, requested_by = ?,
                       decision_note = NULL, decided_at = NULL, decided_by = NULL,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND event_id = ?
                   AND booking_status IN ('PENDING', 'CONFIRMED')
                """, from, to, approvalRequired ? "PENDING" : "CONFIRMED", userId,
                userId, tenantId, eventId);
    }

    void cancelBookings(Long tenantId, Long userId, UUID eventId) {
        jdbc.update("""
                UPDATE cal_resource_bookings
                   SET booking_status = 'CANCELLED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND event_id = ?
                   AND booking_status IN ('PENDING', 'CONFIRMED')
                """, userId, tenantId, eventId);
    }

    ResourceRow saveResource(
            Long tenantId,
            Long actorId,
            UUID resourceId,
            CalendarDtos.ResourceRequest value,
            boolean korean) {
        UUID id = resourceId == null ? UUID.randomUUID() : resourceId;
        if (value.version() == null) {
            jdbc.update("""
                    INSERT INTO cal_resources (
                        resource_id, tenant_id, resource_code, name_ko, name_en,
                        resource_type, site_name, floor_name, capacity, features,
                        time_zone, approval_required, lifecycle_state, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                    """, id, tenantId, value.code(), value.nameKo(), value.nameEn(),
                    value.type().name(), value.site(), blankToNull(value.floor()), value.capacity(),
                    json(value.features()), value.timeZone(), value.approvalRequired(),
                    value.state().name(), actorId, actorId);
        } else {
            int updated = jdbc.update("""
                    UPDATE cal_resources
                       SET resource_code = ?, name_ko = ?, name_en = ?, resource_type = ?,
                           site_name = ?, floor_name = ?, capacity = ?, features = CAST(? AS jsonb),
                           time_zone = ?, approval_required = ?, lifecycle_state = ?,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND resource_id = ? AND version = ?
                    """, value.code(), value.nameKo(), value.nameEn(), value.type().name(),
                    value.site(), blankToNull(value.floor()), value.capacity(), json(value.features()),
                    value.timeZone(), value.approvalRequired(), value.state().name(), actorId,
                    tenantId, id, value.version());
            if (updated == 0) return null;
        }
        return resource(tenantId, id, korean).orElse(null);
    }

    boolean isWorkplaceManagedResource(Long tenantId, UUID resourceId) {
        Boolean managed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM wp_resources
                     WHERE tenant_id = ?
                       AND calendar_resource_id = ?)
                """, Boolean.class, tenantId, resourceId);
        return Boolean.TRUE.equals(managed);
    }

    boolean isWorkplaceResourceBookable(Long tenantId, UUID resourceId) {
        Boolean bookable = jdbc.queryForObject("""
                SELECT NOT EXISTS (
                           SELECT 1
                             FROM wp_resources
                            WHERE tenant_id = ?
                              AND calendar_resource_id = ?)
                    OR EXISTS (
                           SELECT 1
                             FROM wp_resources resource
                             JOIN wp_floors floor
                               ON floor.tenant_id = resource.tenant_id
                              AND floor.floor_id = resource.floor_id
                             JOIN wp_sites site
                               ON site.tenant_id = floor.tenant_id
                              AND site.site_id = floor.site_id
                            WHERE resource.tenant_id = ?
                              AND resource.calendar_resource_id = ?
                              AND resource.lifecycle_state = 'AVAILABLE'
                              AND floor.lifecycle_state = 'ACTIVE'
                              AND site.lifecycle_state = 'ACTIVE')
                """, Boolean.class, tenantId, resourceId, tenantId, resourceId);
        return Boolean.TRUE.equals(bookable);
    }

    AdminStats adminStats(Long tenantId, OffsetDateTime weekStart, OffsetDateTime weekEnd) {
        return jdbc.queryForObject("""
                WITH event_occurrences AS (
                    SELECT event.event_id, event.organizer_user_id,
                           occurrence.local_starts_at AT TIME ZONE event.time_zone AS starts_at,
                           (occurrence.local_starts_at AT TIME ZONE event.time_zone)
                               + (event.ends_at - event.starts_at) AS ends_at
                      FROM cal_events event
                      CROSS JOIN LATERAL generate_series(
                          event.starts_at AT TIME ZONE event.time_zone,
                          LEAST(
                              ?::timestamptz AT TIME ZONE event.time_zone,
                              COALESCE(
                                  event.recurrence_until + TIME '23:59:59',
                                  ?::timestamptz AT TIME ZONE event.time_zone)),
                          CASE event.recurrence_pattern
                              WHEN 'DAILY' THEN make_interval(days => event.recurrence_interval)
                              WHEN 'WEEKLY' THEN make_interval(days => 7 * event.recurrence_interval)
                              WHEN 'MONTHLY' THEN make_interval(months => event.recurrence_interval)
                              ELSE INTERVAL '100 years'
                          END
                      ) occurrence(local_starts_at)
                     WHERE event.tenant_id = ? AND event.status <> 'CANCELLED'
                       AND event.starts_at < ?
                ), booking_occurrences AS (
                    SELECT booking.booking_id, booking.booking_status,
                           occurrence.starts_at,
                           occurrence.starts_at
                               + (booking.ends_at - booking.starts_at) AS ends_at
                      FROM cal_resource_bookings booking
                      JOIN event_occurrences occurrence
                        ON occurrence.event_id = booking.event_id
                     WHERE booking.tenant_id = ?
                       AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                )
                SELECT
                    (SELECT COUNT(*) FROM cal_resources
                      WHERE tenant_id = ? AND lifecycle_state = 'AVAILABLE') AS active_resources,
                    (SELECT COUNT(*) FROM cal_resources
                      WHERE tenant_id = ? AND lifecycle_state = 'MAINTENANCE') AS maintenance_resources,
                    (SELECT COUNT(*) FROM booking_occurrences
                      WHERE starts_at < ? AND ends_at > ?) AS bookings_this_week,
                    (SELECT COUNT(*) FROM cal_resource_bookings
                      WHERE tenant_id = ? AND booking_status = 'PENDING') AS pending_bookings,
                    (SELECT COUNT(*) FROM event_occurrences
                      WHERE starts_at < ? AND ends_at > ?) AS events_this_week,
                    (SELECT COUNT(DISTINCT first_event.organizer_user_id)
                       FROM event_occurrences first_event
                       JOIN event_occurrences second_event
                         ON second_event.event_id > first_event.event_id
                        AND second_event.organizer_user_id = first_event.organizer_user_id
                        AND second_event.starts_at < first_event.ends_at
                        AND second_event.ends_at > first_event.starts_at
                      WHERE first_event.starts_at < ?
                        AND first_event.ends_at > ?) AS conflicted_users
                """, (result, ignored) -> new AdminStats(
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
        jdbc.update("""
                INSERT INTO cal_audit_events (
                    tenant_id, event_id, action, actor_user_id, correlation_id,
                    before_snapshot, after_snapshot)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                """, tenantId, eventId, action, actorId, correlationId,
                json(before), json(after));
    }

    private EventRow eventRow(ResultSet result) throws SQLException {
        UUID resourceId = result.getObject("resource_id", UUID.class);
        ResourceRow resource = resourceId == null ? null : new ResourceRow(
                resourceId,
                result.getString("resource_code"),
                result.getString("resource_name"),
                result.getString("resource_name_ko"),
                result.getString("resource_name_en"),
                ResourceType.valueOf(result.getString("resource_type")),
                result.getString("site_name"),
                result.getString("floor_name"),
                result.getInt("capacity"),
                stringList(result.getString("features")),
                result.getString("time_zone"),
                result.getBoolean("approval_required"),
                ResourceState.valueOf(result.getString("resource_state")),
                true,
                0);
        String response = result.getString("my_response");
        return new EventRow(
                result.getObject("event_id", UUID.class),
                result.getObject("calendar_id", UUID.class),
                result.getString("calendar_name"),
                result.getString("color_hex"),
                result.getLong("organizer_user_id"),
                result.getObject("organizer_person_public_id", UUID.class),
                result.getString("organizer_name"),
                result.getString("organizer_email"),
                result.getString("title"),
                result.getString("description"),
                EventType.valueOf(result.getString("event_type")),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getString("time_zone"),
                result.getBoolean("all_day"),
                result.getString("location"),
                result.getString("conference_url"),
                EventStatus.valueOf(result.getString("status")),
                EventVisibility.valueOf(result.getString("visibility")),
                RecurrencePattern.valueOf(result.getString("recurrence_pattern")),
                result.getInt("recurrence_interval"),
                result.getObject("recurrence_until", LocalDate.class),
                result.getBoolean("response_required"),
                response == null ? null : ResponseStatus.valueOf(response),
                resource,
                result.getLong("version"));
    }

    private ResourceRow resourceRow(ResultSet result) throws SQLException {
        return new ResourceRow(
                result.getObject("resource_id", UUID.class),
                result.getString("resource_code"),
                result.getString("name"),
                result.getString("name_ko"),
                result.getString("name_en"),
                ResourceType.valueOf(result.getString("resource_type")),
                result.getString("site_name"),
                result.getString("floor_name"),
                result.getInt("capacity"),
                stringList(result.getString("features")),
                result.getString("time_zone"),
                result.getBoolean("approval_required"),
                ResourceState.valueOf(result.getString("lifecycle_state")),
                result.getBoolean("available"),
                result.getLong("version"));
    }

    private PolicyRow policyRow(ResultSet result) throws SQLException {
        return new PolicyRow(
                result.getInt("week_start"),
                result.getObject("working_day_start", LocalTime.class),
                result.getObject("working_day_end", LocalTime.class),
                result.getInt("default_event_minutes"),
                result.getInt("minimum_event_minutes"),
                result.getInt("maximum_event_minutes"),
                result.getInt("maximum_advance_days"),
                result.getInt("default_buffer_minutes"),
                result.getInt("weekly_focus_target_minutes"),
                result.getInt("daily_meeting_limit_minutes"),
                result.getBoolean("enforce_meeting_agenda"),
                result.getBoolean("allow_external_attendees"),
                result.getLong("version"));
    }

    private static PolicyRow defaultPolicy() {
        return new PolicyRow(
                1,
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                30,
                15,
                480,
                365,
                10,
                600,
                300,
                false,
                true,
                0);
    }

    private List<String> stringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Calendar JSON data is invalid.", exception);
        }
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
