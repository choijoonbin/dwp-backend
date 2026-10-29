package com.dwp.services.platform.calendar;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Isolated v2 surface; the existing v1 scheduling contract remains unchanged. */
@RestController
@RequestMapping("/v2/calendar/scheduling")
public class CalendarSchedulingV2Controller {

    private final CalendarSchedulingV2Service service;

    public CalendarSchedulingV2Controller(CalendarSchedulingV2Service service) {
        this.service = service;
    }

    @PostMapping("/evaluations")
    @Operation(operationId = "evaluateCalendarSchedulingV2")
    public ApiResponse<CalendarSchedulingV2Dtos.EvaluationResponse> evaluate(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @Valid @RequestBody CalendarSchedulingV2Dtos.EvaluationRequest request) {
        return ApiResponse.success(service.evaluate(
                tenantId, userId, personPublicId, groupRefs, locale, request));
    }

    @PostMapping("/evaluations/{evaluationId}/hard-recheck")
    @Operation(operationId = "hardRecheckCalendarSchedulingV2")
    public ApiResponse<CalendarSchedulingV2Dtos.HardRecheckResponse> hardRecheck(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader("X-DWP-Person-Public-ID") UUID personPublicId,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @PathVariable UUID evaluationId,
            @Valid @RequestBody CalendarSchedulingV2Dtos.HardRecheckRequest request) {
        return ApiResponse.success(service.hardRecheck(
                tenantId, userId, personPublicId, groupRefs, locale,
                evaluationId, request));
    }
}
