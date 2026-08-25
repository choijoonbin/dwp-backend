package com.dwp.services.platform.home.personalization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/** Produces stable command fingerprints independent of map and JSON object insertion order. */
@Component
public class HomeCanonicalJson {
    private final ObjectMapper objectMapper;

    public HomeCanonicalJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String fingerprint(Object value) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(
                    canonicalize(objectMapper.valueToTree(value)));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint a home command.", exception);
        }
    }

    JsonNode canonicalize(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) return value;
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        ObjectNode result = objectMapper.createObjectNode();
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
        fields.forEach((key, child) -> result.set(key, canonicalize(child)));
        return result;
    }
}
