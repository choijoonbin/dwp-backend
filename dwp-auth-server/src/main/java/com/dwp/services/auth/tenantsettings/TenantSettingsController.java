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
    public ApiResponse<TenantSettingsDtos.AuthPolicyChangePage> authPolicyChanges(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(service.authPolicyChanges(
                tenant(authentication, tenantHeader), actor(authentication), limit));
    }

    @PostMapping("/auth-policy/changes")
    public ApiResponse<TenantSettingsDtos.ChangeSet> createAuthPolicyChange(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantSettingsDtos.CreateAuthPolicyChangeRequest request) {
        Long tenantId = tenant(authentication, tenantHeader);
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
        Long tenantId = tenant(authentication, tenantHeader);
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
        Long tenantId = tenant(authentication, tenantHeader);
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
        Long tenantId = tenant(authentication, tenantHeader);
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
        Long tenantId = tenant(authentication, tenantHeader);
        return ApiResponse.success(service.accessProjection(
                tenantId, actor(authentication), query, page, size));
    }

    @GetMapping("/governance-snapshot")
    public ApiResponse<TenantSettingsDtos.TenantGovernanceSnapshot> governanceSnapshot(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) Long userId) {
        Long tenantId = tenant(authentication, tenantHeader);
        Long targetUserId = userId == null
                ? actor(authentication) : userId;
        return ApiResponse.success(service.governanceSnapshot(
                tenantId, actor(authentication), targetUserId));
    }

    @PostMapping("/sso-test-login-jobs")
    public ApiResponse<TenantSettingsDtos.SsoTestLoginReceipt> requestSsoTestLogin(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantSettingsDtos.SsoTestLoginCommand command) {
        Long tenantId = tenant(authentication, tenantHeader);
        return ApiResponse.success(service.requestSsoTestLogin(
                tenantId, actor(authentication), correlationId, command));
    }

    @GetMapping("/sso-test-login-jobs")
    public ApiResponse<TenantSettingsDtos.SsoTestLoginReceiptPage> ssoTestLoginReceipts(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(defaultValue = "20") int limit) {
        Long tenantId = tenant(authentication, tenantHeader);
        return ApiResponse.success(service.ssoTestLoginReceipts(
                tenantId, actor(authentication), limit));
    }

    @GetMapping("/sso-test-login-jobs/{jobId}")
    public ApiResponse<TenantSettingsDtos.SsoTestLoginReceipt> ssoTestLoginReceipt(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @PathVariable UUID jobId) {
        Long tenantId = tenant(authentication, tenantHeader);
        return ApiResponse.success(service.ssoTestLoginReceipt(
                tenantId, actor(authentication), jobId));
    }

    private Long tenant(Authentication authentication, String tenantHeader) {
        return TenantContextResolver.requireTenantId(tenantHeader, authentication);
    }

    private Long actor(Authentication authentication) {
        return AuthenticatedUserResolver.requireUserId(authentication);
    }
}
