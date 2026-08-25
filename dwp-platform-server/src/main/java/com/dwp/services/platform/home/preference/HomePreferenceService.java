package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.HomeCompositionPolicyReader;
import com.dwp.services.platform.home.personalization.HomeViewCompatibilityBridge;
import com.dwp.services.platform.home.personalization.HomePersonalizationScopeLock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class HomePreferenceService {

    private static final Logger log = LoggerFactory.getLogger(HomePreferenceService.class);

    public static final String WORKSPACE_HOME = "workspace-home";
    public static final String HCM_HOME = "hcm-home";
    public static final String LEGACY_HRIS_HOME = "hris-home";
    public static final String APPROVAL_HOME = "approval-home";

    private static final int MAX_LAYOUT_BYTES = 96 * 1024;
    private static final Set<String> PRESENTATIONS = Set.of("balanced", "expressive", "focused");
    private static final Set<String> WIDGET_SIZES = Set.of(
            "fifth", "quarter", "compact", "medium", "large", "full");
    private static final Set<String> WIDGET_HEIGHTS = Set.of(
            "short", "standard", "tall", "expanded");
    private static final Set<String> LEGACY_WORKSPACE_FIXED_ZONE_KEYS = Set.of("announcements");
    private static final Map<String, SurfaceContract> SURFACE_CONTRACTS = Map.of(
            WORKSPACE_HOME, workspaceContract(),
            HCM_HOME, hcmContract(),
            APPROVAL_HOME, approvalContract());

    private final HomePreferenceRepository repository;
    private final ObjectMapper objectMapper;
    private final PlatformAuditService auditService;
    private final HomeCompositionPolicyReader compositionPolicyReader;
    private final HomePersonalizationScopeLock scopeLock;

    @Autowired(required = false)
    private HomeViewCompatibilityBridge compatibilityBridge;

    public HomePreferenceService(
            HomePreferenceRepository repository,
            ObjectMapper objectMapper,
            PlatformAuditService auditService,
            HomeCompositionPolicyReader compositionPolicyReader,
            HomePersonalizationScopeLock scopeLock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.compositionPolicyReader = compositionPolicyReader;
        this.scopeLock = scopeLock;
    }

    @Transactional(readOnly = true)
    public HomePreferenceDtos.HomePreferenceResponse get(
            Long tenantId,
            Long userId,
            String surfaceKey) {
        String canonicalSurfaceKey = canonicalSurfaceKey(surfaceKey);
        SurfaceContract contract = requireSurface(canonicalSurfaceKey);
        return repository.findByTenantIdAndUserIdAndSurfaceKey(
                        tenantId, userId, canonicalSurfaceKey)
                .map(preference -> {
                    if (compatibilityBridge != null) compatibilityBridge.shadowCompare(preference);
                    return response(preference, contract);
                })
                .orElseGet(() -> defaultResponse(canonicalSurfaceKey, contract));
    }

    @Transactional
    public HomePreferenceDtos.HomePreferenceResponse update(
            Long tenantId,
            Long userId,
            String surfaceKey,
            String correlationId,
            HomePreferenceDtos.UpdateHomePreferenceRequest request) {
        String canonicalSurfaceKey = canonicalSurfaceKey(surfaceKey);
        SurfaceContract contract = requireSurface(canonicalSurfaceKey);
        requirePersonalCustomization(tenantId, canonicalSurfaceKey);
        scopeLock.lock(tenantId, userId, canonicalSurfaceKey);
        HomePreferenceDtos.HomeLayoutPayload normalized = normalizeLayout(
                canonicalSurfaceKey,
                contract,
                request.layout(),
                true,
                true);
        java.util.Optional<HomePreference> existing = repository
                .findByTenantIdAndUserIdAndSurfaceKey(tenantId, userId, canonicalSurfaceKey);
        HomePreference preference = existing.orElseGet(
                () -> create(tenantId, userId, canonicalSurfaceKey, request.version()));
        if (existing.isPresent()) requireVersion(preference, request.version());
        Map<String, Object> before = snapshot(preference);
        preference.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        preference.setLayoutPayload(objectMapper.valueToTree(normalized));
        preference.setCustomized(true);
        HomePreference saved;
        try {
            saved = repository.saveAndFlush(preference);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (compatibilityBridge != null) compatibilityBridge.mirrorLegacyPreference(saved);
        auditService.success(
                tenantId,
                userId,
                "home-preference.updated",
                "HOME_PREFERENCE",
                userId + ":" + canonicalSurfaceKey,
                correlationId,
                before,
                snapshot(saved));
        return response(saved, contract);
    }

    @Transactional
    public HomePreferenceDtos.HomePreferenceResponse reset(
            Long tenantId,
            Long userId,
            String surfaceKey,
            String correlationId,
            Long version) {
        String canonicalSurfaceKey = canonicalSurfaceKey(surfaceKey);
        SurfaceContract contract = requireSurface(canonicalSurfaceKey);
        requirePersonalCustomization(tenantId, canonicalSurfaceKey);
        scopeLock.lock(tenantId, userId, canonicalSurfaceKey);
        HomePreference preference = repository
                .findByTenantIdAndUserIdAndSurfaceKey(tenantId, userId, canonicalSurfaceKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(preference, version);
        Map<String, Object> before = snapshot(preference);
        HomePreferenceDtos.HomePreferenceResponse defaults =
                defaultResponse(canonicalSurfaceKey, contract);
        preference.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        preference.setLayoutPayload(objectMapper.valueToTree(defaults.layout()));
        preference.setCustomized(false);
        try {
            repository.saveAndFlush(preference);
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (compatibilityBridge != null) {
            compatibilityBridge.mirrorLegacyReset(
                    tenantId,
                    userId,
                    canonicalSurfaceKey,
                    objectMapper.valueToTree(defaults.layout()));
        }
        auditService.success(
                tenantId,
                userId,
                "home-preference.reset",
                "HOME_PREFERENCE",
                userId + ":" + canonicalSurfaceKey,
                correlationId,
                before,
                snapshot(null));
        return response(preference, contract);
    }

    private HomePreference create(
            Long tenantId,
            Long userId,
            String surfaceKey,
            Long requestedVersion) {
        if (requestedVersion == null || requestedVersion != 0L) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        return HomePreference.builder()
                .tenantId(tenantId)
                .userId(userId)
                .surfaceKey(surfaceKey)
                .schemaVersion(HomePreferenceDtos.SCHEMA_VERSION)
                // Version zero is the create precondition. Persist new rows at one so a
                // second version-zero request serialized behind the scope lock is stale.
                .version(1L)
                .customized(true)
                .build();
    }

    private SurfaceContract requireSurface(String surfaceKey) {
        SurfaceContract contract = SURFACE_CONTRACTS.get(surfaceKey);
        if (contract == null) throw invalid("The personal home surface is not registered.");
        return contract;
    }

    private String canonicalSurfaceKey(String surfaceKey) {
        if (LEGACY_HRIS_HOME.equals(surfaceKey)) return HCM_HOME;
        return surfaceKey;
    }

    private HomePreferenceDtos.HomeLayoutPayload normalizeLayout(
            String surfaceKey,
            SurfaceContract contract,
            HomePreferenceDtos.HomeLayoutPayload layout,
            boolean rejectUnsupportedAppLayout,
            boolean rejectGovernedZoneInput) {
        if (rejectUnsupportedAppLayout
                && !contract.supportsAppLayout()
                && layout.appLayout() != null) {
            throw invalid("This personal home surface does not support an application layout.");
        }
        if (contract.supportsAppLayout()) validateAppLayout(layout.appLayout());

        String presentation = layout.presentation() == null
                ? contract.defaultPresentation()
                : layout.presentation();
        if (!PRESENTATIONS.contains(presentation)) {
            throw invalid("The personal home presentation is not registered.");
        }

        Set<String> unique = new HashSet<>();
        Map<String, HomePreferenceDtos.WidgetPreference> requested = new LinkedHashMap<>();
        for (HomePreferenceDtos.WidgetPreference widget : layout.widgets()) {
            if (widget == null || widget.widgetKey() == null || widget.widgetKey().isBlank()
                    || widget.visible() == null) {
                throw invalid("The personal home layout contains an incomplete widget.");
            }
            if (!unique.add(widget.widgetKey())) {
                throw invalid("The personal home layout contains an unknown or duplicate widget.");
            }
            if (isLegacyWorkspaceFixedZonePreference(surfaceKey, widget.widgetKey())) {
                if (rejectGovernedZoneInput) {
                    throw invalid("A governed home zone cannot be changed by personal layout APIs.");
                }
                continue;
            }
            WidgetContract widgetContract = contract.widgets().get(widget.widgetKey());
            if (widgetContract == null) {
                throw invalid("The personal home layout contains an unknown or duplicate widget.");
            }
            if (!widgetContract.canHide() && !Boolean.TRUE.equals(widget.visible())) {
                throw invalid("A governed personal home widget cannot be hidden.");
            }
            String size = widget.size() == null ? widgetContract.defaultSize() : widget.size();
            if (!WIDGET_SIZES.contains(size) || !widgetContract.allowedSizes().contains(size)) {
                throw invalid("The personal home widget size is not allowed for this widget.");
            }
            String height = widget.height() == null
                    ? widgetContract.defaultHeight()
                    : widget.height();
            if (!WIDGET_HEIGHTS.contains(height)
                    || !widgetContract.allowedHeights().contains(height)) {
                throw invalid("The personal home widget height is not allowed for this widget.");
            }
            requested.put(
                    widget.widgetKey(),
                    new HomePreferenceDtos.WidgetPreference(
                            widget.widgetKey(), widget.visible(), size, height));
        }

        List<HomePreferenceDtos.WidgetPreference> widgets = new ArrayList<>(requested.values());
        contract.widgetOrder().forEach(widgetKey -> {
            if (requested.containsKey(widgetKey)) return;
            WidgetContract widget = contract.widgets().get(widgetKey);
            HomePreferenceDtos.WidgetPreference preference =
                    new HomePreferenceDtos.WidgetPreference(
                            widgetKey, true, widget.defaultSize(), widget.defaultHeight());
            int defaultIndex = contract.widgetOrder().indexOf(widgetKey);
            int insertionIndex = widgets.size();
            for (int index = 0; index < widgets.size(); index++) {
                int candidateIndex = contract.widgetOrder().indexOf(widgets.get(index).widgetKey());
                if (candidateIndex > defaultIndex) {
                    insertionIndex = index;
                    break;
                }
            }
            widgets.add(insertionIndex, preference);
        });
        if (widgets.stream().noneMatch(widget -> Boolean.TRUE.equals(widget.visible()))) {
            throw invalid("At least one personal home widget must remain visible.");
        }

        HomePreferenceDtos.HomeLayoutPayload normalized = new HomePreferenceDtos.HomeLayoutPayload(
                contract.supportsAppLayout() ? layout.appLayout() : null,
                presentation,
                List.copyOf(widgets));
        if (serializedSize(objectMapper.valueToTree(normalized)) > MAX_LAYOUT_BYTES) {
            throw invalid("The personal home layout exceeds the configured size limit.");
        }
        return normalized;
    }

    public HomePreferenceDtos.HomeLayoutPayload normalizeForSurface(
            String surfaceKey,
            HomePreferenceDtos.HomeLayoutPayload layout) {
        String canonical = canonicalSurfaceKey(surfaceKey);
        return normalizeLayout(canonical, requireSurface(canonical), layout, true, true);
    }

    /** Reconciles registry-stale persisted layouts for read-cutover without mutating storage. */
    public HomePreferenceDtos.HomeLayoutPayload reconcileStoredForSurface(
            String surfaceKey,
            HomePreferenceDtos.HomeLayoutPayload layout) {
        String canonical = canonicalSurfaceKey(surfaceKey);
        return normalizeLayout(canonical, requireSurface(canonical), layout, false, false);
    }

    public HomePreferenceDtos.HomeLayoutPayload defaultLayoutForSurface(String surfaceKey) {
        String canonical = canonicalSurfaceKey(surfaceKey);
        return defaultResponse(canonical, requireSurface(canonical)).layout();
    }

    private void validateAppLayout(HomePreferenceDtos.AppLayoutPayloadV1 appLayout) {
        if (appLayout == null) return;
        if (appLayout.version() == null || appLayout.version() != 1
                || appLayout.groups() == null
                || appLayout.folders() == null
                || appLayout.hiddenAppIds() == null) {
            throw invalid("The app layout schema is invalid.");
        }
        validateGroups(appLayout.groups());
        validateFolders(appLayout.groups(), appLayout.folders());
        Set<String> hiddenAppIds = validateHiddenAppIds(appLayout.hiddenAppIds());
        validateAppPlacementExclusivity(appLayout.groups(), appLayout.folders(), hiddenAppIds);
    }

    private void validateGroups(Map<String, List<String>> groups) {
        if (groups.size() > 12) throw invalid("The app layout contains too many groups.");
        groups.forEach((groupId, items) -> {
            if (groupId == null || groupId.isBlank() || groupId.length() > 40
                    || items == null || items.size() > 100) {
                throw invalid("The app layout contains an invalid group.");
            }
            Set<String> unique = new HashSet<>();
            items.forEach(item -> {
                if (item == null || item.isBlank() || item.length() > 100
                        || !unique.add(item)) {
                    throw invalid("The app layout contains an invalid item identifier.");
                }
            });
        });
    }

    private void validateFolders(
            Map<String, List<String>> groups,
            Map<String, HomePreferenceDtos.AppFolderV1> folders) {
        if (folders.size() > 50) throw invalid("The app layout contains too many folders.");
        folders.forEach((folderId, folder) -> {
            if (folderId == null || folderId.length() > 100
                    || folder == null
                    || !folderId.equals(folder.id())
                    || folder.name() == null || folder.name().isBlank()
                    || folder.name().length() > 80
                    || folder.groupId() == null || !groups.containsKey(folder.groupId())
                    || folder.appIds() == null
                    || folder.appIds().size() < 2 || folder.appIds().size() > 50) {
                throw invalid("The app layout contains an invalid folder.");
            }
            Set<String> unique = new HashSet<>();
            folder.appIds().forEach(item -> {
                if (item == null || item.isBlank() || item.length() > 100
                        || !unique.add(item)) {
                    throw invalid("The app folder contains an invalid application identifier.");
                }
            });
        });
    }

    private Set<String> validateHiddenAppIds(List<String> hiddenAppIds) {
        if (hiddenAppIds == null || hiddenAppIds.size() > 100) {
            throw invalid("The app layout contains an invalid hidden application list.");
        }
        Set<String> unique = new HashSet<>();
        hiddenAppIds.forEach(item -> {
            if (item == null || item.isBlank() || item.length() > 100
                    || !unique.add(item)) {
                throw invalid("The hidden application list contains an invalid identifier.");
            }
        });
        return Set.copyOf(unique);
    }

    private void validateAppPlacementExclusivity(
            Map<String, List<String>> groups,
            Map<String, HomePreferenceDtos.AppFolderV1> folders,
            Set<String> hiddenAppIds) {
        Set<String> placedApps = new HashSet<>();
        Set<String> placedFolders = new HashSet<>();
        groups.forEach((groupId, items) -> items.forEach(itemId -> {
            HomePreferenceDtos.AppFolderV1 folder = folders.get(itemId);
            if (folder != null) {
                if (!groupId.equals(folder.groupId()) || !placedFolders.add(itemId)) {
                    throw invalid("An app folder must be placed exactly once in its declared group.");
                }
                return;
            }
            if (hiddenAppIds.contains(itemId) || !placedApps.add(itemId)) {
                throw invalid("An application can be placed in only one visible location.");
            }
        }));
        if (!placedFolders.equals(folders.keySet())) {
            throw invalid("Every app folder must be placed in its declared group.");
        }
        folders.forEach((folderId, folder) -> folder.appIds().forEach(appId -> {
            if (folders.containsKey(appId) || hiddenAppIds.contains(appId)
                    || !placedApps.add(appId)) {
                throw invalid("An application can be placed in only one visible location.");
            }
        }));
    }

    private int serializedSize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException exception) {
            throw invalid("The personal home layout could not be processed.");
        }
    }

    private HomePreferenceDtos.HomePreferenceResponse response(
            HomePreference preference,
            SurfaceContract contract) {
        try {
            String surfaceKey = preference.getSurfaceKey() == null
                    ? WORKSPACE_HOME
                    : preference.getSurfaceKey();
            HomePreferenceDtos.HomeLayoutPayload stored = objectMapper.treeToValue(
                    preference.getLayoutPayload(),
                    HomePreferenceDtos.HomeLayoutPayload.class);
            HomePreferenceDtos.HomeLayoutPayload normalized =
                    normalizeLayout(surfaceKey, contract, stored, false, false);
            boolean unchanged = preference.getSchemaVersion() != null
                    && preference.getSchemaVersion() == HomePreferenceDtos.SCHEMA_VERSION
                    && objectMapper.valueToTree(normalized).equals(preference.getLayoutPayload());
            return new HomePreferenceDtos.HomePreferenceResponse(
                    HomePreferenceDtos.SCHEMA_VERSION,
                    surfaceKey,
                    preference.isCustomized(),
                    unchanged
                            ? HomePreferenceDtos.HomePreferenceIntegrityStatus.VALID
                            : HomePreferenceDtos.HomePreferenceIntegrityStatus.RECONCILED,
                    normalized,
                    preference.getVersion() == null ? 0L : preference.getVersion(),
                    offset(preference.getUpdatedAt()),
                    unchanged ? List.of() : List.of("LAYOUT_RECONCILED"));
        } catch (Exception exception) {
            log.warn(
                    "Invalid stored home preference for tenant {}, user {}, surface {}; returning a recoverable default.",
                    preference.getTenantId(),
                    preference.getUserId(),
                    preference.getSurfaceKey(),
                    exception);
            return recoveryResponse(preference, contract);
        }
    }

    private HomePreferenceDtos.HomePreferenceResponse defaultResponse(
            String surfaceKey,
            SurfaceContract contract) {
        List<HomePreferenceDtos.WidgetPreference> widgets = contract.widgetOrder().stream()
                .map(widgetKey -> {
                    WidgetContract widget = contract.widgets().get(widgetKey);
                    return new HomePreferenceDtos.WidgetPreference(
                            widgetKey, true, widget.defaultSize(), widget.defaultHeight());
                })
                .toList();
        return new HomePreferenceDtos.HomePreferenceResponse(
                HomePreferenceDtos.SCHEMA_VERSION,
                surfaceKey,
                false,
                HomePreferenceDtos.HomePreferenceIntegrityStatus.VALID,
                new HomePreferenceDtos.HomeLayoutPayload(
                        contract.supportsAppLayout() ? emptyAppLayout() : null,
                        contract.defaultPresentation(),
                        widgets),
                0L,
                null,
                List.of());
    }

    private HomePreferenceDtos.HomePreferenceResponse recoveryResponse(
            HomePreference preference,
            SurfaceContract contract) {
        String surfaceKey = preference.getSurfaceKey() == null
                ? WORKSPACE_HOME
                : preference.getSurfaceKey();
        HomePreferenceDtos.HomePreferenceResponse defaults =
                defaultResponse(surfaceKey, contract);
        return new HomePreferenceDtos.HomePreferenceResponse(
                HomePreferenceDtos.SCHEMA_VERSION,
                surfaceKey,
                preference.isCustomized(),
                HomePreferenceDtos.HomePreferenceIntegrityStatus.RECOVERED,
                defaults.layout(),
                preference.getVersion() == null ? 0L : preference.getVersion(),
                offset(preference.getUpdatedAt()),
                List.of("INVALID_STORED_LAYOUT"));
    }

    public HomePreferenceDtos.AppLayoutPayloadV1 emptyAppLayout() {
        return new HomePreferenceDtos.AppLayoutPayloadV1(
                1, Map.of(), Map.of(), List.of());
    }

    /**
     * Shares the same registry contract used by the base layout validator with
     * device-specific overlays. An overlay may change presentation only inside
     * the widget's declared responsive size set.
     */
    public boolean isWidgetSizeAllowed(String surfaceKey, String widgetKey, String size) {
        SurfaceContract contract = SURFACE_CONTRACTS.get(canonicalSurfaceKey(surfaceKey));
        if (contract == null || widgetKey == null || size == null) return false;
        WidgetContract widget = contract.widgets().get(widgetKey);
        return widget != null && WIDGET_SIZES.contains(size) && widget.allowedSizes().contains(size);
    }

    private java.time.OffsetDateTime offset(java.time.LocalDateTime value) {
        return value == null
                ? null
                : value.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private void requireVersion(HomePreference preference, Long requestedVersion) {
        long current = preference.getVersion() == null ? 0L : preference.getVersion();
        if (requestedVersion == null || current != requestedVersion) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private Map<String, Object> snapshot(HomePreference preference) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (preference == null) {
            value.put("customized", false);
            return value;
        }
        value.put("customized", preference.isCustomized());
        value.put("surfaceKey", preference.getSurfaceKey());
        value.put("schemaVersion", preference.getSchemaVersion());
        value.put("layout", preference.getLayoutPayload());
        value.put("version", preference.getVersion() == null ? 0L : preference.getVersion());
        return value;
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private void requirePersonalCustomization(Long tenantId, String surfaceKey) {
        if (WORKSPACE_HOME.equals(surfaceKey)
                && !compositionPolicyReader.personalCustomizationEnabled(tenantId)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Personal workspace customization is disabled by the tenant policy.");
        }
    }

    private boolean isLegacyWorkspaceFixedZonePreference(String surfaceKey, String widgetKey) {
        return WORKSPACE_HOME.equals(surfaceKey)
                && LEGACY_WORKSPACE_FIXED_ZONE_KEYS.contains(widgetKey);
    }

    private static SurfaceContract workspaceContract() {
        Map<String, WidgetContract> widgets = new LinkedHashMap<>();
        widgets.put("command-rail", widget(true, "large", Set.of("large", "full"),
                "short", Set.of("short", "standard")));
        widgets.put("activity", widget(true, "quarter", Set.of("fifth", "quarter", "compact", "medium"),
                "tall", Set.of("short", "standard", "tall")));
        widgets.put("focus", widget(true, "medium", Set.of("quarter", "compact", "medium", "large", "full"),
                "tall", Set.of("short", "standard", "tall", "expanded")));
        widgets.put("schedule", widget(true, "quarter", Set.of("fifth", "quarter", "compact", "medium"),
                "standard", Set.of("short", "standard", "tall")));
        widgets.put("daily-brief", widget(true, "full", Set.of("compact", "large", "full"),
                "standard", Set.of("short", "standard", "tall")));
        return new SurfaceContract(true, "balanced", List.copyOf(widgets.keySet()), Map.copyOf(widgets));
    }

    private static SurfaceContract hcmContract() {
        Map<String, WidgetContract> widgets = new LinkedHashMap<>();
        widgets.put("quick-actions", widget(true, "full", Set.of("medium", "large", "full"),
                "short", Set.of("short", "standard")));
        widgets.put("people-signals", widget(true, "full", Set.of("large", "full"),
                "standard", Set.of("short", "standard", "tall")));
        widgets.put("attention", widget(true, "large", Set.of("medium", "large", "full"),
                "tall", Set.of("standard", "tall", "expanded")));
        widgets.put("profile", widget(true, "compact", Set.of("compact", "medium"),
                "standard", Set.of("short", "standard", "tall")));
        widgets.put("team", widget(true, "full", Set.of("medium", "large", "full"),
                "tall", Set.of("standard", "tall", "expanded")));
        widgets.put("operations", widget(true, "full", Set.of("large", "full"),
                "tall", Set.of("standard", "tall", "expanded")));
        return new SurfaceContract(false, "balanced", List.copyOf(widgets.keySet()), Map.copyOf(widgets));
    }

    private static SurfaceContract approvalContract() {
        Map<String, WidgetContract> widgets = new LinkedHashMap<>();
        widgets.put("decision-pulse", widget(false, "full", Set.of("full"),
                "short", Set.of("short", "standard")));
        widgets.put("focus-queue", widget(true, "large", Set.of("medium", "large", "full"),
                "tall", Set.of("standard", "tall", "expanded")));
        widgets.put("flow", widget(true, "medium", Set.of("medium", "large", "full"),
                "standard", Set.of("short", "standard", "tall")));
        widgets.put("my-requests", widget(true, "medium", Set.of("medium", "large", "full"),
                "standard", Set.of("short", "standard", "tall")));
        widgets.put("insights", widget(true, "medium", Set.of("compact", "medium", "large"),
                "standard", Set.of("short", "standard", "tall")));
        widgets.put("admin-health", widget(true, "full", Set.of("large", "full"),
                "tall", Set.of("standard", "tall", "expanded")));
        return new SurfaceContract(false, "balanced", List.copyOf(widgets.keySet()), Map.copyOf(widgets));
    }

    private static WidgetContract widget(
            boolean canHide,
            String defaultSize,
            Set<String> allowedSizes,
            String defaultHeight,
            Set<String> allowedHeights) {
        return new WidgetContract(
                canHide, defaultSize, Set.copyOf(allowedSizes),
                defaultHeight, Set.copyOf(allowedHeights));
    }

    private record SurfaceContract(
            boolean supportsAppLayout,
            String defaultPresentation,
            List<String> widgetOrder,
            Map<String, WidgetContract> widgets) {
    }

    private record WidgetContract(
            boolean canHide,
            String defaultSize,
            Set<String> allowedSizes,
            String defaultHeight,
            Set<String> allowedHeights) {
    }
}
