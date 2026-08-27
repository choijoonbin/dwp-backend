package com.dwp.services.platform.savedview;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workspace/saved-views")
public class SavedViewController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String ROLES = "X-DWP-Roles";
    private static final String GROUP_REFS = "X-DWP-Group-Refs";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String CORRELATION = "X-Correlation-ID";

    private final SavedViewService service;

    public SavedViewController(SavedViewService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<SavedViewDtos.SavedView>> list(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = GROUP_REFS, required = false) String groupRefs,
            @RequestParam String surfaceKey) {
        return ApiResponse.success(service.list(
                tenantId, actorId, permissions, roles, groupRefs, surfaceKey));
    }

    @PostMapping
    public ApiResponse<SavedViewDtos.SavedView> create(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = GROUP_REFS, required = false) String groupRefs,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @RequestParam String surfaceKey,
            @Valid @RequestBody SavedViewDtos.CreateRequest request) {
        return ApiResponse.success(service.create(
                tenantId, actorId, permissions, roles, groupRefs,
                correlationId, surfaceKey, request));
    }

    @PutMapping("/{savedViewId}")
    public ApiResponse<SavedViewDtos.SavedView> update(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = GROUP_REFS, required = false) String groupRefs,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID savedViewId,
            @Valid @RequestBody SavedViewDtos.UpdateRequest request) {
        return ApiResponse.success(service.update(
                tenantId, actorId, permissions, roles, groupRefs,
                correlationId, savedViewId, request));
    }

    @DeleteMapping("/{savedViewId}")
    public ApiResponse<Void> delete(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = GROUP_REFS, required = false) String groupRefs,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID savedViewId) {
        service.delete(tenantId, actorId, permissions, roles,
                groupRefs, correlationId, savedViewId);
        return ApiResponse.success(null);
    }

    @PutMapping("/{savedViewId}/preference")
    public ApiResponse<SavedViewDtos.SavedView> preference(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = ROLES, required = false) String roles,
            @RequestHeader(value = GROUP_REFS, required = false) String groupRefs,
            @PathVariable UUID savedViewId,
            @Valid @RequestBody SavedViewDtos.PreferenceRequest request) {
        return ApiResponse.success(service.preference(
                tenantId, actorId, permissions, roles, groupRefs, savedViewId, request));
    }

    @PostMapping("/{savedViewId}/use")
    public ApiResponse<Void> markUsed(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = GROUP_REFS, required = false) String groupRefs,
            @PathVariable UUID savedViewId) {
        service.markUsed(tenantId, actorId, permissions, groupRefs, savedViewId);
        return ApiResponse.success(null);
    }
}
