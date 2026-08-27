package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.AccessReviewDtos;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.AccessReviewWorkRouteGuard;
import com.dwp.services.auth.security.DurableIdentityPlaneGuard;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.AccessReviewWorkService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Assigned-review Work API. This controller never exposes admin campaign paths. */
@RestController
@RequestMapping("/auth/work/access-review-items")
public class AccessReviewWorkController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final AccessReviewWorkService service;
    private final AccessReviewWorkRouteGuard routeGuard;
    private final DurableIdentityPlaneGuard identityPlaneGuard;

    public AccessReviewWorkController(
            AccessReviewWorkService service,
            AccessReviewWorkRouteGuard routeGuard,
            DurableIdentityPlaneGuard identityPlaneGuard) {
        this.service = service;
        this.routeGuard = routeGuard;
        this.identityPlaneGuard = identityPlaneGuard;
    }

    @GetMapping("/{workItemRef}")
    @Operation(operationId = "getAssignedAccessReviewWorkItem")
    public ApiResponse<AccessReviewDtos.WorkItemDetail> detail(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @PathVariable UUID workItemRef) {
        identityPlaneGuard.requireTenant(authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        routeGuard.requireDetail(tenantId, actorId, workItemRef);
        return ApiResponse.success(service.detail(tenantId, actorId, workItemRef));
    }

    @PutMapping("/{workItemRef}/decision")
    @Operation(operationId = "decideAssignedAccessReviewWorkItem")
    public ApiResponse<AccessReviewDtos.WorkItemDetail> decide(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID workItemRef,
            @Valid @RequestBody AccessReviewDtos.DecisionRequest request) {
        identityPlaneGuard.requireTenant(authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        routeGuard.requireDecision(tenantId, actorId, workItemRef, request.version());
        return ApiResponse.success(service.decide(
                tenantId, actorId, correlationId, workItemRef, request));
    }
}
