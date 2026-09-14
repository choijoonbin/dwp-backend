package com.dwp.services.auth.approvalsignatures;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;

public final class SignatureAuthorityJson {
    private final ObjectMapper mapper;
    public SignatureAuthorityJson(ObjectMapper source) {
        mapper = source.copy().findAndRegisterModules().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                .maxStringLength(SignatureAuthorityProtocol.TOKEN_LIMIT).maxNumberLength(32).build());
    }
    public JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > SignatureAuthorityProtocol.BODY_LIMIT) throw denied();
        try { return mapper.readTree(bytes); } catch (Exception malformed) { throw denied(); }
    }
    public byte[] bytes(Object value) {
        try { return mapper.writeValueAsBytes(sorted(mapper.valueToTree(value))); }
        catch (Exception invalid) { throw unavailable(); }
    }
    public String digest(Object value) { return sha(bytes(value)); }
    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var values = new TreeMap<String, JsonNode>(); node.fields().forEachRemaining(entry -> values.put(entry.getKey(), sorted(entry.getValue())));
            var result = mapper.createObjectNode(); values.forEach(result::set); return result;
        }
        if (node.isArray()) { var result = mapper.createArrayNode(); node.forEach(value -> result.add(sorted(value))); return result; }
        return node;
    }
    public static void keys(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject()) throw denied();
        var actual = new java.util.HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(keys)) throw denied();
    }
    public static String text(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maximum
                || value.textValue().chars().anyMatch(c -> c < 32 || c == 127)) throw denied();
        return value.textValue();
    }
    public static String hash(JsonNode node, String field) {
        String value = text(node, field, 64); if (!value.matches("[a-f0-9]{64}")) throw denied(); return value;
    }
    public static long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw denied();
        long number = value.longValue(); if (number < 0 || number > SignatureAuthorityProtocol.MAX_INTEGER) throw denied(); return number;
    }
    public static java.util.UUID uuid(JsonNode node, String field) {
        try { String value = text(node, field, 36); var id = java.util.UUID.fromString(value);
            if (!id.toString().equals(value)) throw denied(); return id; }
        catch (IllegalArgumentException invalid) { throw denied(); }
    }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    public static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "Exact current native signature authority is required."); }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Native signature authority is unavailable."); }
    public static BaseException changed() { return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Native signature source authority changed."); }
}
