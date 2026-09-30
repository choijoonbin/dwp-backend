package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.security.TenantContextResolver;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth/tenant-settings/managed-effective/me")
public class TenantSettingRegistryReaderController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private final TenantSettingRegistryService service;

    public TenantSettingRegistryReaderController(TenantSettingRegistryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<TenantSettingRegistryDtos.EffectiveSetting>> effective(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.effective(tenantId));
    }
}
