package com.dwp.services.platform.servicecenter;

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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/services")
public class ServiceCenterController {

    private final ServiceCenterService service;

    public ServiceCenterController(ServiceCenterService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    public ApiResponse<ServiceCenterDtos.CatalogResponse> catalog(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return ApiResponse.success(service.catalog(tenantId, acceptLanguage));
    }

    @GetMapping("/requests")
    public ApiResponse<List<ServiceCenterDtos.RequestSummary>> requests(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestParam(required = false) ServiceCenterTypes.RequestStatus status) {
        return ApiResponse.success(service.myRequests(tenantId, userId, status));
    }

    @GetMapping("/requests/{requestId}")
    public ApiResponse<ServiceCenterDtos.RequestDetail> request(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID requestId) {
        return ApiResponse.success(service.myRequest(tenantId, userId, requestId));
    }

    @PostMapping("/requests")
    public ApiResponse<ServiceCenterDtos.RequestDetail> create(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody ServiceCenterDtos.CreateRequest request) {
        return ApiResponse.success(service.createRequest(
                tenantId, userId, correlationId, request));
    }

    @PutMapping("/requests/{requestId}/draft")
    public ApiResponse<ServiceCenterDtos.RequestDetail> updateDraft(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ServiceCenterDtos.UpdateDraftRequest request) {
        return ApiResponse.success(service.updateDraft(
                tenantId, userId, correlationId, requestId, request));
    }

    @PostMapping("/requests/{requestId}/submit")
    public ApiResponse<ServiceCenterDtos.RequestDetail> submit(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ServiceCenterDtos.VersionRequest request) {
        return ApiResponse.success(service.submitDraft(
                tenantId, userId, correlationId, requestId, request));
    }

    @PostMapping("/requests/{requestId}/cancel")
    public ApiResponse<ServiceCenterDtos.RequestDetail> cancel(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ServiceCenterDtos.VersionRequest request) {
        return ApiResponse.success(service.cancel(
                tenantId, userId, correlationId, requestId, request));
    }
}
