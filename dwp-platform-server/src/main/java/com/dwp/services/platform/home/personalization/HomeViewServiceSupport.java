package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

abstract class HomeViewServiceSupport {
    protected static final Set<String> DEVICE_CLASSES = Set.of("DESKTOP", "MOBILE");
    private static final Set<String> DEVICE_DENSITIES = Set.of("comfortable", "compact");

    protected final HomeViewRepository views;
    protected final HomeViewRevisionRepository revisions;
    protected final HomeDeviceLayoutRepository deviceLayouts;
    protected final HomeWidgetConfigurationRepository widgetConfigurations;
    protected final HomePreferenceService preferenceService;
    protected final ObjectMapper objectMapper;
    protected final HomeViewCompatibilityBridge compatibilityBridge;
    protected final HomeViewSnapshotCodec snapshotCodec;
    protected final HomeWidgetConfigurationPolicy widgetConfigurationPolicy;
    private final HomeCanonicalJson canonicalJson;

    HomeViewServiceSupport(
            HomeViewRepository views,
            HomeViewRevisionRepository revisions,
            HomeDeviceLayoutRepository deviceLayouts,
            HomeWidgetConfigurationRepository widgetConfigurations,
            HomePreferenceService preferenceService,
            ObjectMapper objectMapper,
            HomeViewCompatibilityBridge compatibilityBridge,
            HomeViewSnapshotCodec snapshotCodec,
            HomeWidgetConfigurationPolicy widgetConfigurationPolicy,
            HomeCanonicalJson canonicalJson) {
        this.views = views;
        this.revisions = revisions;
        this.deviceLayouts = deviceLayouts;
        this.widgetConfigurations = widgetConfigurations;
        this.preferenceService = preferenceService;
        this.objectMapper = objectMapper;
        this.compatibilityBridge = compatibilityBridge;
        this.snapshotCodec = snapshotCodec;
        this.widgetConfigurationPolicy = widgetConfigurationPolicy;
        this.canonicalJson = canonicalJson;
    }

    HomeView requireOwnedForUpdate(Long tenantId, Long userId, UUID viewId) {
        return views.findOwnedForUpdate(viewId, tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    protected HomeView requireOwned(Long tenantId, Long userId, UUID viewId) {
        return views.findByViewIdAndTenantIdAndUserId(viewId, tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    HomeViewDtos.HomeViewResponse response(HomeView view) {
        Map<String, HomeViewDtos.WidgetConfigurationPayload> configurations =
                new LinkedHashMap<>();
        widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                view.getViewId(), view.getTenantId(), view.getUserId()).forEach(value ->
                configurations.put(value.getWidgetKey(),
                        widgetConfigurationPolicy.decode(value.getConfigurationPayload())));
        return new HomeViewDtos.HomeViewResponse(
                view.getViewId(), view.getViewKey(), view.getSurfaceKey(), view.getName(),
                view.isDefaultView(), view.isCustomized(), HomePreferenceDtos.SCHEMA_VERSION,
                currentLayout(view),
                version(view), offset(view.getCreatedAt()), offset(view.getUpdatedAt()),
                Map.copyOf(configurations));
    }

    HomePreferenceDtos.HomeLayoutPayload currentLayout(HomeView view) {
        try {
            HomePreferenceDtos.HomeLayoutPayload stored = layout(view.getLayoutPayload());
            HomePreferenceDtos.HomeLayoutPayload reconciled =
                    preferenceService.reconcileStoredForSurface(view.getSurfaceKey(), stored);
            return reconciled == null
                    ? stored
                    : preserveClassicCompatibilitySnapshot(stored, reconciled);
        } catch (RuntimeException exception) {
            HomePreferenceDtos.HomeLayoutPayload fallback =
                    preferenceService.defaultLayoutForSurface(view.getSurfaceKey());
            if (fallback == null) throw exception;
            return fallback;
        }
    }

    HomeViewRevision appendRevision(
            HomeView view,
            String source,
            String summary,
            UUID commandId,
            String requestFingerprint,
            Long actorId) {
        long number = revisions.findTopByViewIdOrderByRevisionNumberDesc(view.getViewId())
                .map(HomeViewRevision::getRevisionNumber).orElse(0L) + 1L;
        HomeViewDtos.HomeViewSnapshot completeSnapshot = snapshotCodec.capture(
                view,
                widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                        view.getViewId(), view.getTenantId(), view.getUserId()),
                deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                        view.getViewId(), view.getTenantId(), view.getUserId()),
                currentLayout(view));
        return saveRevision(HomeViewRevision.builder()
                .revisionId(UUID.randomUUID()).viewId(view.getViewId())
                .tenantId(view.getTenantId()).userId(view.getUserId())
                .revisionNumber(number).schemaVersion(view.getSchemaVersion())
                .snapshot(snapshotCodec.serialize(completeSnapshot)).source(source)
                .changeSummary(summary).commandId(commandId)
                .requestFingerprint(requestFingerprint).createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .createdBy(actorId).build());
    }

    protected void clearDefaults(Long tenantId, Long userId, String surfaceKey) {
        List<HomeView> existing = views
                .findByTenantIdAndUserIdAndSurfaceKeyOrderByUpdatedAtDesc(
                        tenantId, userId, surfaceKey).stream()
                .filter(HomeView::isDefaultView).toList();
        existing.forEach(value -> value.setDefaultView(false));
        if (existing.isEmpty()) return;
        try {
            views.saveAllAndFlush(existing);
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException exception) {
            conflict();
        }
    }

    protected HomeView replay(
            Long tenantId, Long userId, UUID commandId, String requestFingerprint) {
        if (commandId == null) throw invalid("Idempotency-Key is required.");
        HomeViewRevision revision = revisions
                .findByTenantIdAndUserIdAndCommandId(tenantId, userId, commandId)
                .orElse(null);
        if (revision == null) return null;
        if (requestFingerprint == null
                || !requestFingerprint.equals(revision.getRequestFingerprint())) conflict();
        return requireOwned(tenantId, userId, revision.getViewId());
    }

    protected void validateDeviceOverlay(
            HomeView view, HomeViewDtos.DeviceLayoutOverlay overlay) {
        if (overlay == null || overlay.widgetOrder() == null || overlay.widgetSizes() == null
                || overlay.widgetOrder().stream().anyMatch(java.util.Objects::isNull)
                || overlay.widgetSizes().entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || entry.getValue() == null)
                || overlay.density() == null
                || !DEVICE_DENSITIES.contains(overlay.density())) {
            throw invalid("A device overlay contains a null or missing value.");
        }
        HomePreferenceDtos.HomeLayoutPayload current = currentLayout(view);
        Set<String> widgets = current.widgets().stream()
                .map(HomePreferenceDtos.WidgetPreference::widgetKey)
                .collect(java.util.stream.Collectors.toSet());
        if (overlay.widgetOrder().size() != Set.copyOf(overlay.widgetOrder()).size()
                || !widgets.containsAll(overlay.widgetOrder())
                || !widgets.containsAll(overlay.widgetSizes().keySet())
                || overlay.widgetOrder().contains("command-rail")
                || overlay.widgetSizes().containsKey("command-rail")) {
            throw invalid("A device overlay may reference only unique registered widgets.");
        }
        if (overlay.widgetSizes().entrySet().stream().anyMatch(entry ->
                !preferenceService.isWidgetSizeAllowed(
                        view.getSurfaceKey(), entry.getKey(), entry.getValue()))) {
            throw invalid("A device overlay widget size is not allowed for this widget.");
        }
        List<String> semanticOrder = current.widgets().stream()
                .filter(widget -> Boolean.TRUE.equals(widget.visible()))
                .map(HomePreferenceDtos.WidgetPreference::widgetKey)
                .filter(widgetKey -> !"command-rail".equals(widgetKey))
                .toList();
        if (!semanticOrder.equals(overlay.widgetOrder())) {
            throw invalid("A device overlay cannot change semantic widget order.");
        }
    }

    protected HomePreferenceDtos.HomeLayoutPayload preserveClassicFixedWidget(
            HomePreferenceDtos.HomeLayoutPayload current,
            HomePreferenceDtos.HomeLayoutPayload requested,
            String widgetKey) {
        HomePreferenceDtos.WidgetPreference preserved = current.widgets().stream()
                .filter(widget -> widgetKey.equals(widget.widgetKey()))
                .findFirst().orElse(null);
        List<HomePreferenceDtos.WidgetPreference> widgets = new ArrayList<>(
                requested.widgets().stream()
                        .filter(widget -> !widgetKey.equals(widget.widgetKey()))
                        .toList());
        if (preserved != null) {
            int originalIndex = current.widgets().indexOf(preserved);
            widgets.add(Math.min(originalIndex, widgets.size()), preserved);
        }
        return new HomePreferenceDtos.HomeLayoutPayload(
                requested.appLayout(), requested.presentation(), List.copyOf(widgets));
    }

    HomePreferenceDtos.HomeLayoutPayload preserveClassicCompatibilitySnapshot(
            HomePreferenceDtos.HomeLayoutPayload current,
            HomePreferenceDtos.HomeLayoutPayload requested) {
        return preserveClassicFixedWidget(current, requested, "command-rail");
    }

    protected void requireFixedWidgetUnchanged(
            HomePreferenceDtos.HomeLayoutPayload current,
            HomePreferenceDtos.HomeLayoutPayload requested,
            String widgetKey) {
        HomePreferenceDtos.WidgetPreference currentWidget = current.widgets().stream()
                .filter(widget -> widgetKey.equals(widget.widgetKey()))
                .findFirst().orElse(null);
        HomePreferenceDtos.WidgetPreference requestedWidget = requested.widgets().stream()
                .filter(widget -> widgetKey.equals(widget.widgetKey()))
                .findFirst().orElse(null);
        if (!java.util.Objects.equals(currentWidget, requestedWidget)
                || (currentWidget != null
                && current.widgets().indexOf(currentWidget)
                != requested.widgets().indexOf(requestedWidget))) {
            throw invalid("A managed Classic compatibility zone cannot be changed.");
        }
    }

    protected void requireFixedWidgetNotOverridden(
            HomePreferenceDtos.HomeLayoutPayload baseline,
            HomePreferenceDtos.HomeLayoutPayload requested,
            String widgetKey,
            boolean omissionAllowed) {
        HomePreferenceDtos.WidgetPreference requestedWidget = requested.widgets().stream()
                .filter(widget -> widgetKey.equals(widget.widgetKey()))
                .findFirst().orElse(null);
        if (requestedWidget == null && omissionAllowed) return;
        requireFixedWidgetUnchanged(baseline, requested, widgetKey);
    }

    protected void reconcileWidgetConfigurations(
            HomeView view,
            Map<String, HomeViewDtos.WidgetConfigurationPayload> desired) {
        if (desired.size() > 30 || desired.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw invalid("The revision contains invalid widget configurations.");
        }
        desired.forEach((widgetKey, payload) -> widgetConfigurationPolicy.validate(
                currentLayout(view), widgetKey, payload));
        List<HomeWidgetConfiguration> existing = widgetConfigurations
                .findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                        view.getViewId(), view.getTenantId(), view.getUserId());
        Map<String, HomeWidgetConfiguration> byKey = new LinkedHashMap<>();
        existing.forEach(value -> byKey.put(value.getWidgetKey(), value));
        List<HomeWidgetConfiguration> removed = existing.stream()
                .filter(value -> !desired.containsKey(value.getWidgetKey())).toList();
        List<HomeWidgetConfiguration> replacements = new ArrayList<>();
        desired.forEach((widgetKey, payload) -> {
            HomeWidgetConfiguration value = byKey.getOrDefault(
                    widgetKey,
                    HomeWidgetConfiguration.builder()
                            .widgetConfigurationId(UUID.randomUUID())
                            .viewId(view.getViewId()).tenantId(view.getTenantId())
                            .userId(view.getUserId()).widgetKey(widgetKey).build());
            value.setConfigurationPayload(objectMapper.valueToTree(payload));
            replacements.add(value);
        });
        try {
            if (!removed.isEmpty()) {
                widgetConfigurations.deleteAll(removed);
                widgetConfigurations.flush();
            }
            if (!replacements.isEmpty()) widgetConfigurations.saveAllAndFlush(replacements);
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException exception) {
            conflict();
        }
    }

    protected void reconcileDeviceLayouts(
            HomeView view, Map<String, HomeViewDtos.DeviceLayoutOverlay> desired) {
        if (desired.size() > DEVICE_CLASSES.size() || desired.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null
                        || entry.getValue() == null
                        || !DEVICE_CLASSES.contains(entry.getKey()))) {
            throw invalid("The revision contains invalid device layouts.");
        }
        desired.values().forEach(overlay -> validateDeviceOverlay(view, overlay));
        List<HomeDeviceLayout> existing = deviceLayouts
                .findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                        view.getViewId(), view.getTenantId(), view.getUserId());
        Map<String, HomeDeviceLayout> byClass = new LinkedHashMap<>();
        existing.forEach(value -> byClass.put(value.getDeviceClass(), value));
        List<HomeDeviceLayout> removed = existing.stream()
                .filter(value -> !desired.containsKey(value.getDeviceClass())).toList();
        List<HomeDeviceLayout> replacements = new ArrayList<>();
        desired.forEach((deviceClass, overlay) -> {
            HomeDeviceLayout value = byClass.getOrDefault(
                    deviceClass,
                    HomeDeviceLayout.builder().deviceLayoutId(UUID.randomUUID())
                            .viewId(view.getViewId()).tenantId(view.getTenantId())
                            .userId(view.getUserId()).deviceClass(deviceClass).build());
            value.setOverlayPayload(objectMapper.valueToTree(overlay));
            replacements.add(value);
        });
        try {
            if (!removed.isEmpty()) {
                deviceLayouts.deleteAll(removed);
                deviceLayouts.flush();
            }
            if (!replacements.isEmpty()) deviceLayouts.saveAllAndFlush(replacements);
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException exception) {
            conflict();
        }
    }

    protected void touch(HomeView view) {
        view.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        save(view);
    }

    protected void save(HomeView view) {
        try {
            views.saveAndFlush(view);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            conflict();
        }
    }

    protected void saveDeviceLayout(HomeDeviceLayout layout) {
        try {
            deviceLayouts.saveAndFlush(layout);
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException exception) {
            conflict();
        }
    }

    protected void saveWidgetConfiguration(HomeWidgetConfiguration configuration) {
        try {
            widgetConfigurations.saveAndFlush(configuration);
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException exception) {
            conflict();
        }
    }

    private HomeViewRevision saveRevision(HomeViewRevision revision) {
        try {
            return revisions.saveAndFlush(revision);
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException exception) {
            conflict();
            throw new IllegalStateException("unreachable");
        }
    }

    protected void mirrorDefaultView(HomeView view) {
        try {
            compatibilityBridge.mirrorDefaultView(view);
        } catch (DataIntegrityViolationException exception) {
            conflict();
        }
    }

    protected HomeViewDtos.DeviceLayoutResponse deviceResponse(
            HomeDeviceLayout value, long viewVersion) {
        try {
            return new HomeViewDtos.DeviceLayoutResponse(
                    value.getDeviceLayoutId(), value.getViewId(), value.getDeviceClass(),
                    objectMapper.treeToValue(value.getOverlayPayload(),
                            HomeViewDtos.DeviceLayoutOverlay.class),
                    value.getVersion() == null ? 0L : value.getVersion(),
                    viewVersion, offset(value.getUpdatedAt()));
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored device overlay is invalid.", exception);
        }
    }

    protected HomeViewDtos.HomeViewRevisionResponse revisionResponse(HomeViewRevision value) {
        return new HomeViewDtos.HomeViewRevisionResponse(
                value.getRevisionId(), value.getViewId(), value.getRevisionNumber(),
                value.getSchemaVersion(), value.getSource(), value.getChangeSummary(),
                snapshotCodec.decode(value.getSnapshot(), value.getSchemaVersion()).snapshot(),
                value.getCreatedAt(), value.getCreatedBy());
    }

    HomePreferenceDtos.HomeLayoutPayload layout(JsonNode value) {
        try {
            return objectMapper.treeToValue(value, HomePreferenceDtos.HomeLayoutPayload.class);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored home layout is invalid.", exception);
        }
    }

    String fingerprint(Object value) {
        return canonicalJson.fingerprint(value);
    }

    String externalFingerprint(String source, UUID viewId, String requestFingerprint) {
        if (requestFingerprint == null || requestFingerprint.length() != 64) {
            throw invalid("A canonical idempotency fingerprint is required.");
        }
        return fingerprint(Map.of(
                "operation", "APPLY_" + source,
                "viewId", viewId,
                "requestFingerprint", requestFingerprint));
    }

    protected Map<String, Object> snapshot(HomeView view) {
        return Map.of(
                "viewId", view.getViewId(),
                "viewKey", view.getViewKey(),
                "surfaceKey", view.getSurfaceKey(),
                "isDefault", view.isDefaultView(),
                "customized", view.isCustomized(),
                "schemaVersion", view.getSchemaVersion(),
                "layout", view.getLayoutPayload(),
                "version", version(view));
    }

    protected long version(HomeView view) {
        return view.getVersion() == null ? 0L : view.getVersion();
    }

    private OffsetDateTime offset(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    protected void requireVersion(HomeView view, Long expected) {
        if (expected == null || version(view) != expected) conflict();
    }

    protected void requireIntegrity(HomeView view) {
        if (!"VALID".equals(view.getIntegrityState())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Repair the recovered base layout before editing its overlays or activation.");
        }
    }

    protected BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    protected void conflict() {
        throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }
}
