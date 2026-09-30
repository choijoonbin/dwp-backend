package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.DirectoryAdminDtos;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantPermissionAuthorization;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.DirectoryAdminService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/admin/directory")
public class DirectoryAdminController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final DirectoryAdminService directoryAdminService;
    private final TenantPermissionAuthorization authorization;

    public DirectoryAdminController(
            DirectoryAdminService directoryAdminService,
            TenantPermissionAuthorization authorization) {
        this.directoryAdminService = directoryAdminService;
        this.authorization = authorization;
    }

    @GetMapping("/users")
    public ApiResponse<DirectoryAdminDtos.PageResult<DirectoryAdminDtos.DirectoryMemberSummary>>
            users(
                    Authentication authentication,
                    @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
                    @RequestParam(required = false) String query,
                    @RequestParam(defaultValue = "ACTIVE") String status,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "100") int size) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "VIEW");
        return ApiResponse.success(
                directoryAdminService.listUsers(context.tenantId(), query, status, page, size));
    }

    @GetMapping("/organizations")
    public ApiResponse<DirectoryAdminDtos.PageResult<DirectoryAdminDtos.OrganizationUnitSummary>>
            organizations(
                    Authentication authentication,
                    @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
                    @RequestParam(required = false) String query,
                    @RequestParam(defaultValue = "ALL") String status,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "50") int size) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "VIEW");
        return ApiResponse.success(directoryAdminService.listOrganizations(
                context.tenantId(), query, status, page, size));
    }

    @GetMapping("/organizations/{orgUnitId}")
    public ApiResponse<DirectoryAdminDtos.OrganizationUnitDetail> organization(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @PathVariable Long orgUnitId) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "VIEW");
        return ApiResponse.success(directoryAdminService.getOrganization(
                context.tenantId(), orgUnitId));
    }

    @PostMapping("/organizations")
    public ApiResponse<DirectoryAdminDtos.OrganizationUnitSummary> createOrganization(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody DirectoryAdminDtos.CreateOrganizationUnitRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        return ApiResponse.success(directoryAdminService.createOrganization(
                context.tenantId(), context.actorId(), correlationId, request));
    }

    @PatchMapping("/organizations/{orgUnitId}")
    public ApiResponse<DirectoryAdminDtos.OrganizationUnitSummary> updateOrganization(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long orgUnitId,
            @Valid @RequestBody DirectoryAdminDtos.UpdateOrganizationUnitRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        return ApiResponse.success(directoryAdminService.updateOrganization(
                context.tenantId(), context.actorId(), correlationId, orgUnitId, request));
    }

    @PostMapping("/organizations/{orgUnitId}/activate")
    public ApiResponse<DirectoryAdminDtos.OrganizationUnitSummary> activateOrganization(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long orgUnitId,
            @Valid @RequestBody DirectoryAdminDtos.LifecycleRequest request) {
        return changeOrganizationStatus(
                authentication, tenantHeader, correlationId, orgUnitId, "ACTIVE", request);
    }

    @PostMapping("/organizations/{orgUnitId}/deactivate")
    public ApiResponse<DirectoryAdminDtos.OrganizationUnitSummary> deactivateOrganization(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long orgUnitId,
            @Valid @RequestBody DirectoryAdminDtos.LifecycleRequest request) {
        return changeOrganizationStatus(
                authentication, tenantHeader, correlationId, orgUnitId, "INACTIVE", request);
    }

    @PutMapping("/organizations/{orgUnitId}/members")
    public ApiResponse<DirectoryAdminDtos.OrganizationUnitDetail> replaceOrganizationMembers(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long orgUnitId,
            @Valid @RequestBody DirectoryAdminDtos.ReplaceMembersRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        return ApiResponse.success(directoryAdminService.replaceOrganizationMembers(
                context.tenantId(), context.actorId(), correlationId, orgUnitId, request));
    }

    @GetMapping("/groups")
    public ApiResponse<DirectoryAdminDtos.PageResult<DirectoryAdminDtos.DirectoryGroupSummary>>
            groups(
                    Authentication authentication,
                    @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
                    @RequestParam(required = false) String query,
                    @RequestParam(defaultValue = "ALL") String status,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "50") int size) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "VIEW");
        return ApiResponse.success(
                directoryAdminService.listGroups(context.tenantId(), query, status, page, size));
    }

    @GetMapping("/groups/{groupId}")
    public ApiResponse<DirectoryAdminDtos.DirectoryGroupDetail> group(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @PathVariable Long groupId) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "VIEW");
        return ApiResponse.success(directoryAdminService.getGroup(context.tenantId(), groupId));
    }

    @PostMapping("/groups")
    public ApiResponse<DirectoryAdminDtos.DirectoryGroupSummary> createGroup(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody DirectoryAdminDtos.CreateDirectoryGroupRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        return ApiResponse.success(
                directoryAdminService.createGroup(
                        context.tenantId(), context.actorId(), correlationId, request));
    }

    @PatchMapping("/groups/{groupId}")
    public ApiResponse<DirectoryAdminDtos.DirectoryGroupSummary> updateGroup(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long groupId,
            @Valid @RequestBody DirectoryAdminDtos.UpdateDirectoryGroupRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        return ApiResponse.success(directoryAdminService.updateGroup(
                context.tenantId(), context.actorId(), correlationId, groupId, request));
    }

    @PostMapping("/groups/{groupId}/activate")
    public ApiResponse<DirectoryAdminDtos.DirectoryGroupSummary> activateGroup(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long groupId,
            @Valid @RequestBody DirectoryAdminDtos.LifecycleRequest request) {
        return changeGroupStatus(
                authentication, tenantHeader, correlationId, groupId, "ACTIVE", request);
    }

    @PostMapping("/groups/{groupId}/deactivate")
    public ApiResponse<DirectoryAdminDtos.DirectoryGroupSummary> deactivateGroup(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long groupId,
            @Valid @RequestBody DirectoryAdminDtos.LifecycleRequest request) {
        return changeGroupStatus(
                authentication, tenantHeader, correlationId, groupId, "INACTIVE", request);
    }

    @PutMapping("/groups/{groupId}/members")
    public ApiResponse<DirectoryAdminDtos.DirectoryGroupDetail> replaceGroupMembers(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long groupId,
            @Valid @RequestBody DirectoryAdminDtos.ReplaceMembersRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        return ApiResponse.success(directoryAdminService.replaceGroupMembers(
                context.tenantId(), context.actorId(), correlationId, groupId, request));
    }

    private ApiResponse<DirectoryAdminDtos.OrganizationUnitSummary> changeOrganizationStatus(
            Authentication authentication,
            String tenantHeader,
            String correlationId,
            Long orgUnitId,
            String status,
            DirectoryAdminDtos.LifecycleRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        return ApiResponse.success(directoryAdminService.changeOrganizationStatus(
                context.tenantId(), context.actorId(), correlationId, orgUnitId, status, request));
    }

    private ApiResponse<DirectoryAdminDtos.DirectoryGroupSummary> changeGroupStatus(
            Authentication authentication,
            String tenantHeader,
            String correlationId,
            Long groupId,
            String status,
            DirectoryAdminDtos.LifecycleRequest request) {
        AuthorizedTenant context = authorize(authentication, tenantHeader, "MANAGE");
        return ApiResponse.success(directoryAdminService.changeGroupStatus(
                context.tenantId(), context.actorId(), correlationId, groupId, status, request));
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
                TenantPermissionAuthorization.IDENTITY_DIRECTORY,
                permissionCode);
        return new AuthorizedTenant(tenantId, actorId);
    }

    private record AuthorizedTenant(Long tenantId, Long actorId) {
    }
}
