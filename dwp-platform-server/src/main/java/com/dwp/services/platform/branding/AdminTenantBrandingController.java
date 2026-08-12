package com.dwp.services.platform.branding;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;
import java.util.List;

@Validated
@RestController
@RequestMapping("/v1/admin/tenant-branding")
public class AdminTenantBrandingController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final TenantBrandingService service;

    public AdminTenantBrandingController(TenantBrandingService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<TenantBrandingDtos.TenantBrandingResponse> get(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        return ApiResponse.success(service.get(tenantId));
    }

    @PutMapping
    public ApiResponse<TenantBrandingDtos.TenantBrandingResponse> update(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantBrandingDtos.UpdateTenantBrandingRequest request) {
        return ApiResponse.success(service.update(tenantId, actorId, correlationId, request));
    }

    @PostMapping(path = "/logo", consumes = "multipart/form-data")
    public ApiResponse<TenantBrandingDtos.TenantBrandingResponse> uploadLogo(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @RequestParam @Min(0) Long version,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(
                service.uploadLogo(tenantId, actorId, correlationId, version, file));
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

    @PostMapping("/logo/reset")
    public ApiResponse<TenantBrandingDtos.TenantBrandingResponse> resetLogo(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantBrandingDtos.VersionRequest request) {
        return ApiResponse.success(
                service.resetLogo(tenantId, actorId, correlationId, request.version()));
    }

    @GetMapping("/revisions")
    public ApiResponse<List<TenantBrandingDtos.BrandingRevisionResponse>> history(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestParam(defaultValue = "20") @Min(1) int limit) {
        return ApiResponse.success(service.history(tenantId, limit));
    }

    @PostMapping("/revisions/{revisionId}/rollback")
    public ApiResponse<TenantBrandingDtos.TenantBrandingResponse> rollback(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable @Min(1) Long revisionId,
            @Valid @RequestBody TenantBrandingDtos.VersionRequest request) {
        return ApiResponse.success(
                service.rollback(
                        tenantId,
                        actorId,
                        correlationId,
                        revisionId,
                        request.version()));
    }
}
