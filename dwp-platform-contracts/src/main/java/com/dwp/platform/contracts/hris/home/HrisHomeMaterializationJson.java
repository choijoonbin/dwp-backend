package com.dwp.platform.contracts.hris.home;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict JSON primitives shared by the closed Home materialization decoder. */
final class HrisHomeMaterializationJson {

    private HrisHomeMaterializationJson() {
    }

    static JsonNode required(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) throw invalid("missing required field " + field);
        return value;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) throw invalid(field + " must be text");
        return value.textValue();
    }

    static long integer(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(field + " must be an integer");
        }
        return value.longValue();
    }

    static Instant instant(JsonNode node, String field) {
        return Instant.parse(text(node, field));
    }

    static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(text(node, field));
    }

    static <E extends Enum<E>> E enumValue(
            JsonNode node, String field, Class<E> enumType) {
        String value = text(node, field);
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " is not a closed canonical value");
        }
    }

    static void exactObject(JsonNode node, Set<String> fields, String name) {
        if (node == null || !node.isObject()) throw invalid(name + " must be an object");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            Set<String> unknown = new HashSet<>(actual);
            unknown.removeAll(fields);
            Set<String> missing = new HashSet<>(fields);
            missing.removeAll(actual);
            throw invalid(name + " has unknown=" + unknown + " missing=" + missing);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    static void requireEqual(String expected, String actual, String field) {
        if (!expected.equals(actual)) throw invalid(field + " must equal " + expected);
    }

    static void requireDigest(String value, String field) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw invalid(field + " must be a lowercase SHA-256 digest");
        }
    }

    static void positive(long value, String field) {
        if (value < 1) throw invalid(field + " must be positive");
    }

    static boolean safeText(String value) {
        if (value == null || value.isBlank() || value.length() > 120
                || !value.equals(value.trim()) || value.indexOf('<') >= 0
                || value.indexOf('>') >= 0) return false;
        return value.codePoints().noneMatch(Character::isISOControl);
    }
}
