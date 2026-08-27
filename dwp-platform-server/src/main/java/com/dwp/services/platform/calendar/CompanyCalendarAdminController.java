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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/calendar/company-calendars")
public class CompanyCalendarAdminController {

    private final CompanyCalendarAdminService service;

    public CompanyCalendarAdminController(CompanyCalendarAdminService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CalendarDtos.CompanyCalendarSummary>> calendars(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return ApiResponse.success(service.calendars(tenantId, locale));
    }

    @PostMapping
    public ApiResponse<CalendarDtos.CompanyCalendarSummary> createCalendar(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody CalendarDtos.CompanyCalendarRequest request) {
        return ApiResponse.success(service.createCalendar(
                tenantId, userId, personPublicId, locale, correlationId, request));
    }

    @PutMapping("/{calendarId}")
    public ApiResponse<CalendarDtos.CompanyCalendarSummary> updateCalendar(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID calendarId,
            @Valid @RequestBody CalendarDtos.CompanyCalendarRequest request) {
        return ApiResponse.success(service.updateCalendar(
                tenantId, userId, personPublicId, calendarId,
                locale, correlationId, request));
    }

    @GetMapping("/{calendarId}/events")
    public ApiResponse<List<CalendarDtos.CompanyEventSummary>> events(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @PathVariable UUID calendarId,
            @RequestParam OffsetDateTime from,
            @RequestParam OffsetDateTime to,
            @RequestParam(defaultValue = "false") boolean deleted) {
        return ApiResponse.success(service.events(
                tenantId, calendarId, from, to, deleted));
    }

    @PostMapping("/{calendarId}/events")
    public ApiResponse<CalendarDtos.CompanyEventSummary> createEvent(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "X-DWP-Display-Name", required = false) String displayName,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID calendarId,
            @Valid @RequestBody CalendarDtos.CreateEventRequest request) {
        return ApiResponse.success(service.createEvent(
                tenantId, userId, personPublicId, displayName,
                calendarId, correlationId, request));
    }

    @PutMapping("/{calendarId}/events/{eventId}")
    public ApiResponse<CalendarDtos.CompanyEventSummary> updateEvent(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID calendarId,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.UpdateEventRequest request) {
        return ApiResponse.success(service.updateEvent(
                tenantId, userId, personPublicId, calendarId,
                eventId, correlationId, request));
    }

    @PostMapping("/{calendarId}/events/{eventId}/trash")
    public ApiResponse<CalendarDtos.CompanyEventSummary> trashEvent(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID calendarId,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.TrashEventRequest request) {
        return ApiResponse.success(service.trashEvent(
                tenantId, userId, personPublicId, calendarId,
                eventId, correlationId, request));
    }

    @PostMapping("/{calendarId}/events/{eventId}/restore")
    public ApiResponse<CalendarDtos.CompanyEventSummary> restoreEvent(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID calendarId,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.VersionRequest request) {
        return ApiResponse.success(service.restoreEvent(
                tenantId, userId, personPublicId, calendarId,
                eventId, correlationId, request));
    }
}
