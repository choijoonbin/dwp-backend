package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/v1/calendar")
public class CalendarCollaborationController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String PERSON = "X-DWP-Person-Public-ID";
    private static final String GROUPS = "X-DWP-Group-Refs";
    private static final String CORRELATION = "X-Correlation-ID";

    private final CalendarCollaborationService service;

    public CalendarCollaborationController(CalendarCollaborationService service) {
        this.service = service;
    }

    @GetMapping("/calendars/{calendarId}/capabilities")
    public ApiResponse<CalendarDtos.CalendarCapabilities> calendarCapabilities(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @PathVariable UUID calendarId) {
        return ApiResponse.success(service.calendarCapabilities(
                tenantId, userId, personPublicId, groupRefs, calendarId));
    }

    @GetMapping("/calendars/{calendarId}/shares")
    public ApiResponse<List<CalendarDtos.CalendarShare>> listShares(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @PathVariable UUID calendarId) {
        return ApiResponse.success(service.listShares(
                tenantId, userId, personPublicId, groupRefs, calendarId));
    }

    @PutMapping("/calendars/{calendarId}/shares/{sharedPersonPublicId}")
    public ApiResponse<CalendarDtos.CalendarShare> upsertPersonShare(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID calendarId,
            @PathVariable UUID sharedPersonPublicId,
            @Valid @RequestBody CalendarDtos.CalendarShareRequest request) {
        return ApiResponse.success(service.upsertPersonShare(
                tenantId,
                userId,
                personPublicId,
                groupRefs,
                calendarId,
                sharedPersonPublicId,
                correlationId,
                request));
    }

    @DeleteMapping("/calendars/{calendarId}/shares/{grantId}")
    public ApiResponse<Void> revokeShare(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID calendarId,
            @PathVariable UUID grantId,
            @RequestParam @Min(0) long version) {
        service.revokeShare(
                tenantId,
                userId,
                personPublicId,
                groupRefs,
                calendarId,
                grantId,
                version,
                correlationId);
        return ApiResponse.success(null);
    }

    @PutMapping("/calendars/{calendarId}/subscription")
    public ApiResponse<CalendarDtos.CalendarSubscriptionResponse> updateSubscription(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID calendarId,
            @Valid @RequestBody CalendarDtos.CalendarSubscriptionRequest request) {
        return ApiResponse.success(service.updateSubscription(
                tenantId,
                userId,
                personPublicId,
                groupRefs,
                calendarId,
                correlationId,
                request));
    }

    @GetMapping("/events/{eventId}/capabilities")
    public ApiResponse<CalendarDtos.EventCapabilities> eventCapabilities(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @PathVariable UUID eventId) {
        return ApiResponse.success(service.eventCapabilities(
                tenantId, userId, personPublicId, groupRefs, eventId));
    }

    @PutMapping("/events/{eventId}/preference")
    public ApiResponse<CalendarDtos.EventPreferenceResponse> updateEventPreference(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.EventPreferenceRequest request) {
        return ApiResponse.success(service.updateEventPreference(
                tenantId,
                userId,
                personPublicId,
                groupRefs,
                eventId,
                correlationId,
                request));
    }

    @PostMapping("/events/{eventId}/trash")
    public ApiResponse<CalendarDtos.EventCapabilities> trashEvent(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.TrashEventRequest request) {
        return ApiResponse.success(service.trashEvent(
                tenantId,
                userId,
                personPublicId,
                groupRefs,
                eventId,
                correlationId,
                request));
    }

    @PostMapping("/events/{eventId}/restore")
    public ApiResponse<CalendarDtos.EventCapabilities> restoreEvent(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.VersionRequest request) {
        return ApiResponse.success(service.restoreEvent(
                tenantId,
                userId,
                personPublicId,
                groupRefs,
                eventId,
                correlationId,
                request));
    }
}
