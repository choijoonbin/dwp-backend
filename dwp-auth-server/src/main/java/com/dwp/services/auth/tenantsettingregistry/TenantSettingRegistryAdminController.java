package com.dwp.services.auth.tenantsettingregistry;

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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/auth/admin/tenant-setting-registry")
public class TenantSettingRegistryAdminController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final TenantSettingRegistryService service;

    public TenantSettingRegistryAdminController(TenantSettingRegistryService service) {
        this.service = service;
    }

    @GetMapping("/owners")
    public ApiResponse<List<TenantSettingRegistryDtos.OwnerDescriptor>> owners(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        return ApiResponse.success(service.owners(tenant(authentication, tenantHeader),
                actor(authentication)));
    }

    @GetMapping("/changes")
    public ApiResponse<TenantSettingRegistryDtos.ChangePage> changes(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        return ApiResponse.success(service.changes(tenant(authentication, tenantHeader),
                actor(authentication)));
    }

    @PostMapping("/changes")
    public ApiResponse<TenantSettingRegistryDtos.Change> create(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantSettingRegistryDtos.CreateChangeRequest request) {
        return ApiResponse.success(service.create(tenant(authentication, tenantHeader),
                actor(authentication), correlationId, request));
    }

    @PostMapping("/changes/{changeId}/submit")
    public ApiResponse<TenantSettingRegistryDtos.Change> submit(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantSettingRegistryDtos.VersionedCommand command) {
        return ApiResponse.success(service.submit(tenant(authentication, tenantHeader),
                actor(authentication), correlationId, changeId, command));
    }

    @PostMapping("/changes/{changeId}/decision")
    public ApiResponse<TenantSettingRegistryDtos.Change> decide(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantSettingRegistryDtos.DecisionCommand command) {
        return ApiResponse.success(service.decide(tenant(authentication, tenantHeader),
                actor(authentication), correlationId, changeId, command));
    }

    @PostMapping("/changes/{changeId}/publish")
    public ApiResponse<TenantSettingRegistryDtos.Change> publish(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantSettingRegistryDtos.VersionedCommand command) {
        return ApiResponse.success(service.publish(tenant(authentication, tenantHeader),
                actor(authentication), correlationId, changeId, command));
    }

    private Long tenant(Authentication authentication, String tenantHeader) {
        return TenantContextResolver.requireTenantId(tenantHeader, authentication);
    }

    private Long actor(Authentication authentication) {
        return AuthenticatedUserResolver.requireUserId(authentication);
    }
}
