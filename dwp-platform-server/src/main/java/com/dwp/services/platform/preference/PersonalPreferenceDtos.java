package com.dwp.services.platform.preference;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public final class PersonalPreferenceDtos {

    public static final int SCHEMA_VERSION = 2;

    private PersonalPreferenceDtos() {
    }

    public record PatchPersonalPreferenceRequest(
            @NotNull JsonNode patch,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record PersonalPreferenceResponse(
            Integer schemaVersion,
            boolean customized,
            JsonNode preferences,
            ManagedPreferencePolicy managedPolicy,
            Long version,
            LocalDateTime updatedAt) {
    }

    public record ManagedPreferencePolicy(
            String scope,
            String source,
            String owner,
            List<String> managedPaths) {
    }
}
