package com.dwp.services.approval.auditrecords;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ApprovalAuditRedactor {
    private static final Set<String> METADATA_KEYS = Set.of(
            "decision", "delegated", "reasonCode", "status", "stepKey",
            "stepName", "stepSequence", "operation", "commandMode");
    private static final List<String> SECRET_MARKERS = List.of(
            "password", "secret", "credential", "token", "privatekey",
            "private_key", "authorization", "signature", "rawpayload", "raw_payload");

    private final ObjectMapper mapper;

    ApprovalAuditRedactor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, Object> redact(
            Map<String, Object> source,
            ApprovalAuditModels.AccessLevel level) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Object redacted = redactValue(source, level, true);
        return mapper.convertValue(redacted, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    }

    String actorIdentifier(String actorId, ApprovalAuditModels.AccessLevel level) {
        if (actorId == null || actorId.isBlank() || level == ApprovalAuditModels.AccessLevel.METADATA) {
            return null;
        }
        if (level == ApprovalAuditModels.AccessLevel.AUDITOR) {
            return actorId;
        }
        return "pseudonym:" + sha256(actorId).substring(0, 16);
    }

    String evidenceSha256(ApprovalAuditModels.EventProjection event) {
        try {
            return sha256(mapper.writeValueAsString(event));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("The audit evidence cannot be canonicalized.", exception);
        }
    }

    private Object redactValue(
            Object value,
            ApprovalAuditModels.AccessLevel level,
            boolean root) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> output = new LinkedHashMap<>();
            map.forEach((keyObject, nested) -> {
                String key = String.valueOf(keyObject);
                String normalized = key.toLowerCase(Locale.ROOT).replace("-", "");
                if (SECRET_MARKERS.stream().anyMatch(normalized::contains)) {
                    output.put(key, "[REDACTED]");
                } else if (level != ApprovalAuditModels.AccessLevel.METADATA
                        || !root || METADATA_KEYS.contains(key)) {
                    output.put(key, redactValue(nested, level, false));
                }
            });
            return Map.copyOf(output);
        }
        if (value instanceof List<?> list) {
            if (level == ApprovalAuditModels.AccessLevel.METADATA) {
                return List.of();
            }
            return list.stream().map(item -> redactValue(item, level, false)).toList();
        }
        return value;
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
