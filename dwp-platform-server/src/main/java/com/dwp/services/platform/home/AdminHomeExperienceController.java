package com.dwp.services.platform.home;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/v1/admin/home-experience")
public class AdminHomeExperienceController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final HomeExperienceService service;

    public AdminHomeExperienceController(HomeExperienceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<HomeExperienceDtos.HomeExperienceResponse> get(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        return ApiResponse.success(service.get(tenantId));
    }

    @PutMapping
    public ApiResponse<HomeExperienceDtos.HomeExperienceResponse> update(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody HomeExperienceDtos.UpdateHomeExperienceRequest request) {
        return ApiResponse.success(service.update(tenantId, actorId, correlationId, request));
    }

    @PostMapping(path = "/background", consumes = "multipart/form-data")
    public ApiResponse<HomeExperienceDtos.HomeExperienceResponse> uploadBackground(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @RequestParam @Min(0) Long version,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(
                service.uploadBackground(tenantId, actorId, correlationId, version, file));
    }

    @PostMapping("/background/reset")
    public ApiResponse<HomeExperienceDtos.HomeExperienceResponse> resetBackground(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody HomeExperienceDtos.VersionRequest request) {
        return ApiResponse.success(
                service.resetBackground(tenantId, actorId, correlationId, request.version()));
    }
}
