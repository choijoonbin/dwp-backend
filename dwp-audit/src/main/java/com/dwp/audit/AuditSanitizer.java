package com.dwp.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Removes credentials and bounds arbitrary audit context before persistence. */
public final class AuditSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_DEPTH = 6;
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_STRING_LENGTH = 4_000;
    private static final Set<String> SENSITIVE_PARTS = Set.of(
            "authorization", "password", "passwd", "secret", "token", "cookie",
            "credential", "privatekey", "clientsecret", "accesstoken", "refreshtoken",
            "sessiontoken", "apikey");

    private AuditSanitizer() {
    }

    public static Map<String, Object> sanitize(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Map.of();
        Object sanitized = sanitizeValue(value, 0);
        if (sanitized instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return Collections.unmodifiableMap(result);
        }
        return Map.of();
    }

    private static Object sanitizeValue(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (depth >= MAX_DEPTH) return "[TRUNCATED]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_ENTRIES) {
                    sanitized.put("_truncated", true);
                    break;
                }
                String key = truncate(String.valueOf(entry.getKey()));
                sanitized.put(key, sensitive(key) ? REDACTED : sanitizeValue(entry.getValue(), depth + 1));
            }
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= MAX_ENTRIES) {
                    sanitized.add("[TRUNCATED]");
                    break;
                }
                sanitized.add(sanitizeValue(item, depth + 1));
            }
            return sanitized;
        }
        return truncate(String.valueOf(value));
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_PARTS.stream().anyMatch(normalized::contains);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_STRING_LENGTH
                ? value
                : value.substring(0, MAX_STRING_LENGTH);
    }
}
