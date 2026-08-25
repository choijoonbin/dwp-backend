package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class HomeWidgetConfigurationPolicy {
    private static final int MAX_BYTES = 4_096;
    private static final Map<String, Contract> CONTRACTS = contracts();

    private final ObjectMapper objectMapper;

    public HomeWidgetConfigurationPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(
            HomePreferenceDtos.HomeLayoutPayload layout,
            String widgetKey,
            HomeViewDtos.WidgetConfigurationPayload configuration) {
        boolean exists = layout.widgets().stream()
                .anyMatch(widget -> widget.widgetKey().equals(widgetKey));
        Contract contract = CONTRACTS.get(widgetKey);
        if (!exists || contract == null || configuration == null
                || serializedSize(configuration) > MAX_BYTES
                || !contract.sourceKey().equals(configuration.sourceKey())
                || configuration.fieldKeys() == null
                || configuration.fieldKeys().isEmpty()
                || configuration.fieldKeys().size() > 8
                || configuration.fieldKeys().stream().anyMatch(java.util.Objects::isNull)
                || !contract.filterPresets().contains(configuration.filterPreset())) {
            throw invalid("The widget configuration does not match its registry contract.");
        }
        Set<String> uniqueFields = new HashSet<>();
        for (String field : configuration.fieldKeys()) {
            if (field == null || !contract.fieldKeys().contains(field)
                    || !uniqueFields.add(field)) {
                throw invalid("The widget configuration contains an invalid field selection.");
            }
        }
        Integer itemLimit = configuration.itemLimit();
        if (itemLimit != null && (itemLimit < 1 || itemLimit > 20)) {
            throw invalid("Widget itemLimit must be between one and twenty.");
        }
    }

    public HomeViewDtos.WidgetConfigurationPayload decode(JsonNode value) {
        try {
            return objectMapper.treeToValue(value, HomeViewDtos.WidgetConfigurationPayload.class);
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored widget configuration is invalid.",
                    exception);
        }
    }

    private int serializedSize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (JsonProcessingException exception) {
            throw invalid("The widget configuration is not valid JSON.");
        }
    }

    private static Map<String, Contract> contracts() {
        Map<String, Contract> values = new LinkedHashMap<>();
        values.put("activity", config("ACTIVITY",
                Set.of("eventType", "title", "occurredAt", "actor"),
                Set.of("RECENT", "MY_ACTIVITY")));
        values.put("focus", config("WORK",
                Set.of("title", "status", "priority", "dueAt"),
                Set.of("ASSIGNED_TO_ME", "DUE_SOON", "HIGH_PRIORITY")));
        values.put("schedule", config("CALENDAR",
                Set.of("title", "startAt", "endAt", "location"),
                Set.of("TODAY", "NEXT_7_DAYS")));
        values.put("daily-brief", config("RECOMMENDATION",
                Set.of("title", "reason", "action"),
                Set.of("RECOMMENDED", "NEXT_ACTIONS")));
        values.put("quick-actions", config("HCM",
                Set.of("label", "route"), Set.of("FREQUENT", "ROLE_DEFAULT")));
        values.put("people-signals", config("PEOPLE",
                Set.of("label", "value", "trend"), Set.of("MY_SCOPE", "TEAM")));
        values.put("attention", config("PEOPLE",
                Set.of("title", "status", "dueAt"), Set.of("MY_SCOPE", "DUE_SOON")));
        values.put("profile", config("PEOPLE",
                Set.of("label", "value"), Set.of("MY_PROFILE")));
        values.put("team", config("PEOPLE",
                Set.of("name", "status", "role"), Set.of("DIRECT_REPORTS", "MY_TEAM")));
        values.put("operations", config("HCM",
                Set.of("title", "status", "updatedAt"), Set.of("OPEN", "RECENT")));
        values.put("decision-pulse", config("APPROVAL",
                Set.of("label", "value", "trend"), Set.of("MY_SCOPE")));
        values.put("focus-queue", config("APPROVAL",
                Set.of("title", "status", "dueAt", "priority"),
                Set.of("ASSIGNED_TO_ME", "DUE_SOON")));
        values.put("flow", config("APPROVAL",
                Set.of("stage", "count", "duration"), Set.of("ACTIVE", "RECENT")));
        values.put("my-requests", config("APPROVAL",
                Set.of("title", "status", "updatedAt"), Set.of("MINE", "OPEN")));
        values.put("insights", config("APPROVAL",
                Set.of("label", "value", "trend"), Set.of("MY_SCOPE", "TEAM")));
        values.put("admin-health", config("APPROVAL",
                Set.of("label", "status", "value"), Set.of("ACTIVE", "EXCEPTIONS")));
        return Map.copyOf(values);
    }

    private static Contract config(
            String sourceKey, Set<String> fieldKeys, Set<String> filterPresets) {
        return new Contract(sourceKey, Set.copyOf(fieldKeys), Set.copyOf(filterPresets));
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record Contract(
            String sourceKey,
            Set<String> fieldKeys,
            Set<String> filterPresets) {
    }
}
