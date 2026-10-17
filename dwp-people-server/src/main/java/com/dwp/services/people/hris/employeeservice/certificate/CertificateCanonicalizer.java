package com.dwp.services.people.hris.employeeservice.certificate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;

/** Stable request, scope, event, and immutable metadata digests. */
@Component
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class CertificateCanonicalizer {

    private final ObjectMapper mapper;

    CertificateCanonicalizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String requestDigest(Object value) {
        return sha256(canonicalJson(value).getBytes(StandardCharsets.UTF_8));
    }

    String digestText(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    long positiveRevision(String value) {
        byte[] digest = digest(value.getBytes(StandardCharsets.UTF_8));
        long result = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            result = (result << 8) | (digest[index] & 0xffL);
        }
        return result == Long.MIN_VALUE ? 1 : Math.max(1, Math.abs(result));
    }

    String canonicalJson(Object value) {
        try {
            return mapper.writeValueAsString(canonical(mapper.valueToTree(value)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Certificate canonical serialization failed.", exception);
        }
    }

    String canonicalCode(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.trim())
                || !value.matches("[A-Z][A-Z0-9_]{1," + (maximum - 1) + "}")) {
            throw CertificateException.invalid(field + " is invalid.");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    private JsonNode canonical(JsonNode value) {
        if (value == null || value.isNull()) return mapper.nullNode();
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            ArrayList<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name ->
                    result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private String sha256(byte[] value) {
        return HexFormat.of().formatHex(digest(value));
    }

    private byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
