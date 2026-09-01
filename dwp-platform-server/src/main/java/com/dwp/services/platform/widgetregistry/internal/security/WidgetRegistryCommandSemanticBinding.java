package com.dwp.services.platform.widgetregistry.internal.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.regex.Pattern;

/** Closed extraction of payload values used to derive non-resource command targets. */
final class WidgetRegistryCommandSemanticBinding {

    private static final Pattern DEFINITION_KEY = Pattern.compile(
            "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$");
    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Set<String> CONTROL_SCOPES = Set.of(
            "CATALOG_MUTATIONS", "CATALOG_DISCOVERY", "RUNTIME_RENDER", "RUNTIME_ACTION");
    private static final Set<String> RUNTIME_TARGET_TYPES = Set.of("GLOBAL", "DEFINITION", "VERSION");
    private static final Fields EMPTY = new Fields(null, null, null, null, null);

    private WidgetRegistryCommandSemanticBinding() {
    }

    static Fields preserve(String operationId, JsonNode payload)
            throws WidgetRegistryBindingException {
        if (operationId == null || payload == null || !payload.isObject()) throw invalid();
        return switch (operationId) {
            case "createWidgetDefinition" -> new Fields(
                    required(payload, "definitionKey", 128, DEFINITION_KEY), null, null, null, null);
            case "createWidgetDefinitionVersion" -> new Fields(
                    null, required(payload, "semanticVersion", 128, SEMANTIC_VERSION), null, null, null);
            case "disableWidgetRuntimeControl" -> runtime(payload);
            default -> EMPTY;
        };
    }

    private static Fields runtime(JsonNode payload) throws WidgetRegistryBindingException {
        String scope = required(payload, "scope", 64, null);
        String targetType = required(payload, "targetType", 32, null);
        if (!CONTROL_SCOPES.contains(scope) || !RUNTIME_TARGET_TYPES.contains(targetType)
                || !payload.has("targetId")) {
            throw invalid();
        }
        JsonNode targetIdNode = payload.get("targetId");
        String targetId = targetIdNode.isNull()
                ? null
                : required(payload, "targetId", 36, UUID);
        if ("GLOBAL".equals(targetType) != (targetId == null)) throw invalid();
        return new Fields(null, null, scope, targetType, targetId);
    }

    private static String required(JsonNode payload, String field, int maximum, Pattern pattern)
            throws WidgetRegistryBindingException {
        JsonNode node = payload.get(field);
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.textValue();
        if (value.isBlank() || value.length() > maximum
                || pattern != null && !pattern.matcher(value).matches()) {
            throw invalid();
        }
        return value;
    }

    private static WidgetRegistryBindingException invalid() {
        return new WidgetRegistryBindingException(
                WidgetRegistryIngressFailure.REQUEST_BINDING_INVALID);
    }

    record Fields(
            String definitionKey,
            String normalizedSemanticVersion,
            String controlScope,
            String runtimeTargetType,
            String runtimeTargetId) {

        boolean empty() {
            return definitionKey == null
                    && normalizedSemanticVersion == null
                    && controlScope == null
                    && runtimeTargetType == null
                    && runtimeTargetId == null;
        }
    }
}
