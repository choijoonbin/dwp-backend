package com.dwp.services.auth.tenantsettings;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth/tenant-settings/effective-settings/me")
public class TenantEffectiveSettingsController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final TenantSettingsService service;

    public TenantEffectiveSettingsController(TenantSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<TenantSettingsDtos.EffectiveSetting>> effectiveSettings(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.effectiveSettings(tenantId, userId));
    }

    @GetMapping("/preferred-locale")
    public ApiResponse<TenantSettingsDtos.UserPreferenceState> preferredLocale(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.preferredLocale(tenantId, userId));
    }

    @PostMapping("/preferred-locale/restore")
    public ApiResponse<TenantSettingsDtos.UserPreferenceState> restorePreferredLocale(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantSettingsDtos.RestorePreferenceCommand command) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        Long userId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.restorePreferredLocale(
                tenantId, userId, correlationId, command));
    }
}
