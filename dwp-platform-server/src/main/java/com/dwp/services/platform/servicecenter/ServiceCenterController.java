package com.dwp.services.platform.servicecenter;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff;
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
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Auth-Session-ID", required = false) String authSessionId,
            @RequestHeader(value = "X-DWP-Roles", required = false) String roles,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-DWP-DWAI-ON-Handoff-ID", required = false) UUID dwaionHandoffId,
            @RequestHeader(value = "X-DWP-DWAI-ON-Proposal-ID", required = false) UUID dwaionProposalId,
            @RequestHeader(value = "X-DWP-DWAI-ON-Action-Key", required = false) String dwaionActionKey,
            @RequestHeader(value = "X-DWP-DWAI-ON-Handoff-Version", required = false) Long dwaionHandoffVersion,
            @Valid @RequestBody ServiceCenterDtos.CreateRequest request) {
        PlatformDwaionHandoff.Binding dwaionBinding = PlatformDwaionHandoff.Binding.optional(
                dwaionHandoffId, dwaionProposalId, dwaionActionKey, dwaionHandoffVersion,
                "SERVICE.REQUEST.CREATE");
        return ApiResponse.success(service.createRequest(
                tenantId, userId, correlationId, request, dwaionBinding,
                dwaionBinding == null ? null : new PlatformDwaionHandoff.Identity(
                        authSessionId, personPublicId, roles, permissions)));
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

    @PostMapping("/requests/{requestId}/information-response")
    public ApiResponse<ServiceCenterDtos.RequestDetail> informationResponse(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ServiceCenterDtos.InformationResponseRequest request) {
        return ApiResponse.success(service.respondToInformationRequest(
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
