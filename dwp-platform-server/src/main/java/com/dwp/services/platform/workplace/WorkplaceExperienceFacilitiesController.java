package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesDtos.*;

@RestController
@RequestMapping("/v1/workplace/experience/facilities")
public class WorkplaceExperienceFacilitiesController {
    private final WorkplaceExperienceFacilitiesService service;

    WorkplaceExperienceFacilitiesController(WorkplaceExperienceFacilitiesService service) { this.service = service; }

    @PostMapping("/resources/{resourceId}/requests")
    public ApiResponse<FacilityRequest> createRequest(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groups,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
            @PathVariable UUID resourceId, @Valid @RequestBody CreateRequest request) {
        requirePermission(permissions, "APP.WORKPLACE:CREATE");
        return ApiResponse.success(service.createRequest(tenant, actor, resourceId, key, groups, request, correlation));
    }

    @GetMapping("/requests")
    public ApiResponse<RequestPage> ownRequests(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groups,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        requirePermission(permissions, "APP.WORKPLACE:VIEW");
        return ApiResponse.success(service.ownRequests(tenant, actor, groups, page, size));
    }

    @GetMapping("/requests/{requestId}")
    public ApiResponse<FacilityRequest> ownRequest(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groups,
            @PathVariable UUID requestId) {
        requirePermission(permissions, "APP.WORKPLACE:VIEW");
        return ApiResponse.success(service.ownRequest(tenant, actor, groups, requestId));
    }

    @GetMapping("/resources/{resourceId}/booking-availability")
    public ApiResponse<BookingAvailability> availability(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID person,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groups,
            @PathVariable UUID resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        requirePermission(permissions, "APP.WORKPLACE:VIEW");
        return ApiResponse.success(service.bookingAvailability(tenant, actor, person, groups, resourceId, from, to));
    }

    static void requirePermission(String values, String expected) {
        if (!hasPermission(values, expected)) throw new BaseException(ErrorCode.FORBIDDEN, "The required Workplace permission is missing.");
    }
    static boolean hasPermission(String values, String expected) {
        return values != null && Arrays.stream(values.split(","))
                .map(String::trim).map(s -> s.toUpperCase(Locale.ROOT)).anyMatch(expected::equals);
    }
}
