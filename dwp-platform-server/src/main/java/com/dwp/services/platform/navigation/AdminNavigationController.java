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

import java.util.List;

@RestController
@RequestMapping("/v1/admin/navigation")
public class AdminNavigationController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final NavigationService service;

    public AdminNavigationController(NavigationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<NavigationDtos.AdminNode>> list(
            @RequestHeader(TENANT_HEADER) Long tenantId) {
        return ApiResponse.success(service.adminTree(tenantId));
    }

    @PostMapping
    public ApiResponse<NavigationDtos.AdminNode> create(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody NavigationDtos.CreateRequest request) {
        return ApiResponse.success(service.create(tenantId, actorId, correlationId, request));
    }

    @PutMapping("/{itemId}")
    public ApiResponse<NavigationDtos.AdminNode> update(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long itemId,
            @Valid @RequestBody NavigationDtos.UpdateRequest request) {
        return ApiResponse.success(service.update(
                tenantId, actorId, correlationId, itemId, request));
    }

    @PostMapping("/{itemId}/activate")
    public ApiResponse<NavigationDtos.AdminNode> activate(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long itemId,
            @Valid @RequestBody NavigationDtos.VersionRequest request) {
        return ApiResponse.success(service.lifecycle(
                tenantId, actorId, correlationId, itemId, "ACTIVE", request.version()));
    }

    @PostMapping("/{itemId}/retire")
    public ApiResponse<NavigationDtos.AdminNode> retire(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long itemId,
            @Valid @RequestBody NavigationDtos.VersionRequest request) {
        return ApiResponse.success(service.lifecycle(
                tenantId, actorId, correlationId, itemId, "RETIRED", request.version()));
    }

    @PutMapping("/order")
    public ApiResponse<List<NavigationDtos.AdminNode>> reorder(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(USER_HEADER) Long actorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody NavigationDtos.ReorderRequest request) {
        return ApiResponse.success(service.reorder(tenantId, actorId, correlationId, request));
    }
}
