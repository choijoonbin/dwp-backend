package com.dwp.services.platform.home;

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
@RequestMapping("/v1/home-experience")
public class HomeExperienceController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private final HomeExperienceService service;

    public HomeExperienceController(HomeExperienceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<HomeExperienceDtos.HomeExperienceResponse> get(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        return ApiResponse.success(service.get(tenantId));
    }

    @GetMapping("/background")
    public ResponseEntity<org.springframework.core.io.Resource> background(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        HomeExperienceService.BackgroundContent content = service.getBackground(tenantId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.sizeBytes())
                .eTag(content.sha256())
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(content.resource());
    }
}
