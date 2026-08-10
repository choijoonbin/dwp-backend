package com.dwp.services.platform.preference;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/personal-preferences")
public class PersonalPreferenceController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final PersonalPreferenceService service;

    public PersonalPreferenceController(PersonalPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PersonalPreferenceDtos.PersonalPreferenceResponse> get(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId) {
        return ApiResponse.success(service.get(tenantId, userId));
    }

    @PatchMapping
    public ApiResponse<PersonalPreferenceDtos.PersonalPreferenceResponse> patch(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PersonalPreferenceDtos.PatchPersonalPreferenceRequest request) {
        return ApiResponse.success(service.patch(tenantId, userId, correlationId, request));
    }

    @PostMapping("/reset")
    public ApiResponse<PersonalPreferenceDtos.PersonalPreferenceResponse> reset(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PersonalPreferenceDtos.VersionRequest request) {
        return ApiResponse.success(service.reset(tenantId, userId, correlationId, request.version()));
    }
}
