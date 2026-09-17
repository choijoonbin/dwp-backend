package com.dwp.services.platform.personalsettings;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/personal-settings")
public class PersonalSettingsOwnerController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final PersonalSettingsOwnerService service;

    public PersonalSettingsOwnerController(PersonalSettingsOwnerService service) {
        this.service = service;
    }

    @GetMapping("/workspace")
    public ApiResponse<PersonalSettingsDtos.Workspace> workspace(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId) {
        return ApiResponse.success(service.workspace(tenantId, userId));
    }

    @PutMapping("/favorites/{settingKey}")
    public ApiResponse<PersonalSettingsDtos.Favorite> updateFavorite(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable String settingKey,
            @Valid @RequestBody PersonalSettingsDtos.UpdateFavoriteRequest request) {
        return ApiResponse.success(service.updateFavorite(
                tenantId, userId, settingKey, correlationId, request));
    }

    @PostMapping("/activity/view")
    public ApiResponse<PersonalSettingsDtos.Activity> recordView(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @Valid @RequestBody PersonalSettingsDtos.RecordViewRequest request) {
        return ApiResponse.success(service.recordView(tenantId, userId, request.settingKey()));
    }

    @GetMapping("/privacy/consents")
    public ApiResponse<PersonalSettingsDtos.ConsentLedger> consentLedger(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId) {
        return ApiResponse.success(service.consentLedger(tenantId, userId));
    }

    @PutMapping("/privacy/consents/product-analytics")
    public ApiResponse<PersonalSettingsDtos.Consent> updateProductAnalyticsConsent(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PersonalSettingsDtos.UpdateConsentRequest request) {
        return ApiResponse.success(service.updateProductAnalyticsConsent(
                tenantId, userId, correlationId, request));
    }

    @GetMapping("/privacy/requests")
    public ApiResponse<List<PersonalSettingsDtos.PrivacyRequest>> privacyRequests(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId) {
        return ApiResponse.success(service.privacyRequests(tenantId, userId));
    }

    @PostMapping("/privacy/requests")
    public ApiResponse<PersonalSettingsDtos.PrivacyRequest> createPrivacyRequest(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PersonalSettingsDtos.CreatePrivacyRequest request) {
        return ApiResponse.success(service.createPrivacyRequest(
                tenantId, userId, correlationId, request));
    }

    @PatchMapping("/privacy/requests/{requestId}/cancel")
    public ApiResponse<PersonalSettingsDtos.PrivacyRequest> cancelPrivacyRequest(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody PersonalSettingsDtos.VersionRequest request) {
        return ApiResponse.success(service.cancelPrivacyRequest(
                tenantId, userId, requestId, correlationId, request.version()));
    }
}
