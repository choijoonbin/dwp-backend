package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;

@RestController
public class WorkplaceOperationsController {

    private final WorkplaceOperationsService service;

    public WorkplaceOperationsController(WorkplaceOperationsService service) {
        this.service = service;
    }

    @PostMapping("/v1/workplace/bookings")
    public ApiResponse<WorkplaceDtos.Booking> createWorkplaceBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
                    UUID personPublicId,
            @RequestHeader(value = "X-DWP-Display-Name-B64", required = false)
                    String displayName,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @Valid @RequestBody WorkplaceDtos.BookingRequest request) {
        return ApiResponse.success(service.createBooking(
                tenantId, userId, personPublicId, decoded(displayName), locale,
                correlationId, idempotencyKey, groupRefs, request));
    }

    @PostMapping("/v1/workplace/bookings/{bookingId}/relocate")
    public ApiResponse<WorkplaceDtos.Booking> relocateWorkplaceBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
                    UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @PathVariable UUID bookingId,
            @Valid @RequestBody WorkplaceOperationsDtos.RelocateBookingRequest request) {
        return ApiResponse.success(service.relocateBooking(
                tenantId, userId, personPublicId, bookingId,
                locale, correlationId, groupRefs, request));
    }

    @GetMapping("/v1/admin/workplace/bookings")
    public ApiResponse<WorkplaceOperationsDtos.AdminBookingPage> searchWorkplaceBookings(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.adminBookings(
                tenantId, from, to, status, resourceId, userId,
                locale, page, size));
    }

    @PutMapping("/v1/admin/workplace/bookings/{bookingId}/force-cancel")
    public ApiResponse<WorkplaceOperationsDtos.AdminBooking> forceCancelWorkplaceBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody WorkplaceOperationsDtos.ForceCancelBookingRequest request) {
        return ApiResponse.success(service.forceCancel(
                tenantId, actorId, bookingId, locale, correlationId, request));
    }

    @PutMapping("/v1/admin/workplace/bookings/{bookingId}/legal-hold")
    public ApiResponse<WorkplaceOperationsDtos.AdminBooking> updateWorkplaceLegalHold(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody WorkplaceOperationsDtos.LegalHoldRequest request) {
        return ApiResponse.success(service.updateLegalHold(
                tenantId, actorId, bookingId, locale, correlationId, request));
    }

    @GetMapping("/v1/admin/workplace/audit-events")
    public ApiResponse<WorkplaceOperationsDtos.AuditEventPage> workplaceAuditEvents(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) UUID aggregateId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.auditEvents(
                tenantId, from, to, action, aggregateType, aggregateId,
                actorUserId, page, size));
    }

    private String decoded(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
