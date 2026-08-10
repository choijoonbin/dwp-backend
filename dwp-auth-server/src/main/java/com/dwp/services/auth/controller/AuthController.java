package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.AuthPolicyResponse;
import com.dwp.services.auth.dto.CsrfTokenResponse;
import com.dwp.services.auth.dto.IdentityProviderResponse;
import com.dwp.services.auth.dto.MeResponse;
import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.dto.UpdatePreferredLocaleRequest;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.AuthPolicyService;
import com.dwp.services.auth.service.AuthService;
import com.dwp.services.auth.service.IdentityProviderService;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthPolicyService authPolicyService;
    private final IdentityProviderService identityProviderService;
    private final AuthService authService;

    public AuthController(
            AuthPolicyService authPolicyService,
            IdentityProviderService identityProviderService,
            AuthService authService) {
        this.authPolicyService = authPolicyService;
        this.identityProviderService = identityProviderService;
        this.authService = authService;
    }

    @GetMapping("/policy")
    public ApiResponse<AuthPolicyResponse> getPolicy(
            @RequestHeader("X-Tenant-ID") Long tenantId) {
        return ApiResponse.success(authPolicyService.getPolicy(tenantId));
    }

    @GetMapping("/csrf")
    public ApiResponse<CsrfTokenResponse> getCsrfToken(CsrfToken csrfToken) {
        return ApiResponse.success(new CsrfTokenResponse(
                csrfToken.getToken(),
                csrfToken.getHeaderName()));
    }

    @GetMapping("/idp")
    public ApiResponse<List<IdentityProviderResponse>> getIdentityProviders(
            @RequestHeader("X-Tenant-ID") Long tenantId) {
        return ApiResponse.success(identityProviderService.getEnabledProviders(tenantId));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> getMe(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(authService.getMe(userId, tenantId));
    }

    @PatchMapping("/me/locale")
    public ApiResponse<MeResponse> updatePreferredLocale(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader,
            @Valid @RequestBody UpdatePreferredLocaleRequest request) {
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(authService.updatePreferredLocale(userId, tenantId, request));
    }

    @GetMapping("/permissions")
    public ApiResponse<List<PermissionDTO>> getPermissions(
            Authentication authentication,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(authService.getPermissions(userId, tenantId));
    }
}
