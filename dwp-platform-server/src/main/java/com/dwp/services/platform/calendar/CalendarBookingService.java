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
public class CalendarBookingService extends CalendarBookingServiceSupport {

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
        super(bookings, calendar, calendarService, audit, tokens, clock,
                externalPublicRequested, holdTtl, retention);
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

}
