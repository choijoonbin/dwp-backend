package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class HomePreferenceService {

    public static final String WORKSPACE_HOME = "workspace-home";
    public static final String HRIS_HOME = "hris-home";

    private static final int MAX_LAYOUT_BYTES = 96 * 1024;
    private static final Set<String> PRESENTATIONS = Set.of("balanced", "expressive", "focused");
    private static final Set<String> WIDGET_SIZES = Set.of("compact", "medium", "large", "full");
    private static final Map<String, SurfaceContract> SURFACE_CONTRACTS = Map.of(
            WORKSPACE_HOME, workspaceContract(),
            HRIS_HOME, hrisContract());

    private final HomePreferenceRepository repository;
    private final ObjectMapper objectMapper;
    private final PlatformAuditService auditService;

    public HomePreferenceService(
            HomePreferenceRepository repository,
            ObjectMapper objectMapper,
            PlatformAuditService auditService) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public HomePreferenceDtos.HomePreferenceResponse get(
            Long tenantId,
            Long userId,
            String surfaceKey) {
        SurfaceContract contract = requireSurface(surfaceKey);
        return repository.findByTenantIdAndUserIdAndSurfaceKey(tenantId, userId, surfaceKey)
                .map(preference -> response(preference, contract))
                .orElseGet(() -> defaultResponse(surfaceKey, contract));
    }

    @Transactional
    public HomePreferenceDtos.HomePreferenceResponse update(
            Long tenantId,
            Long userId,
            String surfaceKey,
            String correlationId,
            HomePreferenceDtos.UpdateHomePreferenceRequest request) {
        SurfaceContract contract = requireSurface(surfaceKey);
        HomePreferenceDtos.HomeLayoutPayload normalized = normalizeLayout(
                surfaceKey,
                contract,
                request.layout());
        HomePreference preference = repository
                .findByTenantIdAndUserIdAndSurfaceKey(tenantId, userId, surfaceKey)
                .orElseGet(() -> create(tenantId, userId, surfaceKey, request.version()));
        requireVersion(preference, request.version());
        Map<String, Object> before = snapshot(preference);
        preference.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        preference.setLayoutPayload(objectMapper.valueToTree(normalized));
        HomePreference saved = repository.saveAndFlush(preference);
        auditService.success(
                tenantId,
                userId,
                "home-preference.updated",
                "HOME_PREFERENCE",
                userId + ":" + surfaceKey,
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
        SurfaceContract contract = requireSurface(surfaceKey);
        HomePreference preference = repository
                .findByTenantIdAndUserIdAndSurfaceKey(tenantId, userId, surfaceKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(preference, version);
        Map<String, Object> before = snapshot(preference);
        repository.delete(preference);
        repository.flush();
        auditService.success(
                tenantId,
                userId,
                "home-preference.reset",
                "HOME_PREFERENCE",
                userId + ":" + surfaceKey,
                correlationId,
                before,
                snapshot(null));
        return defaultResponse(surfaceKey, contract);
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
                .build();
    }

    private SurfaceContract requireSurface(String surfaceKey) {
        SurfaceContract contract = SURFACE_CONTRACTS.get(surfaceKey);
        if (contract == null) throw invalid("The personal home surface is not registered.");
        return contract;
    }

    private HomePreferenceDtos.HomeLayoutPayload normalizeLayout(
            String surfaceKey,
            SurfaceContract contract,
            HomePreferenceDtos.HomeLayoutPayload layout) {
        if (!contract.supportsAppLayout() && layout.appLayout() != null && !layout.appLayout().isNull()) {
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
            WidgetContract widgetContract = contract.widgets().get(widget.widgetKey());
            if (widgetContract == null || !unique.add(widget.widgetKey())) {
                throw invalid("The personal home layout contains an unknown or duplicate widget.");
            }
            if (!widgetContract.canHide() && !Boolean.TRUE.equals(widget.visible())) {
                throw invalid("A governed personal home widget cannot be hidden.");
            }
            String size = widget.size() == null ? widgetContract.defaultSize() : widget.size();
            if (!WIDGET_SIZES.contains(size) || !widgetContract.allowedSizes().contains(size)) {
                throw invalid("The personal home widget size is not allowed for this widget.");
            }
            requested.put(
                    widget.widgetKey(),
                    new HomePreferenceDtos.WidgetPreference(widget.widgetKey(), widget.visible(), size));
        }

        List<HomePreferenceDtos.WidgetPreference> widgets = new ArrayList<>();
        requested.values().forEach(widgets::add);
        contract.widgetOrder().forEach(widgetKey -> {
            if (requested.containsKey(widgetKey)) return;
            WidgetContract widget = contract.widgets().get(widgetKey);
            widgets.add(new HomePreferenceDtos.WidgetPreference(
                    widgetKey,
                    true,
                    widget.defaultSize()));
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

    private void validateAppLayout(JsonNode appLayout) {
        if (appLayout == null || appLayout.isNull()) return;
        if (!appLayout.isObject()
                || !appLayout.path("version").canConvertToInt()
                || appLayout.path("version").asInt() != 1
                || !appLayout.path("groups").isObject()
                || !appLayout.path("folders").isObject()) {
            throw invalid("The app layout schema is invalid.");
        }
        validateGroups(appLayout.path("groups"));
        validateFolders(appLayout.path("folders"));
        Set<String> hiddenAppIds = validateHiddenAppIds(appLayout.get("hiddenAppIds"));
        validateHiddenAppsAreNotPlaced(hiddenAppIds, appLayout.path("groups"), appLayout.path("folders"));
    }

    private void validateGroups(JsonNode groups) {
        if (groups.size() > 12) throw invalid("The app layout contains too many groups.");
        groups.fields().forEachRemaining(entry -> {
            if (entry.getKey().length() > 40
                    || !entry.getValue().isArray()
                    || entry.getValue().size() > 100) {
                throw invalid("The app layout contains an invalid group.");
            }
            entry.getValue().forEach(item -> {
                if (!item.isTextual() || item.asText().length() > 100) {
                    throw invalid("The app layout contains an invalid item identifier.");
                }
            });
        });
    }

    private void validateFolders(JsonNode folders) {
        if (folders.size() > 50) throw invalid("The app layout contains too many folders.");
        folders.fields().forEachRemaining(entry -> {
            JsonNode folder = entry.getValue();
            if (entry.getKey().length() > 100
                    || !folder.isObject()
                    || !folder.path("name").isTextual()
                    || folder.path("name").asText().length() > 80
                    || !folder.path("groupId").isTextual()
                    || folder.path("groupId").asText().length() > 40
                    || !folder.path("appIds").isArray()
                    || folder.path("appIds").size() > 50) {
                throw invalid("The app layout contains an invalid folder.");
            }
            folder.path("appIds").forEach(item -> {
                if (!item.isTextual() || item.asText().length() > 100) {
                    throw invalid("The app folder contains an invalid application identifier.");
                }
            });
        });
    }

    private Set<String> validateHiddenAppIds(JsonNode hiddenAppIds) {
        if (hiddenAppIds == null || hiddenAppIds.isMissingNode()) return Set.of();
        if (!hiddenAppIds.isArray() || hiddenAppIds.size() > 100) {
            throw invalid("The app layout contains an invalid hidden application list.");
        }
        Set<String> unique = new HashSet<>();
        hiddenAppIds.forEach(item -> {
            if (!item.isTextual()
                    || item.asText().isBlank()
                    || item.asText().length() > 100
                    || !unique.add(item.asText())) {
                throw invalid("The hidden application list contains an invalid identifier.");
            }
        });
        return Set.copyOf(unique);
    }

    private void validateHiddenAppsAreNotPlaced(
            Set<String> hiddenAppIds,
            JsonNode groups,
            JsonNode folders) {
        if (hiddenAppIds.isEmpty()) return;
        groups.forEach(items -> items.forEach(item -> {
            if (item.isTextual() && hiddenAppIds.contains(item.asText())) {
                throw invalid("A hidden application cannot remain in a launchpad group.");
            }
        }));
        folders.forEach(folder -> folder.path("appIds").forEach(item -> {
            if (item.isTextual() && hiddenAppIds.contains(item.asText())) {
                throw invalid("A hidden application cannot remain in an app folder.");
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
            HomePreferenceDtos.HomeLayoutPayload normalized = normalizeLayout(surfaceKey, contract, stored);
            return new HomePreferenceDtos.HomePreferenceResponse(
                    HomePreferenceDtos.SCHEMA_VERSION,
                    surfaceKey,
                    true,
                    normalized,
                    preference.getVersion() == null ? 0L : preference.getVersion(),
                    preference.getUpdatedAt());
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored personal home preference is invalid.",
                    exception);
        }
    }

    private HomePreferenceDtos.HomePreferenceResponse defaultResponse(
            String surfaceKey,
            SurfaceContract contract) {
        List<HomePreferenceDtos.WidgetPreference> widgets = contract.widgetOrder().stream()
                .map(widgetKey -> {
                    WidgetContract widget = contract.widgets().get(widgetKey);
                    return new HomePreferenceDtos.WidgetPreference(widgetKey, true, widget.defaultSize());
                })
                .toList();
        return new HomePreferenceDtos.HomePreferenceResponse(
                HomePreferenceDtos.SCHEMA_VERSION,
                surfaceKey,
                false,
                new HomePreferenceDtos.HomeLayoutPayload(
                        null,
                        contract.defaultPresentation(),
                        widgets),
                0L,
                null);
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
        value.put("customized", true);
        value.put("surfaceKey", preference.getSurfaceKey());
        value.put("schemaVersion", preference.getSchemaVersion());
        value.put("layout", preference.getLayoutPayload());
        value.put("version", preference.getVersion() == null ? 0L : preference.getVersion());
        return value;
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static SurfaceContract workspaceContract() {
        Map<String, WidgetContract> widgets = new LinkedHashMap<>();
        widgets.put("announcements", widget(false, "full", "full"));
        widgets.put("daily-brief", widget(true, "full", "large", "full"));
        widgets.put("focus", widget(true, "medium", "medium", "large", "full"));
        widgets.put("schedule", widget(true, "compact", "compact", "medium"));
        widgets.put("activity", widget(true, "compact", "compact", "medium"));
        return new SurfaceContract(true, "balanced", List.copyOf(widgets.keySet()), Map.copyOf(widgets));
    }

    private static SurfaceContract hrisContract() {
        Map<String, WidgetContract> widgets = new LinkedHashMap<>();
        widgets.put("quick-actions", widget(true, "full", "medium", "large", "full"));
        widgets.put("people-signals", widget(true, "full", "large", "full"));
        widgets.put("attention", widget(true, "large", "medium", "large", "full"));
        widgets.put("profile", widget(true, "compact", "compact", "medium"));
        widgets.put("team", widget(true, "full", "medium", "large", "full"));
        widgets.put("operations", widget(true, "full", "large", "full"));
        return new SurfaceContract(false, "balanced", List.copyOf(widgets.keySet()), Map.copyOf(widgets));
    }

    private static WidgetContract widget(
            boolean canHide,
            String defaultSize,
            String... allowedSizes) {
        return new WidgetContract(canHide, defaultSize, Set.of(allowedSizes));
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
            Set<String> allowedSizes) {
    }
}
