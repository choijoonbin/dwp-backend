package com.dwp.services.people.hr.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Component
public class AssignmentProposalCanonicalizer {

    static final Set<String> ALLOWED_CHANGE_KEYS = Set.of(
            "organizationId", "jobProfileKey", "locationKey", "managerAssignmentId",
            "businessTitle", "workerHours", "fullTimeEquivalent");
    private static final Set<String> UUID_KEYS = Set.of(
            "organizationId", "managerAssignmentId");
    private static final Set<String> REFERENCE_KEY_FIELDS = Set.of(
            "jobProfileKey", "locationKey");
    private static final Set<String> DECIMAL_KEYS = Set.of(
            "workerHours", "fullTimeEquivalent");

    private final ObjectMapper objectMapper;

    public AssignmentProposalCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> changes(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            throw invalid("At least one assignment field change is required.");
        }
        TreeMap<String, Object> normalized = new TreeMap<>();
        input.forEach((key, value) -> {
            if (!ALLOWED_CHANGE_KEYS.contains(key)) {
                throw invalid("Unsupported assignment change field: " + key);
            }
            if (value == null) {
                throw invalid("Assignment change values cannot be null: " + key);
            }
            if (UUID_KEYS.contains(key)) {
                normalized.put(key, uuid(value, key).toString());
            } else if (REFERENCE_KEY_FIELDS.contains(key)) {
                if (!(value instanceof String text) || text.isBlank()
                        || text.length() > 100
                        || !text.equals(text.trim())
                        || !text.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
                    throw invalid(key + " must be a canonical workforce reference key.");
                }
                normalized.put(key, text.toUpperCase(Locale.ROOT));
            } else if (DECIMAL_KEYS.contains(key)) {
                normalized.put(key, decimal(value, key).stripTrailingZeros());
            } else if ("businessTitle".equals(key)) {
                if (!(value instanceof String title) || title.isBlank()
                        || title.trim().length() > 240) {
                    throw invalid("businessTitle must be between 1 and 240 characters.");
                }
                normalized.put(key, title.trim());
            }
        });
        return Map.copyOf(new LinkedHashMap<>(normalized));
    }

    public String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(canonical(objectMapper.valueToTree(value)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Assignment proposal serialization failed.", exception);
        }
    }

    public String sha256(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value == null || value.isNull()) return objectMapper.nullNode();
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private UUID uuid(Object value, String key) {
        try {
            if (value instanceof UUID uuid) return uuid;
            if (value instanceof String text && text.equals(text.trim())) {
                return UUID.fromString(text.toLowerCase(Locale.ROOT));
            }
        } catch (IllegalArgumentException ignored) {
            // Converted below to a stable public validation error.
        }
        throw invalid(key + " must be a canonical UUID.");
    }

    private BigDecimal decimal(Object value, String key) {
        try {
            if (value instanceof BigDecimal decimal) return decimal;
            if (value instanceof Number number) return new BigDecimal(number.toString());
            if (value instanceof String text && text.equals(text.trim())) {
                return new BigDecimal(text);
            }
        } catch (NumberFormatException ignored) {
            // Converted below to a stable public validation error.
        }
        throw invalid(key + " must be a decimal number.");
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
