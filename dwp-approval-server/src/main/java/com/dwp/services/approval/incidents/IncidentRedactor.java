package com.dwp.services.approval.incidents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class IncidentRedactor {
    private IncidentRedactor() {
    }

    static Map<String, Object> redact(Map<String, Object> input) {
        if (input == null || input.isEmpty() || input.size() > 128) {
            throw IncidentRejected.invalid("Incident diagnostic payload is invalid.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) redactValue(input, 0);
        return Map.copyOf(result);
    }

    private static Object redactValue(Object value, int depth) {
        if (depth > 8) throw IncidentRejected.invalid(
                "Incident diagnostic payload is too deeply nested.");
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 128) throw IncidentRejected.invalid(
                    "Incident diagnostic payload is too large.");
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            map.forEach((rawKey, item) -> {
                if (!(rawKey instanceof String key)
                        || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")) {
                    throw IncidentRejected.invalid("Incident diagnostic key is invalid.");
                }
                result.put(key, sensitive(key) ? "[REDACTED]" : redactValue(item, depth + 1));
            });
            return result;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 256) throw IncidentRejected.invalid(
                    "Incident diagnostic payload is too large.");
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(redactValue(item, depth + 1)));
            return List.copyOf(result);
        }
        if (value instanceof String text) {
            String bounded = text.length() > 2_000 ? text.substring(0, 2_000) : text;
            String lower = bounded.toLowerCase(Locale.ROOT);
            if (lower.contains("bearer ") || lower.contains("basic ")
                    || lower.contains("vault://") || lower.contains("-----begin private key")
                    || lower.matches(".*[a-z0-9_-]{48,}.*")) {
                return "[REDACTED]";
            }
            return bounded;
        }
        if (value == null || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Double
                || value instanceof java.math.BigDecimal) return value;
        throw IncidentRejected.invalid("Incident diagnostic value is unsupported.");
    }

    private static boolean sensitive(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("credential") || lower.contains("authorization")
                || lower.contains("cookie") || lower.contains("privatekey")
                || lower.contains("payload") || lower.equals("token")
                || lower.endsWith("token");
    }
}
