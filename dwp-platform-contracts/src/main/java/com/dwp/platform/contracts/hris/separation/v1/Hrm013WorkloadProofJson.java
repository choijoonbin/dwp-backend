package com.dwp.platform.contracts.hris.separation.v1;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofViews.*;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofValues.*;

/** Strict duplicate-free JSON and RFC 8785 canonicalization. */
final class Hrm013WorkloadProofJson {

    private final Hrm013WorkloadProofValues values;

    Hrm013WorkloadProofJson(Hrm013WorkloadProofValues values) {
        this.values = values;
    }

    /** Strict duplicate-free RFC 8785 canonical bytes for HRM-013 digest preimages. */
    byte[] rfc8785CanonicalBytes(ObjectMapper mapper, byte[] jsonBytes) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(jsonBytes, "jsonBytes");
        JsonNode node = readJson(mapper, jsonBytes, Integer.MAX_VALUE,
                "REQUEST_BODY_INVALID", false, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream(jsonBytes.length);
        writeCanonical(node, output);
        return output.toByteArray();
    }

    JsonNode strictCanonicalObject(
            ObjectMapper mapper, byte[] bytes, int limit, Object code) {
        JsonNode node = readJson(mapper, bytes, limit, code, true, true);
        if (!node.isObject()) {
            throw values.failure(code);
        }
        return node;
    }

    JsonNode readJson(
            ObjectMapper mapper,
            byte[] bytes,
            int limit,
            Object code,
            boolean requireCanonical,
            boolean rejectNull) {
        if (bytes.length == 0 || bytes.length > limit) {
            throw values.failure(code);
        }
        try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = mapper.readTree(parser);
            if (node == null || parser.nextToken() != null
                    || (rejectNull && containsNull(node))) {
                throw values.failure(code);
            }
            byte[] canonical = canonicalBytes(node);
            if (requireCanonical && !Arrays.equals(bytes, canonical)) {
                throw values.failure(code);
            }
            return node;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof RuntimeException runtime && values.isFailure(runtime)) {
                throw runtime;
            }
            throw values.failure(code);
        }
    }

    boolean containsNull(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return true;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                if (containsNull(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    byte[] canonicalBytes(JsonNode node) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeCanonical(node, output);
        return output.toByteArray();
    }

    void writeCanonical(JsonNode node, ByteArrayOutputStream output) {
        if (node.isObject()) {
            output.write('{');
            TreeMap<String, JsonNode> sorted = new TreeMap<>(Comparator.naturalOrder());
            node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) {
                    output.write(',');
                }
                first = false;
                writeCanonicalString(entry.getKey(), output);
                output.write(':');
                writeCanonical(entry.getValue(), output);
            }
            output.write('}');
            return;
        }
        if (node.isArray()) {
            output.write('[');
            boolean first = true;
            for (JsonNode child : node) {
                if (!first) {
                    output.write(',');
                }
                first = false;
                writeCanonical(child, output);
            }
            output.write(']');
            return;
        }
        if (node.isTextual()) {
            writeCanonicalString(node.textValue(), output);
            return;
        }
        if (node.isBoolean()) {
            writeAscii(node.booleanValue() ? "true" : "false", output);
            return;
        }
        if (node.isNull()) {
            writeAscii("null", output);
            return;
        }
        if (node.isNumber()) {
            writeAscii(canonicalNumber(node), output);
            return;
        }
        throw new IllegalArgumentException("unsupported JSON value in canonical preimage");
    }

    void writeCanonicalString(String value, ByteArrayOutputStream output) {
        validateUnicode(value);
        output.write('"');
        StringBuilder encoded = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\b' -> encoded.append("\\b");
                case '\t' -> encoded.append("\\t");
                case '\n' -> encoded.append("\\n");
                case '\f' -> encoded.append("\\f");
                case '\r' -> encoded.append("\\r");
                default -> {
                    if (character < 0x20) {
                        encoded.append("\\u00");
                        encoded.append(Character.forDigit((character >>> 4) & 0xf, 16));
                        encoded.append(Character.forDigit(character & 0xf, 16));
                    } else {
                        encoded.append(character);
                    }
                }
            }
        }
        byte[] bytes = encoded.toString().getBytes(StandardCharsets.UTF_8);
        output.writeBytes(bytes);
        output.write('"');
    }

    String canonicalNumber(JsonNode node) {
        if (node.isIntegralNumber()) {
            BigInteger integer = node.bigIntegerValue();
            if (integer.abs().compareTo(BigInteger.valueOf(MAXIMUM_SAFE_JSON_INTEGER)) > 0) {
                throw new IllegalArgumentException("integer is outside the I-JSON safe range");
            }
            return integer.toString();
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite JSON number");
        }
        if (value == 0.0d) {
            return "0";
        }
        boolean negative = value < 0;
        String encoded = ecmaNumber(shortestRoundTrip(Math.abs(value)));
        return negative ? "-" + encoded : encoded;
    }

    BigDecimal shortestRoundTrip(double value) {
        BigDecimal javaShortest = new BigDecimal(
                Double.toString(value)).stripTrailingZeros();
        // Java requires at least two significant digits for some binary64 values;
        // ECMAScript/JCS permits the closer single-digit round-trip representation.
        if (javaShortest.precision() != 2) {
            return javaShortest;
        }
        BigDecimal exact = new BigDecimal(value);
        long expectedBits = Double.doubleToRawLongBits(value);
        BigDecimal lower = exact.round(
                new MathContext(1, RoundingMode.FLOOR)).stripTrailingZeros();
        BigDecimal upper = exact.round(
                new MathContext(1, RoundingMode.CEILING)).stripTrailingZeros();
        BigDecimal best = null;
        BigDecimal bestDistance = null;
        for (BigDecimal candidate : List.of(lower, upper)) {
            if (Double.doubleToRawLongBits(Double.parseDouble(candidate.toString()))
                    != expectedBits) {
                continue;
            }
            BigDecimal distance = exact.subtract(candidate).abs();
            if (best == null || distance.compareTo(bestDistance) < 0
                    || (distance.compareTo(bestDistance) == 0
                    && isEven(candidate) && !isEven(best))) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? javaShortest : best;
    }

    boolean isEven(BigDecimal value) {
        return !value.unscaledValue().abs().testBit(0);
    }

    String ecmaNumber(BigDecimal value) {
        String digits = value.unscaledValue().abs().toString();
        int position = digits.length() - value.scale();
        if (digits.length() <= position && position <= 21) {
            return digits + "0".repeat(position - digits.length());
        }
        if (position > 0 && position <= 21) {
            return digits.substring(0, position) + "." + digits.substring(position);
        }
        if (position > -6 && position <= 0) {
            return "0." + "0".repeat(-position) + digits;
        }
        StringBuilder scientific = new StringBuilder().append(digits.charAt(0));
        if (digits.length() > 1) {
            scientific.append('.').append(digits, 1, digits.length());
        }
        int exponent = position - 1;
        scientific.append('e');
        if (exponent >= 0) {
            scientific.append('+');
        }
        return scientific.append(exponent).toString();
    }

    void validateUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException("lone high surrogate");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("lone low surrogate");
            }
        }
    }

    void writeAscii(String value, ByteArrayOutputStream output) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }
}
