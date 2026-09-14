package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class WorkflowRuntimeJson {
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public JsonNode parse(byte[] bytes, int limit) {
        if (bytes == null || bytes.length == 0 || bytes.length > limit) throw denied();
        try { return mapper.readTree(bytes); } catch (java.io.IOException error) { throw denied(); }
    }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    public byte[] bytes(JsonNode value) {
        try { return mapper.writeValueAsBytes(sorted(value)); } catch (java.io.IOException error) { throw denied(); }
    }
    public JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = mapper.createObjectNode(); var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) object.set(key, sorted(value.get(key))); return object;
        }
        if (value.isArray()) { ArrayNode array = mapper.createArrayNode(); value.forEach(item -> array.add(sorted(item))); return array; }
        if (value.isFloatingPointNumber()) throw denied();
        return value.deepCopy();
    }
    public ObjectNode object() { return mapper.createObjectNode(); }
    public static void exact(JsonNode value, Set<String> keys) {
        if (value == null || !value.isObject()) throw denied();
        var actual = new java.util.HashSet<String>(); value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(keys)) throw denied();
    }
    public static String text(JsonNode value, String key, int max) {
        JsonNode item = value == null ? null : value.get(key);
        if (item == null || !item.isTextual() || item.textValue().isBlank() || item.textValue().length() > max) throw denied(); return item.textValue();
    }
    public static long integer(JsonNode value, String key, long min) {
        JsonNode item = value == null ? null : value.get(key);
        if (item == null || !item.isIntegralNumber() || !item.canConvertToLong() || item.longValue() < min || item.longValue() > 9007199254740991L) throw denied();
        return item.longValue();
    }
    public static UUID uuid(JsonNode value, String key) {
        String text = text(value,key,36); if (!text.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw denied(); return UUID.fromString(text);
    }
    public static String hash(JsonNode value, String key) { String text = text(value,key,64); if (!text.matches("[a-f0-9]{64}")) throw denied(); return text; }
    public static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    public static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    public static byte[] part(String value) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw denied();
        try { byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) throw denied(); return bytes;
        } catch (IllegalArgumentException error) { throw denied(); }
    }
}
