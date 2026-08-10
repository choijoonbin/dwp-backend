package com.dwp.services.platform.home.preference;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public final class HomePreferenceDtos {

    public static final int SCHEMA_VERSION = 1;

    private HomePreferenceDtos() {
    }

    public record WidgetPreference(
            @NotBlank
            @Pattern(regexp = "[a-z][a-z0-9-]{0,39}")
            String widgetKey,
            @NotNull Boolean visible) {
    }

    public record HomeLayoutPayload(
            JsonNode appLayout,
            @NotNull @Size(min = 5, max = 5) List<@Valid WidgetPreference> widgets) {
    }

    public record UpdateHomePreferenceRequest(
            @NotNull @Valid HomeLayoutPayload layout,
            @NotNull @Min(0) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }

    public record HomePreferenceResponse(
            Integer schemaVersion,
            boolean customized,
            HomeLayoutPayload layout,
            Long version,
            LocalDateTime updatedAt) {
    }
}
