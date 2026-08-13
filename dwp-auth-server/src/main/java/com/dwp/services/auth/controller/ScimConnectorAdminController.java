package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.scim.ScimConnectorDtos;
import com.dwp.services.auth.scim.ScimCredentialService;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/auth/admin/provisioning/scim/connectors")
public class ScimConnectorAdminController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final ScimCredentialService service;

    public ScimConnectorAdminController(ScimCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ScimConnectorDtos.ConnectorSummary>> list(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        AuthenticatedUserResolver.requireIdentityAdmin(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.list(tenantId));
    }

    @GetMapping("/events")
    public ApiResponse<List<ScimConnectorDtos.ProvisioningEvent>> events(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) UUID connectorId,
            @RequestParam(defaultValue = "100") int limit) {
        AuthenticatedUserResolver.requireIdentityAdmin(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.events(tenantId, connectorId, limit));
    }

    @PostMapping
    public ApiResponse<ScimConnectorDtos.CredentialIssued> create(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ScimConnectorDtos.CreateRequest request) {
        AuthenticatedUserResolver.requireIdentityAdmin(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.create(tenantId, actorId, correlationId, request));
    }

    @PostMapping("/{connectorId}/rotate-secret")
    public ApiResponse<ScimConnectorDtos.CredentialIssued> rotate(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID connectorId) {
        AuthenticatedUserResolver.requireIdentityAdmin(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.rotate(tenantId, actorId, correlationId, connectorId));
    }

    @PatchMapping("/{connectorId}/lifecycle")
    public ApiResponse<ScimConnectorDtos.ConnectorSummary> lifecycle(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID connectorId,
            @Valid @RequestBody ScimConnectorDtos.LifecycleRequest request) {
        AuthenticatedUserResolver.requireIdentityAdmin(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.lifecycle(
                tenantId, actorId, correlationId, connectorId, request.state()));
    }
}
