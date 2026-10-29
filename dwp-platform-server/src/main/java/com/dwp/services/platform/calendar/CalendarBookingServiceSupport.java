package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarBookingDtos.*;
import static com.dwp.services.platform.calendar.CalendarTypes.*;

/** Shared validation, mapping, and ownership support for the booking transaction service. */
abstract class CalendarBookingServiceSupport {

    static final String PUBLIC_DISABLED_REASON =
            "External public booking is unavailable until bot protection, verified email, "
                    + "rate limiting, and security approval are installed.";

    final CalendarBookingRepository bookings;
    final CalendarRepository calendar;
    final CalendarService calendarService;
    final PlatformAuditService audit;
    final CalendarBookingTokens tokens;
    final Clock clock;
    final Duration holdTtl;
    final Duration retention;

    CalendarBookingServiceSupport(
            CalendarBookingRepository bookings,
            CalendarRepository calendar,
            CalendarService calendarService,
            PlatformAuditService audit,
            CalendarBookingTokens tokens,
            Clock clock,
            boolean externalPublicRequested,
            Duration holdTtl,
            Duration retention) {
        if (externalPublicRequested) {
            throw new IllegalStateException(PUBLIC_DISABLED_REASON);
        }
        if (holdTtl == null || holdTtl.isZero() || holdTtl.isNegative()
                || holdTtl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Calendar booking hold TTL must be 1-600 seconds.");
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("Calendar booking retention must be positive.");
        }
        this.bookings = bookings;
        this.calendar = calendar;
        this.calendarService = calendarService;
        this.audit = audit;
        this.tokens = tokens;
        this.clock = clock;
        this.holdTtl = holdTtl;
        this.retention = retention;
    }

    CalendarBookingRepository.PageRow tokenPage(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            RotateBookingTokenRequest request) {
        requireIdentity(actor);
        if (request == null || request.version() == null || request.version() < 0) {
            throw invalid("Booking token version is required.");
        }
        CalendarBookingRepository.PageRow page = managedPage(actor, pageId, true);
        if (page.version() != request.version()) throw changed();
        if (page.state() != BookingPageState.PUBLISHED
                && page.state() != BookingPageState.PAUSED) {
            throw conflict("Only a published or paused booking page can manage a public token.");
        }
        return page;
    }

    CalendarBookingRepository.AppointmentRow tokenAppointment(
            CalendarBookingAccess.Actor actor,
            UUID appointmentId,
            RotateBookingTokenRequest request) {
        requireIdentity(actor);
        if (request == null || request.version() == null || request.version() < 0) {
            throw invalid("Appointment token version is required.");
        }
        CalendarBookingRepository.AppointmentRow appointment = bookings.lockAppointment(
                        actor.tenantId(), appointmentId)
                .orElseThrow(this::notFound);
        if (!sameBooker(actor, appointment)) throw CalendarBookingAccess.denied();
        if (appointment.state() != AppointmentState.CONFIRMED
                || appointment.version() != request.version()) throw changed();
        return appointment;
    }

    CalendarBookingRepository.PageRow managedPage(
            CalendarBookingAccess.Actor actor, UUID pageId, boolean lock) {
        CalendarBookingRepository.PageRow page = (lock
                ? bookings.lockPage(actor.tenantId(), pageId)
                : bookings.page(actor.tenantId(), pageId)).orElseThrow(this::notFound);
        if (!canManagePage(actor, page)) throw new BaseException(ErrorCode.NOT_FOUND);
        return page;
    }

    boolean canManagePage(
            CalendarBookingAccess.Actor actor,
            CalendarBookingRepository.PageRow page) {
        CalendarRepository.CalendarAccessDecision access = calendar.calendarAccess(
                        actor.tenantId(), actor.userId(), actor.personPublicId(),
                        actor.groupRefs(), page.calendarId())
                .orElse(null);
        if (access == null) return false;
        if (page.ownerType() == BookingOwnerType.PERSONAL) {
            return page.ownerPersonPublicId().equals(actor.personPublicId())
                    && access.calendarType() == CalendarType.PERSONAL
                    && access.accessLevel() == CalendarAccessLevel.OWNER
                    && actor.personPublicId().equals(access.ownerPersonPublicId());
        }
        return actor.hasGroup(page.ownerGroupRef())
                && access.calendarType() == CalendarType.TEAM
                && (access.accessLevel() == CalendarAccessLevel.EDIT
                || access.accessLevel() == CalendarAccessLevel.MANAGE)
                && bookings.hasActiveGroupWriteGrant(
                actor.tenantId(), page.calendarId(), page.ownerGroupRef(), now());
    }

    CalendarBookingRepository.PageRow bookableRow(
            CalendarBookingAccess.Actor actor, UUID pageId) {
        CalendarBookingRepository.PageRow page = bookings.page(actor.tenantId(), pageId)
                .orElseThrow(this::notFound);
        if (page.state() != BookingPageState.PUBLISHED) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return page;
    }

    void requireRequestedOwner(
            CalendarBookingAccess.Actor actor,
            BookingOwnerType ownerType,
            UUID ownerGroupRef) {
        if (ownerType == BookingOwnerType.TEAM && !actor.hasGroup(ownerGroupRef)) {
            throw CalendarBookingAccess.denied();
        }
    }

    void requireCompatibleCalendar(
            CalendarBookingAccess.Actor actor, SaveBookingPageRequest request) {
        CalendarRepository.CalendarAccessDecision access = calendar.calendarAccess(
                        actor.tenantId(), actor.userId(), actor.personPublicId(),
                        actor.groupRefs(), request.calendarId())
                .orElseThrow(this::notFound);
        if (access.accessLevel() != CalendarAccessLevel.OWNER
                && access.accessLevel() != CalendarAccessLevel.MANAGE
                && access.accessLevel() != CalendarAccessLevel.EDIT) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The target calendar is not writable for booking pages.");
        }
        CalendarType required = request.ownerType() == BookingOwnerType.PERSONAL
                ? CalendarType.PERSONAL : CalendarType.TEAM;
        if (access.calendarType() != required
                || (required == CalendarType.PERSONAL
                && !actor.personPublicId().equals(access.ownerPersonPublicId()))) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Booking ownership must match the target calendar.");
        }
        if (required == CalendarType.TEAM && !bookings.hasActiveGroupWriteGrant(
                actor.tenantId(), request.calendarId(), request.ownerGroupRef(), now())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The verified owner group does not hold an active calendar write grant.");
        }
    }

    void requireIdentity(CalendarBookingAccess.Actor actor) {
        if (actor == null || actor.personPublicId() == null
                || !bookings.identityMatches(
                actor.tenantId(), actor.userId(), actor.personPublicId())) {
            throw CalendarBookingAccess.denied();
        }
    }

    CalendarBookingRepository.IdentityProfile profile(
            CalendarBookingAccess.Actor actor) {
        return bookings.identityProfile(
                        actor.tenantId(), actor.userId(), actor.personPublicId())
                .orElseThrow(() -> conflict(
                        "The authenticated booker does not have an active verified email profile."));
    }

    void validateHoldRequest(CreateBookingHoldRequest request) {
        if (request == null || request.startsAt() == null || request.endsAt() == null
                || request.idempotencyKey() == null || request.pageVersion() == null
                || request.pageVersion() < 0 || !request.endsAt().isAfter(request.startsAt())
                || request.startsAt().getSecond() != 0 || request.startsAt().getNano() != 0
                || request.endsAt().getSecond() != 0 || request.endsAt().getNano() != 0) {
            throw invalid("Booking hold time and idempotency values are invalid.");
        }
    }

    void validateSlot(
            CalendarBookingRepository.PageRow page,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        if (!Duration.between(startsAt, endsAt)
                .equals(Duration.ofMinutes(page.durationMinutes()))) {
            throw invalid("Booking duration must match the page configuration.");
        }
        OffsetDateTime now = now();
        if (startsAt.isBefore(now.plusMinutes(page.minimumNoticeMinutes()))
                || startsAt.isAfter(now.plusDays(page.maximumAdvanceDays()))) {
            throw invalid("Booking time is outside the configured notice window.");
        }
        ZoneId zone = ZoneId.of(page.timeZone());
        ZonedDateTime localStart = startsAt.atZoneSameInstant(zone);
        ZonedDateTime localEnd = endsAt.atZoneSameInstant(zone);
        if (!localStart.toLocalDate().equals(localEnd.toLocalDate())) {
            throw invalid("Booking slots must remain within one local day.");
        }
        boolean available = page.availabilityRules().stream().anyMatch(rule ->
                rule.dayOfWeek() == localStart.getDayOfWeek()
                        && !localStart.toLocalTime().isBefore(rule.startsAt())
                        && !localEnd.toLocalTime().isAfter(rule.endsAt()));
        if (!available) throw invalid("Booking time is outside configured availability.");
    }

    void validateConfirmRequest(ConfirmBookingRequest request) {
        if (request == null || request.holdId() == null || request.fencingToken() == null
                || request.fencingToken() < 1 || request.idempotencyKey() == null
                || request.answers() == null || request.answers().size() > 20) {
            throw invalid("Booking confirmation request is invalid.");
        }
    }

    List<BookingAnswer> validateAnswers(
            List<BookingQuestion> questions, List<BookingAnswer> answers) {
        Map<String, BookingQuestion> expected = new HashMap<>();
        questions.forEach(question -> expected.put(question.key(), question));
        Set<String> keys = new HashSet<>();
        for (BookingAnswer answer : answers) {
            if (answer == null || answer.key() == null || answer.value() == null
                    || answer.value().isBlank() || answer.value().length() > 2000
                    || !keys.add(answer.key())) {
                throw invalid("Booking answers are invalid or duplicated.");
            }
            BookingQuestion question = expected.get(answer.key());
            if (question == null) throw invalid("Booking answer key is not configured.");
            if (question.type() == QuestionType.SINGLE_SELECT
                    && question.options().stream().noneMatch(answer.value()::equals)) {
                throw invalid("Booking answer is not an allowed option.");
            }
        }
        if (questions.stream().anyMatch(question -> question.required()
                && !keys.contains(question.key()))) {
            throw invalid("All required booking questions must be answered.");
        }
        return List.copyOf(answers);
    }

    void requireConsumable(
            CalendarBookingAccess.Actor actor,
            CalendarBookingRepository.PageRow page,
            CalendarBookingRepository.HoldRow hold,
            long fencingToken,
            OffsetDateTime now) {
        if (!sameBooker(actor, hold) || hold.state() != BookingHoldState.HELD
                || !hold.expiresAt().isAfter(now) || hold.fencingToken() != fencingToken
                || hold.pageVersion() != page.version()
                || page.state() != BookingPageState.PUBLISHED) {
            throw conflict("The booking hold is stale, expired, or not consumable.");
        }
    }

    CalendarDtos.CreateEventRequest createEventRequest(
            CalendarBookingRepository.PageRow page,
            CalendarBookingRepository.HoldRow hold,
            CalendarBookingRepository.IdentityProfile booker,
            UUID idempotencyKey) {
        return new CalendarDtos.CreateEventRequest(
                page.title(), page.description(), EventType.MEETING,
                hold.startsAt(), hold.endsAt(), page.timeZone(), false,
                page.meetingMode() == MeetingMode.PHONE
                        ? page.dialInInstructions() : page.location(),
                page.conferenceUrl(),
                EventVisibility.DEFAULT, RecurrencePattern.NONE, 1, null, false,
                List.of(new CalendarDtos.AttendeeInput(
                        booker.userId(), booker.personPublicId(), booker.email(),
                        booker.displayName(), AttendeeType.REQUIRED)),
                null, idempotencyKey, page.calendarId(), EventImportance.NORMAL);
    }

    CalendarDtos.UpdateEventRequest updateEventRequest(
            CalendarRepository.EventRow event,
            CalendarBookingRepository.HoldRow hold,
            List<CalendarDtos.AttendeeInput> attendees) {
        return new CalendarDtos.UpdateEventRequest(
                event.title(), event.description(), event.type(),
                hold.startsAt(), hold.endsAt(), event.timeZone(), event.allDay(),
                event.location(), event.conferenceUrl(), event.visibility(),
                event.recurrence(), event.recurrenceInterval(), event.recurrenceUntil(),
                event.responseRequired(), attendees,
                event.resource() == null ? null : event.resource().resourceId(),
                event.version(), event.importance());
    }

    CalendarRepository.EventRow ownerEvent(
            CalendarBookingRepository.PageRow page, UUID eventId, String locale) {
        return CalendarRepositoryRouting.event(
                        calendar, page.tenantId(), page.ownerUserId(),
                        page.ownerPersonPublicId(), pageGroup(page), eventId,
                        locale != null && locale.toLowerCase(java.util.Locale.ROOT)
                                .startsWith("ko"))
                .orElseThrow(this::notFound);
    }

    String pageGroup(CalendarBookingRepository.PageRow page) {
        return page.ownerType() == BookingOwnerType.TEAM
                ? page.ownerGroupRef().toString() : null;
    }

    boolean sameBooker(
            CalendarBookingAccess.Actor actor, CalendarBookingRepository.HoldRow hold) {
        return hold.bookerUserId() == actor.userId()
                && hold.bookerPersonPublicId().equals(actor.personPublicId());
    }

    boolean sameBooker(
            CalendarBookingAccess.Actor actor,
            CalendarBookingRepository.AppointmentRow appointment) {
        return appointment.bookerUserId() == actor.userId()
                && appointment.bookerPersonPublicId().equals(actor.personPublicId());
    }

    BookingPage page(CalendarBookingRepository.PageRow value) {
        return new BookingPage(
                value.pageId(), value.ownerType(), value.ownerPersonPublicId(),
                value.ownerGroupRef(), value.calendarId(), value.state(), value.title(),
                value.description(), value.timeZone(), value.durationMinutes(),
                value.bufferBeforeMinutes(), value.bufferAfterMinutes(), value.dailyLimit(),
                value.minimumNoticeMinutes(), value.maximumAdvanceDays(), value.meetingMode(),
                value.location(), value.conferenceUrl(), value.dialInInstructions(),
                value.availabilityRules(), value.questions(),
                value.publicTokenHash() != null, value.publicTokenVersion(), value.version(),
                value.updatedAt());
    }

    AuthenticatedBookablePage bookable(CalendarBookingRepository.PageRow value) {
        return new AuthenticatedBookablePage(
                value.pageId(), value.version(), value.title(), value.description(),
                value.timeZone(), value.durationMinutes(), value.meetingMode(),
                value.location(), value.conferenceUrl(), value.dialInInstructions(),
                value.questions());
    }

    BookingHold hold(CalendarBookingRepository.HoldRow value) {
        return new BookingHold(
                value.holdId(), value.pageId(), value.bookerPersonPublicId(), value.state(),
                value.startsAt(), value.endsAt(), value.expiresAt(), value.fencingToken(),
                value.version());
    }

    Appointment appointment(CalendarBookingRepository.AppointmentRow value) {
        return new Appointment(
                value.appointmentId(), value.pageId(), value.eventId(),
                value.bookerPersonPublicId(), value.state(), value.startsAt(), value.endsAt(),
                value.supersedesAppointmentId(), value.version(), value.updatedAt());
    }

    OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    static boolean overlaps(
            OffsetDateTime firstStart,
            OffsetDateTime firstEnd,
            OffsetDateTime secondStart,
            OffsetDateTime secondEnd) {
        return firstStart.isBefore(secondEnd) && firstEnd.isAfter(secondStart);
    }

    BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    BaseException changed() {
        return conflict("The Calendar booking value changed. Refresh and try again.");
    }

    BaseException idempotencyConflict() {
        return conflict("The idempotency key was already used with a different request.");
    }

    BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    BaseException conflict(String message, Throwable cause) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }
}
