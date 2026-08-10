package com.dwp.services.platform.navigation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class NavigationDtos {

    private NavigationDtos() {
    }

    public record Label(
            @NotBlank @Size(max = 35) String locale,
            @NotBlank @Size(max = 160) String label,
            @Size(max = 500) String description) {
    }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,119}") String navigationKey,
            @NotBlank @Pattern(regexp = "GROUP|APP") String itemType,
            Long parentNavigationItemId,
            @Size(max = 100) String registryEntryKey,
            @Size(max = 500) String route,
            @Size(max = 80) String iconKey,
            @Size(max = 255) String requiredResourceKey,
            @Pattern(regexp = "[A-Z][A-Z0-9_.-]{0,49}") String requiredPermissionCode,
            @Min(0) int sortOrder,
            @NotEmpty @Size(max = 20) List<@Valid Label> labels) {
    }

    public record UpdateRequest(
            Long parentNavigationItemId,
            @Size(max = 100) String registryEntryKey,
            @Size(max = 500) String route,
            @Size(max = 80) String iconKey,
            @Size(max = 255) String requiredResourceKey,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_.-]{0,49}") String requiredPermissionCode,
            @Min(0) int sortOrder,
            @NotEmpty @Size(max = 20) List<@Valid Label> labels,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record ReorderItem(
            @NotNull Long navigationItemId,
            Long parentNavigationItemId,
            @Min(0) int sortOrder,
            @NotNull @Min(0) Long version) {
    }

    public record ReorderRequest(@NotEmpty @Size(max = 500) List<@Valid ReorderItem> items) {
    }

    public record AdminNode(
            Long navigationItemId,
            String navigationKey,
            String itemType,
            Long parentNavigationItemId,
            String registryEntryKey,
            String route,
            String iconKey,
            String requiredResourceKey,
            String requiredPermissionCode,
            int sortOrder,
            String lifecycleState,
            long version,
            List<Label> labels,
            List<AdminNode> children) {
    }

    public record RuntimeNode(
            String navigationKey,
            String itemType,
            String label,
            String description,
            String registryEntryKey,
            String route,
            String iconKey,
            String requiredResourceKey,
            String requiredPermissionCode,
            List<RuntimeNode> children) {
    }
}
