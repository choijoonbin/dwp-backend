package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Versioned opaque cursor bound to the requesting subject and scheduled-message status filter. */
final class MessagingScheduledCursor {

    private MessagingScheduledCursor() { }

    static String encode(
            MessagingContinuityDtos.ScheduledMessageResponse item,
            long tenantId,
            long userId,
            String statusKey) {
        String payload = "v1\n" + fingerprint(tenantId, userId, statusKey) + "\n"
                + item.createdAt() + "\n"
                + item.scheduledMessageId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(
            String value, long tenantId, long userId, String expectedStatusKey) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 512) throw invalid();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(decoded);
            if (!canonical.equals(value)) throw invalid();
            String[] fields = new String(decoded, StandardCharsets.UTF_8).split("\\n", -1);
            if (fields.length != 4 || !"v1".equals(fields[0])
                    || !fingerprint(tenantId, userId, expectedStatusKey).equals(fields[1])) {
                throw invalid();
            }
            return new Cursor(OffsetDateTime.parse(fields[2]), UUID.fromString(fields[3]));
        } catch (RuntimeException exception) {
            if (exception instanceof BaseException base) throw base;
            throw invalid();
        }
    }

    private static BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "Scheduled message cursor is invalid for the requesting subject or status filter.");
    }

    private static String fingerprint(long tenantId, long userId, String statusKey) {
        String canonical = tenantId + ":" + userId + ":" + statusKey;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    record Cursor(OffsetDateTime createdAt, UUID scheduledMessageId) { }
}
