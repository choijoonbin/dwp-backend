package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
        return ApiResponse.success(service.get(tenantId, userId));
    }

    @PutMapping
    public ApiResponse<HomePreferenceDtos.HomePreferenceResponse> update(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody HomePreferenceDtos.UpdateHomePreferenceRequest request) {
        return ApiResponse.success(service.update(tenantId, userId, correlationId, request));
    }

    @PostMapping("/reset")
    public ApiResponse<HomePreferenceDtos.HomePreferenceResponse> reset(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody HomePreferenceDtos.VersionRequest request) {
        return ApiResponse.success(
                service.reset(tenantId, userId, correlationId, request.version()));
    }
}
