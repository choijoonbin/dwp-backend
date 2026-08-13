package com.dwp.services.platform.servicecenter;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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
@RequestMapping("/v1/admin/services")
public class AdminServiceCenterController {

    private final ServiceCenterService service;

    public AdminServiceCenterController(ServiceCenterService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    public ApiResponse<List<ServiceCenterDtos.AdminCatalogItem>> catalog(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId) {
        return ApiResponse.success(service.adminCatalog(tenantId));
    }

    @PutMapping("/catalog/{serviceKey}")
    public ApiResponse<ServiceCenterDtos.AdminCatalogItem> saveCatalog(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable String serviceKey,
            @Valid @RequestBody ServiceCenterDtos.CatalogDefinitionRequest request) {
        if (!serviceKey.equals(request.serviceKey())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The service key must match the request path.");
        }
        return ApiResponse.success(service.saveDefinition(tenantId, userId, correlationId, request));
    }

    @PostMapping("/catalog")
    public ApiResponse<ServiceCenterDtos.AdminCatalogItem> createCatalog(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody ServiceCenterDtos.CatalogDefinitionRequest request) {
        return ApiResponse.success(service.saveDefinition(tenantId, userId, correlationId, request));
    }

    @GetMapping("/requests")
    public ApiResponse<List<ServiceCenterDtos.RequestSummary>> requests(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestParam(required = false) ServiceCenterTypes.RequestStatus status) {
        return ApiResponse.success(service.operationsQueue(tenantId, status));
    }

    @GetMapping("/requests/{requestId}")
    public ApiResponse<ServiceCenterDtos.RequestDetail> request(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @PathVariable UUID requestId) {
        return ApiResponse.success(service.operationsDetail(tenantId, requestId));
    }

    @PostMapping("/requests/{requestId}/transition")
    public ApiResponse<ServiceCenterDtos.RequestDetail> transition(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ServiceCenterDtos.TransitionRequest request) {
        return ApiResponse.success(service.transition(
                tenantId, userId, correlationId, requestId, request));
    }
}
