package com.dwp.migration.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ControlValues {

    private ControlValues() {
    }

    static void append(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update("-1:".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        String canonical = value instanceof Boolean bool
                ? (bool ? "true" : "false")
                : value.toString();
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    static MessageDigest sha256() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }

    static String finish(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    static String identifier(String value) {
        if (!value.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalStateException("Unsafe migration Control identifier");
        }
        return value;
    }

    static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing migration Control environment: " + name);
        }
        return value;
    }

    static String json(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('\"').toString();
    }
}
