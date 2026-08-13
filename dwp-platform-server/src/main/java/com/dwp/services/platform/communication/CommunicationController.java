package com.dwp.services.platform.communication;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/communications")
public class CommunicationController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String ROLES_HEADER = "X-DWP-Roles";

    private final CommunicationService service;

    public CommunicationController(CommunicationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CommunicationDtos.FeedResponse> feed(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String rolesHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @RequestParam(defaultValue = "for-you") String scope,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "24") @Min(1) @Max(48) int size) {
        return ApiResponse.success(service.feed(
                tenantId, userId, rolesHeader, acceptLanguage, scope, query, type, size));
    }

    @GetMapping("/{communicationId}")
    public ApiResponse<CommunicationDtos.CommunicationItem> detail(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String rolesHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @PathVariable Long communicationId) {
        return ApiResponse.success(service.detail(
                tenantId, userId, rolesHeader, acceptLanguage, communicationId));
    }

    @PostMapping("/{communicationId}/events/{eventType}")
    public ApiResponse<Void> recordInteraction(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String rolesHeader,
            @PathVariable Long communicationId,
            @PathVariable String eventType) {
        service.recordInteraction(tenantId, userId, rolesHeader, communicationId, eventType);
        return ApiResponse.success(null);
    }

    @PutMapping("/{communicationId}/reader-state")
    public ApiResponse<CommunicationDtos.ReaderPreferenceResponse> updatePreference(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String rolesHeader,
            @PathVariable Long communicationId,
            @Valid @RequestBody CommunicationDtos.ReaderPreferenceRequest request) {
        return ApiResponse.success(service.updatePreference(
                tenantId, userId, rolesHeader, communicationId, request));
    }

    @PostMapping("/{communicationId}/acknowledgement")
    public ApiResponse<CommunicationDtos.ReaderPreferenceResponse> acknowledge(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String rolesHeader,
            @PathVariable Long communicationId) {
        return ApiResponse.success(service.acknowledge(
                tenantId, userId, rolesHeader, communicationId));
    }

    @PutMapping("/{communicationId}/reaction")
    public ApiResponse<CommunicationDtos.ReactionSummary> updateReaction(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String rolesHeader,
            @PathVariable Long communicationId,
            @Valid @RequestBody CommunicationDtos.ReactionRequest request) {
        return ApiResponse.success(service.updateReaction(
                tenantId, userId, rolesHeader, communicationId, request));
    }
}
