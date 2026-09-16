package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only effective-view resolver used by Home Runtime independently of editor activation. */
@Service
public class EffectiveHomeViewQuery {

    private final HomeViewRepository views;
    private final HomeDeviceLayoutRepository deviceLayouts;
    private final HomeWidgetConfigurationRepository widgetConfigurations;
    private final HomePreferenceService preferences;
    private final ObjectMapper objectMapper;

    public EffectiveHomeViewQuery(
            HomeViewRepository views,
            HomeDeviceLayoutRepository deviceLayouts,
            HomeWidgetConfigurationRepository widgetConfigurations,
            HomePreferenceService preferences,
            ObjectMapper objectMapper) {
        this.views = views;
        this.deviceLayouts = deviceLayouts;
        this.widgetConfigurations = widgetConfigurations;
        this.preferences = preferences;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public EffectiveView resolve(long tenantId, long userId, String mode, String deviceClass) {
        String canonicalMode = HomeModeKeys.canonical(mode);
        String canonicalDevice = HomeDeviceClasses.canonical(deviceClass);
        List<HomeView> candidates = views
                .findByTenantIdAndUserIdAndSurfaceKeyAndModeKeyOrderByUpdatedAtDesc(
                        tenantId, userId, HomePreferenceService.WORKSPACE_HOME, canonicalMode);
        HomeView selected = candidates.stream().filter(HomeView::isDefaultView)
                .findFirst().orElseGet(() -> candidates.stream().findFirst().orElse(null));
        if (selected == null) {
            return new EffectiveView(
                    null,
                    0L,
                    canonicalMode,
                    canonicalDevice,
                    "GOVERNED_DEFAULT",
                    preferences.defaultLayoutForSurface(HomePreferenceService.WORKSPACE_HOME),
                    null,
                    Map.of());
        }
        try {
            HomePreferenceDtos.HomeLayoutPayload stored = objectMapper.treeToValue(
                    selected.getLayoutPayload(), HomePreferenceDtos.HomeLayoutPayload.class);
            HomePreferenceDtos.HomeLayoutPayload layout = preferences.reconcileStoredForSurface(
                    HomePreferenceService.WORKSPACE_HOME, stored);
            HomeViewDtos.DeviceLayoutOverlay overlay = deviceLayouts
                    .findByViewIdAndTenantIdAndUserIdAndDeviceClass(
                            selected.getViewId(), tenantId, userId, canonicalDevice)
                    .map(value -> tree(value.getOverlayPayload(), HomeViewDtos.DeviceLayoutOverlay.class))
                    .orElse(null);
            Map<String, HomeViewDtos.WidgetConfigurationPayload> configurations =
                    new LinkedHashMap<>();
            widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                            selected.getViewId(), tenantId, userId)
                    .forEach(value -> configurations.put(
                            value.getWidgetKey(),
                            tree(value.getConfigurationPayload(),
                                    HomeViewDtos.WidgetConfigurationPayload.class)));
            return new EffectiveView(
                    selected.getViewId(),
                    selected.getVersion() == null ? 0L : selected.getVersion(),
                    canonicalMode,
                    canonicalDevice,
                    "HOME_VIEW",
                    layout,
                    overlay,
                    Map.copyOf(configurations));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("The effective Home view cannot be decoded.", exception);
        }
    }

    private <T> T tree(com.fasterxml.jackson.databind.JsonNode value, Class<T> type) {
        try {
            return objectMapper.treeToValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored Home view child state is invalid.", exception);
        }
    }

    public record EffectiveView(
            UUID viewId,
            long revision,
            String mode,
            String deviceClass,
            String source,
            HomePreferenceDtos.HomeLayoutPayload layout,
            HomeViewDtos.DeviceLayoutOverlay deviceOverlay,
            Map<String, HomeViewDtos.WidgetConfigurationPayload> widgetConfigurations) {
    }
}
