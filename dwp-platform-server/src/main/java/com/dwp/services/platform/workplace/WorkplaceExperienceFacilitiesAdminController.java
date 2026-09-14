package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.*;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesController.requirePermission;

@RestController
@RequestMapping("/v1/admin/workplace/experience/facilities")
public class WorkplaceExperienceFacilitiesAdminController {
    private final WorkplaceScopedExperienceFacilitiesService service;
    private final WorkplaceDelegatedAdminScopeGuard guard;

    WorkplaceExperienceFacilitiesAdminController(WorkplaceScopedExperienceFacilitiesService service,
                                                WorkplaceDelegatedAdminScopeGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/closures")
    public ApiResponse<ClosurePage> closures(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId, @RequestParam(required = false) UUID floorId,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "false") boolean includeCancelled,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest servletRequest) {
        requirePermission(permissions, "ADMIN.WORKPLACE:VIEW");
        return ApiResponse.success(service.closures(tenant, floorId, resourceId, from, to, includeCancelled, page, size, guard.scope(servletRequest, siteId, CATALOG_VIEW)));
    }

    @GetMapping("/closures/{closureId}")
    public ApiResponse<Closure> closure(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId, @PathVariable UUID closureId, HttpServletRequest servletRequest) {
        requirePermission(permissions, "ADMIN.WORKPLACE:VIEW");
        return ApiResponse.success(service.closure(tenant, closureId, guard.scope(servletRequest, siteId, CATALOG_VIEW)));
    }

    @GetMapping("/resources/{resourceId}/room-booking-impact")
    public ApiResponse<RoomBookingImpact> roomImpact(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId, @PathVariable UUID resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest servletRequest) {
        requirePermission(permissions, "ADMIN.WORKPLACE:VIEW");
        return ApiResponse.success(service.roomImpact(tenant, resourceId, from, to, page, size, permissions, guard.scope(servletRequest, siteId, CATALOG_VIEW)));
    }

    @PostMapping("/resources/{resourceId}/closures")
    public ApiResponse<Closure> createClosure(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
            @RequestParam UUID siteId, @PathVariable UUID resourceId,
            @Valid @RequestBody CreateClosure request, HttpServletRequest servletRequest) {
        requirePermission(permissions, "ADMIN.WORKPLACE:CREATE");
        return ApiResponse.success(service.createClosure(tenant, actor, resourceId, key, request, correlation, permissions, guard.scope(servletRequest, siteId, CATALOG_MANAGE)));
    }

    @PutMapping("/closures/{closureId}/cancel")
    public ApiResponse<Closure> cancelClosure(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
            @RequestParam UUID siteId, @PathVariable UUID closureId,
            @Valid @RequestBody CancelClosure request, HttpServletRequest servletRequest) {
        requirePermission(permissions, "ADMIN.WORKPLACE:UPDATE");
        return ApiResponse.success(service.cancelClosure(tenant, actor, closureId, request, correlation, permissions, guard.scope(servletRequest, siteId, CATALOG_MANAGE)));
    }

    @GetMapping("/requests")
    public ApiResponse<RequestPage> requests(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId, @RequestParam(required = false) UUID floorId,
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest servletRequest) {
        requirePermission(permissions, "ADMIN.WORKPLACE:VIEW");
        return ApiResponse.success(service.requests(tenant, floorId, status, page, size, guard.scope(servletRequest, siteId, CATALOG_VIEW)));
    }

    @PutMapping("/requests/{requestId}/status")
    public ApiResponse<FacilityRequest> status(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
            @RequestParam UUID siteId, @PathVariable UUID requestId,
            @Valid @RequestBody ChangeRequestStatus request, HttpServletRequest servletRequest) {
        requirePermission(permissions, "ADMIN.WORKPLACE:UPDATE");
        return ApiResponse.success(service.status(tenant, actor, requestId, request, correlation, guard.scope(servletRequest, siteId, CATALOG_MANAGE)));
    }
}
