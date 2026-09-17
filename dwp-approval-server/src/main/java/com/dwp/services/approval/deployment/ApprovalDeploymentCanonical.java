package com.dwp.services.approval.deployment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ApprovalDeploymentCanonical {
    private final ObjectMapper mapper;

    ApprovalDeploymentCanonical(ObjectMapper mapper) {
        this.mapper = canonicalMapper(mapper);
    }

    private ObjectMapper canonicalMapper(ObjectMapper source) {
        ObjectMapper canonical = source.copy();
        canonical.setConfig(canonical.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
        return canonical;
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("The deployment value cannot be serialized.", exception);
        }
    }

    <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored deployment data is invalid.", exception);
        }
    }

    String sha256(Object value) {
        return sha256Text(json(value));
    }

    static String sha256Text(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
