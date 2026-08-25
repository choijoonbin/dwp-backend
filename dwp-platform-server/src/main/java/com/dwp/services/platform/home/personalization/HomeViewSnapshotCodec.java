package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HomeViewSnapshotCodec {
    public static final int SNAPSHOT_VERSION = 1;
    private static final int MAX_LEGACY_LAYOUT_BYTES = 96 * 1024;
    // 96 KiB layout + up to 30 bounded 4 KiB widget configs + two overlays.
    public static final int MAX_SNAPSHOT_BYTES = 384 * 1024;

    private final ObjectMapper objectMapper;
    private final HomeWidgetConfigurationPolicy widgetConfigurationPolicy;

    public HomeViewSnapshotCodec(
            ObjectMapper objectMapper,
            HomeWidgetConfigurationPolicy widgetConfigurationPolicy) {
        this.objectMapper = objectMapper;
        this.widgetConfigurationPolicy = widgetConfigurationPolicy;
    }

    public HomeViewDtos.HomeViewSnapshot capture(
            HomeView view,
            List<HomeWidgetConfiguration> configurations,
            List<HomeDeviceLayout> deviceLayouts,
            HomePreferenceDtos.HomeLayoutPayload layout) {
        Map<String, HomeViewDtos.WidgetConfigurationPayload> configurationSnapshot =
                new LinkedHashMap<>();
        configurations.forEach(value -> configurationSnapshot.put(
                value.getWidgetKey(),
                widgetConfigurationPolicy.decode(value.getConfigurationPayload())));
        Map<String, HomeViewDtos.DeviceLayoutOverlay> deviceSnapshot = new LinkedHashMap<>();
        deviceLayouts.forEach(value -> deviceSnapshot.put(
                value.getDeviceClass(), overlay(value.getOverlayPayload())));
        return new HomeViewDtos.HomeViewSnapshot(
                SNAPSHOT_VERSION,
                false,
                new HomeViewDtos.HomeViewSnapshotView(
                        view.getName(), view.isCustomized(), view.getSchemaVersion(), layout),
                Map.copyOf(configurationSnapshot),
                Map.copyOf(deviceSnapshot));
    }

    public DecodedSnapshot decode(JsonNode stored, Integer legacySchemaVersion) {
        try {
            if (stored == null) {
                throw new BaseException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "The stored home view revision has no snapshot.");
            }
            int storedBytes = objectMapper.writeValueAsBytes(stored).length;
            if (storedBytes > MAX_SNAPSHOT_BYTES
                    || (!stored.has("snapshotVersion")
                    && storedBytes > MAX_LEGACY_LAYOUT_BYTES)) {
                throw new BaseException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "The stored home view revision exceeds the size limit.");
            }
            if (stored != null && stored.isObject() && stored.has("snapshotVersion")) {
                HomeViewDtos.HomeViewSnapshot snapshot = objectMapper.treeToValue(
                        stored, HomeViewDtos.HomeViewSnapshot.class);
                validate(snapshot);
                return new DecodedSnapshot(snapshot, false);
            }
            HomePreferenceDtos.HomeLayoutPayload layout = objectMapper.treeToValue(
                    stored, HomePreferenceDtos.HomeLayoutPayload.class);
            if (layout == null) {
                throw new BaseException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        "The legacy home view revision has no layout.");
            }
            return new DecodedSnapshot(
                    new HomeViewDtos.HomeViewSnapshot(
                            0, true,
                            new HomeViewDtos.HomeViewSnapshotView(
                                    null, null, legacySchemaVersion, layout),
                            Map.of(), Map.of()),
                    true);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored home view revision is invalid.",
                    exception);
        }
    }

    public JsonNode serialize(HomeViewDtos.HomeViewSnapshot snapshot) {
        JsonNode value = objectMapper.valueToTree(snapshot);
        try {
            if (objectMapper.writeValueAsBytes(value).length > MAX_SNAPSHOT_BYTES) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The complete home view snapshot exceeds the size limit.");
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The home view snapshot could not be serialized.",
                    exception);
        }
    }

    private HomeViewDtos.DeviceLayoutOverlay overlay(JsonNode value) {
        try {
            return objectMapper.treeToValue(value, HomeViewDtos.DeviceLayoutOverlay.class);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "A stored device layout is invalid.",
                    exception);
        }
    }

    private void validate(HomeViewDtos.HomeViewSnapshot snapshot) {
        if (snapshot == null
                || snapshot.snapshotVersion() == null
                || snapshot.snapshotVersion() != SNAPSHOT_VERSION
                || snapshot.legacyLayoutOnly()
                || snapshot.view() == null
                || snapshot.view().schemaVersion() == null
                || snapshot.view().layout() == null
                || snapshot.widgetConfigurations() == null
                || snapshot.widgetConfigurations().size() > 30
                || snapshot.deviceLayouts() == null
                || snapshot.deviceLayouts().size() > 2) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored home view snapshot envelope is invalid.");
        }
    }

    public record DecodedSnapshot(
            HomeViewDtos.HomeViewSnapshot snapshot,
            boolean legacyLayoutOnly) {
    }
}
