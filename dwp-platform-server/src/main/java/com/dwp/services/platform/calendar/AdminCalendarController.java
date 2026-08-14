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

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/v1/admin/calendar")
public class AdminCalendarController {

    private final CalendarService service;

    public AdminCalendarController(CalendarService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<CalendarDtos.AdminOverview> overview(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return ApiResponse.success(service.adminOverview(tenantId, locale));
    }

    @GetMapping("/policy")
    public ApiResponse<CalendarDtos.Policy> policy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId) {
        return ApiResponse.success(service.policy(tenantId));
    }

    @PutMapping("/policy")
    public ApiResponse<CalendarDtos.Policy> updatePolicy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody CalendarDtos.PolicyRequest request) {
        return ApiResponse.success(service.updatePolicy(
                tenantId, userId, correlationId, request));
    }

    @GetMapping("/bookings/pending")
    public ApiResponse<List<CalendarDtos.BookingSummary>> pendingBookings(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return ApiResponse.success(service.pendingBookings(tenantId, locale));
    }

    @PostMapping("/bookings/{bookingId}/decision")
    public ApiResponse<CalendarDtos.BookingSummary> decideBooking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID bookingId,
            @Valid @RequestBody CalendarDtos.BookingDecisionRequest request) {
        return ApiResponse.success(service.decideBooking(
                tenantId, userId, bookingId, locale, correlationId, request));
    }

    @PostMapping("/resources")
    public ApiResponse<CalendarDtos.ResourceSummary> createResource(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody CalendarDtos.ResourceRequest request) {
        return ApiResponse.success(service.saveResource(
                tenantId, userId, null, locale, correlationId, request));
    }

    @PutMapping("/resources/{resourceId}")
    public ApiResponse<CalendarDtos.ResourceSummary> updateResource(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID resourceId,
            @Valid @RequestBody CalendarDtos.ResourceRequest request) {
        return ApiResponse.success(service.saveResource(
                tenantId, userId, resourceId, locale, correlationId, request));
    }
}
