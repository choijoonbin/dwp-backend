package com.dwp.core.provisioning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Stable command envelope shared by the provider control plane and tenant services.
 * Payloads are hashed as recursively key-sorted JSON so retries remain portable
 * across HTTP clients and Jackson configuration changes.
 */
public final class ProviderTenantCommand {

    private ProviderTenantCommand() {
    }

    public record Request(
            UUID commandId,
            String commandType,
            long expectedRevision,
            long targetRevision,
            String payloadSha256,
            JsonNode payload) {
    }

    public record Receipt(
            UUID commandId,
            UUID providerTenantId,
            String commandType,
            long expectedRevision,
            long targetRevision,
            String payloadSha256,
            JsonNode result,
            Instant appliedAt,
            boolean replayed) {
    }

    public static String payloadSha256(ObjectMapper objectMapper, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("A provider tenant command payload must be a JSON object.");
        }
        try {
            byte[] canonical = objectMapper.writeValueAsString(canonicalize(objectMapper, payload))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not hash the provider tenant command payload.", exception);
        }
    }

    private static JsonNode canonicalize(ObjectMapper objectMapper, JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = objectMapper.createObjectNode();
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, value) -> object.set(key, canonicalize(objectMapper, value)));
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonicalize(objectMapper, value)));
            return array;
        }
        return node.deepCopy();
    }
}
