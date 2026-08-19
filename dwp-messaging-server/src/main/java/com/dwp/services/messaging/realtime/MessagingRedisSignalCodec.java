package com.dwp.services.messaging.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public final class MessagingRedisSignalCodec {

    private static final Set<String> ALLOWED_KEYS =
            Set.of("tenantId", "conversationId", "eventSequence");
    private static final int MAXIMUM_BYTES = 1_024;

    private final ObjectMapper objectMapper;

    public MessagingRedisSignalCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(MessagingRealtimeSignal signal) {
        try {
            String payload = objectMapper.writeValueAsString(signal);
            if (payload.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("Messaging realtime signal is too large.");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode messaging realtime signal.", exception);
        }
    }

    public MessagingRealtimeSignal decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAXIMUM_BYTES) {
            throw new IllegalArgumentException("Messaging realtime signal size is invalid.");
        }
        try {
            JsonNode tree = objectMapper.readTree(payload);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException("Messaging realtime signal must be an object.");
            }
            tree.properties().forEach(entry -> {
                if (!ALLOWED_KEYS.contains(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Messaging realtime signal contains a forbidden field.");
                }
            });
            JsonNode sequence = tree.get("eventSequence");
            if (sequence == null || !sequence.isTextual()) {
                throw new IllegalArgumentException(
                        "Messaging realtime signal sequence must be a JSON decimal string.");
            }
            return objectMapper.treeToValue(tree, MessagingRealtimeSignal.class);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Messaging realtime signal is malformed.", exception);
        }
    }
}
