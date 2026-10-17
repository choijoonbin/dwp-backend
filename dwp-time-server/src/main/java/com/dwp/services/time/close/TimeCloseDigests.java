package com.dwp.services.time.close;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** RFC 8785/JCS canonical JSON and domain-separated SHA-256 helpers. */
final class TimeCloseDigests {

    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private TimeCloseDigests() {}

    static String canonicalJson(Object value) {
        if (value == null) throw new IllegalArgumentException("canonical value is required");
        return canonicalJson(JSON.valueToTree(value));
    }

    static String canonicalJsonDigest(String domain, String rawJson) {
        if (rawJson == null) throw new IllegalArgumentException("JSON body is required");
        try {
            JsonNode parsed = JSON.readTree(rawJson);
            if (parsed == null) throw new IllegalArgumentException("JSON body is required");
            return sha256(domain, canonicalJson(parsed));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("JSON body is invalid", invalid);
        }
    }

    static String canonicalJsonWithBindings(
            String rawJson,
            TimeCloseModels.PayrollTimeHandoffOutcomeV1 outcome,
            long tenantId,
            String lowercaseHandoffId) {
        if (tenantId <= 0 || lowercaseHandoffId == null) {
            throw new IllegalArgumentException("outcome bindings are required");
        }
        if (rawJson == null || outcome == null) {
            throw new IllegalArgumentException("outcome body is required");
        }
        try {
            JsonNode parsed = JSON.readTree(rawJson);
            if (!(parsed instanceof ObjectNode body)) {
                throw new IllegalArgumentException("outcome body must be an object");
            }
            if (body.has("tenantId") || body.has("handoffId")) {
                throw new IllegalArgumentException("outcome body cannot override signed bindings");
            }
            if (!canonicalJson(body).equals(canonicalJson(outcome))) {
                throw new IllegalArgumentException(
                        "outcome body does not match the typed outcome");
            }
            ObjectNode signed = body.deepCopy();
            signed.put("tenantId", tenantId);
            signed.put("handoffId", lowercaseHandoffId);
            return canonicalJson(signed);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("JSON body is invalid", invalid);
        }
    }

    static String timeCloseRequestDigest(
            String rawBody,
            String lowercasePeriodId,
            long expectedAggregateVersion,
            String purposeCode,
            String expectedDecisionRevision) {
        if (expectedAggregateVersion <= 0 || lowercasePeriodId == null
                || purposeCode == null || expectedDecisionRevision == null) {
            throw new IllegalArgumentException("request digest bindings are required");
        }
        try {
            JsonNode parsed = JSON.readTree(rawBody);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("command body must be an object");
            }
            ObjectNode signed = JSON.createObjectNode();
            signed.put("periodId", lowercasePeriodId);
            signed.put("expectedAggregateVersion", expectedAggregateVersion);
            signed.put("purposeCode", purposeCode);
            signed.put("expectedDecisionRevision", expectedDecisionRevision);
            signed.set("body", parsed);
            return sha256Utf8(canonicalJson(signed));
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("JSON body is invalid", invalid);
        }
    }

    static String sha256Utf8(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String sha256(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String canonicalJson(JsonNode value) {
        StringBuilder output = new StringBuilder();
        append(value, output);
        return output.toString();
    }

    private static void append(JsonNode value, StringBuilder output) {
        if (value.isObject()) {
            output.append('{');
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            value.fields().forEachRemaining(fields::add);
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            for (int index = 0; index < fields.size(); index++) {
                if (index > 0) output.append(',');
                appendString(fields.get(index).getKey(), output);
                output.append(':');
                append(fields.get(index).getValue(), output);
            }
            output.append('}');
            return;
        }
        if (value.isArray()) {
            output.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) output.append(',');
                append(value.get(index), output);
            }
            output.append(']');
            return;
        }
        if (value.isTextual()) {
            appendString(value.textValue(), output);
            return;
        }
        if (value.isNumber()) {
            output.append(canonicalNumber(value.doubleValue()));
            return;
        }
        if (value.isBoolean()) {
            output.append(value.booleanValue() ? "true" : "false");
            return;
        }
        if (value.isNull()) {
            output.append("null");
            return;
        }
        throw new IllegalArgumentException("JSON value cannot be JCS canonicalized");
    }

    /* RFC 8785 delegates number serialization to ECMAScript's finite binary64 form. */
    private static String canonicalNumber(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JCS numbers must be finite binary64 values");
        }
        if (value == 0.0d) return "0";
        boolean negative = value < 0;
        double magnitude = Math.abs(value);
        BigDecimal decimal = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
        String encoded;
        if (magnitude >= 1.0e-6d && magnitude < 1.0e21d) {
            encoded = decimal.toPlainString();
        } else {
            String digits = decimal.unscaledValue().abs().toString();
            int exponent = decimal.precision() - decimal.scale() - 1;
            StringBuilder scientific = new StringBuilder().append(digits.charAt(0));
            if (digits.length() > 1) scientific.append('.').append(digits, 1, digits.length());
            scientific.append('e');
            if (exponent >= 0) scientific.append('+');
            scientific.append(exponent);
            encoded = scientific.toString();
        }
        return negative ? "-" + encoded : encoded;
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("JCS strings cannot contain lone surrogates");
                }
                output.append(unit).append(value.charAt(++index));
                continue;
            }
            if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("JCS strings cannot contain lone surrogates");
            }
            switch (unit) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\t' -> output.append("\\t");
                case '\n' -> output.append("\\n");
                case '\f' -> output.append("\\f");
                case '\r' -> output.append("\\r");
                default -> {
                    if (unit <= 0x1f) {
                        output.append("\\u00")
                                .append(Character.forDigit((unit >>> 4) & 0xf, 16))
                                .append(Character.forDigit(unit & 0xf, 16));
                    } else {
                        output.append(unit);
                    }
                }
            }
        }
        output.append('"');
    }
}
