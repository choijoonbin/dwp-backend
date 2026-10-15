package com.dwp.services.people.hris.experience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;

/** Small canonical hashing boundary shared by HRM-002 evidence paths. */
final class HrisExperienceDigests {

    private HrisExperienceDigests() {
    }

    static String sha256(String value) {
        return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] canonicalBytes(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsBytes(canonical(mapper.valueToTree(value), mapper));
        } catch (Exception exception) {
            throw new IllegalStateException("HRIS experience evidence serialization failed.",
                    exception);
        }
    }

    static String canonicalSha256(ObjectMapper mapper, Object value) {
        return HexFormat.of().formatHex(digest(canonicalBytes(mapper, value)));
    }

    static String assignmentPopulationDigest(ObjectMapper mapper, String revision) {
        return canonicalSha256(mapper, revision);
    }

    static long assignmentRevision(String value) {
        String digest = sha256(value == null ? "" : value);
        return Long.parseLong(digest.substring(0, 15), 16) + 1;
    }

    static long employeeChangeRevision(String value) {
        byte[] digest = digest(value.getBytes(StandardCharsets.UTF_8));
        long result = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            result = (result << 8) | (digest[index] & 0xffL);
        }
        return result == Long.MIN_VALUE ? 1 : Math.max(1, Math.abs(result));
    }

    private static JsonNode canonical(JsonNode value, ObjectMapper mapper) {
        if (value == null || value.isNull()) return mapper.nullNode();
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            ArrayList<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(
                    name -> result.set(name, canonical(value.get(name), mapper)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item, mapper)));
            return result;
        }
        return value;
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
