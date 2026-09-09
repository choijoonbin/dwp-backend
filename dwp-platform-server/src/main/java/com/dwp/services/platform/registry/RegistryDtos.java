package com.dwp.services.platform.registry;

import com.dwp.services.platform.reference.ReferenceLifecycle;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class RegistryDtos {

    public static final int AGENT_CATALOG_SCHEMA_VERSION = 1;

    private RegistryDtos() {
    }

    public record CreateRegistryEntryRequest(
            @NotNull RegistryType registryType,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,99}") String entryKey,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 160) String ownerRef,
            @NotNull RiskTier riskTier,
            @NotBlank @Size(max = 64) String artifactVersion) {
    }

    public record CreateRegistryRevisionRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 160) String ownerRef,
            @NotNull RiskTier riskTier,
            @NotBlank @Size(max = 64) String artifactVersion) {
    }

    public record UpdateRegistryRevisionRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 160) String ownerRef,
            @NotNull RiskTier riskTier,
            @NotBlank @Size(max = 64) String artifactVersion,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record RegistryEntryResponse(
            RegistryType registryType,
            String entryKey,
            Integer revision,
            String name,
            String description,
            String ownerRef,
            RiskTier riskTier,
            String artifactVersion,
            ReferenceLifecycle lifecycleState,
            Long version,
            LocalDateTime updatedAt,
            Long updatedBy,
            AgentCatalogProfile agentCatalogProfile) {
    }

    public record RegistryEntryDetail(
            RegistryEntryResponse current,
            List<RegistryEntryResponse> history) {
    }

    public record RuntimeRegistryEntry(
            RegistryType registryType,
            String entryKey,
            Integer revision,
            String name,
            String description,
            String ownerRef,
            RiskTier riskTier,
            String artifactVersion,
            LocalDateTime updatedAt,
            AgentCatalogProfile agentCatalogProfile) {
    }

    public enum AgentCatalogCategory {
        GENERAL,
        APPROVAL
    }

    public enum AgentSourceAccessMode {
        READ_ONLY
    }

    public enum AgentSourcePermissionMatch {
        ANY_OF
    }

    public record LocalizedCatalogText(
            @NotBlank @Size(max = 1000) String ko,
            @NotBlank @Size(max = 1000) String en) {
    }

    public record AgentCatalogSource(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String sourceSystem,
            @NotNull LocalizedCatalogText displayName,
            @NotNull @Size(min = 1, max = 8) List<
                    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_.-]{0,99}:[A-Z][A-Z0-9_.-]{0,39}") String>
                    requiredPermissions,
            @NotNull AgentSourcePermissionMatch permissionMatch,
            @NotNull AgentSourceAccessMode accessMode) {
    }

    public record AgentCatalogProfile(
            @NotNull @Min(1) Integer schemaVersion,
            @NotNull AgentCatalogCategory category,
            @NotNull LocalizedCatalogText displayName,
            @NotNull LocalizedCatalogText description,
            @NotNull @Size(min = 1, max = 8) List<LocalizedCatalogText> capabilities,
            @NotNull @Size(min = 1, max = 8) List<LocalizedCatalogText> boundaries,
            @NotNull @Size(min = 1, max = 8) List<AgentCatalogSource> sources,
            @NotNull @Size(min = 1, max = 6) List<LocalizedCatalogText> starterPrompts,
            @NotNull LocalizedCatalogText safetySummary,
            boolean humanConfirmationRequired) {
    }

    public record PageResult<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    static Map<String, Object> snapshot(RegistryEntry entry) {
        return Map.of(
                "registryType", entry.getRegistryType().name(),
                "entryKey", entry.getEntryKey(),
                "revision", entry.getRevision(),
                "name", entry.getName(),
                "ownerRef", entry.getOwnerRef(),
                "riskTier", entry.getRiskTier().name(),
                "artifactVersion", entry.getArtifactVersion(),
                "lifecycleState", entry.getLifecycleState().name(),
                "version", entry.getVersion() == null ? 0L : entry.getVersion());
    }
}
