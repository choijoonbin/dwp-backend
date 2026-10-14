package com.dwp.services.time.timecard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Stable digest and identity helpers for receipts, ledger facts, and v1 events. */
final class TimecardDigests {

    private TimecardDigests() {}

    static String sha256(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '|');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static UUID stableUuid(Object... values) {
        return UUID.nameUUIDFromBytes(String.join("|", java.util.Arrays.stream(values)
                        .map(String::valueOf)
                        .toList())
                .getBytes(StandardCharsets.UTF_8));
    }
}
