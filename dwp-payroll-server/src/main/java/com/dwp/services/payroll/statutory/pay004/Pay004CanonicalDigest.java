package com.dwp.services.payroll.statutory.pay004;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/** Versioned, length-framed SHA-256 for immutable PAY-004 domain facts. */
final class Pay004CanonicalDigest {

    private static final DateTimeFormatter INSTANT =
            new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    private Pay004CanonicalDigest() {
    }

    static String sha256(String schema, Object... orderedFacts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            frame(digest, schema);
            for (Object fact : orderedFacts) {
                frame(digest, canonicalText(fact));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "PAY-004 canonical digest authority is unavailable.",
                    exception);
        }
    }

    private static String canonicalText(Object fact) {
        if (fact == null) return null;
        if (fact instanceof Instant instant) return INSTANT.format(instant);
        return fact.toString();
    }

    private static void frame(MessageDigest digest, String value) {
        if (value == null) {
            digest.update("-1:".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(
                StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
    }
}
