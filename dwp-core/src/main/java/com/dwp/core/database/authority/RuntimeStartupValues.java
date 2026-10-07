package com.dwp.core.database.authority;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Value-free diagnostics: never attach input JSON, driver exceptions or credentials. */
final class RuntimeStartupValues {
    private RuntimeStartupValues() { }

    static void require(boolean condition, String message) {
        if (!condition) throw failure(message);
    }
    static IllegalStateException failure(String message) {
        return new IllegalStateException("Runtime startup: " + message);
    }
    static void identifier(String value) {
        require(value != null && value.matches("[a-z][a-z0-9_]{0,62}"), "invalid identifier");
    }
    static void key(String value) {
        require(value != null && value.matches("[a-z][a-z0-9_.-]{0,127}"), "invalid contract key");
    }
    static void qualifier(String value) {
        require(value != null && value.matches("[a-z][a-zA-Z0-9]{0,127}"), "invalid runtime qualifier");
    }
    static void digest(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"), "invalid digest");
    }
    static void uuid(String value) {
        try { require(UUID.fromString(value).toString().equals(value), "noncanonical UUID"); }
        catch (Exception exception) { throw failure("noncanonical UUID"); }
    }
    static Instant instant(String value) {
        try {
            Instant parsed = Instant.parse(value);
            require(parsed.toString().equals(value), "noncanonical UTC instant");
            return parsed;
        } catch (Exception exception) { throw failure("noncanonical UTC instant"); }
    }
    static void controlReference(String value) {
        require(value != null && value.matches("dwp-migration-control-v2:[0-9a-f]{64}"),
                "invalid Control reference");
    }
    static void signatureEncoding(String encoded) {
        try {
            require(encoded != null && encoded.matches("[A-Za-z0-9_-]{86}"), "signature encoding rejected");
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            require(bytes.length == 64 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded),
                    "signature encoding rejected");
        } catch (Exception exception) { throw failure("signature encoding rejected"); }
    }
    static <T> List<T> ordered(List<T> input, Function<T, String> key, String label) {
        List<T> result = List.copyOf(Objects.requireNonNull(input));
        String previous = null;
        for (T item : result) {
            String current = key.apply(Objects.requireNonNull(item));
            require(current != null && (previous == null || previous.compareTo(current) < 0),
                    label + " must be unique and ordered");
            previous = current;
        }
        return result;
    }
}
