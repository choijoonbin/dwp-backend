package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceExperienceReportDtos.*;

/** PlatformSecurityFilter authenticates trusted headers; the shared delegated interceptor resolves site grants. */
@RestController
@RequestMapping("/v1/admin/workplace")
public class WorkplaceExperienceReportController {
    private final WorkplaceScopedExperienceReportService service;
    private final WorkplaceDelegatedAdminScopeGuard guard;

    WorkplaceExperienceReportController(WorkplaceScopedExperienceReportService service,
                                        WorkplaceDelegatedAdminScopeGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/experience-report")
    public ApiResponse<Report> report(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId, @RequestParam(required = false) UUID floorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        requireAccess(permissions);
        return ApiResponse.success(service.report(tenantId, floorId, from, to, page, size, guard.scope(request, siteId, CATALOG_VIEW)));
    }

    @GetMapping("/experience-report/bookings/{bookingId}")
    public ApiResponse<BookingDetail> booking(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId, @PathVariable UUID bookingId, HttpServletRequest request) {
        requireAccess(permissions);
        return ApiResponse.success(service.booking(tenantId, bookingId, guard.scope(request, siteId, CATALOG_VIEW)));
    }

    @GetMapping("/resources/{resourceId}/future-booking-impact")
    public ApiResponse<FutureBookingImpact> futureImpact(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @PathVariable UUID resourceId, @RequestParam UUID siteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        requireAccess(permissions);
        return ApiResponse.success(service.futureImpact(tenantId, resourceId, from, to, page, size, guard.scope(request, siteId, CATALOG_VIEW)));
    }

    @GetMapping("/policy-impact-preview")
    public ApiResponse<PolicyImpact> policyImpact(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId, @RequestParam(required = false) UUID floorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Boolean requireCheckIn,
            @RequestParam(required = false) Integer autoReleaseMinutes,
            @RequestParam(required = false) Integer minimumBookingMinutes,
            @RequestParam(required = false) Integer maximumBookingMinutes,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime workingDayStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime workingDayEnd,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        requireAccess(permissions);
        return ApiResponse.success(service.policyImpact(tenantId, floorId, from, to,
                new PolicyChanges(requireCheckIn, autoReleaseMinutes, minimumBookingMinutes,
                        maximumBookingMinutes, workingDayStart, workingDayEnd), page, size, guard.scope(request, siteId, POLICY_MANAGE)));
    }

    static void requireAccess(String permissions) {
        Set<String> values = permissions == null ? Set.of() : Arrays.stream(permissions.split(","))
                .map(String::trim).map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (!values.contains("ADMIN.WORKPLACE:VIEW")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Workplace administration view permission is required.");
        }
    }
}
