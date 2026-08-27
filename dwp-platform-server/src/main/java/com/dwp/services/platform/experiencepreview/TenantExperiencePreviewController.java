package com.dwp.services.platform.experiencepreview;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/tenant-experience-preview")
public class TenantExperiencePreviewController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";

    private final TenantExperiencePreviewService service;

    public TenantExperiencePreviewController(TenantExperiencePreviewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<TenantExperiencePreviewDtos.TenantExperiencePreviewResponse> get(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        return ApiResponse.success(service.get(tenantId));
    }
}
