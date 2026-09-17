package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceFacilitiesController.requirePermission;
import static com.dwp.services.platform.workplace.WorkplaceFacilityClosureImpactDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.CATALOG_MANAGE;

@RestController
@RequestMapping("/v1/admin/workplace/experience/facilities")
public class WorkplaceFacilityClosureImpactAdminController {
    private final WorkplaceScopedFacilityClosureImpactService service;
    private final WorkplaceDelegatedAdminScopeGuard guard;

    WorkplaceFacilityClosureImpactAdminController(WorkplaceScopedFacilityClosureImpactService service,
                                                   WorkplaceDelegatedAdminScopeGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @PostMapping("/resources/{resourceId}/closure-impact-previews")
    public ApiResponse<ImpactPreview> preview(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam UUID siteId,
            @PathVariable UUID resourceId,
            @Valid @RequestBody CreateImpactPreview input,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requirePermission(permissions, "ADMIN.WORKPLACE:UPDATE");
        return ApiResponse.success(service.preview(tenant, actor, resourceId, key, input, permissions,
                guard.scope(request, siteId, CATALOG_MANAGE)));
    }

    @GetMapping("/closure-impact-previews/{previewId}")
    public ApiResponse<ImpactPreview> preview(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId,
            @PathVariable UUID previewId,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requirePermission(permissions, "ADMIN.WORKPLACE:UPDATE");
        return ApiResponse.success(service.preview(tenant, previewId,
                guard.scope(request, siteId, CATALOG_MANAGE)));
    }

    @PostMapping("/closure-impact-previews/{previewId}/commands")
    public ApiResponse<ClosureCommand> execute(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-DWP-Active-Access-Mode") String activeAccessMode,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlation,
            @RequestParam UUID siteId,
            @PathVariable UUID previewId,
            @Valid @RequestBody ExecuteImpactCommand input,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requirePermission(permissions, "ADMIN.WORKPLACE:UPDATE");
        requireElevated(activeAccessMode);
        return ApiResponse.success(service.execute(tenant, actor, previewId, key, input, correlation,
                permissions, guard.scope(request, siteId, CATALOG_MANAGE)));
    }

    @GetMapping("/closure-commands/{commandId}")
    public ApiResponse<ClosureCommand> command(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId,
            @PathVariable UUID commandId,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requirePermission(permissions, "ADMIN.WORKPLACE:UPDATE");
        return ApiResponse.success(service.command(tenant, commandId,
                guard.scope(request, siteId, CATALOG_MANAGE)));
    }

    @GetMapping("/closure-commands/{commandId}/receipt")
    public ApiResponse<CommandReceipt> receipt(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestParam UUID siteId,
            @PathVariable UUID commandId,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requirePermission(permissions, "ADMIN.WORKPLACE:UPDATE");
        return ApiResponse.success(service.receipt(tenant, commandId,
                guard.scope(request, siteId, CATALOG_MANAGE)));
    }

    @PostMapping("/closure-commands/{commandId}/notifications/reconcile")
    public ApiResponse<ClosureCommand> reconcile(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader("X-DWP-Active-Access-Mode") String activeAccessMode,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam UUID siteId,
            @PathVariable UUID commandId,
            @Valid @RequestBody ReconcileNotifications input,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requirePermission(permissions, "ADMIN.WORKPLACE:UPDATE");
        requireElevated(activeAccessMode);
        return ApiResponse.success(service.reconcile(tenant, actor, commandId, key, input,
                guard.scope(request, siteId, CATALOG_MANAGE)));
    }

    @PostMapping("/closure-commands/{commandId}/notifications/retry")
    public ApiResponse<ClosureCommand> retry(
            @RequestHeader("X-DWP-Tenant-ID") Long tenant,
            @RequestHeader("X-DWP-User-ID") Long actor,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader("X-DWP-Active-Access-Mode") String activeAccessMode,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam UUID siteId,
            @PathVariable UUID commandId,
            @Valid @RequestBody ReconcileNotifications input,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        requirePermission(permissions, "ADMIN.WORKPLACE:UPDATE");
        requireElevated(activeAccessMode);
        return ApiResponse.success(service.retry(tenant, actor, commandId, key, input,
                guard.scope(request, siteId, CATALOG_MANAGE)));
    }

    static void requireElevated(String accessMode) {
        if (!"ELEVATED".equals(accessMode)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Facility closure execution and notification recovery require current elevated access.");
        }
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
