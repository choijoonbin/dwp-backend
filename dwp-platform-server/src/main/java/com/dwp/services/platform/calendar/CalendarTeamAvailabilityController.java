package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/calendar/team-availability")
public class CalendarTeamAvailabilityController {
    private final CalendarTeamAvailabilityService service;

    public CalendarTeamAvailabilityController(CalendarTeamAvailabilityService service) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    @Operation(operationId = "getCalendarTeamAvailabilitySnapshot", summary = "Read availability of members who share their personal calendar with the caller")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Short-lived shared-member snapshot; never online presence"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid time zone"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Trusted tenant session required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Calendar and People VIEW, workspace membership, and matching person identity required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Snapshot is incomplete, invalid, or expired; discard prior data")
    })
    public ApiResponse<CalendarTeamAvailabilityDtos.Snapshot> snapshot(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestParam(defaultValue = "Asia/Seoul") String timeZone) {
        // Must not be persisted in shared HTTP caches, including error responses.
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        return ApiResponse.success(service.snapshot(CalendarTeamAvailabilityAccess.require(request), timeZone));
    }
}
