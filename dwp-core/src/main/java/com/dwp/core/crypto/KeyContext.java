package com.dwp.core.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Trusted row context bound to content and wrapped data keys as AAD. */
public record KeyContext(
        String environment,
        String service,
        String purpose,
        long tenantId,
        String resourceType,
        String resourceId,
        String field) {

    public static final String AAD_PROFILE = "dwp-aad-v1";
    public static final int ENVELOPE_FORMAT_VERSION = 2;

    private static final Set<String> ENVIRONMENTS = Set.of("local", "dev", "qa", "prod");

    public KeyContext {
        environment = required(environment, "environment", 16);
        if (!ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("Encryption environment is not canonical.");
        }
        service = required(service, "service", 80);
        purpose = required(purpose, "purpose", 80);
        if (tenantId < 0) {
            throw new IllegalArgumentException("Encryption tenant ID cannot be negative.");
        }
        resourceType = required(resourceType, "resourceType", 80);
        resourceId = required(resourceId, "resourceId", 256);
        field = required(field, "field", 80);
    }

    public byte[] canonicalAad() {
        String[] values = {
            environment,
            service,
            purpose,
            Long.toString(tenantId),
            resourceType,
            resourceId,
            field,
            Integer.toString(ENVELOPE_FORMAT_VERSION)
        };
        StringBuilder canonical = new StringBuilder(AAD_PROFILE);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        for (String value : values) {
            canonical.append('.').append(encoder.encodeToString(
                    value.getBytes(StandardCharsets.UTF_8)));
        }
        return canonical.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Non-sensitive provider context. Resource identifiers stay only in the canonical AAD because
     * provider audit logs may retain context values in plaintext.
     */
    public Map<String, String> providerContext() {
        Map<String, String> context = new LinkedHashMap<>();
        context.put("environment", environment);
        context.put("service", service);
        context.put("purpose", purpose);
        context.put("tenantId", Long.toString(tenantId));
        context.put("resourceType", resourceType);
        context.put("formatVersion", Integer.toString(ENVELOPE_FORMAT_VERSION));
        return Map.copyOf(context);
    }

    private static String required(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Encryption " + fieldName + " is invalid.");
        }
        return value;
    }
}
