package com.dwp.services.platform.preference;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/preference-exceptions")
public class AdminManagedPreferenceController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String CORRELATION = "X-Correlation-ID";

    private final ManagedPreferenceService service;

    public AdminManagedPreferenceController(ManagedPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ManagedPreferenceDtos.PreferenceExceptionRequest>> requests(
            @RequestHeader(TENANT) Long tenantId,
            @RequestParam(defaultValue = "ALL") String state) {
        return ApiResponse.success(service.adminRequests(tenantId, state));
    }

    @PostMapping("/{requestId}/decision")
    public ApiResponse<ManagedPreferenceDtos.PreferenceExceptionRequest> decide(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ManagedPreferenceDtos.DecideExceptionRequest request) {
        return ApiResponse.success(service.decideRequest(
                tenantId, actorId, correlationId, requestId, request));
    }
}
