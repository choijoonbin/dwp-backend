package com.dwp.services.auth.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public final class DirectoryAdminDtos {

    private DirectoryAdminDtos() {
    }

    public record OrganizationUnitSummary(
            Long orgUnitId,
            String orgKey,
            String name,
            String description,
            Long parentOrgUnitId,
            String parentName,
            String sourceType,
            String status,
            long memberCount,
            Long revision,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }

    public record DirectoryGroupSummary(
            Long groupId,
            String groupKey,
            String displayName,
            String description,
            String sourceType,
            String status,
            long memberCount,
            Long revision,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy) {
    }

    public record DirectoryMemberSummary(
            Long userId,
            String displayName,
            String email,
            String status,
            Long primaryOrgUnitId,
            String primaryOrgName) {
    }

    public record OrganizationUnitDetail(
            OrganizationUnitSummary organization,
            List<DirectoryMemberSummary> members) {
    }

    public record DirectoryGroupDetail(
            DirectoryGroupSummary group,
            List<DirectoryMemberSummary> members) {
    }

    public record CreateOrganizationUnitRequest(
            @NotBlank
            @Size(max = 100)
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,99}")
            String orgKey,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Positive Long parentOrgUnitId) {
    }

    public record UpdateOrganizationUnitRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Positive Long parentOrgUnitId,
            @NotNull @Min(0) Long version) {
    }

    public record CreateDirectoryGroupRequest(
            @NotBlank
            @Size(max = 100)
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,99}")
            String groupKey,
            @NotBlank @Size(max = 200) String displayName,
            @Size(max = 2000) String description) {
    }

    public record UpdateDirectoryGroupRequest(
            @NotBlank @Size(max = 200) String displayName,
            @Size(max = 2000) String description,
            @NotNull @Min(0) Long version) {
    }

    public record LifecycleRequest(@NotNull @Min(0) Long version) {
    }

    public record ReplaceMembersRequest(
            @NotNull @Size(max = 500) Set<@NotNull @Positive Long> userIds,
            @NotNull @Min(0) Long version) {
    }

    public record PageResult<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
