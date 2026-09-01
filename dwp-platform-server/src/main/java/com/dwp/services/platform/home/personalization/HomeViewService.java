package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.HomeCompositionPolicyReader;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class HomeViewService extends HomeViewServiceSupport {
    private static final int MAX_VIEWS = 10;

    private final HomeCompositionPolicyReader compositionPolicy;
    private final HomePersonalizationAccess access;
    private final PlatformAuditService audit;
    private final HomePersonalizationScopeLock scopeLock;
    private final HomeCommandReceiptService commandReceipts;

    public HomeViewService(
            HomeViewRepository views,
            HomeViewRevisionRepository revisions,
            HomeDeviceLayoutRepository deviceLayouts,
            HomeWidgetConfigurationRepository widgetConfigurations,
            HomePreferenceService preferenceService,
            HomeCompositionPolicyReader compositionPolicy,
            HomePersonalizationAccess access,
            PlatformAuditService audit,
            ObjectMapper objectMapper,
            HomeViewCompatibilityBridge compatibilityBridge,
            HomePersonalizationScopeLock scopeLock,
            HomeViewSnapshotCodec snapshotCodec,
            HomeWidgetConfigurationPolicy widgetConfigurationPolicy,
            HomeCanonicalJson canonicalJson,
            HomeCommandReceiptService commandReceipts) {
        super(views, revisions, deviceLayouts, widgetConfigurations, preferenceService,
                objectMapper, compatibilityBridge, snapshotCodec, widgetConfigurationPolicy,
                canonicalJson);
        this.compositionPolicy = compositionPolicy;
        this.access = access;
        this.audit = audit;
        this.scopeLock = scopeLock;
        this.commandReceipts = commandReceipts;
    }

    @Transactional(readOnly = true)
    public List<HomeViewDtos.HomeViewResponse> list(
            Long tenantId, Long userId, String surfaceKey) {
        access.requirePersonalization();
        return views.findByTenantIdAndUserIdAndSurfaceKeyOrderByUpdatedAtDesc(
                        tenantId, userId, surfaceKey)
                .stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public HomeViewDtos.HomeViewResponse get(Long tenantId, Long userId, UUID viewId) {
        HomeView view = requireOwned(tenantId, userId, viewId);
        access.requirePersonalization();
        return response(view);
    }

    @Transactional
    public HomeViewDtos.HomeViewResponse create(
            Long tenantId,
            Long userId,
            UUID commandId,
            String correlationId,
            HomeViewDtos.CreateHomeViewRequest request) {
        access.requirePersonalization();
        String fingerprint = fingerprint(Map.of(
                "operation", "CREATE_VIEW", "surfaceKey", HomePreferenceService.WORKSPACE_HOME,
                "request", request));
        String receiptTarget = HomePreferenceService.WORKSPACE_HOME + ":" + request.viewKey();
        HomeViewDtos.HomeViewResponse receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "CREATE_VIEW", receiptTarget,
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (receiptReplay != null) return receiptReplay;
        requirePersonalization(tenantId, HomePreferenceService.WORKSPACE_HOME);
        scopeLock.lock(tenantId, userId, HomePreferenceService.WORKSPACE_HOME);
        receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "CREATE_VIEW", receiptTarget,
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (receiptReplay != null) return receiptReplay;
        HomeView replay = replay(tenantId, userId, commandId, fingerprint);
        if (replay != null) return response(replay);
        if (views.countByTenantIdAndUserIdAndSurfaceKey(
                tenantId, userId, HomePreferenceService.WORKSPACE_HOME) >= MAX_VIEWS) {
            throw invalid("A user can own up to ten home views per surface.");
        }
        HomePreferenceDtos.HomeLayoutPayload requestedLayout = preferenceService.normalizeForSurface(
                HomePreferenceService.WORKSPACE_HOME, request.layout());
        boolean first = views.countByTenantIdAndUserIdAndSurfaceKey(
                tenantId, userId, HomePreferenceService.WORKSPACE_HOME) == 0;
        if (first || request.makeDefault()) clearDefaults(
                tenantId, userId, HomePreferenceService.WORKSPACE_HOME);
        HomeView view = HomeView.builder()
                .viewId(UUID.randomUUID())
                .tenantId(tenantId)
                .userId(userId)
                .surfaceKey(HomePreferenceService.WORKSPACE_HOME)
                .viewKey(request.viewKey())
                .name(request.name().trim())
                .defaultView(first || request.makeDefault())
                .customized(true)
                .schemaVersion(HomePreferenceDtos.SCHEMA_VERSION)
                .layoutPayload(objectMapper.valueToTree(requestedLayout))
                .build();
        save(view);
        mirrorDefaultView(view);
        appendRevision(view, "USER", "Home view created", commandId, fingerprint, userId);
        audit.success(tenantId, userId, "home-view.created", "HOME_VIEW",
                view.getViewId().toString(), correlationId, null, snapshot(view));
        HomeViewDtos.HomeViewResponse result = response(view);
        commandReceipts.record(tenantId, userId, commandId, "CREATE_VIEW",
                receiptTarget, fingerprint, result);
        return result;
    }

    @Transactional
    public HomeViewDtos.HomeViewResponse update(
            Long tenantId,
            Long userId,
            UUID viewId,
            UUID commandId,
            String correlationId,
            HomeViewDtos.UpdateHomeViewRequest request) {
        access.requirePersonalization();
        String fingerprint = fingerprint(Map.of(
                "operation", "UPDATE_VIEW", "viewId", viewId, "request", request));
        HomeViewDtos.HomeViewResponse receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "UPDATE_VIEW", viewId.toString(),
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (receiptReplay != null) return receiptReplay;
        HomeView owned = requireOwned(tenantId, userId, viewId);
        requirePersonalization(tenantId, owned.getSurfaceKey());
        scopeLock.lock(tenantId, userId, owned.getSurfaceKey());
        receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "UPDATE_VIEW", viewId.toString(),
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (receiptReplay != null) return receiptReplay;
        HomeView replay = replay(tenantId, userId, commandId, fingerprint);
        if (replay != null) return response(replay);
        HomeView view = requireOwnedForUpdate(tenantId, userId, viewId);
        requireVersion(view, request.version());
        Object before = snapshot(view);
        HomePreferenceDtos.HomeLayoutPayload layout = preferenceService.normalizeForSurface(
                view.getSurfaceKey(), request.layout());
        view.setName(request.name().trim());
        view.setLayoutPayload(objectMapper.valueToTree(layout));
        view.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        view.setIntegrityState("VALID");
        view.setCustomized(true);
        save(view);
        mirrorDefaultView(view);
        appendRevision(view, "USER", "Home view updated", commandId, fingerprint, userId);
        audit.success(tenantId, userId, "home-view.updated", "HOME_VIEW",
                viewId.toString(), correlationId, before, snapshot(view));
        HomeViewDtos.HomeViewResponse result = response(view);
        commandReceipts.record(tenantId, userId, commandId, "UPDATE_VIEW",
                viewId.toString(), fingerprint, result);
        return result;
    }

    /**
     * Restores the governed organization layout for one personal View.
     *
     * <p>Reset is intentionally distinct from a regular PUT. A regular PUT is a personal
     * customization even when its pixels happen to equal today's defaults. Reset establishes a
     * durable {@code customized=false} state and removes every child override so future governed
     * defaults are not shadowed by stale widget or device configuration.</p>
     */
    @Transactional
    public HomeViewDtos.HomeViewResponse reset(
            Long tenantId,
            Long userId,
            UUID viewId,
            UUID commandId,
            String correlationId,
            Long version) {
        access.requirePersonalization();
        String fingerprint = fingerprint(Map.of(
                "operation", "RESET_VIEW", "viewId", viewId, "version", version));
        HomeViewDtos.HomeViewResponse replay = commandReceipts.replay(
                tenantId, userId, commandId, "RESET_VIEW", viewId.toString(),
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (replay != null) return replay;
        HomeView observed = requireOwned(tenantId, userId, viewId);
        requirePersonalization(tenantId, observed.getSurfaceKey());
        scopeLock.lock(tenantId, userId, observed.getSurfaceKey());
        replay = commandReceipts.replay(
                tenantId, userId, commandId, "RESET_VIEW", viewId.toString(),
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (replay != null) return replay;
        HomeView view = requireOwnedForUpdate(tenantId, userId, viewId);
        requireVersion(view, version);
        HomeViewDtos.HomeViewSnapshot before = snapshotCodec.capture(
                view,
                widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                        viewId, tenantId, userId),
                deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                        viewId, tenantId, userId),
                currentLayout(view));
        HomePreferenceDtos.HomeLayoutPayload defaults = preferenceService.normalizeForSurface(
                view.getSurfaceKey(),
                preferenceService.defaultLayoutForSurface(view.getSurfaceKey()));
        view.setLayoutPayload(objectMapper.valueToTree(defaults));
        view.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        view.setIntegrityState("VALID");
        view.setCustomized(false);
        reconcileWidgetConfigurations(view, Map.of());
        reconcileDeviceLayouts(view, Map.of());
        save(view);
        mirrorDefaultView(view);
        appendRevision(view, "USER", "Home view reset to organization defaults",
                commandId, fingerprint, userId);
        audit.success(tenantId, userId, "home-view.reset", "HOME_VIEW",
                viewId.toString(), correlationId, before, snapshot(view));
        HomeViewDtos.HomeViewResponse result = response(view);
        commandReceipts.record(tenantId, userId, commandId, "RESET_VIEW",
                viewId.toString(), fingerprint, result);
        return result;
    }

    @Transactional
    public HomeViewDtos.HomeViewResponse activate(
            Long tenantId,
            Long userId,
            UUID viewId,
            UUID commandId,
            String correlationId,
            Long version) {
        access.requirePersonalization();
        String fingerprint = fingerprint(Map.of(
                "operation", "ACTIVATE_VIEW", "viewId", viewId, "version", version));
        HomeViewDtos.HomeViewResponse replay = commandReceipts.replay(
                tenantId, userId, commandId, "ACTIVATE_VIEW", viewId.toString(),
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (replay != null) return replay;
        HomeView owned = requireOwned(tenantId, userId, viewId);
        requirePersonalization(tenantId, owned.getSurfaceKey());
        scopeLock.lock(tenantId, userId, owned.getSurfaceKey());
        replay = commandReceipts.replay(
                tenantId, userId, commandId, "ACTIVATE_VIEW", viewId.toString(),
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (replay != null) return replay;
        HomeView view = requireOwnedForUpdate(tenantId, userId, viewId);
        requireVersion(view, version);
        requireIntegrity(view);
        if (!view.isDefaultView()) {
            clearDefaults(tenantId, userId, view.getSurfaceKey());
            view.setDefaultView(true);
            save(view);
            mirrorDefaultView(view);
            audit.success(tenantId, userId, "home-view.activated", "HOME_VIEW",
                    viewId.toString(), correlationId, null, snapshot(view));
        }
        HomeViewDtos.HomeViewResponse result = response(view);
        commandReceipts.record(tenantId, userId, commandId, "ACTIVATE_VIEW",
                viewId.toString(), fingerprint, result);
        return result;
    }

    @Transactional
    public HomeViewDtos.DeleteHomeViewResponse delete(
            Long tenantId, Long userId, UUID viewId, UUID commandId,
            Long version, String correlationId) {
        access.requirePersonalization();
        String fingerprint = fingerprint(Map.of(
                "operation", "DELETE_VIEW", "viewId", viewId, "version", version));
        HomeViewDtos.DeleteHomeViewResponse replay = commandReceipts.replay(
                tenantId, userId, commandId, "DELETE_VIEW", viewId.toString(),
                fingerprint, HomeViewDtos.DeleteHomeViewResponse.class);
        if (replay != null) return replay;
        requirePersonalization(tenantId, HomePreferenceService.WORKSPACE_HOME);
        scopeLock.lock(tenantId, userId, HomePreferenceService.WORKSPACE_HOME);
        replay = commandReceipts.replay(
                tenantId, userId, commandId, "DELETE_VIEW", viewId.toString(),
                fingerprint, HomeViewDtos.DeleteHomeViewResponse.class);
        if (replay != null) return replay;
        HomeView view = requireOwnedForUpdate(tenantId, userId, viewId);
        requireVersion(view, version);
        long count = views.countByTenantIdAndUserIdAndSurfaceKey(
                tenantId, userId, view.getSurfaceKey());
        if (count <= 1 || view.isDefaultView()) {
            throw invalid("The active default or last home view cannot be deleted.");
        }
        HomeViewDtos.HomeViewSnapshot before = snapshotCodec.capture(
                view,
                widgetConfigurations.findByViewIdAndTenantIdAndUserIdOrderByWidgetKey(
                        viewId, tenantId, userId),
                deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                        viewId, tenantId, userId),
                currentLayout(view));
        view.setDefaultView(false);
        view.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        view.setDeletedBy(userId);
        save(view);
        UUID active = views.findByTenantIdAndUserIdAndSurfaceKeyOrderByUpdatedAtDesc(
                        tenantId, userId, view.getSurfaceKey()).stream()
                .filter(HomeView::isDefaultView).map(HomeView::getViewId).findFirst().orElse(null);
        audit.success(tenantId, userId, "home-view.deleted", "HOME_VIEW",
                viewId.toString(), correlationId, before, null);
        HomeViewDtos.DeleteHomeViewResponse result =
                new HomeViewDtos.DeleteHomeViewResponse(viewId, active);
        commandReceipts.record(tenantId, userId, commandId, "DELETE_VIEW",
                viewId.toString(), fingerprint, result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<HomeViewDtos.HomeViewRevisionResponse> revisions(
            Long tenantId, Long userId, UUID viewId) {
        HomeView view = requireOwned(tenantId, userId, viewId);
        access.requirePersonalization();
        return revisions
                .findTop50ByViewIdAndTenantIdAndUserIdAndRestorableTrueOrderByRevisionNumberDesc(
                        viewId, tenantId, userId)
                .stream().map(this::revisionResponse).toList();
    }

    @Transactional
    public HomeViewDtos.HomeViewResponse restore(
            Long tenantId,
            Long userId,
            UUID viewId,
            UUID revisionId,
            UUID commandId,
            String correlationId,
            Long version) {
        access.requirePersonalization();
        String rawFingerprint = fingerprint(Map.of(
                "operation", "RESTORE_VIEW", "viewId", viewId,
                "revisionId", revisionId, "version", version));
        String requestFingerprint = externalFingerprint(
                "RESTORE", viewId, rawFingerprint);
        HomeViewDtos.HomeViewResponse receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "RESTORE_VIEW", viewId.toString(),
                requestFingerprint, HomeViewDtos.HomeViewResponse.class);
        if (receiptReplay != null) return receiptReplay;
        HomeView observed = requireOwned(tenantId, userId, viewId);
        requirePersonalization(tenantId, observed.getSurfaceKey());
        scopeLock.lock(tenantId, userId, observed.getSurfaceKey());
        receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "RESTORE_VIEW", viewId.toString(),
                requestFingerprint, HomeViewDtos.HomeViewResponse.class);
        if (receiptReplay != null) return receiptReplay;
        HomeView replay = replay(tenantId, userId, commandId, requestFingerprint);
        if (replay != null) return response(replay);
        HomeView view = requireOwnedForUpdate(tenantId, userId, viewId);
        requireVersion(view, version);
        HomeViewRevision source = revisions
                .findByRevisionIdAndViewIdAndTenantIdAndUserId(
                        revisionId, viewId, tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!source.isRestorable()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This quarantined legacy revision cannot be restored.");
        }
        HomeViewSnapshotCodec.DecodedSnapshot decoded = snapshotCodec.decode(
                source.getSnapshot(), source.getSchemaVersion());
        HomePreferenceDtos.HomeLayoutPayload revisionLayout = decoded.snapshot().view().layout();
        HomePreferenceDtos.HomeLayoutPayload restored = preferenceService.normalizeForSurface(
                view.getSurfaceKey(), revisionLayout);
        Object before = snapshot(view);
        view.setLayoutPayload(objectMapper.valueToTree(restored));
        view.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        view.setIntegrityState("VALID");
        if (!decoded.legacyLayoutOnly()
                && decoded.snapshot().view().customized() != null) {
            view.setCustomized(decoded.snapshot().view().customized());
        }
        if (!decoded.legacyLayoutOnly()) {
            String restoredName = decoded.snapshot().view().name();
            if (restoredName == null || restoredName.isBlank() || restoredName.length() > 80) {
                throw invalid("The revision contains an invalid home view name.");
            }
            view.setName(restoredName.trim());
        }
        // V171 legacy backfill predates child state; its empty maps are authoritative.
        reconcileWidgetConfigurations(view, decoded.snapshot().widgetConfigurations());
        reconcileDeviceLayouts(view, decoded.snapshot().deviceLayouts());
        save(view);
        mirrorDefaultView(view);
        appendRevision(
                view, "RESTORE", "Revision " + source.getRevisionNumber() + " restored",
                commandId, requestFingerprint, userId);
        audit.success(tenantId, userId, "home-view.revision-restored", "HOME_VIEW",
                viewId.toString(), correlationId, before, snapshot(view));
        HomeViewDtos.HomeViewResponse result = response(view);
        commandReceipts.record(tenantId, userId, commandId, "RESTORE_VIEW",
                viewId.toString(), requestFingerprint, result);
        return result;
    }

    @Transactional(readOnly = true)
    public List<HomeViewDtos.DeviceLayoutResponse> deviceLayouts(
            Long tenantId, Long userId, UUID viewId) {
        HomeView view = requireOwned(tenantId, userId, viewId);
        access.requirePersonalization();
        return deviceLayouts.findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
                        viewId, tenantId, userId)
                .stream().map(value -> deviceResponse(value, version(view))).toList();
    }

    @Transactional
    public HomeViewDtos.DeviceLayoutResponse putDeviceLayout(
            Long tenantId,
            Long userId,
            UUID viewId,
            String rawDeviceClass,
            UUID commandId,
            String correlationId,
            HomeViewDtos.UpdateDeviceLayoutRequest request) {
        String deviceClass = rawDeviceClass.toUpperCase();
        if (!DEVICE_CLASSES.contains(deviceClass)) throw invalid("Unsupported device class.");
        access.requirePersonalization();
        String fingerprint = fingerprint(Map.of(
                "operation", "PUT_DEVICE_LAYOUT", "viewId", viewId,
                "deviceClass", deviceClass, "request", request));
        String receiptTarget = viewId + ":" + deviceClass;
        HomeViewDtos.DeviceLayoutResponse replay = commandReceipts.replay(
                tenantId, userId, commandId, "PUT_DEVICE_LAYOUT", receiptTarget,
                fingerprint, HomeViewDtos.DeviceLayoutResponse.class);
        if (replay != null) return replay;
        HomeView owned = requireOwned(tenantId, userId, viewId);
        requirePersonalization(tenantId, owned.getSurfaceKey());
        scopeLock.lock(tenantId, userId, owned.getSurfaceKey());
        replay = commandReceipts.replay(
                tenantId, userId, commandId, "PUT_DEVICE_LAYOUT", receiptTarget,
                fingerprint, HomeViewDtos.DeviceLayoutResponse.class);
        if (replay != null) return replay;
        HomeView view = requireOwnedForUpdate(tenantId, userId, viewId);
        requireVersion(view, request.viewVersion());
        requireIntegrity(view);
        validateDeviceOverlay(view, request.overlay());
        HomeDeviceLayout layout = deviceLayouts
                .findByViewIdAndTenantIdAndUserIdAndDeviceClass(
                        viewId, tenantId, userId, deviceClass)
                .orElseGet(() -> HomeDeviceLayout.builder()
                        .deviceLayoutId(UUID.randomUUID()).viewId(viewId)
                        .tenantId(tenantId).userId(userId).deviceClass(deviceClass).build());
        if (layout.getVersion() != null && request.version() != null
                && !layout.getVersion().equals(request.version())) conflict();
        layout.setOverlayPayload(objectMapper.valueToTree(request.overlay()));
        view.setCustomized(true);
        touch(view);
        saveDeviceLayout(layout);
        mirrorDefaultView(view);
        appendRevision(view, "USER", deviceClass + " device overlay updated",
                commandId, fingerprint, userId);
        audit.success(tenantId, userId, "home-view.device-layout-updated",
                "HOME_VIEW_DEVICE_LAYOUT", layout.getDeviceLayoutId().toString(),
                correlationId, null, layout.getOverlayPayload());
        HomeViewDtos.DeviceLayoutResponse result = deviceResponse(layout, version(view));
        commandReceipts.record(tenantId, userId, commandId, "PUT_DEVICE_LAYOUT",
                receiptTarget, fingerprint, result);
        return result;
    }

    @Transactional
    public HomeViewDtos.HomeViewResponse putWidgetConfiguration(
            Long tenantId,
            Long userId,
            UUID viewId,
            String widgetKey,
            UUID commandId,
            String correlationId,
            HomeViewDtos.UpdateWidgetConfigurationRequest request) {
        access.requirePersonalization();
        String fingerprint = fingerprint(Map.of(
                "operation", "PUT_WIDGET_CONFIGURATION", "viewId", viewId,
                "widgetKey", widgetKey, "request", request));
        String receiptTarget = viewId + ":" + widgetKey;
        HomeViewDtos.HomeViewResponse replay = commandReceipts.replay(
                tenantId, userId, commandId, "PUT_WIDGET_CONFIGURATION", receiptTarget,
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (replay != null) return replay;
        HomeView owned = requireOwned(tenantId, userId, viewId);
        requirePersonalization(tenantId, owned.getSurfaceKey());
        scopeLock.lock(tenantId, userId, owned.getSurfaceKey());
        replay = commandReceipts.replay(
                tenantId, userId, commandId, "PUT_WIDGET_CONFIGURATION", receiptTarget,
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (replay != null) return replay;
        HomeView view = requireOwnedForUpdate(tenantId, userId, viewId);
        requireVersion(view, request.viewVersion());
        requireIntegrity(view);
        widgetConfigurationPolicy.validate(
                currentLayout(view), widgetKey, request.configuration());
        HomeWidgetConfiguration configuration = widgetConfigurations
                .findByViewIdAndTenantIdAndUserIdAndWidgetKey(
                        viewId, tenantId, userId, widgetKey)
                .orElseGet(() -> HomeWidgetConfiguration.builder()
                        .widgetConfigurationId(UUID.randomUUID()).viewId(viewId)
                        .tenantId(tenantId).userId(userId).widgetKey(widgetKey).build());
        configuration.setConfigurationPayload(objectMapper.valueToTree(request.configuration()));
        saveWidgetConfiguration(configuration);
        view.setCustomized(true);
        touch(view);
        mirrorDefaultView(view);
        appendRevision(view, "USER", widgetKey + " configuration updated",
                commandId, fingerprint, userId);
        audit.success(tenantId, userId, "home-view.widget-configured",
                "HOME_WIDGET_CONFIGURATION", configuration.getWidgetConfigurationId().toString(),
                correlationId, null, request.configuration());
        HomeViewDtos.HomeViewResponse result = response(view);
        commandReceipts.record(tenantId, userId, commandId,
                "PUT_WIDGET_CONFIGURATION", receiptTarget, fingerprint, result);
        return result;
    }

    @Transactional
    public HomeViewDtos.HomeViewResponse applyExternalLayout(
            Long tenantId,
            Long userId,
            UUID viewId,
            Long expectedVersion,
            HomePreferenceDtos.HomeLayoutPayload requested,
            String source,
            String summary,
            UUID commandId,
            String requestFingerprint,
            Long actorId,
            String correlationId) {
        access.requirePersonalization();
        String scopedFingerprint = externalFingerprint(source, viewId, requestFingerprint);
        String operation = "APPLY_" + source;
        boolean ownsReceipt = !Set.of("AI", "UNDO", "TEMPLATE").contains(source);
        if (ownsReceipt) {
            HomeViewDtos.HomeViewResponse receiptReplay = commandReceipts.replay(
                    tenantId, userId, commandId, operation, viewId.toString(),
                    scopedFingerprint, HomeViewDtos.HomeViewResponse.class);
            if (receiptReplay != null) return receiptReplay;
        }
        HomeView owned = requireOwned(tenantId, userId, viewId);
        requirePersonalization(tenantId, owned.getSurfaceKey());
        scopeLock.lock(tenantId, userId, owned.getSurfaceKey());
        if (ownsReceipt) {
            HomeViewDtos.HomeViewResponse receiptReplay = commandReceipts.replay(
                    tenantId, userId, commandId, operation, viewId.toString(),
                    scopedFingerprint, HomeViewDtos.HomeViewResponse.class);
            if (receiptReplay != null) return receiptReplay;
        }
        HomeView replay = replay(tenantId, userId, commandId, scopedFingerprint);
        if (replay != null) return response(replay);
        HomeView view = requireOwnedForUpdate(tenantId, userId, viewId);
        requireVersion(view, expectedVersion);
        Object before = snapshot(view);
        HomePreferenceDtos.HomeLayoutPayload layout = preferenceService.normalizeForSurface(
                view.getSurfaceKey(), requested);
        view.setLayoutPayload(objectMapper.valueToTree(layout));
        view.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        view.setIntegrityState("VALID");
        view.setCustomized(true);
        save(view);
        mirrorDefaultView(view);
        appendRevision(view, source, summary, commandId, scopedFingerprint, actorId);
        audit.success(tenantId, actorId, "home-view.layout-applied", "HOME_VIEW",
                viewId.toString(), correlationId, before, snapshot(view));
        HomeViewDtos.HomeViewResponse result = response(view);
        if (ownsReceipt) {
            commandReceipts.record(tenantId, userId, commandId, operation,
                    viewId.toString(), scopedFingerprint, result);
        }
        return result;
    }

    void requirePersonalizationForView(Long tenantId, Long userId, UUID viewId) {
        requireOwned(tenantId, userId, viewId);
        access.requirePersonalization();
    }

    void lockPersonalizationScopeForView(Long tenantId, Long userId, UUID viewId) {
        HomeView view = requireOwned(tenantId, userId, viewId);
        requirePersonalization(tenantId, view.getSurfaceKey());
        scopeLock.lock(tenantId, userId, view.getSurfaceKey());
    }

    void requirePolicy(Long tenantId, String surfaceKey) {
        requirePersonalization(tenantId, surfaceKey);
    }

    private void requirePersonalization(Long tenantId, String surfaceKey) {
        access.requirePersonalization();
        if (HomePreferenceService.WORKSPACE_HOME.equals(surfaceKey)
                && !compositionPolicy.flowPersonalizationEnabled(tenantId)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Flow home personalization is disabled by tenant policy or rollout.");
        }
    }

}
