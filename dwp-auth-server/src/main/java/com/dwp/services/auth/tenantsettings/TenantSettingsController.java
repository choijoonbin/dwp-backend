package com.dwp.services.auth.tenantsettings;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/auth/admin/tenant-settings")
public class TenantSettingsController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final TenantSettingsService service;

    public TenantSettingsController(TenantSettingsService service) {
        this.service = service;
    }

    @GetMapping("/auth-policy/changes")
    public ApiResponse<List<TenantSettingsDtos.ChangeSet>> authPolicyChanges(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        return ApiResponse.success(service.authPolicyChanges(
                tenantAdmin(authentication, tenantHeader)));
    }

    @PostMapping("/auth-policy/changes")
    public ApiResponse<TenantSettingsDtos.ChangeSet> createAuthPolicyChange(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantSettingsDtos.CreateAuthPolicyChangeRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.createAuthPolicyChange(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, request));
    }

    @PostMapping("/auth-policy/changes/{changeSetId}/submit")
    public ApiResponse<TenantSettingsDtos.ChangeSet> submit(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeSetId,
            @Valid @RequestBody TenantSettingsDtos.VersionedCommand command) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.submit(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, changeSetId, command));
    }

    @PostMapping("/auth-policy/changes/{changeSetId}/decision")
    public ApiResponse<TenantSettingsDtos.ChangeSet> decide(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeSetId,
            @Valid @RequestBody TenantSettingsDtos.DecisionCommand command) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.decide(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, changeSetId, command));
    }

    @PostMapping("/auth-policy/changes/{changeSetId}/publish")
    public ApiResponse<TenantSettingsDtos.ChangeSet> publish(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID changeSetId,
            @Valid @RequestBody TenantSettingsDtos.VersionedCommand command) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.publish(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, changeSetId, command));
    }

    @GetMapping("/access-projection")
    public ApiResponse<TenantSettingsDtos.AccessProjection> accessProjection(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AuthenticatedUserResolver.requireIdentityAdmin(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.accessProjection(tenantId, query, page, size));
    }

    private Long tenantAdmin(Authentication authentication, String tenantHeader) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        return TenantContextResolver.requireTenantId(tenantHeader, authentication);
    }
}
