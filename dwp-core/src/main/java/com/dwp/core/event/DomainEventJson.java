package com.dwp.core.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class DomainEventJson {

    private DomainEventJson() {
    }

    static String serialize(ObjectMapper objectMapper, DomainEventEnvelope event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Domain-event serialization failed.", exception);
        }
    }

    static DomainEventEnvelope deserialize(ObjectMapper objectMapper, String payload) {
        try {
            return objectMapper.readValue(payload, DomainEventEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid domain-event envelope.", exception);
        }
    }

    static String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
