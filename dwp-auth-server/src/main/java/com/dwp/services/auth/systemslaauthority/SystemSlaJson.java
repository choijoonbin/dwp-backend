package com.dwp.services.auth.systemslaauthority;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class SystemSlaJson {
    private final ObjectMapper mapper;
    public SystemSlaJson(ObjectMapper source) {
        mapper = source.copy().findAndRegisterModules()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40)
                .maxNumberLength(20).maxStringLength(SystemSlaProtocol.BODY_LIMIT).build());
    }
    public JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > SystemSlaProtocol.BODY_LIMIT) throw denied();
        try { var utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
            var value = mapper.readTree(utf8.toString()); if (value == null) throw denied(); validate(value, new int[]{0}); return value; }
        catch (java.io.IOException | IllegalArgumentException error) { throw denied(); }
    }
    private void validate(JsonNode value, int[] count) {
        if (++count[0] > 80000 || value.isNumber() && (!value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0 || value.longValue() > SystemSlaProtocol.MAX_SAFE_INTEGER)) throw denied();
        value.forEach(child -> validate(child, count));
    }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    public byte[] bytes(Object value) {
        try { return mapper.writeValueAsBytes(value); } catch (java.io.IOException error) { throw unavailable(); }
    }
    public String digest(Object value) { return sha(bytes(sorted(tree(value)))); }
    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var object = mapper.createObjectNode(); var keys = new java.util.TreeSet<String>();
            value.fieldNames().forEachRemaining(keys::add); keys.forEach(key -> object.set(key, sorted(value.get(key)))); return object;
        }
        if (value.isArray()) { var array = mapper.createArrayNode(); value.forEach(child -> array.add(sorted(child))); return array; }
        return value;
    }
    public static String sha(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException error) { throw unavailable(); }
    }
    public static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    public static void keys(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw denied();
        var found = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(found::add);
        if (!found.equals(expected)) throw denied();
    }
    public static String text(JsonNode value, String key, int maximum) {
        var field = value.get(key);
        if (field == null || !field.isTextual() || field.textValue().isBlank() || field.textValue().length() > maximum
                || field.textValue().chars().anyMatch(ch -> ch < 32 || ch == 127)) throw denied();
        return field.textValue();
    }
    public static long integer(JsonNode value, String key, boolean positive) {
        var field = value.get(key);
        if (field == null || !field.isIntegralNumber() || !field.canConvertToLong() || field.longValue() < (positive ? 1 : 0)
                || field.longValue() > SystemSlaProtocol.MAX_SAFE_INTEGER) throw denied();
        return field.longValue();
    }
    public static UUID uuid(JsonNode value, String key) {
        String text = text(value, key, 36);
        if (!text.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw denied();
        return UUID.fromString(text);
    }
    public static String hash(JsonNode value, String key) { String text = text(value, key, 64); if (!text.matches("[a-f0-9]{64}")) throw denied(); return text; }
    public static Instant instant(JsonNode value, String key) {
        try { return Instant.parse(text(value, key, 40)); } catch (java.time.DateTimeException error) { throw denied(); }
    }
    public static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "Invalid SYSTEM_SLA source proof."); }
    public static BaseException notReviewed() { return new BaseException(ErrorCode.FORBIDDEN, "DENY_NOT_REVIEWED"); }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "SYSTEM_SLA source is unavailable."); }
    public static BaseException changed() { return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "SYSTEM_SLA source changed."); }
}
