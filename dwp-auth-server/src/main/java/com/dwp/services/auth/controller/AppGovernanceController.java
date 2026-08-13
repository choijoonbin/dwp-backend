package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.AppGovernanceService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/auth/admin/access/app-governance")
public class AppGovernanceController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final AppGovernanceService service;

    public AppGovernanceController(AppGovernanceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<AppGovernanceDtos.Dashboard> dashboard(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.dashboard(tenantId, actorId));
    }

    @PostMapping("/resource-sets")
    public ApiResponse<AppGovernanceDtos.ResourceSet> createResourceSet(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AppGovernanceDtos.CreateResourceSetRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(
                service.createResourceSet(tenantId, actorId, correlationId, request));
    }

    @PutMapping("/resource-sets/{resourceSetId}")
    public ApiResponse<AppGovernanceDtos.ResourceSet> updateResourceSet(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID resourceSetId,
            @Valid @RequestBody AppGovernanceDtos.UpdateResourceSetRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.updateResourceSet(
                tenantId, actorId, correlationId, resourceSetId, request));
    }

    @PostMapping("/assignments")
    public ApiResponse<AppGovernanceDtos.Assignment> requestAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AppGovernanceDtos.CreateAssignmentRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(
                service.requestAssignment(tenantId, actorId, correlationId, request));
    }

    @PostMapping("/assignments/{assignmentId}/decision")
    public ApiResponse<AppGovernanceDtos.Assignment> decideAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AppGovernanceDtos.AssignmentDecisionRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.decideAssignment(
                tenantId, actorId, correlationId, assignmentId, request));
    }

    @PatchMapping("/assignments/{assignmentId}/revoke")
    public ApiResponse<AppGovernanceDtos.Assignment> revokeAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AppGovernanceDtos.RevokeAssignmentRequest request) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.revokeAssignment(
                tenantId, actorId, correlationId, assignmentId, request));
    }
}
