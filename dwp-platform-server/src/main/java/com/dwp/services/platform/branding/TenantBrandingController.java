package com.dwp.services.platform.branding;

import com.dwp.core.common.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/v1/tenant-branding")
public class TenantBrandingController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private final TenantBrandingService service;

    public TenantBrandingController(TenantBrandingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<TenantBrandingDtos.TenantBrandingResponse> get(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        return ApiResponse.success(service.get(tenantId));
    }

    @GetMapping("/logo")
    public ResponseEntity<org.springframework.core.io.Resource> logo(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        TenantBrandingService.LogoContent content = service.getLogo(tenantId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .eTag(content.sha256())
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .body(content.resource());
    }
}
