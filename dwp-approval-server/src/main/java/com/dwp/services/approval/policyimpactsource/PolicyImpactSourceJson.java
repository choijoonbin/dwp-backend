package com.dwp.services.approval.policyimpactsource;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

public final class PolicyImpactSourceJson {
    private final ObjectMapper mapper;
    public PolicyImpactSourceJson(ObjectMapper source) {
        mapper = source.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24)
                .maxNumberLength(20).maxStringLength(PolicyImpactSourceProtocol.OWNER_LIMIT).build());
    }
    public JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > PolicyImpactSourceProtocol.BODY_LIMIT) throw denied();
        try {
            var value = mapper.readTree(bytes); if (value == null) throw denied();
            validate(value, new int[]{0}); return value;
        } catch (java.io.IOException | IllegalArgumentException error) { throw denied(); }
    }
    private void validate(JsonNode node, int[] count) {
        if (++count[0] > 10000 || node.isNumber() && (!node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() < 0 || node.longValue() > PolicyImpactSourceProtocol.MAX_SAFE_INTEGER)) throw denied();
        node.forEach(child -> validate(child, count));
    }
    public byte[] bytes(Object value) {
        try { return mapper.writeValueAsBytes(value); } catch (java.io.IOException error) { throw unavailable(); }
    }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    public String digest(Object value) { return sha(bytes(sorted(tree(value)))); }
    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var result = mapper.createObjectNode(); var names = new java.util.TreeSet<String>();
            value.fieldNames().forEachRemaining(names::add); names.forEach(name -> result.set(name, sorted(value.get(name)))); return result;
        }
        if (value.isArray()) { var result = mapper.createArrayNode(); value.forEach(child -> result.add(sorted(child))); return result; }
        return value;
    }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw unavailable(); }
    }
    public static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    public static void keys(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw denied();
        var actual = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw denied();
    }
    public static String text(JsonNode value, String key, int max) {
        var field = value.get(key);
        if (field == null || !field.isTextual() || field.textValue().isBlank() || field.textValue().length() > max
                || field.textValue().chars().anyMatch(ch -> ch < 32 || ch == 127)) throw denied();
        return field.textValue();
    }
    public static long integer(JsonNode value, String key, boolean positive) {
        var field = value.get(key);
        if (field == null || !field.isIntegralNumber() || !field.canConvertToLong() || field.longValue() < (positive ? 1 : 0)
                || field.longValue() > PolicyImpactSourceProtocol.MAX_SAFE_INTEGER) throw denied();
        return field.longValue();
    }
    public static String hash(JsonNode value, String key) {
        String result = text(value, key, 64); if (!result.matches("[a-f0-9]{64}")) throw denied(); return result;
    }
    public static Instant instant(JsonNode value, String key) {
        try { return Instant.parse(text(value, key, 40)); } catch (java.time.DateTimeException error) { throw denied(); }
    }
    public static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "Invalid policy impact source evidence."); }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
            "Policy impact source authority is unavailable."); }
    public static BaseException changed() { return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT); }
}
