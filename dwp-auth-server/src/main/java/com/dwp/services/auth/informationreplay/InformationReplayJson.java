package com.dwp.services.auth.informationreplay;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Strict JSON and digest primitives; this class grants no replay or historical admission authority. */
public final class InformationReplayJson {
    private static final int MAX_BYTES = 524288;
    private final ObjectMapper mapper;

    public InformationReplayJson(ObjectMapper source) {
        mapper = source.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(SerializationFeature.INDENT_OUTPUT);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(40).maxNumberLength(32).maxStringLength(262144).build());
    }

    public JsonNode parse(byte[] bytes, int limit) {
        if (bytes == null || bytes.length == 0 || limit < 1 || limit > MAX_BYTES || bytes.length > limit) throw denied();
        try {
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode value = mapper.readTree(raw);
            if (value == null) throw denied();
            validate(value, new int[]{0});
            return value;
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw denied();
        }
    }

    public JsonNode tree(Object value) { return mapper.valueToTree(value); }

    public String canonical(JsonNode value) {
        if (value == null) throw denied();
        validate(value, new int[]{0});
        try { return mapper.writeValueAsString(sorted(value)); }
        catch (java.io.IOException error) { throw denied(); }
    }

    public String digest(JsonNode value) { return sha(canonical(value)); }

    private void validate(JsonNode value, int[] count) {
        if (++count[0] > 100000) throw denied();
        if (value.isTextual()) unicode(value.textValue());
        if (value.isObject()) value.fieldNames().forEachRemaining(InformationReplayJson::unicode);
        value.forEach(child -> validate(child, count));
    }

    private static void unicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) throw denied();
            } else if (Character.isLowSurrogate(current)) throw denied();
        }
    }

    private JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            var result = mapper.createObjectNode();
            var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, sorted(value.get(key))));
            return result;
        }
        if (value.isArray()) {
            var result = mapper.createArrayNode(); value.forEach(child -> result.add(sorted(child)));
            return result;
        }
        return value.deepCopy();
    }

    public static void exact(JsonNode value, Set<String> keys) {
        if (value == null || !value.isObject()) throw denied();
        var actual = new HashSet<String>(); value.fieldNames().forEachRemaining(actual::add);
        if (!keys.equals(actual)) throw denied();
    }

    public static String text(JsonNode value, String key, int limit) {
        var field = value == null ? null : value.get(key);
        if (field == null || !field.isTextual()) throw denied();
        String raw = field.textValue(); unicode(raw);
        if (raw.isBlank() || raw.length() > limit || !raw.equals(raw.strip())
                || raw.codePoints().anyMatch(Character::isISOControl)) throw denied();
        return raw;
    }

    public static long number(JsonNode value, String key, long minimum, long maximum) {
        var field = value == null ? null : value.get(key);
        if (field == null || !field.isIntegralNumber() || !field.canConvertToLong()
                || field.longValue() < minimum || field.longValue() > maximum) throw denied();
        return field.longValue();
    }

    public static UUID uuid(JsonNode value, String key) {
        String raw = text(value, key, 36);
        try {
            UUID id = UUID.fromString(raw);
            if (!id.toString().equals(raw)) throw denied();
            return id;
        } catch (IllegalArgumentException error) { throw denied(); }
    }

    public static String hash(JsonNode value, String key) {
        String raw = text(value, key, 64);
        if (!raw.matches("[a-f0-9]{64}")) throw denied();
        return raw;
    }

    public static byte[] part(String raw) {
        if (raw == null || raw.isEmpty() || !raw.matches("[A-Za-z0-9_-]+")) throw denied();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) throw denied();
            return bytes;
        } catch (IllegalArgumentException error) { throw denied(); }
    }

    public static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    public static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public static BaseException denied() {
        return new BaseException(ErrorCode.FORBIDDEN, "Invalid information replay proof.");
    }

    public static BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Information replay authority is unavailable.");
    }
}
