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
@RequestMapping("/v2/calendar/external-events")
final class CalendarExternalProjectionController {

    private final CalendarExternalProjectionService service;

    CalendarExternalProjectionController(CalendarExternalProjectionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listExternalCalendarEventsV2")
    ApiResponse<CalendarExternalDtos.Projection> events(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(defaultValue = "100") int limit) {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        return ApiResponse.success(service.project(
                CalendarExternalAccess.require(request), from, to, limit));
    }
}
