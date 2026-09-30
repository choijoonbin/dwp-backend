package com.dwp.services.auth.tenantappadoption;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
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

@RestController
@RequestMapping("/auth/admin/tenant-app-adoption")
public class TenantAppAdoptionController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final TenantAppAdoptionService service;

    public TenantAppAdoptionController(TenantAppAdoptionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<TenantAppAdoptionDtos.AdoptionProjection> projection(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.projection(
                TenantContextResolver.requireTenantId(tenantHeader, authentication), actorId));
    }

    @PostMapping("/installations")
    public ApiResponse<TenantAppAdoptionDtos.Installation> createInstallation(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantAppAdoptionDtos.CreateInstallationRequest request) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.createInstallation(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, request));
    }

    @PostMapping("/installations/{installationId}/submit")
    public ApiResponse<TenantAppAdoptionDtos.Installation> submitInstallation(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID installationId,
            @Valid @RequestBody TenantAppAdoptionDtos.VersionedCommand command) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.submitInstallation(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, installationId, command));
    }

    @PostMapping("/installations/{installationId}/decision")
    public ApiResponse<TenantAppAdoptionDtos.Installation> decideInstallation(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID installationId,
            @Valid @RequestBody TenantAppAdoptionDtos.DecisionCommand command) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.decideInstallation(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, installationId, command));
    }

    @PostMapping("/installations/{installationId}/activate")
    public ApiResponse<TenantAppAdoptionDtos.Installation> activateInstallation(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID installationId,
            @Valid @RequestBody TenantAppAdoptionDtos.ActivationCommand command) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.activateInstallation(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, installationId, command));
    }

    @GetMapping("/assignments")
    public ApiResponse<List<TenantAppAdoptionDtos.Assignment>> assignments(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) UUID installationId) {
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.assignments(
                TenantContextResolver.requireTenantId(tenantHeader, authentication),
                actorId, installationId));
    }

    @PostMapping("/assignments")
    public ApiResponse<TenantAppAdoptionDtos.Assignment> createAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantAppAdoptionDtos.CreateAssignmentRequest request) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.createAssignment(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, request));
    }

    @PostMapping("/assignments/{assignmentId}/decision")
    public ApiResponse<TenantAppAdoptionDtos.Assignment> decideAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody TenantAppAdoptionDtos.DecisionCommand command) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.decideAssignment(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, assignmentId, command));
    }

    @PostMapping("/assignments/{assignmentId}/activate")
    public ApiResponse<TenantAppAdoptionDtos.Assignment> activateAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody TenantAppAdoptionDtos.ActivationCommand command) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.activateAssignment(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, assignmentId, command));
    }

    @PostMapping("/assignments/{assignmentId}/revoke")
    public ApiResponse<TenantAppAdoptionDtos.Assignment> revokeAssignment(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody TenantAppAdoptionDtos.RevokeCommand command) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.revokeAssignment(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, assignmentId, command));
    }

}
