package com.dwp.services.approval.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class ApprovalPepProjectionJson {

    private ApprovalPepProjectionJson() {
    }

    static ObjectNode readProjection(ObjectMapper objectMapper, String resource) {
        try (InputStream input = ApprovalPilotPepRegistry.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Generated Approval Pilot PEP is absent.");
            }
            JsonNode value = objectMapper.readTree(input);
            if (value instanceof ObjectNode object) return object;
            throw new IllegalStateException("Generated Approval Pilot PEP must be an object.");
        } catch (IOException exception) {
            throw new IllegalStateException("Generated Approval Pilot PEP cannot be read.", exception);
        }
    }

    static Map<String, JsonNode> index(ObjectNode root, String field, String keyField) {
        return indexArray(requiredArray(root, field), keyField, field);
    }

    static Map<String, JsonNode> indexArray(JsonNode values, String keyField, String label) {
        require(values.isArray(), label + " must be an array");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String key = value.path(keyField).asText();
            require(!key.isBlank() && result.putIfAbsent(key, value) == null,
                    label + ": duplicate or empty " + keyField);
        }
        return Map.copyOf(result);
    }

    static JsonNode descriptor(Map<String, JsonNode> values, String key, String label) {
        JsonNode value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Unknown Approval " + label + ' ' + key);
        }
        return value;
    }

    static Set<String> textValues(JsonNode value) {
        require(value.isArray(), "Expected a generated string array");
        Set<String> result = new LinkedHashSet<>();
        value.forEach(item -> require(item.isTextual() && result.add(item.asText()),
                "Generated string array contains an invalid or duplicate value"));
        return Set.copyOf(result);
    }

    static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isInt() ? value.intValue() : null;
    }

    static Boolean booleanOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    static ArrayNode requiredArray(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value instanceof ArrayNode, field + " must be an array");
        return (ArrayNode) value;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
