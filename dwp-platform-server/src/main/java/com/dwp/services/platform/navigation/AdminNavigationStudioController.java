package com.dwp.services.platform.navigation;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/navigation/studio")
public class AdminNavigationStudioController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final NavigationStudioService service;

    public AdminNavigationStudioController(NavigationStudioService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<NavigationStudioDtos.Workspace> workspace(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId) {
        return ApiResponse.success(service.workspace(tenantId, actorId));
    }

    @PostMapping("/drafts")
    public ApiResponse<NavigationStudioDtos.Revision> createDraft(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody NavigationStudioDtos.CreateDraftRequest request) {
        return ApiResponse.success(service.createDraft(
                tenantId, actorId, correlationId, request));
    }

    @PutMapping("/drafts/{revisionId}")
    public ApiResponse<NavigationStudioDtos.Revision> saveDraft(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody NavigationStudioDtos.SaveDraftRequest request) {
        return ApiResponse.success(service.saveDraft(
                tenantId, actorId, correlationId, revisionId, request));
    }

    @PostMapping("/drafts/{revisionId}/publish")
    public ApiResponse<NavigationStudioDtos.Revision> publish(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody NavigationStudioDtos.VersionRequest request) {
        return ApiResponse.success(service.publish(
                tenantId, actorId, correlationId, revisionId, request));
    }

    @PostMapping("/drafts/{revisionId}/cancel")
    public ApiResponse<NavigationStudioDtos.Revision> cancel(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody NavigationStudioDtos.VersionRequest request) {
        return ApiResponse.success(service.cancel(
                tenantId, actorId, correlationId, revisionId, request));
    }

    @PostMapping("/revisions/{revisionId}/restore")
    public ApiResponse<NavigationStudioDtos.Revision> restore(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID revisionId,
            @Valid @RequestBody NavigationStudioDtos.CreateDraftRequest request) {
        return ApiResponse.success(service.restore(
                tenantId, actorId, correlationId, revisionId, request));
    }
}
