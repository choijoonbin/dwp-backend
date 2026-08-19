package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/rooms")
public class AdminRoomsController {

    private final CalendarService service;

    public AdminRoomsController(CalendarService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<CalendarDtos.AdminOverview> getRoomsAdminOverview(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return ApiResponse.success(service.roomsAdminOverview(tenantId, locale));
    }

    @GetMapping("/policy")
    public ApiResponse<CalendarDtos.Policy> getRoomsPolicy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId) {
        return ApiResponse.success(service.policy(tenantId));
    }

    @PutMapping("/policy")
    public ApiResponse<CalendarDtos.Policy> updateRoomsPolicy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody CalendarDtos.PolicyRequest request) {
        return ApiResponse.success(service.updatePolicy(tenantId, userId, correlationId, request));
    }

    @GetMapping("/bookings/pending")
    public ApiResponse<List<CalendarDtos.BookingSummary>> getPendingRoomBookings(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return ApiResponse.success(service.pendingRoomBookings(tenantId, locale));
    }

    @PostMapping("/bookings/{bookingId}/decision")
    public ApiResponse<CalendarDtos.BookingSummary> decideRoomBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CalendarDtos.BookingDecisionRequest request) {
        return ApiResponse.success(service.decideRoomBooking(
                tenantId, userId, bookingId, locale, correlationId, request));
    }

    @PostMapping("/resources")
    public ApiResponse<CalendarDtos.ResourceSummary> createRoomResource(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody CalendarDtos.ResourceRequest request) {
        return ApiResponse.success(service.saveRoomResource(
                tenantId, userId, null, locale, correlationId, request));
    }

    @PutMapping("/resources/{resourceId}")
    public ApiResponse<CalendarDtos.ResourceSummary> updateRoomResource(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID resourceId,
            @Valid @RequestBody CalendarDtos.ResourceRequest request) {
        return ApiResponse.success(service.saveRoomResource(
                tenantId, userId, resourceId, locale, correlationId, request));
    }
}
