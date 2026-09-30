package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.scim.ScimConnectorDtos;
import com.dwp.services.auth.scim.ScimCredentialService;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantPermissionAuthorization;
import com.dwp.services.auth.security.TenantContextResolver;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
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
    private final TenantPermissionAuthorization authorization;

    public ScimConnectorAdminController(
            ScimCredentialService service,
            TenantPermissionAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping
    public ApiResponse<List<ScimConnectorDtos.ConnectorSummary>> list(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "VIEW");
        return ApiResponse.success(service.list(context.tenantId()));
    }

    @GetMapping("/events")
    public ApiResponse<ScimConnectorDtos.ProvisioningEventPage> events(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) UUID connectorId,
            @RequestParam(defaultValue = "100") int limit) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "VIEW");
        return ApiResponse.success(service.events(context.tenantId(), connectorId, limit));
    }

    @PostMapping
    public ApiResponse<ScimConnectorDtos.CredentialIssued> create(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            HttpServletResponse response,
            @Valid @RequestBody ScimConnectorDtos.CreateRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        preventCredentialCaching(response);
        return ApiResponse.success(service.create(
                context.tenantId(), context.actorId(), correlationId, request));
    }

    @PostMapping("/{connectorId}/rotate-secret")
    public ApiResponse<ScimConnectorDtos.CredentialIssued> rotate(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID connectorId,
            HttpServletResponse response,
            @Valid @RequestBody ScimConnectorDtos.RotateRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        preventCredentialCaching(response);
        return ApiResponse.success(service.rotate(
                context.tenantId(), context.actorId(), correlationId, connectorId, request));
    }

    @PatchMapping("/{connectorId}/lifecycle")
    public ApiResponse<ScimConnectorDtos.ConnectorSummary> lifecycle(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID connectorId,
            @Valid @RequestBody ScimConnectorDtos.LifecycleRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        return ApiResponse.success(service.lifecycle(
                context.tenantId(), context.actorId(), correlationId, connectorId, request.state()));
    }

    private AuthorizedTenant authorize(
            Authentication authentication,
            String tenantHeader,
            String permissionCode) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        authorization.require(
                tenantId,
                actorId,
                TenantPermissionAuthorization.IDENTITY_PROVISIONING,
                permissionCode);
        return new AuthorizedTenant(tenantId, actorId);
    }

    private void preventCredentialCaching(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }

    private record AuthorizedTenant(Long tenantId, Long actorId) {
    }
}
