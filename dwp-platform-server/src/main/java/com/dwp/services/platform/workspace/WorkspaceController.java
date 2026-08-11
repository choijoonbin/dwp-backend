package com.dwp.services.platform.workspace;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workspace")
public class WorkspaceController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String LOCALE = "Accept-Language";
    private static final String CORRELATION = "X-Correlation-ID";

    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @GetMapping("/work-items")
    public ApiResponse<WorkspaceDtos.WorkQueue> workItems(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = LOCALE, required = false) String locale) {
        return ApiResponse.success(service.workQueue(tenantId, actorId, permissions, locale));
    }

    @PatchMapping("/work-items/{workItemId}/status")
    public ApiResponse<WorkspaceDtos.WorkItem> updateWorkStatus(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID workItemId,
            @Valid @RequestBody WorkspaceDtos.UpdateWorkStatusRequest request) {
        return ApiResponse.success(service.updateWorkStatus(
                tenantId, actorId, permissions, locale, correlationId, workItemId, request));
    }

    @GetMapping("/activity")
    public ApiResponse<WorkspaceDtos.ActivityFeed> activity(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = LOCALE, required = false) String locale) {
        return ApiResponse.success(service.activity(tenantId, actorId, permissions, locale));
    }

    @GetMapping("/apps")
    public ApiResponse<List<WorkspaceDtos.WorkspaceApp>> apps(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = LOCALE, required = false) String locale) {
        return ApiResponse.success(service.apps(tenantId, actorId, permissions, locale));
    }

    @PatchMapping("/apps/{appId}/pin")
    public ApiResponse<WorkspaceDtos.WorkspaceApp> setPinned(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable String appId,
            @Valid @RequestBody WorkspaceDtos.PinAppRequest request) {
        return ApiResponse.success(service.setPinned(
                tenantId, actorId, permissions, locale, correlationId, appId, request));
    }

    @PostMapping("/apps/{appId}/launch")
    public ApiResponse<WorkspaceDtos.AppLaunch> launch(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable String appId) {
        return ApiResponse.success(service.launch(
                tenantId, actorId, permissions, locale, correlationId, appId));
    }
}
