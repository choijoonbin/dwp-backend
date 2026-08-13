package com.dwp.services.platform.preference;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

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
            ManagedPreferenceDtos.ManagedPreferencePolicy managedPolicy,
            Long version,
            LocalDateTime updatedAt) {
    }
}
