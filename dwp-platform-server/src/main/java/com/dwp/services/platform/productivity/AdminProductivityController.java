package com.dwp.services.platform.productivity;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
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
@RequestMapping("/v1/admin/integrations/productivity")
public class AdminProductivityController {

    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String CORRELATION = "X-Correlation-ID";

    private final ProductivityService service;
    private final ProductivityAccessGuard access;

    public AdminProductivityController(
            ProductivityService service,
            ProductivityAccessGuard access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/overview")
    public ApiResponse<ProductivityDtos.Overview> overview(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions) {
        access.view(permissions);
        return ApiResponse.success(service.overview(tenantId));
    }

    @GetMapping("/connectors")
    public ApiResponse<List<ProductivityDtos.Connector>> connectors(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions) {
        access.view(permissions);
        return ApiResponse.success(service.connectors(tenantId));
    }

    @PostMapping("/connectors")
    public ApiResponse<ProductivityDtos.Connector> create(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody ProductivityDtos.SaveConnectorRequest request) {
        access.manage(permissions);
        return ApiResponse.success(service.createConnector(
                tenantId, actorId, correlationId, request));
    }

    @PutMapping("/connectors/{connectorId}")
    public ApiResponse<ProductivityDtos.Connector> update(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID connectorId,
            @Valid @RequestBody ProductivityDtos.SaveConnectorRequest request) {
        access.manage(permissions);
        return ApiResponse.success(service.updateConnector(
                tenantId, actorId, correlationId, connectorId, request));
    }

    @PostMapping("/connectors/{connectorId}/configuration-check")
    public ApiResponse<ProductivityDtos.ConfigurationCheck> configurationCheck(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID connectorId) {
        access.manage(permissions);
        return ApiResponse.success(service.checkConfiguration(tenantId, connectorId));
    }

    @PostMapping("/connectors/{connectorId}/activate")
    public ApiResponse<ProductivityDtos.Connector> activate(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID connectorId,
            @Valid @RequestBody ProductivityDtos.LifecycleRequest request) {
        access.manage(permissions);
        return ApiResponse.success(service.activate(
                tenantId, actorId, correlationId, connectorId, request.version()));
    }

    @PostMapping("/connectors/{connectorId}/suspend")
    public ApiResponse<ProductivityDtos.Connector> suspend(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID connectorId,
            @Valid @RequestBody ProductivityDtos.LifecycleRequest request) {
        access.manage(permissions);
        return ApiResponse.success(service.suspend(
                tenantId, actorId, correlationId, connectorId, request.version()));
    }

    @GetMapping("/subjects")
    public ApiResponse<List<ProductivityDtos.Subject>> subjects(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(defaultValue = "100") int limit) {
        access.view(permissions);
        return ApiResponse.success(service.subjects(tenantId, limit));
    }

    @GetMapping("/runs")
    public ApiResponse<List<ProductivityDtos.SyncRun>> runs(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(defaultValue = "100") int limit) {
        access.view(permissions);
        return ApiResponse.success(service.runs(tenantId, limit));
    }
}
