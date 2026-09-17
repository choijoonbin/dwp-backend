package com.dwp.services.platform.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class AdminMailCommandFingerprint {

    private final ObjectMapper objectMapper;

    AdminMailCommandFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String digest(Object... values) {
        try {
            List<Object> canonical = new ArrayList<>(values.length);
            for (Object value : values) canonical.add(canonical(value));
            byte[] encoded = objectMapper.writeValueAsBytes(canonical);
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint the mail administrator command", exception);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> sorted = new TreeMap<>();
            source.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof List<?> source) {
            return source.stream().map(this::canonical).toList();
        }
        if (value instanceof OffsetDateTime timestamp) {
            return timestamp.toInstant().truncatedTo(ChronoUnit.MICROS).toString();
        }
        if (value instanceof String text) return text.trim();
        return value;
    }
}
