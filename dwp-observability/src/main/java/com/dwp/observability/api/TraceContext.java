package com.dwp.observability.api;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal W3C Trace Context parser and child span generator. */
public record TraceContext(
        String traceId,
        String spanId,
        String parentSpanId,
        boolean sampled) {

    private static final Pattern VERSION_ZERO = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public static TraceContext childOf(String traceParent) {
        Matcher matcher = traceParent == null
                ? VERSION_ZERO.matcher("")
                : VERSION_ZERO.matcher(traceParent.trim().toLowerCase());
        if (matcher.matches()
                && !allZero(matcher.group(1))
                && !allZero(matcher.group(2))) {
            int flags = Integer.parseInt(matcher.group(3), 16);
            return new TraceContext(
                    matcher.group(1), randomHex(8), matcher.group(2), (flags & 1) == 1);
        }
        return new TraceContext(randomHex(16), randomHex(8), null, true);
    }

    public String traceParent() {
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        do {
            RANDOM.nextBytes(value);
        } while (allZero(value));
        return HexFormat.of().formatHex(value);
    }

    private static boolean allZero(String value) {
        return value.chars().allMatch(character -> character == '0');
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) if (item != 0) return false;
        return true;
    }
}
