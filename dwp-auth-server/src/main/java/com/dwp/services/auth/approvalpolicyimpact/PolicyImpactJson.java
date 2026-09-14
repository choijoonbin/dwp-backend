package com.dwp.services.auth.approvalpolicyimpact;

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
import java.util.UUID;

/** Independent strict wire parser. It does not deserialize a caller's grants or authorization profile. */
public final class PolicyImpactJson {
    private final ObjectMapper mapper;
    public PolicyImpactJson(ObjectMapper source) {
        mapper = source.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24)
                .maxNumberLength(20).maxStringLength(PolicyImpactProtocol.OWNER_LIMIT).build());
    }
    public JsonNode parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > PolicyImpactProtocol.BODY_LIMIT) throw denied();
        try {
            JsonNode value = mapper.readTree(body);
            if (value == null) throw denied();
            validate(value, new int[]{0});
            return value;
        } catch (java.io.IOException | IllegalArgumentException error) { throw denied(); }
    }
    private void validate(JsonNode value, int[] count) {
        if (++count[0] > 10000 || value.isNumber() && (!value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0 || value.longValue() > PolicyImpactProtocol.MAX_SAFE_INTEGER)) throw denied();
        value.forEach(child -> validate(child, count));
    }
    public byte[] bytes(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (java.io.IOException error) { throw unavailable(); }
    }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    public String digest(Object value) { return sha(bytes(sorted(mapper.valueToTree(value)))); }
    public String evidenceDigest(Object value) { return sha(bytes(evidenceSorted(mapper.valueToTree(value)))); }
    private JsonNode evidenceSorted(JsonNode value) {
        if (value.isArray()) {
            var children = new java.util.ArrayList<JsonNode>(); value.forEach(child -> children.add(evidenceSorted(child)));
            children.sort(java.util.Comparator.comparing(child -> new String(bytes(child), StandardCharsets.UTF_8)));
            var result = mapper.createArrayNode(); children.forEach(result::add); return result;
        }
        if (value.isObject()) {
            var result = mapper.createObjectNode(); var names = new java.util.TreeSet<String>();
            value.fieldNames().forEachRemaining(names::add); names.forEach(name -> result.set(name, evidenceSorted(value.get(name)))); return result;
        }
        return value;
    }
    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var result = mapper.createObjectNode();
            var names = new java.util.TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, sorted(value.get(name)))); return result;
        }
        if (value.isArray()) {
            var result = mapper.createArrayNode(); value.forEach(child -> result.add(sorted(child))); return result;
        }
        return value;
    }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw unavailable(); }
    }
    public static String sha(String text) { return sha(text.getBytes(StandardCharsets.UTF_8)); }
    public static void keys(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw denied();
        var found = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(found::add);
        if (!found.equals(expected)) throw denied();
    }
    public static String text(JsonNode value, String key, int max) {
        JsonNode field = value.get(key);
        if (field == null || !field.isTextual() || field.textValue().isBlank() || field.textValue().length() > max
                || field.textValue().chars().anyMatch(ch -> ch < 32 || ch == 127)) throw denied();
        return field.textValue();
    }
    public static long integer(JsonNode value, String key, boolean positive) {
        var field = value.get(key);
        if (field == null || !field.isIntegralNumber() || !field.canConvertToLong() || field.longValue() < (positive ? 1 : 0)
                || field.longValue() > PolicyImpactProtocol.MAX_SAFE_INTEGER) throw denied();
        return field.longValue();
    }
    public static UUID uuid(JsonNode value, String key) {
        String text = text(value, key, 36);
        if (!text.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw denied();
        return UUID.fromString(text);
    }
    public static String hash(JsonNode value, String key) {
        String result = text(value, key, 64); if (!result.matches("[a-f0-9]{64}")) throw denied(); return result;
    }
    public static Instant instant(JsonNode value, String key) {
        try { return Instant.parse(text(value, key, 40)); }
        catch (java.time.DateTimeException error) { throw denied(); }
    }
    public static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "Invalid policy impact source proof."); }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
            "Policy impact source authority is unavailable."); }
}
