package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/v2/calendar/events/search")
final class CalendarEventSearchController {

    private final CalendarEventSearchService service;

    CalendarEventSearchController(CalendarEventSearchService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "searchCalendarEventsV2")
    ApiResponse<CalendarEventSearchDtos.SearchPage> search(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(name = "q") String query,
            @RequestParam(name = "from")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(name = "to")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(name = "timeZone", defaultValue = "Asia/Seoul") String timeZone,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        noStore(response);
        CalendarEventSearchAccess.Actor actor = CalendarEventSearchAccess.require(request);
        return ApiResponse.success(service.search(
                actor.tenantId(), actor.userId(), actor.personPublicId(),
                actor.groupRefs(), actor.locale(), query, from, to,
                timeZone, limit, cursor));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
    }
}
