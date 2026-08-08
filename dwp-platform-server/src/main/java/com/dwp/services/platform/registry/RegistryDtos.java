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
            Long updatedBy) {
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
            String artifactVersion) {
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

