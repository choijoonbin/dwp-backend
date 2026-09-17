package com.dwp.services.platform.home.preference;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.dwp.services.platform.home.personalization.StrictLongDeserializer;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;

public final class HomePreferenceDtos {

    public static final int SCHEMA_VERSION = 5;

    private HomePreferenceDtos() {
    }

    public record WidgetPreference(
            @NotBlank
            @Pattern(regexp = "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
            @Size(max = 160)
            String widgetKey,
            @NotNull Boolean visible,
            @Pattern(regexp = "fifth|quarter|compact|medium|large|full")
            String size,
            @Pattern(regexp = "short|standard|tall|expanded")
            String height) {
    }

    public record AppFolderV1(
            @NotBlank @Pattern(regexp = "folder-[A-Za-z0-9_-]{1,93}") String id,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 40) String groupId,
            @NotNull @Size(min = 2, max = 50) List<@NotBlank @Size(max = 100) String> appIds) {
    }

    public record AppLayoutPayloadV1(
            @NotNull @Min(1) Integer version,
            @NotNull @Size(max = 12) Map<String, List<String>> groups,
            @NotNull @Size(max = 50) Map<String, @Valid AppFolderV1> folders,
            @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> hiddenAppIds) {
    }

    @JsonDeserialize(using = HomeLayoutPayloadDeserializer.class)
    public record HomeLayoutPayload(
            @Valid AppLayoutPayloadV1 appLayout,
            @Pattern(regexp = "balanced|expressive|focused")
            String presentation,
            @NotNull @Size(min = 1, max = 30) List<@Valid WidgetPreference> widgets) {
    }

    public record UpdateHomePreferenceRequest(
            @NotNull @Valid HomeLayoutPayload layout,
            @Pattern(regexp = "CLASSIC|FLOW_V1|MZ_V1") String currentMode,
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class) Long version) {

        public UpdateHomePreferenceRequest(HomeLayoutPayload layout, Long version) {
            this(layout, null, version);
        }
    }

    /**
     * Changes only the member's active Home mode. Mode selection is independent from permission
     * to customize the selected mode's layout.
     */
    public record UpdateHomeCurrentModeRequest(
            @NotNull @Pattern(regexp = "CLASSIC|FLOW_V1|MZ_V1") String currentMode,
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class) Long version) {
    }

    public record VersionRequest(
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class) Long version) {
    }

    @Schema(requiredProperties = {
            "schemaVersion", "surfaceKey", "customized", "integrityStatus",
            "layout", "allowedModes", "enabledModes", "disabledModeReasons",
            "defaultMode", "currentMode", "version", "warnings"
    })
    public record HomePreferenceResponse(
            Integer schemaVersion,
            String surfaceKey,
            boolean customized,
            HomePreferenceIntegrityStatus integrityStatus,
            HomeLayoutPayload layout,
            List<String> allowedModes,
            List<String> enabledModes,
            Map<String, String> disabledModeReasons,
            String defaultMode,
            String currentMode,
            Long version,
            OffsetDateTime updatedAt,
            List<String> warnings) {
    }

    public enum HomePreferenceIntegrityStatus {
        VALID,
        RECONCILED,
        RECOVERED
    }
}
