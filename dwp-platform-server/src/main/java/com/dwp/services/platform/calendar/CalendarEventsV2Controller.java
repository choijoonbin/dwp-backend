package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/v2/calendar")
final class CalendarEventsV2Controller {

    private final CalendarEventsV2Service service;

    CalendarEventsV2Controller(CalendarEventsV2Service service) {
        this.service = service;
    }

    @GetMapping("/events")
    @Operation(operationId = "listCalendarEventsV2")
    ApiResponse<CalendarEventsV2Dtos.EventPage> events(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(defaultValue = "Asia/Seoul") String timeZone,
            @RequestParam(name = "limit", defaultValue = "100") int pageSize,
            @RequestParam(required = false) String cursor) {
        return ApiResponse.success(service.events(
                tenantId, userId, personPublicId, groupRefs, locale,
                from, to, timeZone, pageSize, cursor));
    }
}
