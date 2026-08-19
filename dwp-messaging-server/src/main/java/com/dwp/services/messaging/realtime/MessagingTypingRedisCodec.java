package com.dwp.services.messaging.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public final class MessagingTypingRedisCodec {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "signalId", "tenantId", "conversationId", "userId",
            "started", "changedAt", "expiresAt");
    private static final int MAXIMUM_BYTES = 1_024;

    private final ObjectMapper objectMapper;

    public MessagingTypingRedisCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(MessagingTypingSignal signal) {
        try {
            String payload = objectMapper.writeValueAsString(signal);
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("Messaging typing signal is too large.");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode messaging typing signal.", exception);
        }
    }

    public MessagingTypingSignal decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("Messaging typing signal size is invalid.");
        }
        try {
            JsonNode tree = objectMapper.readTree(payload);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException("Messaging typing signal must be an object.");
            }
            tree.properties().forEach(entry -> {
                if (!ALLOWED_KEYS.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Messaging typing signal contains a forbidden field.");
                }
            });
            return objectMapper.treeToValue(tree, MessagingTypingSignal.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Messaging typing signal is malformed.", exception);
        }
    }
}
