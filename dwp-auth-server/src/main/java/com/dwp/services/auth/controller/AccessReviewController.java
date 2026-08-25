package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.AccessReviewDtos;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.AccessReviewService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/auth/admin/access/reviews")
public class AccessReviewController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final AccessReviewService service;

    public AccessReviewController(AccessReviewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AccessReviewDtos.CampaignSummary>> campaigns(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.campaigns(tenantId, actorId, true));
    }

    @PostMapping
    public ApiResponse<AccessReviewDtos.CampaignSummary> create(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AccessReviewDtos.CreateCampaignRequest request) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.createCampaign(
                tenantId, actorId, correlationId, request));
    }

    @GetMapping("/{campaignId}")
    public ApiResponse<AccessReviewDtos.CampaignItems> items(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @PathVariable UUID campaignId) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.campaignItems(tenantId, actorId, true, campaignId));
    }

    @PostMapping("/{campaignId}/activate")
    public ApiResponse<AccessReviewDtos.CampaignSummary> activate(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody AccessReviewDtos.VersionRequest request) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.activate(
                tenantId, actorId, correlationId, campaignId, request.version()));
    }

    @PutMapping("/{campaignId}/items/{itemId}/decision")
    public ApiResponse<AccessReviewDtos.ItemSummary> decide(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID campaignId,
            @PathVariable UUID itemId,
            @Valid @RequestBody AccessReviewDtos.DecisionRequest request) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.decide(
                tenantId, actorId, true, correlationId, campaignId, itemId, request));
    }

    @PostMapping("/{campaignId}/complete")
    public ApiResponse<AccessReviewDtos.CampaignSummary> complete(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody AccessReviewDtos.VersionRequest request) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.complete(
                tenantId, actorId, correlationId, campaignId, request.version()));
    }
}
