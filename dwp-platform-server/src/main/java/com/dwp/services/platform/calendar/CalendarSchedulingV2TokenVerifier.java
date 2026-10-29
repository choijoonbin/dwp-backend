package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Atomically binds a short-lived scheduling PASS to the event command that applies it. */
@Component
final class CalendarSchedulingV2TokenVerifier {

    private final CalendarSchedulingV2Repository repository;
    private final Clock clock;

    @Autowired
    CalendarSchedulingV2TokenVerifier(CalendarSchedulingV2Repository repository) {
        this(repository, Clock.systemUTC());
    }

    CalendarSchedulingV2TokenVerifier(
            CalendarSchedulingV2Repository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    void consume(
            long tenantId,
            long actorUserId,
            UUID actorPersonPublicId,
            String token,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID roomId,
            String timeZone,
            CalendarTypes.EventType eventType,
            boolean allDay,
            CalendarTypes.RecurrencePattern recurrence,
            int recurrenceInterval,
            LocalDate recurrenceUntil,
            Long resourceVersion,
            long policyVersion,
            List<CalendarDtos.AttendeeInput> attendees,
            UUID eventId) {
        if (actorPersonPublicId == null || token == null || token.isBlank()
                || token.length() > 256 || startsAt == null || endsAt == null
                || eventId == null || timeZone == null || timeZone.isBlank()
                || eventType != CalendarTypes.EventType.MEETING || allDay
                || recurrence != CalendarTypes.RecurrencePattern.NONE
                || recurrenceInterval != 1 || recurrenceUntil != null
                || !repository.identityMatches(
                        tenantId, actorUserId, actorPersonPublicId)) {
            throw denied();
        }
        if (attendees != null && attendees.stream().anyMatch(
                attendee -> attendee == null || attendee.personPublicId() == null
                        || attendee.type() == null)) {
            throw denied();
        }
        List<CalendarDtos.AttendeeInput> eventAttendees = attendees == null
                ? List.of() : attendees;
        // External attendees cannot be reconstructed from the UUID-bound evaluation evidence.
        if (attendees != null && attendees.stream().anyMatch(
                attendee -> attendee.type() == CalendarTypes.AttendeeType.RESOURCE)) {
            throw denied();
        }
        String participantHash = CalendarSchedulingV2Hashes.eventParticipants(
                actorPersonPublicId, eventAttendees);
        String selectionHash = CalendarSchedulingV2Hashes.selection(
                startsAt, endsAt, roomId, timeZone);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        boolean consumed = repository.consumeToken(
                tenantId, actorUserId, actorPersonPublicId,
                CalendarSchedulingV2Hashes.sha256(token), participantHash, selectionHash,
                repository.sourceVersion(tenantId), roomId, resourceVersion,
                policyVersion, now, eventId);
        if (!consumed) throw denied();
    }

    private BaseException denied() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The Calendar hard-recheck evidence is invalid, expired, or already used.");
    }
}
