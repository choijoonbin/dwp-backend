package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/home-preferences")
public class HomePreferenceController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final HomePreferenceService service;

    public HomePreferenceController(HomePreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<HomePreferenceDtos.HomePreferenceResponse> get(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId) {
        return ApiResponse.success(service.get(tenantId, userId, HomePreferenceService.WORKSPACE_HOME));
    }

    @GetMapping("/surfaces/{surfaceKey}")
    public ApiResponse<HomePreferenceDtos.HomePreferenceResponse> getSurface(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @PathVariable String surfaceKey) {
        return ApiResponse.success(service.get(tenantId, userId, surfaceKey));
    }

    @PutMapping
    public ApiResponse<HomePreferenceDtos.HomePreferenceResponse> update(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody HomePreferenceDtos.UpdateHomePreferenceRequest request) {
        return ApiResponse.success(service.update(
                tenantId,
                userId,
                HomePreferenceService.WORKSPACE_HOME,
                correlationId,
                request));
    }

    @PutMapping("/surfaces/{surfaceKey}")
    public ApiResponse<HomePreferenceDtos.HomePreferenceResponse> updateSurface(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String surfaceKey,
            @Valid @RequestBody HomePreferenceDtos.UpdateHomePreferenceRequest request) {
        return ApiResponse.success(service.update(
                tenantId,
                userId,
                surfaceKey,
                correlationId,
                request));
    }

    @PostMapping("/reset")
    public ApiResponse<HomePreferenceDtos.HomePreferenceResponse> reset(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody HomePreferenceDtos.VersionRequest request) {
        return ApiResponse.success(service.reset(
                tenantId,
                userId,
                HomePreferenceService.WORKSPACE_HOME,
                correlationId,
                request.version()));
    }

    @PostMapping("/surfaces/{surfaceKey}/reset")
    public ApiResponse<HomePreferenceDtos.HomePreferenceResponse> resetSurface(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String surfaceKey,
            @Valid @RequestBody HomePreferenceDtos.VersionRequest request) {
        return ApiResponse.success(service.reset(
                tenantId,
                userId,
                surfaceKey,
                correlationId,
                request.version()));
    }
}
