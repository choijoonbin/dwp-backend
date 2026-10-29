package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/** Versioned, subject- and filter-bound cursor for the durable conversation ordering. */
final class MessagingConversationCursor {

    private static final String VERSION = "v1";
    private static final int MAX_CURSOR_LENGTH = 1_024;

    private MessagingConversationCursor() {
    }

    static Cursor decode(
            String value,
            long tenantId,
            long userId,
            String scope,
            String query) {
        if (value == null || value.isBlank()) return null;
        if (!value.equals(value.trim()) || value.length() > MAX_CURSOR_LENGTH) throw invalid();
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] fields = decoded.split("\\n", -1);
            if (fields.length != 6 || !VERSION.equals(fields[0])
                    || !fingerprint(tenantId, userId, scope, query).equals(fields[1])) {
                throw invalid();
            }
            boolean pinned = flag(fields[2]);
            boolean favorite = flag(fields[3]);
            OffsetDateTime lastMessageAt = "~".equals(fields[4])
                    ? null
                    : OffsetDateTime.ofInstant(Instant.parse(fields[4]), ZoneOffset.UTC);
            UUID conversationId = UUID.fromString(fields[5]);
            Cursor cursor = new Cursor(pinned, favorite, lastMessageAt, conversationId);
            if (!value.equals(encode(cursor, tenantId, userId, scope, query))) throw invalid();
            return cursor;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    static String encode(
            MessagingDtos.ConversationSummary conversation,
            long tenantId,
            long userId,
            String scope,
            String query) {
        return encode(new Cursor(
                conversation.pinned(), conversation.favorite(),
                conversation.lastMessageAt(), conversation.conversationId()),
                tenantId, userId, scope, query);
    }

    private static String encode(
            Cursor cursor,
            long tenantId,
            long userId,
            String scope,
            String query) {
        String canonical = String.join(
                "\n",
                VERSION,
                fingerprint(tenantId, userId, scope, query),
                cursor.pinned() ? "1" : "0",
                cursor.favorite() ? "1" : "0",
                cursor.lastMessageAt() == null
                        ? "~"
                        : cursor.lastMessageAt().toInstant().toString(),
                cursor.conversationId().toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean flag(String value) {
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw invalid();
    }

    private static String fingerprint(
            long tenantId,
            long userId,
            String scope,
            String query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (tenantId + "\u0000" + userId + "\u0000" + scope + "\u0000" + query)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private static BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "The conversation cursor is invalid or belongs to a different subject or filter.");
    }

    record Cursor(
            boolean pinned,
            boolean favorite,
            OffsetDateTime lastMessageAt,
            UUID conversationId) {
    }
}
