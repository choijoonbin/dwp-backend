package com.dwp.services.platform.workplace;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/workplace")
public class AdminWorkplaceController {

    private final WorkplaceService service;

    public AdminWorkplaceController(WorkplaceService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<WorkplaceDtos.AdminOverview> getWorkplaceAdminOverview(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId) {
        return ApiResponse.success(service.adminOverview(tenantId));
    }

    @GetMapping("/sites")
    public ApiResponse<List<WorkplaceDtos.Site>> getWorkplaceSites(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String locale) {
        return ApiResponse.success(service.sites(tenantId, locale));
    }

    @PostMapping("/sites")
    public ApiResponse<WorkplaceDtos.Site> createWorkplaceSite(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody WorkplaceDtos.SiteRequest request) {
        return ApiResponse.success(service.saveSite(
                tenantId, actorId, null, locale, correlationId, request));
    }

    @PutMapping("/sites/{siteId}")
    public ApiResponse<WorkplaceDtos.Site> updateWorkplaceSite(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID siteId,
            @Valid @RequestBody WorkplaceDtos.SiteRequest request) {
        return ApiResponse.success(service.saveSite(
                tenantId, actorId, siteId, locale, correlationId, request));
    }

    @GetMapping("/floors")
    public ApiResponse<List<WorkplaceDtos.Floor>> getWorkplaceFloors(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam UUID siteId) {
        return ApiResponse.success(service.floors(tenantId, siteId, locale));
    }

    @PostMapping("/sites/{siteId}/floors")
    public ApiResponse<WorkplaceDtos.Floor> createWorkplaceFloor(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID siteId,
            @Valid @RequestBody WorkplaceDtos.FloorRequest request) {
        return ApiResponse.success(service.saveFloor(
                tenantId, actorId, siteId, null, locale, correlationId, request));
    }

    @PutMapping("/sites/{siteId}/floors/{floorId}")
    public ApiResponse<WorkplaceDtos.Floor> updateWorkplaceFloor(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID siteId,
            @PathVariable UUID floorId,
            @Valid @RequestBody WorkplaceDtos.FloorRequest request) {
        return ApiResponse.success(service.saveFloor(
                tenantId, actorId, siteId, floorId, locale, correlationId, request));
    }

    @PostMapping(path = "/floors/{floorId}/background", consumes = "multipart/form-data")
    public ApiResponse<WorkplaceDtos.Floor> uploadWorkplaceFloorBackground(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID floorId,
            @RequestParam Long version,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(service.uploadFloorBackground(
                tenantId, actorId, floorId, version, locale, correlationId, file));
    }

    @GetMapping("/floors/{floorId}/resources")
    public ApiResponse<List<WorkplaceDtos.Resource>> getWorkplaceResources(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @PathVariable UUID floorId) {
        return ApiResponse.success(service.resources(tenantId, floorId, locale));
    }

    @PostMapping("/floors/{floorId}/resources")
    public ApiResponse<WorkplaceDtos.Resource> createWorkplaceResource(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID floorId,
            @Valid @RequestBody WorkplaceDtos.ResourceRequest request) {
        return ApiResponse.success(service.saveResource(
                tenantId, actorId, floorId, null, locale, correlationId, request));
    }

    @PutMapping("/floors/{floorId}/resources/{resourceId}")
    public ApiResponse<WorkplaceDtos.Resource> updateWorkplaceResource(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID floorId,
            @PathVariable UUID resourceId,
            @Valid @RequestBody WorkplaceDtos.ResourceRequest request) {
        return ApiResponse.success(service.saveResource(
                tenantId, actorId, floorId, resourceId, locale, correlationId, request));
    }

    @PutMapping("/floors/{floorId}/layout")
    public ApiResponse<List<WorkplaceDtos.Resource>> updateWorkplaceLayout(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID floorId,
            @Valid @RequestBody WorkplaceDtos.LayoutRequest request) {
        return ApiResponse.success(service.updateLayout(
                tenantId, actorId, floorId, locale, correlationId, request));
    }

    @GetMapping("/policy")
    public ApiResponse<WorkplaceDtos.Policy> getWorkplacePolicy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId) {
        return ApiResponse.success(service.policy(tenantId));
    }

    @PutMapping("/policy")
    public ApiResponse<WorkplaceDtos.Policy> updateWorkplacePolicy(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long actorId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody WorkplaceDtos.PolicyRequest request) {
        return ApiResponse.success(service.updatePolicy(
                tenantId, actorId, correlationId, request));
    }
}
