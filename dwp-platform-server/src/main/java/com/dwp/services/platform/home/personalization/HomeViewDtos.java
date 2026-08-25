package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HomeViewDtos {
    private HomeViewDtos() {
    }

    public record CreateHomeViewRequest(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,79}") String viewKey,
            @NotBlank @Size(max = 80) String name,
            boolean makeDefault,
            @NotNull @Valid HomePreferenceDtos.HomeLayoutPayload layout) {
    }

    public record UpdateHomeViewRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Valid HomePreferenceDtos.HomeLayoutPayload layout,
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class) Long version) {
    }

    public record VersionRequest(
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class) Long version) {
    }

    public record UpdateWidgetConfigurationRequest(
            @NotNull @Valid WidgetConfigurationPayload configuration,
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class)
            Long viewVersion) {
    }

    @Schema(requiredProperties = {"sourceKey", "fieldKeys", "filterPreset"})
    public record WidgetConfigurationPayload(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String sourceKey,
            @NotNull @Size(min = 1, max = 8)
            List<@NotNull @Pattern(regexp = "[A-Za-z][A-Za-z0-9]{0,39}") String> fieldKeys,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String filterPreset,
            @Min(1) @Max(20) @JsonDeserialize(using = StrictIntegerDeserializer.class)
            Integer itemLimit) {
    }

    public record DeviceLayoutOverlay(
            @NotNull @Size(max = 30) List<@NotNull @Pattern(regexp = "[a-z][a-z0-9-]{0,39}") String> widgetOrder,
            @NotNull @Size(max = 30) Map<
                    @NotNull @Pattern(regexp = "[a-z][a-z0-9-]{0,39}") String,
                    @NotNull @Pattern(regexp = "fifth|quarter|compact|medium|large|full") String> widgetSizes,
            @NotBlank @Pattern(regexp = "comfortable|compact")
            @Schema(allowableValues = {"comfortable", "compact"}) String density) {
    }

    public record UpdateDeviceLayoutRequest(
            @NotNull @Valid DeviceLayoutOverlay overlay,
            @NotNull @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class)
            Long viewVersion,
            @Min(0) @JsonDeserialize(using = StrictLongDeserializer.class) Long version) {
    }

    @Schema(requiredProperties = {
            "viewId", "viewKey", "surfaceKey", "name", "isDefault",
            "customized", "schemaVersion", "layout", "version", "widgetConfigurations"
    })
    public record HomeViewResponse(
            UUID viewId,
            String viewKey,
            String surfaceKey,
            String name,
            boolean isDefault,
            boolean customized,
            Integer schemaVersion,
            HomePreferenceDtos.HomeLayoutPayload layout,
            Long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Map<String, WidgetConfigurationPayload> widgetConfigurations) {
    }

    @Schema(requiredProperties = {
            "deviceLayoutId", "viewId", "deviceClass", "overlay", "version", "viewVersion"
    })
    public record DeviceLayoutResponse(
            UUID deviceLayoutId,
            UUID viewId,
            @Schema(allowableValues = {"DESKTOP", "MOBILE"})
            String deviceClass,
            DeviceLayoutOverlay overlay,
            Long version,
            Long viewVersion,
            OffsetDateTime updatedAt) {
    }

    @Schema(requiredProperties = {
            "revisionId", "viewId", "revisionNumber", "schemaVersion",
            "source", "snapshot", "createdAt"
    })
    public record HomeViewRevisionResponse(
            UUID revisionId,
            UUID viewId,
            Long revisionNumber,
            Integer schemaVersion,
            @Schema(allowableValues = {"USER", "TEMPLATE", "AI", "RESTORE", "UNDO"})
            String source,
            String changeSummary,
            HomeViewSnapshot snapshot,
            OffsetDateTime createdAt,
            Long createdBy) {
    }

    @Schema(requiredProperties = {
            "snapshotVersion", "legacyLayoutOnly", "view",
            "widgetConfigurations", "deviceLayouts"
    })
    public record HomeViewSnapshot(
            Integer snapshotVersion,
            boolean legacyLayoutOnly,
            HomeViewSnapshotView view,
            Map<String, WidgetConfigurationPayload> widgetConfigurations,
            Map<String, DeviceLayoutOverlay> deviceLayouts) {
    }

    @Schema(requiredProperties = {"schemaVersion", "layout"})
    public record HomeViewSnapshotView(
            String name,
            Boolean customized,
            Integer schemaVersion,
            HomePreferenceDtos.HomeLayoutPayload layout) {
    }

    public record DeleteHomeViewResponse(UUID deletedViewId, UUID activeViewId) {
    }
}
