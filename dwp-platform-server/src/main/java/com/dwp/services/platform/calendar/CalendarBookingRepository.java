package com.dwp.services.platform.calendar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarBookingDtos.*;

@Repository
class CalendarBookingRepository {

    private static final TypeReference<List<AvailabilityRule>> RULES = new TypeReference<>() { };
    private static final TypeReference<List<BookingQuestion>> QUESTIONS = new TypeReference<>() { };
    private static final TypeReference<List<BookingAnswer>> ANSWERS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    CalendarBookingRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean identityMatches(long tenantId, long userId, UUID personPublicId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM cal_identity_links
                 WHERE tenant_id = ? AND user_id = ? AND person_public_id = ?
                """, Integer.class, tenantId, userId, personPublicId);
        return count != null && count == 1;
    }

    Optional<IdentityProfile> identityProfile(
            long tenantId, long userId, UUID personPublicId) {
        return jdbc.query("""
                SELECT identity.user_id, identity.person_public_id,
                       account.email_address, account.display_name
                  FROM cal_identity_links identity
                  JOIN mail_accounts account
                    ON account.tenant_id = identity.tenant_id
                   AND account.owner_user_id = identity.user_id
                   AND account.owner_person_public_id = identity.person_public_id
                   AND account.account_kind = 'PERSONAL'
                   AND account.is_default = TRUE
                   AND account.connection_state = 'ACTIVE'
                 WHERE identity.tenant_id = ?
                   AND identity.user_id = ?
                   AND identity.person_public_id = ?
                 ORDER BY account.account_id
                 LIMIT 1
                """, (result, ignored) -> new IdentityProfile(
                        result.getLong("user_id"),
                        result.getObject("person_public_id", UUID.class),
                        result.getString("email_address"),
                        result.getString("display_name")),
                tenantId, userId, personPublicId).stream().findFirst();
    }

    boolean hasActiveGroupWriteGrant(
            long tenantId, UUID calendarId, UUID groupRef, OffsetDateTime now) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM cal_calendar_access_grants grant_row
                     WHERE grant_row.tenant_id = ? AND grant_row.calendar_id = ?
                       AND grant_row.principal_type = 'GROUP'
                       AND grant_row.principal_group_ref = ?
                       AND grant_row.lifecycle_state = 'ACTIVE'
                       AND grant_row.access_level IN ('EDIT', 'MANAGE')
                       AND (grant_row.valid_until IS NULL OR grant_row.valid_until > ?)
                )
                """, Boolean.class, tenantId, calendarId, groupRef, now);
        return Boolean.TRUE.equals(allowed);
    }

    List<PageRow> pages(long tenantId, UUID personPublicId, String groupRefs) {
        return jdbc.query("""
                SELECT *
                  FROM cal_booking_pages
                 WHERE tenant_id = ?
                   AND (owner_person_public_id = ?
                        OR owner_group_ref = ANY (?::uuid[]))
                 ORDER BY updated_at DESC, page_id
                """, this::page, tenantId, personPublicId,
                CalendarVerifiedGroups.databaseArray(groupRefs));
    }

    Optional<PageRow> page(long tenantId, UUID pageId) {
        return jdbc.query("""
                SELECT * FROM cal_booking_pages
                 WHERE tenant_id = ? AND page_id = ?
                """, this::page, tenantId, pageId).stream().findFirst();
    }

    Optional<PageRow> lockPage(long tenantId, UUID pageId) {
        return jdbc.query("""
                SELECT * FROM cal_booking_pages
                 WHERE tenant_id = ? AND page_id = ?
                 FOR UPDATE
                """, this::page, tenantId, pageId).stream().findFirst();
    }

    void insertPage(
            UUID pageId,
            CalendarBookingAccess.Actor actor,
            SaveBookingPageRequest value,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_booking_pages (
                    page_id, tenant_id, owner_user_id, owner_person_public_id,
                    owner_type, owner_group_ref, calendar_id, state, title, description,
                    time_zone, duration_minutes, buffer_before_minutes,
                    buffer_after_minutes, daily_limit, minimum_notice_minutes,
                    maximum_advance_days, meeting_mode, location,
                    conference_url, dial_in_instructions,
                    availability_rules, questions, version,
                    created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?::jsonb, ?::jsonb, 0, ?, ?, ?, ?)
                """, pageId, actor.tenantId(), actor.userId(), actor.personPublicId(),
                value.ownerType().name(), value.ownerGroupRef(), value.calendarId(),
                value.title(), blankToNull(value.description()), value.timeZone(),
                value.durationMinutes(), value.bufferBeforeMinutes(), value.bufferAfterMinutes(),
                value.dailyLimit(), value.minimumNoticeMinutes(), value.maximumAdvanceDays(),
                value.meetingMode().name(), blankToNull(value.location()),
                blankToNull(value.conferenceUrl()), blankToNull(value.dialInInstructions()),
                write(value.availabilityRules()), write(value.questions()), now,
                actor.userId(), now, actor.userId());
    }

    int updatePage(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            SaveBookingPageRequest value,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_booking_pages
                   SET owner_type = ?, owner_group_ref = ?, calendar_id = ?,
                       title = ?, description = ?, time_zone = ?, duration_minutes = ?,
                       buffer_before_minutes = ?, buffer_after_minutes = ?, daily_limit = ?,
                       minimum_notice_minutes = ?, maximum_advance_days = ?,
                       meeting_mode = ?, location = ?, conference_url = ?,
                       dial_in_instructions = ?, availability_rules = ?::jsonb,
                       questions = ?::jsonb, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND page_id = ? AND version = ?
                """, value.ownerType().name(), value.ownerGroupRef(), value.calendarId(),
                value.title(), blankToNull(value.description()), value.timeZone(),
                value.durationMinutes(), value.bufferBeforeMinutes(), value.bufferAfterMinutes(),
                value.dailyLimit(), value.minimumNoticeMinutes(), value.maximumAdvanceDays(),
                value.meetingMode().name(), blankToNull(value.location()),
                blankToNull(value.conferenceUrl()), blankToNull(value.dialInInstructions()),
                write(value.availabilityRules()), write(value.questions()), now,
                actor.userId(), actor.tenantId(), pageId, value.version());
    }

    int changeState(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            long version,
            BookingPageState state,
            String tokenHash,
            long tokenVersion,
            OffsetDateTime now,
            OffsetDateTime purgeAfter) {
        return jdbc.update("""
                UPDATE cal_booking_pages
                   SET state = ?, public_token_hash = ?, public_token_version = ?,
                       public_token_rotated_at = CASE WHEN ?::varchar IS NULL
                           THEN public_token_rotated_at ELSE ?::timestamptz END,
                       archived_at = CASE WHEN ? = 'ARCHIVED'
                           THEN ?::timestamptz ELSE NULL::timestamptz END,
                       purge_after = CASE WHEN legal_hold THEN NULL::timestamptz
                           ELSE ?::timestamptz END,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND page_id = ? AND version = ?
                """, state.name(), tokenHash, tokenVersion, tokenHash, now,
                state.name(), now, purgeAfter, now, actor.userId(), actor.tenantId(),
                pageId, version);
    }

    int rotatePageToken(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            long version,
            String tokenHash,
            long tokenVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_booking_pages
                   SET public_token_hash = ?, public_token_version = ?,
                       public_token_rotated_at = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND page_id = ? AND version = ?
                """, tokenHash, tokenVersion, now, now, actor.userId(), actor.tenantId(),
                pageId, version);
    }

    void lockHostSchedule(long tenantId, UUID calendarId) {
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) locked",
                Integer.class, tenantId + ":calendar-booking:" + calendarId);
    }

    void expireHolds(long tenantId, UUID calendarId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE cal_booking_holds
                   SET state = 'EXPIRED', version = version + 1,
                       updated_at = ?, updated_by = booker_user_id
                 WHERE tenant_id = ? AND host_calendar_id = ?
                   AND state = 'HELD' AND expires_at <= ?
                """, now, tenantId, calendarId, now);
    }

    Optional<HoldRow> holdByIdempotency(
            long tenantId, UUID pageId, long userId, UUID idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM cal_booking_holds
                 WHERE tenant_id = ? AND page_id = ?
                   AND booker_user_id = ? AND idempotency_key = ?
                """, this::hold, tenantId, pageId, userId, idempotencyKey)
                .stream().findFirst();
    }

    Optional<HoldRow> lockHold(long tenantId, UUID pageId, UUID holdId) {
        return jdbc.query("""
                SELECT * FROM cal_booking_holds
                 WHERE tenant_id = ? AND page_id = ? AND hold_id = ?
                 FOR UPDATE
                """, this::hold, tenantId, pageId, holdId).stream().findFirst();
    }

    HoldRow insertHold(
            UUID holdId,
            CalendarBookingAccess.Actor actor,
            PageRow page,
            CreateBookingHoldRequest value,
            OffsetDateTime blockedStartsAt,
            OffsetDateTime blockedEndsAt,
            OffsetDateTime expiresAt,
            String fingerprint,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_booking_holds (
                    hold_id, tenant_id, page_id, host_calendar_id, booker_user_id,
                    booker_person_public_id, state, starts_at, ends_at,
                    blocked_starts_at, blocked_ends_at, expires_at, page_version,
                    idempotency_key, request_fingerprint, version,
                    created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'HELD', ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """, holdId, actor.tenantId(), page.pageId(), page.calendarId(), actor.userId(),
                actor.personPublicId(), value.startsAt(), value.endsAt(), blockedStartsAt,
                blockedEndsAt, expiresAt, page.version(), value.idempotencyKey(), fingerprint,
                now, now, actor.userId());
        return lockHold(actor.tenantId(), page.pageId(), holdId)
                .orElseThrow(() -> new IllegalStateException("Booking hold was not persisted."));
    }

    int reservationsInRange(
            long tenantId,
            UUID pageId,
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime now) {
        Integer count = jdbc.queryForObject("""
                SELECT (
                    SELECT count(*) FROM cal_booking_holds
                     WHERE tenant_id = ? AND page_id = ? AND starts_at >= ? AND starts_at < ?
                       AND state = 'HELD' AND expires_at > ?
                ) + (
                    SELECT count(*) FROM cal_booking_appointments
                     WHERE tenant_id = ? AND page_id = ? AND starts_at >= ? AND starts_at < ?
                       AND state = 'CONFIRMED'
                )
                """, Integer.class, tenantId, pageId, from, to, now,
                tenantId, pageId, from, to);
        return count == null ? 0 : count;
    }

    List<TimeRange> activeHoldRanges(
            long tenantId,
            UUID calendarId,
            OffsetDateTime from,
            OffsetDateTime to,
            OffsetDateTime now) {
        return jdbc.query("""
                SELECT blocked_starts_at, blocked_ends_at
                  FROM cal_booking_holds
                 WHERE tenant_id = ? AND host_calendar_id = ?
                   AND state = 'HELD' AND expires_at > ?
                   AND blocked_starts_at < ? AND blocked_ends_at > ?
                 ORDER BY blocked_starts_at, fencing_token
                """, (result, ignored) -> new TimeRange(
                        result.getObject("blocked_starts_at", OffsetDateTime.class),
                        result.getObject("blocked_ends_at", OffsetDateTime.class)),
                tenantId, calendarId, now, to, from);
    }

    List<HostBusyRow> hostBusy(
            long tenantId,
            UUID personPublicId,
            OffsetDateTime from,
            OffsetDateTime to,
            UUID excludedEventId) {
        return jdbc.query("""
                WITH relevant_events AS (
                    SELECT event.tenant_id, event.event_id, event.starts_at, event.ends_at,
                           event.time_zone, event.recurrence_pattern,
                           event.recurrence_interval, event.recurrence_until,
                           event.recurrence_count, event.recurrence_days
                      FROM cal_events event
                      LEFT JOIN cal_identity_links organizer_identity
                        ON organizer_identity.tenant_id = event.tenant_id
                       AND organizer_identity.user_id = event.organizer_user_id
                     WHERE event.tenant_id = ? AND event.status <> 'CANCELLED'
                       AND event.deleted_at IS NULL
                       AND (?::uuid IS NULL OR event.event_id <> ?::uuid)
                       AND (
                           COALESCE(event.organizer_person_public_id,
                                    organizer_identity.person_public_id) = ?
                           OR EXISTS (
                               SELECT 1 FROM cal_event_attendees attendee
                                WHERE attendee.event_id = event.event_id
                                  AND attendee.response_status <> 'DECLINED'
                                  AND attendee.attendee_person_public_id = ?))
                       AND (event.starts_at < ? OR EXISTS (
                           SELECT 1 FROM cal_event_occurrence_overrides moved
                            WHERE moved.tenant_id = event.tenant_id
                              AND moved.event_id = event.event_id
                              AND moved.override_kind = 'MODIFIED'
                              AND moved.starts_at < ? AND moved.ends_at > ?))
                ), base_occurrences AS (
                    SELECT event.tenant_id, event.event_id, occurrence.original_starts_at,
                           occurrence.starts_at, occurrence.ends_at
                      FROM relevant_events event
                      CROSS JOIN LATERAL cal_project_recurrences(
                          event.starts_at, event.ends_at, event.time_zone,
                          event.recurrence_pattern, event.recurrence_interval,
                          event.recurrence_until, event.recurrence_count,
                          event.recurrence_days, ?::timestamptz, ?::timestamptz,
                          4000) occurrence
                ), occurrences AS (
                    SELECT base.event_id, base.starts_at, base.ends_at
                      FROM base_occurrences base
                      LEFT JOIN cal_event_occurrence_overrides override
                        ON override.tenant_id = base.tenant_id
                       AND override.event_id = base.event_id
                       AND override.original_starts_at = base.original_starts_at
                     WHERE override.event_id IS NULL
                    UNION ALL
                    SELECT event.event_id, override.starts_at, override.ends_at
                      FROM relevant_events event
                      JOIN cal_event_occurrence_overrides override
                        ON override.tenant_id = event.tenant_id
                       AND override.event_id = event.event_id
                       AND override.override_kind = 'MODIFIED'
                )
                SELECT event_id, starts_at, ends_at FROM occurrences
                 WHERE starts_at IS NOT NULL AND ends_at IS NOT NULL
                   AND starts_at < ? AND ends_at > ?
                 ORDER BY starts_at, event_id
                """, (result, ignored) -> new HostBusyRow(
                        result.getObject("event_id", UUID.class),
                        result.getObject("starts_at", OffsetDateTime.class),
                        result.getObject("ends_at", OffsetDateTime.class)),
                tenantId, excludedEventId, excludedEventId, personPublicId, personPublicId,
                to, to, from, from, to, to, from);
    }

    int cancelHold(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            UUID holdId,
            long fencingToken,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_booking_holds
                   SET state = 'CANCELLED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND page_id = ? AND hold_id = ?
                   AND booker_user_id = ? AND booker_person_public_id = ?
                   AND state = 'HELD' AND fencing_token = ?
                """, now, actor.userId(), actor.tenantId(), pageId, holdId,
                actor.userId(), actor.personPublicId(), fencingToken);
    }

    int consumeHold(
            CalendarBookingAccess.Actor actor,
            HoldRow hold,
            long fencingToken,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_booking_holds
                   SET state = 'CONSUMED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND hold_id = ? AND page_id = ?
                   AND booker_user_id = ? AND booker_person_public_id = ?
                   AND state = 'HELD' AND expires_at > ?
                   AND fencing_token = ? AND version = ?
                """, now, actor.userId(), actor.tenantId(), hold.holdId(), hold.pageId(),
                actor.userId(), actor.personPublicId(), now, fencingToken, hold.version());
    }

    Optional<AppointmentRow> appointmentByIdempotency(
            long tenantId, UUID pageId, long userId, UUID idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM cal_booking_appointments
                 WHERE tenant_id = ? AND page_id = ?
                   AND booker_user_id = ? AND idempotency_key = ?
                """, this::appointment, tenantId, pageId, userId, idempotencyKey)
                .stream().findFirst();
    }

    Optional<AppointmentRow> appointment(long tenantId, UUID appointmentId) {
        return jdbc.query("""
                SELECT * FROM cal_booking_appointments
                 WHERE tenant_id = ? AND appointment_id = ?
                """, this::appointment, tenantId, appointmentId).stream().findFirst();
    }

    Optional<AppointmentRow> bookerAppointment(
            long tenantId, long userId, UUID personPublicId, UUID appointmentId) {
        return jdbc.query("""
                SELECT * FROM cal_booking_appointments
                 WHERE tenant_id = ? AND booker_user_id = ?
                   AND booker_person_public_id = ? AND appointment_id = ?
                """, this::appointment, tenantId, userId, personPublicId, appointmentId)
                .stream().findFirst();
    }

    List<AppointmentRow> bookerAppointments(
            long tenantId, long userId, UUID personPublicId, int limit) {
        return jdbc.query("""
                SELECT * FROM cal_booking_appointments
                 WHERE tenant_id = ? AND booker_user_id = ?
                   AND booker_person_public_id = ?
                 ORDER BY starts_at DESC, appointment_id
                 LIMIT ?
                """, this::appointment, tenantId, userId, personPublicId, limit);
    }

    Optional<AppointmentRow> lockAppointment(long tenantId, UUID appointmentId) {
        return jdbc.query("""
                SELECT * FROM cal_booking_appointments
                 WHERE tenant_id = ? AND appointment_id = ?
                 FOR UPDATE
                """, this::appointment, tenantId, appointmentId).stream().findFirst();
    }

    void insertAppointment(
            UUID appointmentId,
            CalendarBookingAccess.Actor actor,
            PageRow page,
            HoldRow hold,
            UUID eventId,
            UUID supersedesAppointmentId,
            List<BookingAnswer> answers,
            String managementTokenHash,
            long managementTokenVersion,
            UUID idempotencyKey,
            String fingerprint,
            OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO cal_booking_appointments (
                    appointment_id, tenant_id, page_id, hold_id, event_id,
                    booker_user_id, booker_person_public_id, state, starts_at, ends_at,
                    supersedes_appointment_id, answers, management_token_hash,
                    management_token_version, idempotency_key, request_fingerprint,
                    version, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?::jsonb,
                        ?, ?, ?, ?, 0, ?, ?, ?)
                """, appointmentId, actor.tenantId(), page.pageId(), hold.holdId(), eventId,
                actor.userId(), actor.personPublicId(), hold.startsAt(), hold.endsAt(),
                supersedesAppointmentId, write(answers), managementTokenHash,
                managementTokenVersion, idempotencyKey, fingerprint, now, now, actor.userId());
    }

    int markRescheduled(
            CalendarBookingAccess.Actor actor,
            AppointmentRow appointment,
            OffsetDateTime purgeAfter,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_booking_appointments
                   SET state = 'RESCHEDULED', management_token_hash = NULL,
                       purge_after = CASE WHEN legal_hold THEN NULL::timestamptz
                           ELSE ?::timestamptz END,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND appointment_id = ?
                   AND state = 'CONFIRMED' AND version = ?
                """, purgeAfter, now, actor.userId(), actor.tenantId(),
                appointment.appointmentId(), appointment.version());
    }

    int cancelAppointment(
            CalendarBookingAccess.Actor actor,
            AppointmentRow appointment,
            OffsetDateTime purgeAfter,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_booking_appointments
                   SET state = 'CANCELLED', management_token_hash = NULL,
                       cancelled_at = ?, purge_after = CASE WHEN legal_hold
                           THEN NULL::timestamptz ELSE ?::timestamptz END,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND appointment_id = ?
                   AND state = 'CONFIRMED' AND version = ?
                """, now, purgeAfter, now, actor.userId(), actor.tenantId(),
                appointment.appointmentId(), appointment.version());
    }

    int rotateAppointmentToken(
            CalendarBookingAccess.Actor actor,
            AppointmentRow appointment,
            String tokenHash,
            long tokenVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE cal_booking_appointments
                   SET management_token_hash = ?, management_token_version = ?,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND appointment_id = ?
                   AND state = 'CONFIRMED' AND version = ?
                """, tokenHash, tokenVersion, now, actor.userId(), actor.tenantId(),
                appointment.appointmentId(), appointment.version());
    }

    private PageRow page(java.sql.ResultSet result, int ignored) throws java.sql.SQLException {
        return new PageRow(
                result.getObject("page_id", UUID.class),
                result.getLong("tenant_id"),
                result.getLong("owner_user_id"),
                result.getObject("owner_person_public_id", UUID.class),
                BookingOwnerType.valueOf(result.getString("owner_type")),
                result.getObject("owner_group_ref", UUID.class),
                result.getObject("calendar_id", UUID.class),
                BookingPageState.valueOf(result.getString("state")),
                result.getString("title"), result.getString("description"),
                result.getString("time_zone"), result.getInt("duration_minutes"),
                result.getInt("buffer_before_minutes"), result.getInt("buffer_after_minutes"),
                result.getInt("daily_limit"), result.getInt("minimum_notice_minutes"),
                result.getInt("maximum_advance_days"),
                MeetingMode.valueOf(result.getString("meeting_mode")),
                result.getString("location"),
                result.getString("conference_url"),
                result.getString("dial_in_instructions"),
                read(result.getString("availability_rules"), RULES),
                read(result.getString("questions"), QUESTIONS),
                result.getString("public_token_hash"),
                result.getLong("public_token_version"), result.getBoolean("legal_hold"),
                result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private HoldRow hold(java.sql.ResultSet result, int ignored) throws java.sql.SQLException {
        return new HoldRow(
                result.getObject("hold_id", UUID.class),
                result.getLong("tenant_id"), result.getObject("page_id", UUID.class),
                result.getLong("booker_user_id"),
                result.getObject("booker_person_public_id", UUID.class),
                BookingHoldState.valueOf(result.getString("state")),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getObject("blocked_starts_at", OffsetDateTime.class),
                result.getObject("blocked_ends_at", OffsetDateTime.class),
                result.getObject("expires_at", OffsetDateTime.class),
                result.getLong("fencing_token"), result.getLong("page_version"),
                result.getObject("idempotency_key", UUID.class),
                result.getString("request_fingerprint"), result.getLong("version"));
    }

    private AppointmentRow appointment(
            java.sql.ResultSet result, int ignored) throws java.sql.SQLException {
        return new AppointmentRow(
                result.getObject("appointment_id", UUID.class),
                result.getLong("tenant_id"), result.getObject("page_id", UUID.class),
                result.getObject("hold_id", UUID.class),
                result.getObject("event_id", UUID.class), result.getLong("booker_user_id"),
                result.getObject("booker_person_public_id", UUID.class),
                AppointmentState.valueOf(result.getString("state")),
                result.getObject("starts_at", OffsetDateTime.class),
                result.getObject("ends_at", OffsetDateTime.class),
                result.getObject("supersedes_appointment_id", UUID.class),
                read(result.getString("answers"), ANSWERS),
                result.getString("management_token_hash"),
                result.getLong("management_token_version"),
                result.getObject("idempotency_key", UUID.class),
                result.getString("request_fingerprint"), result.getBoolean("legal_hold"),
                result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Calendar booking JSON is invalid.", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Calendar booking JSON is invalid.", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record IdentityProfile(long userId, UUID personPublicId, String email, String displayName) { }

    record TimeRange(OffsetDateTime startsAt, OffsetDateTime endsAt) { }

    record HostBusyRow(UUID eventId, OffsetDateTime startsAt, OffsetDateTime endsAt) { }

    record PageRow(
            UUID pageId,
            long tenantId,
            long ownerUserId,
            UUID ownerPersonPublicId,
            BookingOwnerType ownerType,
            UUID ownerGroupRef,
            UUID calendarId,
            BookingPageState state,
            String title,
            String description,
            String timeZone,
            int durationMinutes,
            int bufferBeforeMinutes,
            int bufferAfterMinutes,
            int dailyLimit,
            int minimumNoticeMinutes,
            int maximumAdvanceDays,
            MeetingMode meetingMode,
            String location,
            String conferenceUrl,
            String dialInInstructions,
            List<AvailabilityRule> availabilityRules,
            List<BookingQuestion> questions,
            String publicTokenHash,
            long publicTokenVersion,
            boolean legalHold,
            long version,
            OffsetDateTime updatedAt) { }

    record HoldRow(
            UUID holdId,
            long tenantId,
            UUID pageId,
            long bookerUserId,
            UUID bookerPersonPublicId,
            BookingHoldState state,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime blockedStartsAt,
            OffsetDateTime blockedEndsAt,
            OffsetDateTime expiresAt,
            long fencingToken,
            long pageVersion,
            UUID idempotencyKey,
            String requestFingerprint,
            long version) { }

    record AppointmentRow(
            UUID appointmentId,
            long tenantId,
            UUID pageId,
            UUID holdId,
            UUID eventId,
            long bookerUserId,
            UUID bookerPersonPublicId,
            AppointmentState state,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID supersedesAppointmentId,
            List<BookingAnswer> answers,
            String managementTokenHash,
            long managementTokenVersion,
            UUID idempotencyKey,
            String requestFingerprint,
            boolean legalHold,
            long version,
            OffsetDateTime updatedAt) { }
}
