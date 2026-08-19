package com.dwp.services.notification.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.services.notification.api.NotificationVersionCodec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
public final class NotificationRedisSignalCodec {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "tenantId", "userId", "changeVersion", "counterVersion", "changedIds");
    private static final int MAXIMUM_BYTES = 8 * 1024;

    private final ObjectMapper objectMapper;

    public NotificationRedisSignalCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(NotificationRealtimeEnvelope envelope) {
        try {
            String payload = objectMapper.writeValueAsString(envelope);
            if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("Notification realtime signal is too large.");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode notification realtime signal.", exception);
        }
    }

    public NotificationRealtimeEnvelope decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("Notification realtime signal size is invalid.");
        }
        try {
            JsonNode tree = objectMapper.readTree(payload);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException("Notification realtime signal must be an object.");
            }
            tree.properties().forEach(entry -> {
                if (!ALLOWED_KEYS.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Notification realtime signal contains a forbidden field.");
                }
            });
            requireDecimalText(tree, "changeVersion");
            requireDecimalText(tree, "counterVersion");
            return objectMapper.treeToValue(tree, NotificationRealtimeEnvelope.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Notification realtime signal is malformed.", exception);
        }
    }

    private void requireDecimalText(JsonNode tree, String field) {
        JsonNode value = tree.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a JSON decimal string.");
        }
        NotificationVersionCodec.nonNegative(value.textValue(), field);
    }
}
