package com.dwp.services.auth.tenantcapabilityoverride;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/auth/admin/tenant-app-adoption/capability-overrides")
public class TenantCapabilityOverrideController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final TenantCapabilityOverrideService service;

    public TenantCapabilityOverrideController(TenantCapabilityOverrideService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<TenantCapabilityOverrideDtos.Projection> projection(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        return ApiResponse.success(service.projection(
                tenant(authentication, tenantHeader), actor(authentication)));
    }

    @PostMapping
    public ApiResponse<TenantCapabilityOverrideDtos.Change> create(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantCapabilityOverrideDtos.CreateRequest request) {
        return ApiResponse.success(service.create(
                tenant(authentication, tenantHeader), actor(authentication),
                correlationId, request));
    }

    @PostMapping("/{changeId}/submit")
    public ApiResponse<TenantCapabilityOverrideDtos.Change> submit(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantCapabilityOverrideDtos.VersionedCommand command) {
        return ApiResponse.success(service.submit(
                tenant(authentication, tenantHeader), actor(authentication), correlationId,
                changeId, command));
    }

    @PostMapping("/{changeId}/decision")
    public ApiResponse<TenantCapabilityOverrideDtos.Change> decide(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantCapabilityOverrideDtos.DecisionCommand command) {
        return ApiResponse.success(service.decide(
                tenant(authentication, tenantHeader), actor(authentication), correlationId,
                changeId, command));
    }

    @PostMapping("/{changeId}/activate")
    public ApiResponse<TenantCapabilityOverrideDtos.Change> activate(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantCapabilityOverrideDtos.ReasonedCommand command) {
        return ApiResponse.success(service.activate(
                tenant(authentication, tenantHeader), actor(authentication), correlationId,
                changeId, command));
    }

    @PostMapping("/{changeId}/revoke")
    public ApiResponse<TenantCapabilityOverrideDtos.Change> revoke(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantCapabilityOverrideDtos.ReasonedCommand command) {
        return ApiResponse.success(service.revoke(
                tenant(authentication, tenantHeader), actor(authentication), correlationId,
                changeId, command));
    }

    private static Long tenant(Authentication authentication, String tenantHeader) {
        return TenantContextResolver.requireTenantId(tenantHeader, authentication);
    }

    private static Long actor(Authentication authentication) {
        return AuthenticatedUserResolver.requireUserId(authentication);
    }
}
