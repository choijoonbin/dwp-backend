package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarBookingDtos.*;
import static com.dwp.services.platform.calendar.CalendarTypes.*;

@Service
public class CalendarBookingService {

    private static final String PUBLIC_DISABLED_REASON =
            "External public booking is unavailable until bot protection, verified email, "
                    + "rate limiting, and security approval are installed.";

    private final CalendarBookingRepository bookings;
    private final CalendarRepository calendar;
    private final CalendarService calendarService;
    private final PlatformAuditService audit;
    private final CalendarBookingTokens tokens;
    private final Clock clock;
    private final Duration holdTtl;
    private final Duration retention;

    @Autowired
    public CalendarBookingService(
            CalendarBookingRepository bookings,
            CalendarRepository calendar,
            CalendarService calendarService,
            PlatformAuditService audit,
            ObjectMapper json,
            @Value("${dwp.platform.calendar.booking.external-public-enabled:false}")
                    boolean externalPublicRequested,
            @Value("${dwp.platform.calendar.booking.hold-ttl-seconds:300}")
                    int holdTtlSeconds,
            @Value("${dwp.platform.calendar.booking.retention-days:365}")
                    int retentionDays) {
        this(bookings, calendar, calendarService, audit, new CalendarBookingTokens(json),
                Clock.systemUTC(), externalPublicRequested,
                Duration.ofSeconds(holdTtlSeconds), Duration.ofDays(retentionDays));
    }

    CalendarBookingService(
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

    public BookingSecurityCapabilities capabilities() {
        return new BookingSecurityCapabilities(
                true, false, PUBLIC_DISABLED_REASON, Math.toIntExact(holdTtl.toSeconds()));
    }

    @Transactional(readOnly = true)
    public List<BookingPage> pages(CalendarBookingAccess.Actor actor) {
        requireIdentity(actor);
        return bookings.pages(actor.tenantId(), actor.personPublicId(), actor.groupRefs())
                .stream().filter(value -> canManagePage(actor, value))
                .map(this::page).toList();
    }

    @Transactional(readOnly = true)
    public BookingPage page(CalendarBookingAccess.Actor actor, UUID pageId) {
        requireIdentity(actor);
        return page(managedPage(actor, pageId, false));
    }

    @Transactional(readOnly = true)
    public AuthenticatedBookablePage bookablePage(
            CalendarBookingAccess.Actor actor, UUID pageId) {
        requireIdentity(actor);
        return bookable(bookableRow(actor, pageId));
    }

    @Transactional(readOnly = true)
    public BookingAvailability availability(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            OffsetDateTime from,
            OffsetDateTime to) {
        requireIdentity(actor);
        OffsetDateTime generatedAt = now();
        if (from == null || to == null || !to.isAfter(from)
                || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0
                || to.isBefore(generatedAt)) {
            throw invalid("Booking availability range must be future-facing and at most 31 days.");
        }
        CalendarBookingRepository.PageRow page = bookableRow(actor, pageId);
        OffsetDateTime effectiveFrom = from.isBefore(generatedAt) ? generatedAt : from;
        List<CalendarBookingRepository.HostBusyRow> busy = bookings.hostBusy(
                actor.tenantId(), page.ownerPersonPublicId(),
                effectiveFrom.minusMinutes(page.bufferBeforeMinutes()),
                to.plusMinutes(page.bufferAfterMinutes()), null);
        List<CalendarBookingRepository.TimeRange> holds = bookings.activeHoldRanges(
                actor.tenantId(), page.calendarId(),
                effectiveFrom.minusMinutes(page.bufferBeforeMinutes()),
                to.plusMinutes(page.bufferAfterMinutes()), generatedAt);
        ZoneId zone = ZoneId.of(page.timeZone());
        LocalDate first = effectiveFrom.atZoneSameInstant(zone).toLocalDate();
        LocalDate last = to.atZoneSameInstant(zone).toLocalDate();
        Map<LocalDate, Boolean> dailyCapacity = new HashMap<>();
        java.util.ArrayList<AvailabilitySlot> slots = new java.util.ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last) && slots.size() < 500;
                date = date.plusDays(1)) {
            ZonedDateTime dayStart = date.atStartOfDay(zone);
            ZonedDateTime nextDay = date.plusDays(1).atStartOfDay(zone);
            boolean capacity = dailyCapacity.computeIfAbsent(date, ignored ->
                    bookings.reservationsInRange(
                            actor.tenantId(), page.pageId(), dayStart.toOffsetDateTime(),
                            nextDay.toOffsetDateTime(), generatedAt) < page.dailyLimit());
            if (!capacity) continue;
            for (AvailabilityRule rule : page.availabilityRules()) {
                if (rule.dayOfWeek() != date.getDayOfWeek()) continue;
                ZonedDateTime candidate = ZonedDateTime.of(date, rule.startsAt(), zone);
                ZonedDateTime ruleEnd = ZonedDateTime.of(date, rule.endsAt(), zone);
                while (!candidate.plusMinutes(page.durationMinutes()).isAfter(ruleEnd)
                        && slots.size() < 500) {
                    OffsetDateTime startsAt = candidate.toOffsetDateTime();
                    OffsetDateTime endsAt = candidate.plusMinutes(
                            page.durationMinutes()).toOffsetDateTime();
                    OffsetDateTime blockedStart = startsAt.minusMinutes(
                            page.bufferBeforeMinutes());
                    OffsetDateTime blockedEnd = endsAt.plusMinutes(page.bufferAfterMinutes());
                    boolean inRange = !startsAt.isBefore(effectiveFrom)
                            && !endsAt.isAfter(to)
                            && !startsAt.isBefore(generatedAt.plusMinutes(
                            page.minimumNoticeMinutes()))
                            && !startsAt.isAfter(generatedAt.plusDays(
                            page.maximumAdvanceDays()));
                    boolean eventFree = busy.stream().noneMatch(value -> overlaps(
                            blockedStart, blockedEnd, value.startsAt(), value.endsAt()));
                    boolean holdFree = holds.stream().noneMatch(value -> overlaps(
                            blockedStart, blockedEnd, value.startsAt(), value.endsAt()));
                    if (inRange && eventFree && holdFree) {
                        slots.add(new AvailabilitySlot(startsAt, endsAt));
                    }
                    candidate = candidate.plusMinutes(15);
                }
            }
        }
        return new BookingAvailability(
                page.pageId(), page.version(), from, to, List.copyOf(slots), generatedAt);
    }

    @Transactional(readOnly = true)
    public List<Appointment> appointments(CalendarBookingAccess.Actor actor) {
        requireIdentity(actor);
        return bookings.bookerAppointments(
                        actor.tenantId(), actor.userId(), actor.personPublicId(), 100)
                .stream().map(this::appointment).toList();
    }

    @Transactional(readOnly = true)
    public Appointment appointment(
            CalendarBookingAccess.Actor actor, UUID appointmentId) {
        requireIdentity(actor);
        return appointment(bookings.bookerAppointment(
                        actor.tenantId(), actor.userId(), actor.personPublicId(), appointmentId)
                .orElseThrow(this::notFound));
    }

    @Transactional
    public BookingPage createPage(
            CalendarBookingAccess.Actor actor,
            String correlationId,
            SaveBookingPageRequest request) {
        requireIdentity(actor);
        SaveBookingPageRequest value = CalendarBookingContract.validate(request);
        requireRequestedOwner(actor, value.ownerType(), value.ownerGroupRef());
        requireCompatibleCalendar(actor, value);
        UUID pageId = UUID.randomUUID();
        OffsetDateTime now = now();
        bookings.insertPage(pageId, actor, value, now);
        BookingPage result = page(bookings.page(actor.tenantId(), pageId)
                .orElseThrow(() -> new IllegalStateException("Booking page was not persisted.")));
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking-page.created",
                "CALENDAR_BOOKING_PAGE", pageId.toString(), correlationId, null, result);
        return result;
    }

    @Transactional
    public BookingPage updatePage(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            String correlationId,
            SaveBookingPageRequest request) {
        requireIdentity(actor);
        SaveBookingPageRequest value = CalendarBookingContract.validate(request);
        CalendarBookingRepository.PageRow before = managedPage(actor, pageId, true);
        requireRequestedOwner(actor, value.ownerType(), value.ownerGroupRef());
        requireCompatibleCalendar(actor, value);
        if (before.state() != BookingPageState.DRAFT
                && (before.ownerType() != value.ownerType()
                || !Objects.equals(before.ownerGroupRef(), value.ownerGroupRef())
                || !before.calendarId().equals(value.calendarId()))) {
            throw conflict("Published booking ownership and host calendar are immutable.");
        }
        if (bookings.updatePage(actor, pageId, value, now()) != 1) throw changed();
        CalendarBookingRepository.PageRow after = managedPage(actor, pageId, false);
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking-page.updated",
                "CALENDAR_BOOKING_PAGE", pageId.toString(), correlationId,
                page(before), page(after));
        return page(after);
    }

    @Transactional
    public BookingPagePublishReceipt changeState(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            String correlationId,
            ChangeBookingPageStateRequest request) {
        requireIdentity(actor);
        if (request == null || request.state() == null || request.version() == null
                || request.version() < 0) throw invalid("Booking page state request is invalid.");
        CalendarBookingRepository.PageRow before = managedPage(actor, pageId, true);
        if (before.version() != request.version()) throw changed();
        CalendarBookingLifecycle.requirePageTransition(before.state(), request.state());
        OffsetDateTime now = now();
        CalendarBookingTokens.Material material = null;
        String tokenHash = before.publicTokenHash();
        long tokenVersion = before.publicTokenVersion();
        if (request.state() == BookingPageState.PUBLISHED && tokenHash == null) {
            material = tokens.create();
            tokenHash = material.hash();
            tokenVersion = Math.addExact(tokenVersion, 1L);
        }
        OffsetDateTime purgeAfter = request.state() == BookingPageState.ARCHIVED
                ? now.plus(retention) : null;
        if (request.state() == BookingPageState.ARCHIVED) tokenHash = null;
        if (bookings.changeState(actor, pageId, before.version(), request.state(),
                tokenHash, tokenVersion, now, purgeAfter) != 1) throw changed();
        CalendarBookingRepository.PageRow after = managedPage(actor, pageId, false);
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking-page.state-changed",
                "CALENDAR_BOOKING_PAGE", pageId.toString(), correlationId,
                Map.of("state", before.state().name(), "version", before.version()),
                Map.of("state", after.state().name(), "version", after.version()));
        return new BookingPagePublishReceipt(page(after), material == null ? null : material.raw());
    }

    @Transactional
    public BookingPagePublishReceipt rotatePageToken(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            String correlationId,
            RotateBookingTokenRequest request) {
        CalendarBookingRepository.PageRow before = tokenPage(actor, pageId, request);
        CalendarBookingTokens.Material material = tokens.create();
        if (bookings.rotatePageToken(actor, pageId, before.version(), material.hash(),
                Math.addExact(before.publicTokenVersion(), 1L), now()) != 1) throw changed();
        CalendarBookingRepository.PageRow after = managedPage(actor, pageId, false);
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking-page.token-rotated",
                "CALENDAR_BOOKING_PAGE", pageId.toString(), correlationId,
                Map.of("tokenVersion", before.publicTokenVersion()),
                Map.of("tokenVersion", after.publicTokenVersion()));
        return new BookingPagePublishReceipt(page(after), material.raw());
    }

    @Transactional
    public BookingPage revokePageToken(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            String correlationId,
            RotateBookingTokenRequest request) {
        CalendarBookingRepository.PageRow before = tokenPage(actor, pageId, request);
        if (bookings.rotatePageToken(actor, pageId, before.version(), null,
                Math.addExact(before.publicTokenVersion(), 1L), now()) != 1) throw changed();
        CalendarBookingRepository.PageRow after = managedPage(actor, pageId, false);
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking-page.token-revoked",
                "CALENDAR_BOOKING_PAGE", pageId.toString(), correlationId,
                Map.of("tokenVersion", before.publicTokenVersion()),
                Map.of("tokenVersion", after.publicTokenVersion()));
        return page(after);
    }

    @Transactional
    public BookingHold createHold(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            String correlationId,
            CreateBookingHoldRequest request) {
        requireIdentity(actor);
        validateHoldRequest(request);
        CalendarBookingRepository.PageRow page = bookings.lockPage(actor.tenantId(), pageId)
                .orElseThrow(this::notFound);
        if (page.state() != BookingPageState.PUBLISHED) {
            throw conflict("The booking page is not accepting reservations.");
        }
        if (request.pageVersion() != page.version()) throw changed();
        validateSlot(page, request.startsAt(), request.endsAt());
        OffsetDateTime now = now();
        bookings.lockHostSchedule(actor.tenantId(), page.calendarId());
        bookings.expireHolds(actor.tenantId(), page.calendarId(), now);
        String fingerprint = tokens.fingerprint(request);
        CalendarBookingRepository.HoldRow existing = bookings.holdByIdempotency(
                actor.tenantId(), pageId, actor.userId(), request.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!fingerprint.equals(existing.requestFingerprint())) throw idempotencyConflict();
            return hold(existing);
        }
        OffsetDateTime blockedStart = request.startsAt().minusMinutes(page.bufferBeforeMinutes());
        OffsetDateTime blockedEnd = request.endsAt().plusMinutes(page.bufferAfterMinutes());
        if (!calendar.busySlots(actor.tenantId(), List.of(page.ownerPersonPublicId()),
                blockedStart, blockedEnd).isEmpty()) {
            throw conflict("The booking host is no longer available for this slot.");
        }
        ZonedDateTime localStart = request.startsAt().atZoneSameInstant(ZoneId.of(page.timeZone()));
        ZonedDateTime dayStart = localStart.toLocalDate().atStartOfDay(localStart.getZone());
        ZonedDateTime nextDay = localStart.toLocalDate().plusDays(1)
                .atStartOfDay(localStart.getZone());
        if (bookings.reservationsInRange(actor.tenantId(), pageId,
                dayStart.toOffsetDateTime(), nextDay.toOffsetDateTime(), now)
                >= page.dailyLimit()) {
            throw conflict("The booking page daily limit has been reached.");
        }
        try {
            CalendarBookingRepository.HoldRow created = bookings.insertHold(
                    UUID.randomUUID(), actor, page, request, blockedStart, blockedEnd,
                    now.plus(holdTtl), fingerprint, now);
            BookingHold result = hold(created);
            audit.success(actor.tenantId(), actor.userId(), "calendar.booking-hold.created",
                    "CALENDAR_BOOKING_HOLD", created.holdId().toString(), correlationId,
                    null, result);
            return result;
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The booking slot is already held or reserved.", exception);
        }
    }

    @Transactional
    public BookingHold cancelHold(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            UUID holdId,
            String correlationId,
            CancelBookingHoldRequest request) {
        requireIdentity(actor);
        if (request == null || request.fencingToken() == null || request.fencingToken() < 1) {
            throw invalid("Booking hold fencing evidence is required.");
        }
        CalendarBookingRepository.HoldRow before = bookings.lockHold(
                        actor.tenantId(), pageId, holdId)
                .orElseThrow(this::notFound);
        if (!sameBooker(actor, before)) throw CalendarBookingAccess.denied();
        if (before.state() == BookingHoldState.CANCELLED) return hold(before);
        if (before.state() != BookingHoldState.HELD) {
            throw conflict("Only an active booking hold can be cancelled.");
        }
        if (bookings.cancelHold(actor, pageId, holdId, request.fencingToken(), now()) != 1) {
            throw changed();
        }
        CalendarBookingRepository.HoldRow after = bookings.lockHold(
                        actor.tenantId(), pageId, holdId)
                .orElseThrow(this::notFound);
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking-hold.cancelled",
                "CALENDAR_BOOKING_HOLD", holdId.toString(), correlationId,
                Map.of("state", before.state().name()), Map.of("state", after.state().name()));
        return hold(after);
    }

    @Transactional
    public AppointmentReceipt confirm(
            CalendarBookingAccess.Actor actor,
            UUID pageId,
            String locale,
            String correlationId,
            ConfirmBookingRequest request) {
        requireIdentity(actor);
        validateConfirmRequest(request);
        CalendarBookingRepository.PageRow page = bookings.lockPage(actor.tenantId(), pageId)
                .orElseThrow(this::notFound);
        bookings.lockHostSchedule(actor.tenantId(), page.calendarId());
        OffsetDateTime now = now();
        bookings.expireHolds(actor.tenantId(), page.calendarId(), now);
        String fingerprint = tokens.fingerprint(request);
        CalendarBookingRepository.AppointmentRow existing = bookings.appointmentByIdempotency(
                actor.tenantId(), pageId, actor.userId(), request.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!fingerprint.equals(existing.requestFingerprint())) throw idempotencyConflict();
            return new AppointmentReceipt(appointment(existing), null);
        }
        CalendarBookingRepository.HoldRow hold = bookings.lockHold(
                        actor.tenantId(), pageId, request.holdId())
                .orElseThrow(this::notFound);
        requireConsumable(actor, page, hold, request.fencingToken(), now);
        List<BookingAnswer> answers = validateAnswers(page.questions(), request.answers());
        CalendarBookingRepository.IdentityProfile booker = profile(actor);
        CalendarBookingRepository.IdentityProfile owner = bookings.identityProfile(
                        actor.tenantId(), page.ownerUserId(), page.ownerPersonPublicId())
                .orElseThrow(() -> conflict(
                        "The booking host does not have an active authoritative profile."));
        if (!bookings.hostBusy(actor.tenantId(), page.ownerPersonPublicId(),
                hold.blockedStartsAt(), hold.blockedEndsAt(), null).isEmpty()) {
            throw conflict("The booking host became busy after this hold was created.");
        }
        if (bookings.consumeHold(actor, hold, request.fencingToken(), now) != 1) throw changed();
        CalendarDtos.EventSummary event = calendarService.create(
                actor.tenantId(), page.ownerUserId(), page.ownerPersonPublicId(),
                owner.displayName(), locale, correlationId, pageGroup(page),
                createEventRequest(page, hold, booker, request.idempotencyKey()));
        CalendarBookingTokens.Material material = tokens.create();
        UUID appointmentId = UUID.randomUUID();
        bookings.insertAppointment(
                appointmentId, actor, page, hold, event.eventId(), null, answers,
                material.hash(), 1L, request.idempotencyKey(), fingerprint, now);
        CalendarBookingRepository.AppointmentRow created = bookings.appointment(
                        actor.tenantId(), appointmentId)
                .orElseThrow(() -> new IllegalStateException("Appointment was not persisted."));
        Appointment result = appointment(created);
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking.confirmed",
                "CALENDAR_BOOKING_APPOINTMENT", appointmentId.toString(), correlationId,
                null, result);
        return new AppointmentReceipt(result, material.raw());
    }

    @Transactional
    public Appointment reschedule(
            CalendarBookingAccess.Actor actor,
            UUID appointmentId,
            String locale,
            String correlationId,
            RescheduleAppointmentRequest request) {
        requireIdentity(actor);
        if (request == null || request.replacementHoldId() == null
                || request.fencingToken() == null || request.fencingToken() < 1
                || request.idempotencyKey() == null || request.version() == null
                || request.version() < 0) {
            throw invalid("Appointment reschedule request is invalid.");
        }
        CalendarBookingRepository.AppointmentRow before = bookings.lockAppointment(
                        actor.tenantId(), appointmentId)
                .orElseThrow(this::notFound);
        if (!sameBooker(actor, before)) throw CalendarBookingAccess.denied();
        String fingerprint = tokens.fingerprint(request);
        CalendarBookingRepository.AppointmentRow existing = bookings.appointmentByIdempotency(
                actor.tenantId(), before.pageId(), actor.userId(), request.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            if (!fingerprint.equals(existing.requestFingerprint())) throw idempotencyConflict();
            return appointment(existing);
        }
        if (before.version() != request.version()) throw changed();
        if (before.state() != AppointmentState.CONFIRMED) {
            throw conflict("Only a confirmed appointment can be rescheduled.");
        }
        CalendarBookingRepository.PageRow page = bookings.lockPage(
                        actor.tenantId(), before.pageId())
                .orElseThrow(this::notFound);
        bookings.lockHostSchedule(actor.tenantId(), page.calendarId());
        OffsetDateTime now = now();
        bookings.expireHolds(actor.tenantId(), page.calendarId(), now);
        CalendarBookingRepository.HoldRow replacement = bookings.lockHold(
                        actor.tenantId(), page.pageId(), request.replacementHoldId())
                .orElseThrow(this::notFound);
        requireConsumable(actor, page, replacement, request.fencingToken(), now);
        if (!bookings.hostBusy(actor.tenantId(), page.ownerPersonPublicId(),
                replacement.blockedStartsAt(), replacement.blockedEndsAt(),
                before.eventId()).isEmpty()) {
            throw conflict("The booking host became busy after this hold was created.");
        }
        if (bookings.consumeHold(actor, replacement, request.fencingToken(), now) != 1) {
            throw changed();
        }
        if (bookings.markRescheduled(actor, before, now.plus(retention), now) != 1) {
            throw changed();
        }
        CalendarRepository.EventRow event = ownerEvent(page, before.eventId(), locale);
        List<CalendarDtos.AttendeeInput> attendees = calendar.attendees(
                        actor.tenantId(), event.eventId()).stream()
                .map(value -> new CalendarDtos.AttendeeInput(
                        value.userId(), value.personPublicId(), value.email(), value.name(),
                        value.type()))
                .toList();
        calendarService.update(
                actor.tenantId(), page.ownerUserId(), page.ownerPersonPublicId(), event.eventId(),
                locale, correlationId, pageGroup(page),
                updateEventRequest(event, replacement, attendees), null);
        UUID nextId = UUID.randomUUID();
        bookings.insertAppointment(
                nextId, actor, page, replacement, before.eventId(), before.appointmentId(),
                before.answers(), before.managementTokenHash(), before.managementTokenVersion(),
                request.idempotencyKey(), fingerprint, now);
        CalendarBookingRepository.AppointmentRow after = bookings.appointment(
                        actor.tenantId(), nextId)
                .orElseThrow(() -> new IllegalStateException("Rescheduled appointment missing."));
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking.rescheduled",
                "CALENDAR_BOOKING_APPOINTMENT", nextId.toString(), correlationId,
                appointment(before), appointment(after));
        return appointment(after);
    }

    @Transactional
    public Appointment cancel(
            CalendarBookingAccess.Actor actor,
            UUID appointmentId,
            String locale,
            String correlationId,
            CancelAppointmentRequest request) {
        requireIdentity(actor);
        if (request == null || request.version() == null || request.version() < 0) {
            throw invalid("Appointment cancellation request is invalid.");
        }
        CalendarBookingRepository.AppointmentRow before = bookings.lockAppointment(
                        actor.tenantId(), appointmentId)
                .orElseThrow(this::notFound);
        if (!sameBooker(actor, before)) throw CalendarBookingAccess.denied();
        if (before.state() == AppointmentState.CANCELLED) return appointment(before);
        if (before.state() != AppointmentState.CONFIRMED || before.version() != request.version()) {
            throw changed();
        }
        CalendarBookingRepository.PageRow page = bookings.lockPage(
                        actor.tenantId(), before.pageId())
                .orElseThrow(this::notFound);
        CalendarRepository.EventRow event = ownerEvent(page, before.eventId(), locale);
        calendarService.cancel(
                actor.tenantId(), page.ownerUserId(), page.ownerPersonPublicId(), event.eventId(),
                locale, correlationId, pageGroup(page),
                new CalendarDtos.VersionRequest(event.version()));
        OffsetDateTime now = now();
        if (bookings.cancelAppointment(actor, before, now.plus(retention), now) != 1) {
            throw changed();
        }
        CalendarBookingRepository.AppointmentRow after = bookings.appointment(
                        actor.tenantId(), appointmentId)
                .orElseThrow(this::notFound);
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking.cancelled",
                "CALENDAR_BOOKING_APPOINTMENT", appointmentId.toString(), correlationId,
                appointment(before), Map.of(
                        "appointment", appointment(after),
                        "reason", request.reason() == null ? "" : request.reason()));
        return appointment(after);
    }

    @Transactional
    public AppointmentReceipt rotateAppointmentToken(
            CalendarBookingAccess.Actor actor,
            UUID appointmentId,
            String correlationId,
            RotateBookingTokenRequest request) {
        CalendarBookingRepository.AppointmentRow before = tokenAppointment(
                actor, appointmentId, request);
        CalendarBookingTokens.Material material = tokens.create();
        if (bookings.rotateAppointmentToken(actor, before, material.hash(),
                Math.addExact(before.managementTokenVersion(), 1L), now()) != 1) {
            throw changed();
        }
        CalendarBookingRepository.AppointmentRow after = bookings.appointment(
                        actor.tenantId(), appointmentId)
                .orElseThrow(this::notFound);
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking.management-token-rotated",
                "CALENDAR_BOOKING_APPOINTMENT", appointmentId.toString(), correlationId,
                Map.of("tokenVersion", before.managementTokenVersion()),
                Map.of("tokenVersion", after.managementTokenVersion()));
        return new AppointmentReceipt(appointment(after), material.raw());
    }

    @Transactional
    public Appointment revokeAppointmentToken(
            CalendarBookingAccess.Actor actor,
            UUID appointmentId,
            String correlationId,
            RotateBookingTokenRequest request) {
        CalendarBookingRepository.AppointmentRow before = tokenAppointment(
                actor, appointmentId, request);
        if (bookings.rotateAppointmentToken(actor, before, null,
                Math.addExact(before.managementTokenVersion(), 1L), now()) != 1) {
            throw changed();
        }
        CalendarBookingRepository.AppointmentRow after = bookings.appointment(
                        actor.tenantId(), appointmentId)
                .orElseThrow(this::notFound);
        audit.success(actor.tenantId(), actor.userId(), "calendar.booking.management-token-revoked",
                "CALENDAR_BOOKING_APPOINTMENT", appointmentId.toString(), correlationId,
                Map.of("tokenVersion", before.managementTokenVersion()),
                Map.of("tokenVersion", after.managementTokenVersion()));
        return appointment(after);
    }

    private CalendarBookingRepository.PageRow tokenPage(
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

    private CalendarBookingRepository.AppointmentRow tokenAppointment(
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

    private CalendarBookingRepository.PageRow managedPage(
            CalendarBookingAccess.Actor actor, UUID pageId, boolean lock) {
        CalendarBookingRepository.PageRow page = (lock
                ? bookings.lockPage(actor.tenantId(), pageId)
                : bookings.page(actor.tenantId(), pageId)).orElseThrow(this::notFound);
        if (!canManagePage(actor, page)) throw new BaseException(ErrorCode.NOT_FOUND);
        return page;
    }

    private boolean canManagePage(
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

    private CalendarBookingRepository.PageRow bookableRow(
            CalendarBookingAccess.Actor actor, UUID pageId) {
        CalendarBookingRepository.PageRow page = bookings.page(actor.tenantId(), pageId)
                .orElseThrow(this::notFound);
        if (page.state() != BookingPageState.PUBLISHED) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return page;
    }

    private void requireRequestedOwner(
            CalendarBookingAccess.Actor actor,
            BookingOwnerType ownerType,
            UUID ownerGroupRef) {
        if (ownerType == BookingOwnerType.TEAM && !actor.hasGroup(ownerGroupRef)) {
            throw CalendarBookingAccess.denied();
        }
    }

    private void requireCompatibleCalendar(
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

    private void requireIdentity(CalendarBookingAccess.Actor actor) {
        if (actor == null || actor.personPublicId() == null
                || !bookings.identityMatches(
                actor.tenantId(), actor.userId(), actor.personPublicId())) {
            throw CalendarBookingAccess.denied();
        }
    }

    private CalendarBookingRepository.IdentityProfile profile(
            CalendarBookingAccess.Actor actor) {
        return bookings.identityProfile(
                        actor.tenantId(), actor.userId(), actor.personPublicId())
                .orElseThrow(() -> conflict(
                        "The authenticated booker does not have an active verified email profile."));
    }

    private void validateHoldRequest(CreateBookingHoldRequest request) {
        if (request == null || request.startsAt() == null || request.endsAt() == null
                || request.idempotencyKey() == null || request.pageVersion() == null
                || request.pageVersion() < 0 || !request.endsAt().isAfter(request.startsAt())
                || request.startsAt().getSecond() != 0 || request.startsAt().getNano() != 0
                || request.endsAt().getSecond() != 0 || request.endsAt().getNano() != 0) {
            throw invalid("Booking hold time and idempotency values are invalid.");
        }
    }

    private void validateSlot(
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

    private void validateConfirmRequest(ConfirmBookingRequest request) {
        if (request == null || request.holdId() == null || request.fencingToken() == null
                || request.fencingToken() < 1 || request.idempotencyKey() == null
                || request.answers() == null || request.answers().size() > 20) {
            throw invalid("Booking confirmation request is invalid.");
        }
    }

    private List<BookingAnswer> validateAnswers(
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

    private void requireConsumable(
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

    private CalendarDtos.CreateEventRequest createEventRequest(
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

    private CalendarDtos.UpdateEventRequest updateEventRequest(
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

    private CalendarRepository.EventRow ownerEvent(
            CalendarBookingRepository.PageRow page, UUID eventId, String locale) {
        return CalendarRepositoryRouting.event(
                        calendar, page.tenantId(), page.ownerUserId(),
                        page.ownerPersonPublicId(), pageGroup(page), eventId,
                        locale != null && locale.toLowerCase(java.util.Locale.ROOT)
                                .startsWith("ko"))
                .orElseThrow(this::notFound);
    }

    private String pageGroup(CalendarBookingRepository.PageRow page) {
        return page.ownerType() == BookingOwnerType.TEAM
                ? page.ownerGroupRef().toString() : null;
    }

    private boolean sameBooker(
            CalendarBookingAccess.Actor actor, CalendarBookingRepository.HoldRow hold) {
        return hold.bookerUserId() == actor.userId()
                && hold.bookerPersonPublicId().equals(actor.personPublicId());
    }

    private boolean sameBooker(
            CalendarBookingAccess.Actor actor,
            CalendarBookingRepository.AppointmentRow appointment) {
        return appointment.bookerUserId() == actor.userId()
                && appointment.bookerPersonPublicId().equals(actor.personPublicId());
    }

    private BookingPage page(CalendarBookingRepository.PageRow value) {
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

    private AuthenticatedBookablePage bookable(CalendarBookingRepository.PageRow value) {
        return new AuthenticatedBookablePage(
                value.pageId(), value.version(), value.title(), value.description(),
                value.timeZone(), value.durationMinutes(), value.meetingMode(),
                value.location(), value.conferenceUrl(), value.dialInInstructions(),
                value.questions());
    }

    private BookingHold hold(CalendarBookingRepository.HoldRow value) {
        return new BookingHold(
                value.holdId(), value.pageId(), value.bookerPersonPublicId(), value.state(),
                value.startsAt(), value.endsAt(), value.expiresAt(), value.fencingToken(),
                value.version());
    }

    private Appointment appointment(CalendarBookingRepository.AppointmentRow value) {
        return new Appointment(
                value.appointmentId(), value.pageId(), value.eventId(),
                value.bookerPersonPublicId(), value.state(), value.startsAt(), value.endsAt(),
                value.supersedesAppointmentId(), value.version(), value.updatedAt());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static boolean overlaps(
            OffsetDateTime firstStart,
            OffsetDateTime firstEnd,
            OffsetDateTime secondStart,
            OffsetDateTime secondEnd) {
        return firstStart.isBefore(secondEnd) && firstEnd.isAfter(secondStart);
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    private BaseException changed() {
        return conflict("The Calendar booking value changed. Refresh and try again.");
    }

    private BaseException idempotencyConflict() {
        return conflict("The idempotency key was already used with a different request.");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException conflict(String message, Throwable cause) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }
}
