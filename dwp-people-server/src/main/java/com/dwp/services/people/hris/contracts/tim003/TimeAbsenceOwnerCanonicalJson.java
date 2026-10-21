package com.dwp.services.people.hris.contracts.tim003;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Canonical JSON for the closed string, integer, boolean, null, object and array domain. */
@Component
final class TimeAbsenceOwnerCanonicalJson {

    private final ObjectMapper mapper;

    TimeAbsenceOwnerCanonicalJson(ObjectMapper mapper) {
        this.mapper = mapper.copy();
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    String json(Object value) {
        try {
            return mapper.writeValueAsString(sort(mapper.valueToTree(value)));
        } catch (JsonProcessingException exception) {
            throw TimeAbsenceOwnerContract.Failure.contractInvalid();
        }
    }

    String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Structural JWS guard only; Time later verifies RSA trust and the signature bytes. */
    boolean validDigestJws(
            String compact,
            String expectedType,
            String expectedKeyId,
            String expectedDigest) {
        try {
            String[] segments = compact.split("\\.", -1);
            if (segments.length != 3) return false;
            byte[] headerBytes = decodeCanonical(segments[0]);
            byte[] payloadBytes = decodeCanonical(segments[1]);
            byte[] signatureBytes = decodeCanonical(segments[2]);
            if (headerBytes == null || payloadBytes == null || signatureBytes == null
                    || signatureBytes.length < 256) {
                return false;
            }
            JsonNode header = mapper.readTree(headerBytes);
            if (header == null || !header.isObject() || header.size() != 3
                    || !"RS256".equals(header.path("alg").textValue())
                    || !expectedType.equals(header.path("typ").textValue())
                    || !expectedKeyId.equals(header.path("kid").textValue())) {
                return false;
            }
            return MessageDigest.isEqual(
                    expectedDigest.getBytes(StandardCharsets.US_ASCII), payloadBytes);
        } catch (RuntimeException | IOException exception) {
            return false;
        }
    }

    private static byte[] decodeCanonical(String segment) {
        if (segment.isEmpty() || segment.indexOf('=') >= 0
                || !segment.matches("[A-Za-z0-9_-]+")) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(segment);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
                    .equals(segment) ? decoded : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private JsonNode sort(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, child) -> result.set(name, sort(child)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(child -> result.add(sort(child)));
            return result;
        }
        return value;
    }
}
