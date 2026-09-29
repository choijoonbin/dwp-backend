package com.dwp.services.people.hris.contracts.workforce.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Untrusted query values; tenant authority and cursor claims are resolved owner-side. */
public record WorkforceSnapshotQuery(
        String purposeCode,
        Instant asOf,
        WorkforceSnapshotProjection projection,
        int limit,
        String cursorToken) {

    public static final String PURPOSE_CODE = "HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP";
    public static final int MAX_LIMIT = 500;
    public static final int MAX_CURSOR_LENGTH = 2_048;

    public WorkforceSnapshotQuery {
        if (!PURPOSE_CODE.equals(purposeCode)) {
            throw new IllegalArgumentException(
                    "purposeCode must be HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP");
        }
        Objects.requireNonNull(asOf, "asOf must not be null");
        if (projection != WorkforceSnapshotProjection.PERFORMANCE_V1) {
            throw new IllegalArgumentException("projection must be PERFORMANCE_V1");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (cursorToken != null) {
            cursorToken = validatedToken(cursorToken, "cursorToken");
        }
    }

    public String cursorTokenDigest() {
        return digestToken(cursorToken);
    }

    static String digestToken(String token) {
        if (token == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String validatedToken(String value, String fieldName) {
        if (value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_CURSOR_LENGTH) {
            throw new IllegalArgumentException(
                    fieldName + " must be nonblank and at most "
                            + MAX_CURSOR_LENGTH + " UTF-8 bytes");
        }
        return value;
    }
}
