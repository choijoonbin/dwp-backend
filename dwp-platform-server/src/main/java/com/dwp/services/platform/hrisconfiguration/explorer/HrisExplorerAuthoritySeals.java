package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.security.HcmEligibilityScopeKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Closed deterministic encodings used by Explorer authority and receipt seals. */
public final class HrisExplorerAuthoritySeals {

    public static final long MAX_JSON_SAFE_INTEGER = (1L << 53) - 1L;

    private HrisExplorerAuthoritySeals() {
    }

    public static String populationScopeDigest(String canonicalDerivedHcmScopeKey) {
        if (!HcmEligibilityScopeKey.isCanonical(canonicalDerivedHcmScopeKey)) {
            throw new IllegalArgumentException(
                    "A canonical current HCM eligibility scope is required.");
        }
        return HexFormat.of().formatHex(digest(
                "population-scope:" + canonicalDerivedHcmScopeKey));
    }

    public static long numericRevision(String domain, String sourceRevision) {
        return numericRevision(domain, sourceRevision, MAX_JSON_SAFE_INTEGER);
    }

    public static long gatewayAuthorityRevision(
            String domain, String sourceRevision) {
        return numericRevision(domain, sourceRevision, Long.MAX_VALUE);
    }

    /** Public JSON projection of a trusted positive 63-bit Gateway revision. */
    public static long publicRevision(long authorityRevision) {
        if (authorityRevision < 1) {
            throw new IllegalArgumentException("A positive authority revision is required.");
        }
        long projected = authorityRevision & MAX_JSON_SAFE_INTEGER;
        return projected == 0L ? 1L : projected;
    }

    private static long numericRevision(
            String domain, String sourceRevision, long mask) {
        requireCanonical(domain, 80, "revision domain");
        requireCanonical(sourceRevision, 500, "source revision");
        byte[] digest = digest(domain + ':' + sourceRevision);
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            value = (value << 8) | (digest[index] & 0xffL);
        }
        value &= mask;
        return value == 0L ? 1L : value;
    }

    public static String sha256Hex(String material) {
        if (material == null) throw new IllegalArgumentException("Digest material is required.");
        return HexFormat.of().formatHex(digest(material));
    }

    private static byte[] digest(String material) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    material.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private static void requireCanonical(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip()) || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " is not canonical.");
        }
    }
}
