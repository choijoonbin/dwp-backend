package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;
import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;

@Service
public class WorkplaceOperationsService {

    private static final Duration MAX_OPERATION_SEARCH_SPAN = Duration.ofDays(400);
    private static final int MAX_PAGE_SIZE = 100;

    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceBookingRepository bookings;
    private final WorkplaceOperationsRepository operations;
    private final WorkplaceService workplace;
    private final WorkplaceDomainEvents domainEvents;

    public WorkplaceOperationsService(
            WorkplaceCatalogRepository catalog,
            WorkplaceBookingRepository bookings,
            WorkplaceOperationsRepository operations,
            WorkplaceService workplace,
            WorkplaceDomainEvents domainEvents) {
        this.catalog = catalog;
        this.bookings = bookings;
        this.operations = operations;
        this.workplace = workplace;
        this.domainEvents = domainEvents;
    }

    @Transactional
    public WorkplaceDtos.Booking createBooking(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String displayName,
            String locale,
            String correlationId,
            String idempotencyKey,
            String verifiedGroupRefs,
            WorkplaceDtos.BookingRequest request) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (key == null) {
            return workplace.createBooking(
                    tenantId, userId, personPublicId, displayName,
                    locale, correlationId, verifiedGroupRefs, request);
        }

        String fingerprint = fingerprint(request);
        operations.lockUserBookingScope(tenantId, userId);
        WorkplaceOperationsRepository.IdempotencyRow existing = operations
                .idempotency(tenantId, userId, key).orElse(null);
        if (existing != null) {
            if (!fingerprint.equals(existing.requestFingerprint())) {
                throw conflict("The idempotency key was already used with a different request.");
            }
            return bookingById(tenantId, userId, existing.bookingId(), locale);
        }

        WorkplaceDtos.Booking created = workplace.createBooking(
                tenantId, userId, personPublicId, displayName,
                locale, correlationId, verifiedGroupRefs, request);
        if (operations.attachIdempotency(
                tenantId, userId, created.bookingId(), key, fingerprint) != 1) {
            throw conflict("The booking idempotency state changed. Retry with a new key.");
        }
        return created;
    }

    @Transactional
    public WorkplaceDtos.Booking relocateBooking(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            UUID bookingId,
            String locale,
            String correlationId,
            String verifiedGroupRefs,
            WorkplaceOperationsDtos.RelocateBookingRequest request) {
        operations.lockUserBookingScope(tenantId, userId);
        WorkplaceBookingRepository.BookingRow current = bookings
                .booking(tenantId, userId, bookingId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now();
        if (current.version() != request.version()) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        if (current.resourceType() == ResourceType.ROOM) {
            throw invalid("Meeting rooms must be changed with the calendar-aware room flow.");
        }
        if (current.status() != BookingStatus.RESERVED || !now.isBefore(current.startsAt())) {
            throw invalid("Only a future reserved booking can be relocated.");
        }
        if (current.resourceId().equals(request.resourceId())
                && current.startsAt().isEqual(request.startsAt())
                && current.endsAt().isEqual(request.endsAt())) {
            throw invalid("The relocated booking must change its resource or time.");
        }

        boolean ko = korean(locale);
        WorkplaceCatalogRepository.ResourceRow target = catalog
                .resource(tenantId, request.resourceId(), ko)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (target.type() == ResourceType.ROOM) {
            throw invalid("Meeting rooms must be reserved with the calendar-aware room flow.");
        }
        WorkplaceCatalogRepository.FloorRow floor = catalog
                .floor(tenantId, target.floorId(), ko)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        WorkplaceCatalogRepository.SiteRow site = catalog
                .site(tenantId, floor.siteId(), ko)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        WorkplaceCatalogRepository.PolicyRow basePolicy = catalog.policy(tenantId);
        WorkplaceCatalogRepository.PolicyRow resolvedPolicy = workplace.resolveBookingPolicy(
                tenantId, target.resourceId(), basePolicy);
        WorkplaceCatalogRepository.PolicyRow policy =
                resolvedPolicy == null ? basePolicy : resolvedPolicy;
        WorkplaceDtos.BookingRequest relocated = new WorkplaceDtos.BookingRequest(
                target.resourceId(), request.startsAt(), request.endsAt(),
                current.purpose(), current.visibleToColleagues());
        workplace.validateBookable(
                tenantId, target, userId, personPublicId, verifiedGroupRefs,
                relocated, site, floor, policy);
        if (operations.userHasConflictExcluding(
                tenantId, userId, bookingId, request.startsAt(), request.endsAt())) {
            throw conflict("You already have another Workplace reservation in this time range.");
        }

        try {
            if (operations.relocate(
                    tenantId, userId, bookingId, request.version(), request.resourceId(),
                    request.startsAt(), request.endsAt(), now) != 1) {
                throw conflict("The reservation changed. Refresh and try again.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The selected time conflicts with an existing Workplace reservation.");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("previousResourceId", current.resourceId());
        snapshot.put("resourceId", request.resourceId());
        snapshot.put("previousStartsAt", current.startsAt());
        snapshot.put("previousEndsAt", current.endsAt());
        snapshot.put("startsAt", request.startsAt());
        snapshot.put("endsAt", request.endsAt());
        snapshot.put("reason", blank(request.reason()));
        operations.audit(tenantId, userId, "workplace.booking.relocated",
                bookingId, correlationId, snapshot);
        domainEvents.bookingChanged(
                WorkplaceDomainEvents.RELOCATED,
                tenantId,
                correlationId,
                new WorkplaceDomainEvents.BookingEvent(
                        bookingId,
                        null,
                        request.resourceId(),
                        site.siteId(),
                        floor.floorId(),
                        BookingStatus.RESERVED.name(),
                        request.startsAt(),
                        request.endsAt(),
                        "MEMBER_RELOCATED",
                        current.version() + 1));
        return bookingById(tenantId, userId, bookingId, locale);
    }

    @Transactional(readOnly = true)
    public WorkplaceOperationsDtos.AdminBookingPage adminBookings(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            BookingStatus status,
            UUID resourceId,
            Long userId,
            String locale,
            int page,
            int size) {
        validateRange(from, to);
        validatePage(page, size);
        WorkplaceOperationsRepository.AdminBookingPageRows result = operations.adminBookings(
                tenantId, from, to, status, resourceId, userId,
                korean(locale), page, size);
        return new WorkplaceOperationsDtos.AdminBookingPage(
                result.content().stream().map(this::adminBooking).toList(),
                page, size, result.totalElements(), totalPages(result.totalElements(), size));
    }

    @Transactional
    public WorkplaceOperationsDtos.AdminBooking forceCancel(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String locale,
            String correlationId,
            WorkplaceOperationsDtos.ForceCancelBookingRequest request) {
        String reason = blank(request.reason());
        if (reason == null) {
            throw invalid("A force-cancellation reason is required.");
        }
        WorkplaceOperationsRepository.AdminBookingRow current = operations
                .adminBookingForUpdate(tenantId, bookingId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (current.version() != request.version()) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        if (current.status() != BookingStatus.RESERVED
                && current.status() != BookingStatus.CHECKED_IN) {
            throw invalid("Only an active reservation can be force-cancelled.");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (operations.forceCancel(
                tenantId, actorId, bookingId, request.version(), now) != 1) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        operations.audit(tenantId, actorId, "workplace.booking.force.cancelled",
                bookingId, correlationId, Map.of(
                        "reason", reason,
                        "bookingUserId", current.userId(),
                        "resourceId", current.resourceId(),
                        "previousStatus", current.status().name(),
                        "cancelledAt", now));
        domainEvents.bookingChanged(
                WorkplaceDomainEvents.CANCELLED,
                tenantId,
                correlationId,
                new WorkplaceDomainEvents.BookingEvent(
                        bookingId,
                        null,
                        current.resourceId(),
                        current.siteId(),
                        current.floorId(),
                        BookingStatus.CANCELLED.name(),
                        current.startsAt(),
                        current.endsAt(),
                        "ADMIN_FORCE_CANCELLED",
                        current.version() + 1));
        return operations.adminBooking(tenantId, bookingId, korean(locale))
                .map(this::adminBooking)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public WorkplaceOperationsDtos.AdminBooking updateLegalHold(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String locale,
            String correlationId,
            WorkplaceOperationsDtos.LegalHoldRequest request) {
        String reason = blank(request.reason());
        if (reason == null) {
            throw invalid("A legal-hold reason is required.");
        }
        WorkplaceOperationsRepository.AdminBookingRow current = operations
                .adminBookingForUpdate(tenantId, bookingId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (current.version() != request.version()) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        if (current.anonymizedAt() != null) {
            throw invalid("An anonymized reservation cannot be placed on legal hold.");
        }
        if (current.legalHold() == request.legalHold()) {
            throw invalid("The reservation already has the requested legal-hold state.");
        }
        if (operations.updateLegalHold(
                tenantId, actorId, bookingId, request.version(), request.legalHold()) != 1) {
            throw conflict("The reservation changed. Refresh and try again.");
        }
        operations.audit(
                tenantId,
                actorId,
                request.legalHold()
                        ? "workplace.booking.legal_hold.applied"
                        : "workplace.booking.legal_hold.released",
                bookingId,
                correlationId,
                Map.of(
                        "reason", reason,
                        "previousLegalHold", current.legalHold(),
                        "legalHold", request.legalHold(),
                        "personalDataExpiresAt", current.personalDataExpiresAt()));
        return operations.adminBooking(tenantId, bookingId, korean(locale))
                .map(this::adminBooking)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public WorkplaceOperationsDtos.AuditEventPage auditEvents(
            Long tenantId,
            OffsetDateTime from,
            OffsetDateTime to,
            String action,
            String aggregateType,
            UUID aggregateId,
            Long actorUserId,
            int page,
            int size) {
        validateRange(from, to);
        validatePage(page, size);
        String normalizedAction = filter(action, 100, "action");
        String normalizedAggregateType = filter(aggregateType, 40, "aggregate type");
        WorkplaceOperationsRepository.AuditPageRows result = operations.auditEvents(
                tenantId, from, to, normalizedAction, normalizedAggregateType,
                aggregateId, actorUserId, page, size);
        List<WorkplaceOperationsDtos.AuditEvent> content = result.content().stream()
                .map(value -> new WorkplaceOperationsDtos.AuditEvent(
                        value.auditEventId(), value.action(), value.aggregateType(),
                        value.aggregateId(), value.actorUserId(), value.correlationId(),
                        value.snapshot(), value.occurredAt()))
                .toList();
        return new WorkplaceOperationsDtos.AuditEventPage(
                content, page, size, result.totalElements(),
                totalPages(result.totalElements(), size));
    }

    private WorkplaceOperationsDtos.AdminBooking adminBooking(
            WorkplaceOperationsRepository.AdminBookingRow value) {
        return new WorkplaceOperationsDtos.AdminBooking(
                value.bookingId(), value.resourceId(), value.resourceName(), value.resourceType(),
                value.siteName(), value.floorName(), value.userId(), value.personPublicId(),
                value.bookedForDisplayName(), value.purpose(), value.startsAt(), value.endsAt(),
                value.status(), value.visibleToColleagues(), value.checkedInAt(),
                value.releasedAt(), value.cancelledAt(), value.legalHold(),
                value.personalDataExpiresAt(), value.anonymizedAt(), value.version(),
                value.createdAt(), value.updatedAt());
    }

    private WorkplaceDtos.Booking bookingById(
            Long tenantId, Long userId, UUID bookingId, String locale) {
        WorkplaceBookingRepository.BookingRow row = bookings
                .booking(tenantId, userId, bookingId, korean(locale))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return workplace.booking(row, catalog.policy(tenantId), OffsetDateTime.now());
    }

    private String normalizeIdempotencyKey(String value) {
        String key = blank(value);
        if (key == null) return null;
        if (key.length() > 160 || key.chars().anyMatch(character ->
                character < 0x21 || character > 0x7e)) {
            throw invalid("Idempotency-Key must contain 1 to 160 visible ASCII characters.");
        }
        return key;
    }

    private String fingerprint(WorkplaceDtos.BookingRequest request) {
        String canonical = request.resourceId() + "\n"
                + request.startsAt().toInstant() + "\n"
                + request.endsAt().toInstant() + "\n"
                + String.valueOf(blank(request.purpose())) + "\n"
                + request.visibleToColleagues();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw invalid("A valid operation search range is required.");
        }
        if (Duration.between(from, to).compareTo(MAX_OPERATION_SEARCH_SPAN) > 0) {
            throw invalid("Workplace operation searches are limited to 400 days.");
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("Page must be non-negative and size must be between 1 and 100.");
        }
    }

    private String filter(String value, int maximumLength, String label) {
        String normalized = blank(value);
        if (normalized != null && normalized.length() > maximumLength) {
            throw invalid("The Workplace audit " + label + " filter is too long.");
        }
        return normalized;
    }

    private int totalPages(long totalElements, int size) {
        return totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean korean(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
