package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record HomeRuntimeContext(
        long tenantId,
        long userId,
        UUID personPublicId,
        Set<String> permissions,
        Set<String> roles,
        Set<String> groupRefs,
        String authorityDecisionRevision,
        OffsetDateTime authorityRevalidateAt,
        String locale,
        String timeZone,
        String fingerprint) {

    private static final int MAX_AUTHORITY_HEADER = 16_384;

    public static HomeRuntimeContext create(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String permissions,
            String roles,
            String groupRefs,
            String decisionRevision,
            String revalidateAt,
            String locale,
            String timeZone) {
        if (tenantId == null || tenantId <= 0 || userId == null || userId <= 0) {
            throw new BaseException(ErrorCode.UNAUTHORIZED);
        }
        String revision = bounded(decisionRevision, 160);
        if (revision == null || !revision.matches("[A-Za-z0-9._:-]{1,160}")) {
            throw unavailable("Trusted Home authority decision revision is missing or invalid.");
        }
        OffsetDateTime revalidate = parseFuture(revalidateAt);
        Set<String> permissionSet = values(permissions, true);
        Set<String> roleSet = values(roles, true);
        Set<String> groups = values(groupRefs, false);
        String resolvedLocale = bounded(locale, 80);
        String resolvedTimeZone = bounded(timeZone, 80);
        String material = tenantId + "\n" + userId + "\n"
                + (personPublicId == null ? "" : personPublicId) + "\n"
                + revision + "\n"
                + String.join(",", permissionSet.stream().sorted().toList()) + "\n"
                + String.join(",", roleSet.stream().sorted().toList()) + "\n"
                + String.join(",", groups.stream().sorted().toList());
        return new HomeRuntimeContext(
                tenantId,
                userId,
                personPublicId,
                permissionSet,
                roleSet,
                groups,
                revision,
                revalidate,
                resolvedLocale == null ? "ko-KR" : resolvedLocale,
                resolvedTimeZone == null ? "Asia/Seoul" : resolvedTimeZone,
                sha256(material));
    }

    public boolean has(String authority) {
        return permissions.contains(authority.toUpperCase(Locale.ROOT));
    }

    public String permissionsHeader() {
        return String.join(",", permissions.stream().sorted().toList());
    }

    public String rolesHeader() {
        return String.join(",", roles.stream().sorted().toList());
    }

    public String groupsHeader() {
        return String.join(",", groupRefs.stream().sorted().toList());
    }

    private static Set<String> values(String header, boolean uppercase) {
        if (header == null || header.isBlank()) return Set.of();
        if (header.length() > MAX_AUTHORITY_HEADER) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Home authority context is too large.");
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> uppercase ? value.toUpperCase(Locale.ROOT) : value)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static OffsetDateTime parseFuture(String raw) {
        String value = bounded(raw, 80);
        if (value == null) throw unavailable("Trusted Home authority expiry is missing.");
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (!parsed.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
                throw unavailable("Trusted Home authority decision has expired.");
            }
            return parsed;
        } catch (DateTimeParseException exception) {
            throw unavailable("Trusted Home authority expiry is invalid.");
        }
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maximum || normalized.contains("\r") || normalized.contains("\n")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid Home request context.");
        }
        return normalized;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
