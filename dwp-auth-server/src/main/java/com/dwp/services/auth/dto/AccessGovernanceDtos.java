package com.dwp.services.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AccessGovernanceDtos {

    private AccessGovernanceDtos() {
    }

    public record RoleSummary(
            Long roleId,
            String code,
            String name,
            String description,
            String roleType,
            boolean privileged,
            boolean assignableToGroups,
            String status,
            long version,
            List<PermissionGrant> permissions) {
    }

    public record PermissionGrant(
            Long resourceId,
            String resourceType,
            String resourceKey,
            String resourceName,
            String permissionCode,
            String effect) {
    }

    public record ResourceSummary(
            Long resourceId,
            String type,
            String key,
            String name,
            boolean enabled) {
    }

    public record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,49}") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            boolean privileged,
            boolean assignableToGroups) {
    }

    public record UpdateRoleRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            boolean privileged,
            boolean assignableToGroups,
            @NotNull @Min(0) Long version) {
    }

    public record PermissionSelection(
            @NotNull Long resourceId,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_.-]{0,49}") String permissionCode,
            @NotBlank @Pattern(regexp = "ALLOW|DENY") String effect) {
    }

    public record ReplacePermissionsRequest(
            @NotNull @Min(0) Long version,
            @NotNull @Size(max = 500) List<@Valid PermissionSelection> permissions) {
    }

    public record CreateResourceRequest(
            @NotBlank @Pattern(regexp = "APP|NAVIGATION|API|ACTION|DATA") String type,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9:._/-]{0,254}") String key,
            @NotBlank @Size(max = 200) String name) {
    }

    public record GroupRoleAssignmentSummary(
            Long assignmentId,
            Long groupId,
            String groupName,
            Long roleId,
            String roleCode,
            String assignmentType,
            String scopeType,
            String scopeRef,
            Instant validFrom,
            Instant validTo,
            String lifecycleState,
            String justification,
            long version) {
    }

    public record CreateGroupRoleAssignmentRequest(
            @NotNull Long groupId,
            @NotNull Long roleId,
            @NotBlank @Pattern(regexp = "ACTIVE|ELIGIBLE") String assignmentType,
            @NotBlank @Pattern(regexp = "TENANT|ORG_UNIT|RESOURCE") String scopeType,
            @Size(max = 160) String scopeRef,
            Instant validFrom,
            @Future Instant validTo,
            @NotBlank @Size(max = 1000) String justification) {
    }

    public record EffectiveRole(
            Long roleId,
            String roleCode,
            String source,
            Long sourceGroupId,
            String sourceGroupName,
            String scopeType,
            String scopeRef,
            Instant validTo) {
    }

    public record EffectivePermission(
            String resourceType,
            String resourceKey,
            String permissionCode,
            String effect,
            List<String> grantedByRoles) {
    }

    public record EffectiveAccess(
            Long userId,
            String displayName,
            long accessRevision,
            List<EffectiveRole> roles,
            List<EffectivePermission> permissions) {
    }
}
