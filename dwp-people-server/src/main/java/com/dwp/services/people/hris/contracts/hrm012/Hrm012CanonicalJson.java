package com.dwp.services.people.hris.contracts.hrm012;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Deterministic canonical JSON and SHA-256 helper for immutable evidence. */
public final class Hrm012CanonicalJson {
    private final ObjectMapper mapper;

    public Hrm012CanonicalJson(ObjectMapper mapper) {
        this.mapper = mapper.copy();
    }

    public byte[] bytes(Object value) {
        try {
            if (value == null) {
                throw new IllegalArgumentException("Canonical value is required.");
            }
            StringBuilder output = new StringBuilder();
            append(mapper.valueToTree(value), output);
            return output.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw Hrm012Problem.invalid("Canonical HRM-012 JSON could not be produced.");
        }
    }

    public String digest(Object value) {
        return sha256(bytes(value));
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private void append(JsonNode value, StringBuilder output) {
        if (value == null || value.isMissingNode()) {
            throw new IllegalArgumentException("A JSON value is required.");
        }
        if (value.isObject()) {
            output.append('{');
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            value.fields().forEachRemaining(fields::add);
            // Java String ordering is lexicographic UTF-16 code-unit ordering, as JCS
            // requires for property names (including supplementary code points).
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
        throw new IllegalArgumentException("The value is outside the JCS JSON domain.");
    }

    /** RFC 8785 delegates numeric serialization to ECMAScript finite binary64. */
    private String canonicalNumber(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JCS numbers must be finite binary64 values.");
        }
        if (value == 0.0d) return "0";
        boolean negative = value < 0;
        double magnitude = Math.abs(value);
        String encoded = ecmaNumber(shortestRoundTrip(magnitude));
        return negative ? "-" + encoded : encoded;
    }

    private BigDecimal shortestRoundTrip(double value) {
        BigDecimal javaShortest = new BigDecimal(
                Double.toString(value)).stripTrailingZeros();
        // Java 21 already selects the closest shortest round-trip decimal, but
        // its contract requires at least two significant digits. ECMAScript
        // has no such floor, so only a two-digit Java result can shorten here.
        if (javaShortest.precision() != 2) return javaShortest;

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

    private boolean isEven(BigDecimal value) {
        return !value.unscaledValue().abs().testBit(0);
    }

    private String ecmaNumber(BigDecimal value) {
        String digits = value.unscaledValue().abs().toString();
        int position = digits.length() - value.scale();
        if (digits.length() <= position && position <= 21) {
            return digits + "0".repeat(position - digits.length());
        }
        if (position > 0 && position <= 21) {
            return digits.substring(0, position) + '.' + digits.substring(position);
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
        if (exponent >= 0) scientific.append('+');
        return scientific.append(exponent).toString();
    }

    private void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            "JCS strings cannot contain lone surrogates.");
                }
                output.append(unit).append(value.charAt(++index));
                continue;
            }
            if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(
                        "JCS strings cannot contain lone surrogates.");
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
