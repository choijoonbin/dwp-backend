package com.dwp.services.platform.preference;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/personal-preferences")
public class ManagedPreferenceController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String CORRELATION = "X-Correlation-ID";

    private final ManagedPreferenceService service;

    public ManagedPreferenceController(ManagedPreferenceService service) {
        this.service = service;
    }

    @GetMapping("/managed-policy")
    public ApiResponse<ManagedPreferenceDtos.ManagedPreferencePolicy> policy(
            @RequestHeader(TENANT) Long tenantId) {
        return ApiResponse.success(service.policy(tenantId));
    }

    @GetMapping("/exceptions")
    public ApiResponse<List<ManagedPreferenceDtos.PreferenceExceptionRequest>> requests(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId) {
        return ApiResponse.success(service.myRequests(tenantId, userId));
    }

    @PostMapping("/exceptions")
    public ApiResponse<ManagedPreferenceDtos.PreferenceExceptionRequest> request(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ManagedPreferenceDtos.CreateExceptionRequest request) {
        return ApiResponse.success(service.requestException(
                tenantId, userId, correlationId, request));
    }

    @PostMapping("/exceptions/{requestId}/cancel")
    public ApiResponse<ManagedPreferenceDtos.PreferenceExceptionRequest> cancel(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ManagedPreferenceDtos.VersionRequest request) {
        return ApiResponse.success(service.cancelRequest(
                tenantId, userId, correlationId, requestId, request.version()));
    }
}
