package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceBookingCommandCoordinator.CANCEL;
import static com.dwp.services.platform.workplace.WorkplaceBookingCommandCoordinator.CHECK_IN;
import static com.dwp.services.platform.workplace.WorkplaceBookingCommandCoordinator.RELEASE;
import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;

final class WorkplaceBookingLifecycleService {

    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceBookingRepository bookings;
    private final WorkplaceBookingAccessGuard bookingAccess;
    private final WorkplaceDomainEvents domainEvents;
    private final WorkplaceBookingCommandCoordinator commands;

    WorkplaceBookingLifecycleService(
            WorkplaceCatalogRepository catalog,
            WorkplaceBookingRepository bookings,
            WorkplaceBookingAccessGuard bookingAccess,
            WorkplaceDomainEvents domainEvents) {
        this.catalog = catalog;
        this.bookings = bookings;
        this.bookingAccess = bookingAccess;
        this.domainEvents = domainEvents;
        this.commands = new WorkplaceBookingCommandCoordinator(bookings);
    }

    WorkplaceDtos.Booking checkIn(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            String idempotencyKey,
            WorkplaceDtos.VersionRequest request) {
        return commands.execute(
                tenantId, userId, bookingId, CHECK_IN, idempotencyKey, correlationId,
                List.of(request.version()),
                () -> authorizeReplay(tenantId, userId, bookingId, locale, verifiedGroupRefs),
                () -> checkInOnceCompletion(
                        tenantId, userId, bookingId, locale, correlationId,
                        verifiedGroupRefs, request));
    }

    WorkplaceDtos.Booking checkInOnce(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            WorkplaceDtos.VersionRequest request) {
        return checkInOnceCompletion(
                tenantId, userId, bookingId, locale, correlationId,
                verifiedGroupRefs, request).result();
    }

    WorkplaceDtos.Booking cancel(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            String idempotencyKey,
            WorkplaceDtos.VersionRequest request) {
        return commands.execute(
                tenantId, userId, bookingId, CANCEL, idempotencyKey, correlationId,
                List.of(request.version()),
                () -> authorizeReplay(tenantId, userId, bookingId, locale, verifiedGroupRefs),
                () -> cancelOnceCompletion(
                        tenantId, userId, bookingId, locale, correlationId,
                        verifiedGroupRefs, request));
    }

    WorkplaceDtos.Booking cancelOnce(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            WorkplaceDtos.VersionRequest request) {
        return cancelOnceCompletion(
                tenantId, userId, bookingId, locale, correlationId,
                verifiedGroupRefs, request).result();
    }

    WorkplaceDtos.Booking release(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            String idempotencyKey,
            WorkplaceDtos.VersionRequest request) {
        return commands.execute(
                tenantId, userId, bookingId, RELEASE, idempotencyKey, correlationId,
                List.of(request.version()),
                () -> authorizeReplay(tenantId, userId, bookingId, locale, verifiedGroupRefs),
                () -> releaseOnceCompletion(
                        tenantId, userId, bookingId, locale, correlationId,
                        verifiedGroupRefs, request));
    }

    WorkplaceDtos.Booking releaseOnce(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            WorkplaceDtos.VersionRequest request) {
        return releaseOnceCompletion(
                tenantId, userId, bookingId, locale, correlationId,
                verifiedGroupRefs, request).result();
    }

    private WorkplaceBookingCommandCoordinator.Completion checkInOnceCompletion(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            WorkplaceDtos.VersionRequest request) {
        WorkplaceBookingRepository.BookingRow current = requireBooking(
                tenantId, userId, bookingId, locale);
        bookingAccess.requireBook(tenantId, userId, verifiedGroupRefs, current);
        OffsetDateTime now = OffsetDateTime.now();
        if (!current.requireCheckIn() || current.status() != BookingStatus.RESERVED) {
            throw invalid("This reservation is not eligible for check-in.");
        }
        OffsetDateTime opens = current.startsAt().minusMinutes(current.checkInLeadMinutes());
        OffsetDateTime closes = current.startsAt().plusMinutes(current.autoReleaseMinutes());
        if (!withinCheckInWindow(now, opens, closes, current.endsAt())) {
            throw invalid("Check-in is outside the allowed arrival window.");
        }
        if (bookings.checkIn(tenantId, userId, bookingId, request.version(), now) == 0) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        UUID auditEventId = bookings.auditCommand(
                tenantId, userId, "workplace.booking.checked_in", bookingId,
                correlationId, Map.of("checkedInAt", now));
        WorkplaceBookingRepository.BookingRow saved = requireBooking(
                tenantId, userId, bookingId, locale);
        recordEvent(WorkplaceDomainEvents.CHECKED_IN, tenantId, correlationId,
                saved, "MEMBER_CHECKED_IN");
        return new WorkplaceBookingCommandCoordinator.Completion(
                WorkplaceBookingViewMapper.toBooking(saved, now), auditEventId);
    }

    private WorkplaceBookingCommandCoordinator.Completion cancelOnceCompletion(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            WorkplaceDtos.VersionRequest request) {
        WorkplaceBookingRepository.BookingRow current = requireBooking(
                tenantId, userId, bookingId, locale);
        bookingAccess.requireBook(tenantId, userId, verifiedGroupRefs, current);
        OffsetDateTime now = OffsetDateTime.now();
        if (current.status() != BookingStatus.RESERVED || !now.isBefore(current.startsAt())) {
            throw invalid("Only a future reserved booking can be cancelled.");
        }
        if (bookings.cancel(tenantId, userId, bookingId, request.version(), now) == 0) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        UUID auditEventId = bookings.auditCommand(
                tenantId, userId, "workplace.booking.cancelled", bookingId,
                correlationId, Map.of("cancelledAt", now));
        WorkplaceBookingRepository.BookingRow saved = requireBooking(
                tenantId, userId, bookingId, locale);
        recordEvent(WorkplaceDomainEvents.CANCELLED, tenantId, correlationId,
                saved, "MEMBER_CANCELLED");
        return new WorkplaceBookingCommandCoordinator.Completion(
                WorkplaceBookingViewMapper.toBooking(saved, now), auditEventId);
    }

    private WorkplaceBookingCommandCoordinator.Completion releaseOnceCompletion(
            Long tenantId,
            Long userId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            WorkplaceDtos.VersionRequest request) {
        WorkplaceBookingRepository.BookingRow current = requireBooking(
                tenantId, userId, bookingId, locale);
        bookingAccess.requireBook(tenantId, userId, verifiedGroupRefs, current);
        OffsetDateTime now = OffsetDateTime.now();
        boolean active = current.status() == BookingStatus.RESERVED
                || current.status() == BookingStatus.CHECKED_IN;
        if (!active || now.isBefore(current.startsAt()) || !now.isBefore(current.endsAt())) {
            throw invalid("Only an active booking can be released after it starts.");
        }
        if (bookings.release(tenantId, userId, bookingId, request.version(), now) == 0) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        UUID auditEventId = bookings.auditCommand(
                tenantId, userId, "workplace.booking.released", bookingId,
                correlationId, Map.of("releasedAt", now));
        WorkplaceBookingRepository.BookingRow saved = requireBooking(
                tenantId, userId, bookingId, locale);
        recordEvent(WorkplaceDomainEvents.RELEASED, tenantId, correlationId,
                saved, "MEMBER_RELEASED");
        return new WorkplaceBookingCommandCoordinator.Completion(
                WorkplaceBookingViewMapper.toBooking(saved, now), auditEventId);
    }

    private void authorizeReplay(
            Long tenantId, Long userId, UUID bookingId, String locale, String groups) {
        bookingAccess.requireBook(
                tenantId, userId, groups, requireBooking(tenantId, userId, bookingId, locale));
    }

    private WorkplaceBookingRepository.BookingRow requireBooking(
            Long tenantId, Long userId, UUID bookingId, String locale) {
        boolean korean = locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("ko");
        return bookings.booking(tenantId, userId, bookingId, korean)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void recordEvent(
            String type,
            Long tenantId,
            String correlationId,
            WorkplaceBookingRepository.BookingRow booking,
            String reasonCode) {
        WorkplaceCatalogRepository.ResourceRow resource = catalog
                .resource(tenantId, booking.resourceId(), false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        WorkplaceCatalogRepository.FloorRow floor = catalog
                .floor(tenantId, resource.floorId(), false)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        domainEvents.bookingChanged(
                type,
                tenantId,
                correlationId,
                new WorkplaceDomainEvents.BookingEvent(
                        booking.bookingId(), null, booking.resourceId(), floor.siteId(),
                        floor.floorId(), booking.status().name(), booking.startsAt(),
                        booking.endsAt(), reasonCode, booking.version()));
    }

    static boolean withinCheckInWindow(
            OffsetDateTime now,
            OffsetDateTime opens,
            OffsetDateTime closes,
            OffsetDateTime endsAt) {
        return !now.isBefore(opens)
                && !now.isAfter(closes)
                && now.isBefore(endsAt);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
