package com.dwp.services.approval.systemslaauthority;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Dedicated bounded UTF-8/canonical codec; legacy/global mappers are not changed. */
public final class SystemSlaJson {
    private final ObjectMapper mapper;
    public SystemSlaJson(ObjectMapper mapper) {
        this.mapper = mapper.copy().findAndRegisterModules().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).maxNumberLength(20).maxStringLength(524288).build());
    }
    public JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 524288) throw denied();
        try {
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var node = mapper.readTree(raw); bounded(node, new int[]{0}); return node;
        } catch (Exception invalid) { throw denied(); }
    }
    public JsonNode tree(Object value) { return mapper.valueToTree(value); }
    public byte[] bytes(Object value) {
        try { return mapper.writeValueAsBytes(sorted(tree(value))); } catch (Exception error) { throw denied(); }
    }
    public String digest(Object value) { return sha(bytes(value)); }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception error) { throw unavailable(); }
    }
    private static void bounded(JsonNode value, int[] nodes) {
        if (value == null || ++nodes[0] > 80000) throw denied();
        if (value.isNumber() && (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0 || value.longValue() > 9007199254740991L)) throw denied();
        if (value.isContainerNode()) value.forEach(item -> bounded(item, nodes));
    }
    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var output = JsonNodeFactory.instance.objectNode(); var fields = new TreeMap<String, JsonNode>(); value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> output.set(key, sorted(child))); return output;
        }
        if (value.isArray()) { var output = JsonNodeFactory.instance.arrayNode(); value.forEach(child -> output.add(sorted(child))); return output; }
        return value;
    }
    public static void keys(JsonNode value, Set<String> expected) {
        if (value == null || !value.isObject()) throw denied();
        var actual = new HashSet<String>(); value.fieldNames().forEachRemaining(actual::add); if (!expected.equals(actual)) throw denied();
    }
    public static String text(JsonNode value, String field, int maximum) {
        var child = value.get(field); if (child == null || !child.isTextual()) throw denied(); String raw = child.textValue();
        if (raw.isBlank() || raw.length() > maximum || !raw.equals(raw.strip()) || raw.chars().anyMatch(c -> c < 32 || c == 127)) throw denied(); return raw;
    }
    public static long integer(JsonNode value, String field, boolean positive) {
        var child = value.get(field); if (child == null || !child.isIntegralNumber() || !child.canConvertToLong()
                || child.longValue() < (positive ? 1 : 0) || child.longValue() > 9007199254740991L) throw denied(); return child.longValue();
    }
    public static UUID uuid(JsonNode value, String field) {
        String raw = text(value, field, 36); try { UUID id = UUID.fromString(raw); if (id.toString().equals(raw)) return id; } catch (IllegalArgumentException error) { throw denied(); } throw denied();
    }
    public static String hash(JsonNode value, String field) { String raw = text(value, field, 64); if (!raw.matches("[a-f0-9]{64}")) throw denied(); return raw; }
    public static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "Exact dedicated SYSTEM_SLA proof is required."); }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Current SYSTEM_SLA source is unavailable."); }
    public static BaseException changed() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "SYSTEM_SLA source evidence changed."); }
}
