package com.dwp.services.auth.tenantsettings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Canonical JSON projection and hashing for governed tenant setting snapshots. */
final class TenantSettingsJsonSupport {

    private final ObjectMapper objectMapper;

    TenantSettingsJsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> objectMap(JsonNode value) {
        return objectMapper.convertValue(value, Map.class);
    }

    String digest(JsonNode value) {
        try {
            JsonNode canonical = canonical(value);
            byte[] bytes = objectMapper.writeValueAsString(canonical)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Tenant setting state hashing failed.", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            var result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }
}
