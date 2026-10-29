package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Versioned people-directory cursor bound to actor, tenant, and normalized filters. */
final class MessagingPeopleCursor {

    private static final String VERSION = "v2";
    private static final int MAX_CURSOR_LENGTH = 2_048;
    private static final int MAX_DISPLAY_NAME_BYTES = 640;

    private MessagingPeopleCursor() {
    }

    static Cursor decode(
            String value,
            long tenantId,
            long userId,
            String query,
            String organization,
            String jobTitle,
            String presenceFilter) {
        if (value == null || value.isBlank()) return null;
        if (!value.equals(value.trim()) || value.length() > MAX_CURSOR_LENGTH) throw invalid();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String[] fields = new String(decoded, StandardCharsets.UTF_8).split("\\n", -1);
            if (fields.length != 5 || !VERSION.equals(fields[0])
                    || !fingerprint(
                            tenantId, userId, query, organization, jobTitle, presenceFilter)
                            .equals(fields[1])) {
                throw invalid();
            }
            byte[] nameBytes = Base64.getUrlDecoder().decode(fields[2]);
            if (nameBytes.length == 0 || nameBytes.length > MAX_DISPLAY_NAME_BYTES) throw invalid();
            String displayName = new String(nameBytes, StandardCharsets.UTF_8);
            UUID personPublicId = UUID.fromString(fields[3]);
            if (!fields[2].equals(Base64.getUrlEncoder().withoutPadding().encodeToString(nameBytes))
                    || !personPublicId.toString().equals(fields[3])
                    || !fields[4].isEmpty()) {
                throw invalid();
            }
            Cursor cursor = new Cursor(displayName, personPublicId);
            if (!value.equals(encode(
                    cursor, tenantId, userId, query, organization, jobTitle, presenceFilter))) {
                throw invalid();
            }
            return cursor;
        } catch (RuntimeException exception) {
            if (exception instanceof BaseException base) throw base;
            throw invalid();
        }
    }

    static String encode(
            MessagingDtos.PersonSummary person,
            long tenantId,
            long userId,
            String query,
            String organization,
            String jobTitle,
            String presenceFilter) {
        if (person.personPublicId() == null) {
            throw new IllegalStateException("People directory public identity is required.");
        }
        return encode(
                new Cursor(person.displayName(), person.personPublicId()),
                tenantId, userId, query, organization, jobTitle, presenceFilter);
    }

    private static String encode(
            Cursor cursor,
            long tenantId,
            long userId,
            String query,
            String organization,
            String jobTitle,
            String presenceFilter) {
        String encodedName = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(cursor.displayName().getBytes(StandardCharsets.UTF_8));
        String payload = String.join(
                "\n",
                VERSION,
                fingerprint(tenantId, userId, query, organization, jobTitle, presenceFilter),
                encodedName,
                cursor.personPublicId().toString(),
                "");
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String fingerprint(
            long tenantId,
            long userId,
            String query,
            String organization,
            String jobTitle,
            String presenceFilter) {
        String canonical = String.join(
                "\u0000",
                Long.toString(tenantId),
                Long.toString(userId),
                query,
                organization,
                jobTitle,
                presenceFilter);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private static BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "The people directory cursor is invalid or belongs to different filters.");
    }

    record Cursor(String displayName, UUID personPublicId) {
    }
}
