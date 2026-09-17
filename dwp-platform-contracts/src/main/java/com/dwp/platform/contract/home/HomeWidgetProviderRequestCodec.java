package com.dwp.platform.contract.home;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Strict decoder that rejects unknown and recipient-spoofing fields before data binding. */
public final class HomeWidgetProviderRequestCodec {

    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "widgets");
    private static final Set<String> WIDGET_FIELDS = Set.of(
            "instanceId", "definitionKey", "definitionVersion", "definitionManifestHash",
            "rendererBindingRevision", "configuration", "itemLimit");
    private static final Set<String> FORBIDDEN_CONFIGURATION_FRAGMENTS = Set.of(
            "tenant", "user", "recipient", "authority", "permission", "role", "group",
            "person", "token", "secret", "credential", "password", "cookie", "html",
            "script", "authorization");
    private static final int MAX_DEPTH = 6;
    private static final int MAX_NODES = 1_000;
    private static final int MAX_BYTES = 65_536;

    private HomeWidgetProviderRequestCodec() {
    }

    public static HomeWidgetProviderContract.BatchRequest decode(
            JsonNode raw,
            ObjectMapper objectMapper) {
        if (raw == null || !raw.isObject()
                || raw.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            bad("HOME_PROVIDER_BATCH_INVALID", "The Home provider batch is invalid or too large.");
        }
        exactFields(raw, ROOT_FIELDS);
        JsonNode widgets = raw.get("widgets");
        if (widgets == null || !widgets.isArray()
                || widgets.isEmpty()
                || widgets.size() > HomeWidgetProviderContract.MAX_WIDGETS_PER_BATCH) {
            bad("HOME_PROVIDER_BATCH_BUDGET_INVALID", "The Home provider batch budget is invalid.");
        }
        Set<UUID> instances = new HashSet<>();
        for (JsonNode widget : widgets) {
            if (!widget.isObject()) bad("HOME_PROVIDER_WIDGET_INVALID", "A widget request is invalid.");
            exactFields(widget, WIDGET_FIELDS);
            requireText(widget, "definitionKey", "[a-z][a-z0-9.-]{2,119}");
            requireText(widget, "definitionVersion", "[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?");
            requireText(widget, "definitionManifestHash", "[0-9a-f]{64}");
            requireText(widget, "rendererBindingRevision", "[A-Za-z0-9._:@+-]{1,160}");
            try {
                if (!instances.add(UUID.fromString(widget.path("instanceId").asText()))) {
                    bad("HOME_PROVIDER_INSTANCE_DUPLICATED", "Widget instance identifiers must be unique.");
                }
            } catch (IllegalArgumentException exception) {
                bad("HOME_PROVIDER_INSTANCE_INVALID", "A canonical widget instance identifier is required.");
            }
            int itemLimit = widget.path("itemLimit").asInt(-1);
            if (!widget.path("itemLimit").isIntegralNumber() || itemLimit < 1
                    || itemLimit > HomeWidgetProviderContract.MAX_ITEM_LIMIT) {
                bad("HOME_PROVIDER_ITEM_BUDGET_INVALID", "The widget item budget is invalid.");
            }
            JsonNode configuration = widget.get("configuration");
            if (configuration == null || !configuration.isObject()) {
                bad("HOME_PROVIDER_CONFIGURATION_INVALID", "Widget configuration must be an object.");
            }
            inspect(configuration, 0, new int[]{0});
        }
        try {
            HomeWidgetProviderContract.BatchRequest request = objectMapper.treeToValue(
                    raw, HomeWidgetProviderContract.BatchRequest.class);
            if (request.schemaVersion() != HomeWidgetProviderContract.SCHEMA_VERSION) {
                bad("HOME_PROVIDER_SCHEMA_UNSUPPORTED", "The Home provider schema is unsupported.");
            }
            return request;
        } catch (JsonProcessingException exception) {
            throw new HomeWidgetProviderRequestException(
                    HomeWidgetProviderRequestException.Kind.BAD_REQUEST,
                    "HOME_PROVIDER_BATCH_INVALID",
                    "The Home provider batch cannot be decoded.");
        }
    }

    private static void exactFields(JsonNode object, Set<String> allowed) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!allowed.contains(field)) {
                bad("HOME_PROVIDER_FIELD_REJECTED",
                        "Unknown or recipient-spoofing Home provider fields are rejected.");
            }
        }
        if (!object.fieldNames().hasNext()) {
            bad("HOME_PROVIDER_BATCH_INVALID", "The Home provider object cannot be empty.");
        }
    }

    private static void requireText(JsonNode object, String field, String pattern) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || !value.asText().matches(pattern)) {
            bad("HOME_PROVIDER_WIDGET_PIN_INVALID", "The widget definition pins are invalid.");
        }
    }

    private static void inspect(JsonNode node, int depth, int[] count) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_NODES) {
            bad("HOME_PROVIDER_CONFIGURATION_BUDGET_EXCEEDED",
                    "Widget configuration complexity exceeded its budget.");
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (FORBIDDEN_CONFIGURATION_FRAGMENTS.stream().anyMatch(key::contains)) {
                    bad("HOME_PROVIDER_CONFIGURATION_AUTHORITY_REJECTED",
                            "Widget configuration cannot carry identity or authority.");
                }
                inspect(entry.getValue(), depth + 1, count);
            });
        } else if (node.isArray()) {
            node.forEach(child -> inspect(child, depth + 1, count));
        } else if (node.isTextual() && node.asText().length() > 1_024) {
            bad("HOME_PROVIDER_CONFIGURATION_BUDGET_EXCEEDED",
                    "Widget configuration text exceeded its budget.");
        }
    }

    private static void bad(String reasonCode, String message) {
        throw new HomeWidgetProviderRequestException(
                HomeWidgetProviderRequestException.Kind.BAD_REQUEST, reasonCode, message);
    }
}
