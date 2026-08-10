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

    private static final int MAX_APP_LAYOUT_BYTES = 65_536;
    private static final Set<String> WIDGET_KEYS = Set.of(
            "announcements", "daily-brief", "focus", "schedule", "activity");

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
    public HomePreferenceDtos.HomePreferenceResponse get(Long tenantId, Long userId) {
        return repository.findByTenantIdAndUserId(tenantId, userId)
                .map(this::response)
                .orElseGet(this::defaultResponse);
    }

    @Transactional
    public HomePreferenceDtos.HomePreferenceResponse update(
            Long tenantId,
            Long userId,
            String correlationId,
            HomePreferenceDtos.UpdateHomePreferenceRequest request) {
        validateLayout(request.layout());
        HomePreference preference = repository.findByTenantIdAndUserId(tenantId, userId)
                .orElseGet(() -> create(tenantId, userId, request.version()));
        requireVersion(preference, request.version());
        Map<String, Object> before = snapshot(preference);
        preference.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        preference.setLayoutPayload(objectMapper.valueToTree(request.layout()));
        HomePreference saved = repository.saveAndFlush(preference);
        auditService.success(
                tenantId,
                userId,
                "home-preference.updated",
                "HOME_PREFERENCE",
                userId.toString(),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Transactional
    public HomePreferenceDtos.HomePreferenceResponse reset(
            Long tenantId,
            Long userId,
            String correlationId,
            Long version) {
        HomePreference preference = repository.findByTenantIdAndUserId(tenantId, userId)
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
                userId.toString(),
                correlationId,
                before,
                snapshot(null));
        return defaultResponse();
    }

    private HomePreference create(Long tenantId, Long userId, Long requestedVersion) {
        if (requestedVersion == null || requestedVersion != 0L) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        return HomePreference.builder()
                .tenantId(tenantId)
                .userId(userId)
                .schemaVersion(HomePreferenceDtos.SCHEMA_VERSION)
                .build();
    }

    private void validateLayout(HomePreferenceDtos.HomeLayoutPayload layout) {
        List<HomePreferenceDtos.WidgetPreference> widgets = layout.widgets();
        Set<String> unique = new HashSet<>();
        for (HomePreferenceDtos.WidgetPreference widget : widgets) {
            if (!WIDGET_KEYS.contains(widget.widgetKey()) || !unique.add(widget.widgetKey())) {
                throw invalid("The home widget layout contains an unknown or duplicate widget.");
            }
            if ("announcements".equals(widget.widgetKey()) && !Boolean.TRUE.equals(widget.visible())) {
                throw invalid("The governed announcements widget cannot be hidden.");
            }
        }
        if (!unique.equals(WIDGET_KEYS)) {
            throw invalid("The home widget layout must include every registered widget.");
        }
        validateAppLayout(layout.appLayout());
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
        if (serializedSize(appLayout) > MAX_APP_LAYOUT_BYTES) {
            throw invalid("The app layout exceeds the configured size limit.");
        }
        validateGroups(appLayout.path("groups"));
        validateFolders(appLayout.path("folders"));
    }

    private void validateGroups(JsonNode groups) {
        if (groups.size() > 12) throw invalid("The app layout contains too many groups.");
        groups.fields().forEachRemaining(entry -> {
            if (entry.getKey().length() > 40 || !entry.getValue().isArray()
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

    private int serializedSize(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException exception) {
            throw invalid("The app layout could not be processed.");
        }
    }

    private HomePreferenceDtos.HomePreferenceResponse response(HomePreference preference) {
        try {
            HomePreferenceDtos.HomeLayoutPayload layout = objectMapper.treeToValue(
                    preference.getLayoutPayload(), HomePreferenceDtos.HomeLayoutPayload.class);
            return new HomePreferenceDtos.HomePreferenceResponse(
                    preference.getSchemaVersion(),
                    true,
                    layout,
                    preference.getVersion() == null ? 0L : preference.getVersion(),
                    preference.getUpdatedAt());
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored home preference is invalid.",
                    exception);
        }
    }

    private HomePreferenceDtos.HomePreferenceResponse defaultResponse() {
        List<HomePreferenceDtos.WidgetPreference> widgets = new ArrayList<>();
        widgets.add(new HomePreferenceDtos.WidgetPreference("announcements", true));
        widgets.add(new HomePreferenceDtos.WidgetPreference("daily-brief", true));
        widgets.add(new HomePreferenceDtos.WidgetPreference("focus", true));
        widgets.add(new HomePreferenceDtos.WidgetPreference("schedule", true));
        widgets.add(new HomePreferenceDtos.WidgetPreference("activity", true));
        return new HomePreferenceDtos.HomePreferenceResponse(
                HomePreferenceDtos.SCHEMA_VERSION,
                false,
                new HomePreferenceDtos.HomeLayoutPayload(null, List.copyOf(widgets)),
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
        value.put("schemaVersion", preference.getSchemaVersion());
        value.put("layout", preference.getLayoutPayload());
        value.put("version", preference.getVersion() == null ? 0L : preference.getVersion());
        return value;
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
