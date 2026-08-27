package com.dwp.services.platform.widgetregistry.internal.security;

import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryRequestBinding.BindingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Small fail-closed primitives shared by the executable Widget Registry JSON contracts. */
final class WidgetRegistryJsonContract {

    static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    static final Pattern LOWER_KEY = Pattern.compile(
            "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$");
    static final Pattern UPPER_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    static final Pattern UPPER_KEY = Pattern.compile(
            "^[A-Z][A-Z0-9_]*(?:\\.[A-Z0-9_]+)*$");
    static final Pattern APP_RESOURCE_KEY = Pattern.compile(
            "^APP\\.[A-Z][A-Z0-9_]*(?:\\.[A-Z0-9_]+)*$");
    static final Pattern AUTHORITY = Pattern.compile(
            "^APP\\.[A-Z][A-Z0-9_.]*:[A-Z][A-Z0-9_]*$");
    static final Pattern FIELD_KEY = Pattern.compile("^[a-z][A-Za-z0-9]*$");
    static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
                    + "(?:\\.[0-9]{1,9})?Z$");

    private WidgetRegistryJsonContract() {
    }

    static void exactObject(JsonNode value, Set<String> required, Set<String> optional)
            throws BindingException {
        require(value != null && value.isObject());
        Set<String> fields = new HashSet<>();
        value.fieldNames().forEachRemaining(fields::add);
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        require(fields.containsAll(required) && allowed.containsAll(fields));
    }

    static JsonNode requiredNode(JsonNode object, String field) throws BindingException {
        JsonNode value = object.get(field);
        require(value != null);
        return value;
    }

    static String text(JsonNode object, String field, int minimum, int maximum, Pattern pattern)
            throws BindingException {
        JsonNode node = requiredNode(object, field);
        require(node.isTextual());
        String value = node.textValue();
        int length = value.codePointCount(0, value.length());
        require(length >= minimum && length <= maximum);
        require(pattern == null || pattern.matcher(value).matches());
        return value;
    }

    static String optionalText(
            JsonNode object,
            String field,
            int minimum,
            int maximum,
            Pattern pattern) throws BindingException {
        if (!object.has(field)) return null;
        return text(object, field, minimum, maximum, pattern);
    }

    static String opaque(JsonNode object, String field) throws BindingException {
        String value = text(object, field, 1, 128, null);
        require(noControlCharacters(value));
        return value;
    }

    static String optionalOpaque(JsonNode object, String field) throws BindingException {
        if (!object.has(field)) return null;
        return opaque(object, field);
    }

    static String reasonText(JsonNode object, String field) throws BindingException {
        String value = text(object, field, 1, 500, null);
        require(noControlCharacters(value));
        return value;
    }

    static String optionalReasonText(JsonNode object, String field) throws BindingException {
        if (!object.has(field)) return null;
        return reasonText(object, field);
    }

    static String uuid(JsonNode object, String field) throws BindingException {
        return text(object, field, 36, 36, UUID);
    }

    static String optionalUuid(JsonNode object, String field) throws BindingException {
        if (!object.has(field)) return null;
        return uuid(object, field);
    }

    static String nullableUuid(JsonNode object, String field) throws BindingException {
        JsonNode value = requiredNode(object, field);
        if (value.isNull()) return null;
        return uuid(object, field);
    }

    static String sha256(JsonNode object, String field) throws BindingException {
        return text(object, field, 64, 64, SHA256);
    }

    static long nonNegativeInteger(JsonNode object, String field) throws BindingException {
        JsonNode value = requiredNode(object, field);
        require(value.isNumber());
        try {
            long integer = value.decimalValue().toBigIntegerExact().longValueExact();
            require(integer >= 0);
            return integer;
        } catch (ArithmeticException exception) {
            throw invalid();
        }
    }

    static long integerBetween(JsonNode object, String field, long minimum, long maximum)
            throws BindingException {
        long value = nonNegativeInteger(object, field);
        require(value >= minimum && value <= maximum);
        return value;
    }

    static boolean bool(JsonNode object, String field) throws BindingException {
        JsonNode value = requiredNode(object, field);
        require(value.isBoolean());
        return value.booleanValue();
    }

    static String enumText(JsonNode object, String field, Set<String> allowed)
            throws BindingException {
        String value = text(object, field, 1, 160, null);
        require(allowed.contains(value));
        return value;
    }

    static Instant timestamp(JsonNode object, String field) throws BindingException {
        String value = text(object, field, 20, 30, TIMESTAMP);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalid(exception);
        }
    }

    static Instant optionalTimestamp(JsonNode object, String field) throws BindingException {
        if (!object.has(field)) return null;
        return timestamp(object, field);
    }

    static Set<String> textArray(
            JsonNode object,
            String field,
            int minimumItems,
            int maximumItems,
            int minimumLength,
            int maximumLength,
            Pattern pattern) throws BindingException {
        JsonNode values = requiredNode(object, field);
        require(values.isArray() && values.size() >= minimumItems && values.size() <= maximumItems);
        Set<String> result = new HashSet<>();
        for (JsonNode item : values) {
            require(item.isTextual());
            String value = item.textValue();
            int length = value.codePointCount(0, value.length());
            require(length >= minimumLength && length <= maximumLength);
            require(pattern == null || pattern.matcher(value).matches());
            require(result.add(value));
        }
        return Set.copyOf(result);
    }

    static Set<String> opaqueArray(JsonNode object, String field, int maximumItems)
            throws BindingException {
        Set<String> values = textArray(object, field, 0, maximumItems, 1, 128, null);
        require(values.stream().allMatch(WidgetRegistryJsonContract::noControlCharacters));
        return values;
    }

    static Set<String> uuidArray(JsonNode object, String field, int maximumItems)
            throws BindingException {
        return textArray(object, field, 0, maximumItems, 36, 36, UUID);
    }

    static void requireSorted(JsonNode object, String field, List<String> rank)
            throws BindingException {
        JsonNode values = requiredNode(object, field);
        require(values.isArray());
        String previous = null;
        int previousRank = -1;
        for (JsonNode item : values) {
            require(item.isTextual());
            String current = item.textValue();
            if (rank == null) {
                require(previous == null || previous.compareTo(current) < 0);
                previous = current;
            } else {
                int currentRank = rank.indexOf(current);
                require(currentRank >= 0 && currentRank > previousRank);
                previousRank = currentRank;
            }
        }
    }

    static void require(boolean valid) throws BindingException {
        if (!valid) throw invalid();
    }

    static BindingException invalid() {
        return new BindingException(WidgetRegistryIngressFailure.REQUEST_BINDING_INVALID);
    }

    private static BindingException invalid(Exception cause) {
        return new BindingException(WidgetRegistryIngressFailure.REQUEST_BINDING_INVALID, cause);
    }

    private static boolean noControlCharacters(String value) {
        return value.codePoints().noneMatch(character -> character < 0x20 || character == 0x7f);
    }
}
