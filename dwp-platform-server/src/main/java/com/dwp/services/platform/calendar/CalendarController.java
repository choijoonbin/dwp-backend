package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.mail.MailProposalHandoffBinding;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/calendar")
public class CalendarController {

    private final CalendarService service;
    private final CalendarInsightsService insightsService;

    public CalendarController(CalendarService service) {
        this(service, null);
    }

    @Autowired
    CalendarController(CalendarService service, CalendarInsightsService insightsService) {
        this.service = service;
        this.insightsService = insightsService;
    }

    @GetMapping("/home")
    public ApiResponse<CalendarDtos.HomeResponse> home(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam(defaultValue = "Asia/Seoul") String timeZone,
            @RequestParam(required = false) Integer insightWeeks) {
        if (insightWeeks != null) {
            if (personPublicId == null || insightsService == null) throw CalendarSettingsAccess.denied();
            CalendarInsightsService.validateWeeks(insightWeeks);
        }
        CalendarDtos.HomeResponse home = service.home(
                tenantId, userId, personPublicId, timeZone, locale, groupRefs);
        if (insightWeeks == null) return ApiResponse.success(home);
        return ApiResponse.success(home.withInsights(insightsService.insights(
                new CalendarSettingsAccess.Actor(tenantId, userId, personPublicId),
                groupRefs, insightWeeks, timeZone, locale)));
    }

    @GetMapping("/calendars")
    public ApiResponse<List<CalendarDtos.CalendarSummary>> calendars(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return ApiResponse.success(service.calendars(
                tenantId, userId, personPublicId, groupRefs, locale));
    }

    @GetMapping("/events")
    public ApiResponse<List<CalendarDtos.EventSummary>> events(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.success(service.events(
                tenantId, userId, personPublicId, groupRefs, from, to, locale));
    }

    @PostMapping("/events")
    public ApiResponse<CalendarDtos.EventSummary> create(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Display-Name-B64", required = false) String displayName,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "X-DWP-Mail-Proposal-ID", required = false) UUID mailProposalId,
            @RequestHeader(value = "X-DWP-Mail-Command-ID", required = false) UUID mailCommandId,
            @RequestHeader(value = "X-DWP-Mail-Proposal-Version", required = false) Long mailProposalVersion,
            @RequestHeader(value = "X-DWP-Auth-Session-ID", required = false) String authSessionId,
            @RequestHeader(value = "X-DWP-Roles", required = false) String roles,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-DWP-DWAI-ON-Handoff-ID", required = false) UUID dwaionHandoffId,
            @RequestHeader(value = "X-DWP-DWAI-ON-Proposal-ID", required = false) UUID dwaionProposalId,
            @RequestHeader(value = "X-DWP-DWAI-ON-Action-Key", required = false) String dwaionActionKey,
            @RequestHeader(value = "X-DWP-DWAI-ON-Handoff-Version", required = false) Long dwaionHandoffVersion,
            @Valid @RequestBody CalendarDtos.CreateEventRequest request) {
        PlatformDwaionHandoff.Binding dwaionBinding = PlatformDwaionHandoff.Binding.optional(
                dwaionHandoffId, dwaionProposalId, dwaionActionKey, dwaionHandoffVersion,
                "CALENDAR.EVENT.CREATE");
        return ApiResponse.success(service.create(
                tenantId, userId, personPublicId, decoded(displayName),
                locale, correlationId, groupRefs, request,
                MailProposalHandoffBinding.optional(
                        mailProposalId, mailCommandId, mailProposalVersion),
                dwaionBinding,
                dwaionBinding == null ? null : new PlatformDwaionHandoff.Identity(
                        authSessionId, personPublicId, roles, permissions)));
    }

    @PutMapping("/events/{eventId}")
    public ApiResponse<CalendarDtos.EventSummary> update(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.UpdateEventRequest request) {
        return ApiResponse.success(service.update(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, groupRefs, request));
    }

    @PostMapping("/events/{eventId}/response")
    public ApiResponse<CalendarDtos.EventSummary> respond(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.RespondRequest request) {
        return ApiResponse.success(service.respond(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, groupRefs, request));
    }

    @PostMapping("/events/{eventId}/cancel")
    @Operation(operationId = "cancelCalendarEvent")
    public ApiResponse<Void> cancel(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @PathVariable UUID eventId,
            @Valid @RequestBody CalendarDtos.VersionRequest request) {
        service.cancel(
                tenantId, userId, personPublicId, eventId,
                locale, correlationId, groupRefs, request);
        return ApiResponse.success(null);
    }

    @GetMapping("/resources")
    public ApiResponse<List<CalendarDtos.ResourceSummary>> resources(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ApiResponse.success(service.resources(
                tenantId, userId, groupRefs, from, to, locale));
    }

    @GetMapping("/policy")
    public ApiResponse<CalendarDtos.Policy> calendarPolicy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId) {
        return ApiResponse.success(service.policy(tenantId));
    }

    @GetMapping("/availability")
    public ApiResponse<CalendarDtos.AvailabilityResponse> availability(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam(required = false, defaultValue = "") List<UUID> personIds,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "30") int durationMinutes,
            @RequestParam(defaultValue = "Asia/Seoul") String timeZone) {
        return ApiResponse.success(service.availability(
                tenantId, userId, personPublicId, groupRefs, personIds, from, to,
                durationMinutes, timeZone, locale));
    }

    @PostMapping("/scheduling/evaluations")
    public ApiResponse<CalendarDtos.SchedulingEvaluationResponse> evaluateScheduling(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @Valid @RequestBody CalendarDtos.SchedulingEvaluationRequest request) {
        return ApiResponse.success(service.evaluateScheduling(
                tenantId, userId, personPublicId, groupRefs, locale, request));
    }

    private String decoded(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8).trim();
            return decoded.isBlank() || decoded.length() > 160 ? null : decoded;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
