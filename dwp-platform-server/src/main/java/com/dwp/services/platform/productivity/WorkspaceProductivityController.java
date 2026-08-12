package com.dwp.services.platform.productivity;

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

import static com.dwp.services.platform.productivity.ProductivityTypes.ResourceKind;

@RestController
@RequestMapping("/v1/workspace/productivity")
public class WorkspaceProductivityController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String CORRELATION = "X-Correlation-ID";

    private final ProductivityService service;
    private final ProductivityAccessGuard access;

    public WorkspaceProductivityController(
            ProductivityService service,
            ProductivityAccessGuard access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/connections")
    public ApiResponse<List<ProductivityDtos.Connection>> connections(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(PERMISSIONS) String permissions) {
        access.use(permissions);
        return ApiResponse.success(service.connections(tenantId, userId));
    }

    @PostMapping("/connections/{connectorId}/authorization")
    public ApiResponse<ProductivityDtos.AuthorizationStart> beginAuthorization(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID connectorId) {
        access.use(permissions);
        return ApiResponse.success(service.beginAuthorization(tenantId, userId, connectorId));
    }

    @PostMapping("/authorization/callback")
    public ApiResponse<ProductivityDtos.Connection> completeAuthorization(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ProductivityDtos.AuthorizationCallbackRequest request) {
        access.use(permissions);
        return ApiResponse.success(service.completeAuthorization(
                tenantId, userId, correlationId, request));
    }

    @PostMapping("/connections/{connectorId}/sync")
    public ApiResponse<ProductivityDtos.SyncRun> sync(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID connectorId,
            @Valid @RequestBody ProductivityDtos.SyncRequest request) {
        access.use(permissions);
        return ApiResponse.success(service.sync(
                tenantId, userId, correlationId, connectorId, request));
    }

    @GetMapping("/items")
    public ApiResponse<ProductivityDtos.ItemPage> items(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) ResourceKind resourceKind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        access.use(permissions);
        return ApiResponse.success(service.items(
                tenantId, userId, resourceKind, page, size));
    }
}
