package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

final class VideoMeetingCommandPolicy {

    private VideoMeetingCommandPolicy() {
    }

    static String commandKey(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 8 || normalized.length() > 160
                || !normalized.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$")) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Idempotency-Key must contain 8 to 160 safe characters.");
        }
        return normalized;
    }

    static String correlation(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? UUID.randomUUID().toString()
                : normalized.substring(0, Math.min(160, normalized.length()));
    }

    static String requestHash(Object... values) {
        String material = java.util.Arrays.stream(values)
                .map(value -> Objects.toString(value, ""))
                .reduce((left, right) -> left + "\u001f" + right)
                .orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    static boolean requestHashesMatch(String first, String second) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    static List<Long> canonicalUserIds(List<Long> userIds) {
        return userIds == null ? List.of() : userIds.stream().distinct().sorted().toList();
    }

    static List<String> canonicalGuests(List<VideoMeetingDtos.GuestInvitee> guests) {
        if (guests == null) return List.of();
        return guests.stream()
                .map(guest -> guest.emailAddress().trim().toLowerCase(Locale.ROOT)
                        + ":" + guest.displayName().trim())
                .distinct().sorted().toList();
    }

    static String normalized(String value) {
        return value.trim();
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
