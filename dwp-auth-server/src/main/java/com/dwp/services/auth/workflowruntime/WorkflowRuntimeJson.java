package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.denied;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class WorkflowRuntimeJson {
    private final ObjectMapper mapper;
    public WorkflowRuntimeJson(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40)
                .maxStringLength(WorkflowRuntimeProtocol.MAX_BODY).build());
    }
    public JsonNode read(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return mapper.readTree(text);
        } catch (Exception exception) { throw denied(); }
    }
    public JsonNode read(String value) { return read(value.getBytes(StandardCharsets.UTF_8)); }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    public String canonical(JsonNode value) {
        try { return mapper.writeValueAsString(sorted(value)); }
        catch (Exception exception) { throw denied(); }
    }
    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var object = mapper.createObjectNode();
            var fields = new java.util.TreeSet<String>(); value.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> object.set(field, sorted(value.get(field)))); return object;
        }
        if (value.isArray()) { var array = mapper.createArrayNode(); value.forEach(item -> array.add(sorted(item))); return array; }
        return value.deepCopy();
    }
    public static void exact(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject()) throw denied();
        var actual = new HashSet<String>(); value.fieldNames().forEachRemaining(actual::add);
        if (!fields.equals(actual)) throw denied();
    }
    public static String text(JsonNode value, String field) { return text(value, field, 500); }
    public static String text(JsonNode value, String field, int limit) {
        var node = value.get(field);
        if (node == null || !node.isTextual()) throw denied();
        String text = node.textValue();
        if (text.isBlank() || text.length() > limit || !text.equals(text.strip())
                || text.codePoints().anyMatch(Character::isISOControl)) throw denied();
        return text;
    }
    public static long number(JsonNode value, String field, long min, long max) {
        var node = value.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < min || node.longValue() > max) throw denied();
        return node.longValue();
    }
    public static UUID uuid(JsonNode value, String field) {
        try { String raw = text(value, field, 36); UUID id = UUID.fromString(raw); if (!id.toString().equals(raw)) throw denied(); return id; }
        catch (IllegalArgumentException exception) { throw denied(); }
    }
    public static String hash(JsonNode value, String field) { String text = text(value, field, 64); if (!text.matches("[a-f0-9]{64}")) throw denied(); return text; }
    public static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public static byte[] part(String value) {
        if (value.isEmpty() || !value.matches("[A-Za-z0-9_-]+")) throw denied();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) throw denied(); return bytes;
        } catch (IllegalArgumentException exception) { throw denied(); }
    }
}
