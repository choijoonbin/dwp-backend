package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Mirrors the owner's recursive object ordering without sorting or replacing arrays. */
public final class PlanningJson {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public PlanningJson() {
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24)
                .maxNumberLength(20).maxStringLength(OWNER_LIMIT).build());
    }
    public JsonNode parse(byte[] bytes, int limit) {
        if (bytes == null || bytes.length == 0 || bytes.length > limit) throw denied();
        try { var value = mapper.readTree(bytes); validate(value, new int[]{0}); return value; }
        catch (java.io.IOException | IllegalArgumentException error) { throw denied(); }
    }
    private static void validate(JsonNode value, int[] count) {
        if (value == null || ++count[0] > 50000 || value.isFloatingPointNumber()
                || value.isIntegralNumber() && (!value.canConvertToLong() || value.longValue() < -9007199254740991L
                    || value.longValue() > 9007199254740991L)) throw denied();
        value.forEach(child -> validate(child, count));
    }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    public byte[] bytes(Object value) {
        try { return mapper.writeValueAsBytes(sorted(tree(value))); } catch (java.io.IOException error) { throw unavailable(); }
    }
    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var result = mapper.createObjectNode(); var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) result.set(key, sorted(value.get(key))); return result;
        }
        if (value.isArray()) { var result = mapper.createArrayNode(); value.forEach(child -> result.add(sorted(child))); return result; }
        if (value.isFloatingPointNumber()) throw denied(); return value.deepCopy();
    }
    public String digest(Object value) { return sha(bytes(value)); }
    public static void exact(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw denied();
        var keys = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(keys::add); if (!keys.equals(expected)) throw denied();
    }
    public static String text(JsonNode value, String key, int limit) {
        var field = value == null ? null : value.get(key);
        if (field == null || !field.isTextual() || field.textValue().isBlank() || field.textValue().length() > limit
                || field.textValue().codePoints().anyMatch(Character::isISOControl)) throw denied(); return field.textValue();
    }
    public static long integer(JsonNode value, String key, long minimum) {
        var field = value == null ? null : value.get(key);
        if (field == null || !field.isIntegralNumber() || !field.canConvertToLong() || field.longValue() < minimum
                || field.longValue() > 9007199254740991L) throw denied(); return field.longValue();
    }
    public static UUID uuid(JsonNode value, String key) {
        String raw = text(value, key, 36); if (!raw.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw denied(); return UUID.fromString(raw);
    }
    public static String hash(JsonNode value, String key) { String raw = text(value, key, 64); if (!raw.matches("[a-f0-9]{64}")) throw denied(); return raw; }
    public static byte[] part(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]+")) throw denied();
        try { byte[] bytes = Base64.getUrlDecoder().decode(raw); if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) throw denied(); return bytes; }
        catch (IllegalArgumentException error) { throw denied(); }
    }
    public static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    public static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
