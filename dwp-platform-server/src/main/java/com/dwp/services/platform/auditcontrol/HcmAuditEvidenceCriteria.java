package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

record HcmAuditEvidenceCriteria(
        Long tenantId,
        AuditWindow window,
        String category,
        String severity,
        String outcome,
        String sourceService,
        String query,
        Instant from,
        Instant snapshotAt,
        int size) {

    static HcmAuditEvidenceCriteria of(
            Long tenantId,
            AuditWindow window,
            String category,
            String severity,
            String outcome,
            String sourceService,
            String query,
            int size,
            Instant snapshotAt,
            Set<String> registeredServices) {
        if (tenantId == null || tenantId <= 0) throw invalid("Verified tenant is invalid.");
        if (size < 10 || size > 100) throw invalid("Page size must be between 10 and 100.");
        AuditWindow resolvedWindow = window == null ? AuditWindow.D7 : window;
        String resolvedCategory = exactEnum(
                category, "ALL", Set.of("ALL", "ADMIN_CHANGE", "POLICY_DENIED"), "category");
        String resolvedSeverity = exactEnum(
                severity, "ALL", Set.of("ALL", "INFO", "HIGH", "CRITICAL"), "severity");
        String resolvedOutcome = exactEnum(
                outcome, "ALL", Set.of("ALL", "SUCCESS", "DENIED", "FAILED"), "outcome");
        String resolvedService = normalized(sourceService, 120, "sourceService");
        if (resolvedService != null && !registeredServices.contains(resolvedService)) {
            throw invalid("Audit source service is not registered.");
        }
        String resolvedQuery = normalized(query, 160, "query");
        if (resolvedQuery != null && (!resolvedQuery.matches("[A-Za-z0-9.:/_-]+")
                || resolvedQuery.indexOf('%') >= 0)) {
            throw invalid("Audit evidence query contains unsupported characters.");
        }
        Instant resolvedSnapshot = snapshotAt == null ? Instant.now() : snapshotAt;
        return new HcmAuditEvidenceCriteria(
                tenantId,
                resolvedWindow,
                resolvedCategory,
                resolvedSeverity,
                resolvedOutcome,
                resolvedService,
                resolvedQuery,
                resolvedSnapshot.minus(resolvedWindow.duration()),
                resolvedSnapshot,
                size);
    }

    HcmAuditEvidenceCriteria atSnapshot(Instant value) {
        if (value == null) throw invalid("Audit evidence snapshot is invalid.");
        return new HcmAuditEvidenceCriteria(
                tenantId, window, category, severity, outcome, sourceService, query,
                value.minus(window.duration()), value, size);
    }

    String filterDigest() {
        String canonical = String.join("\n",
                window.name(), category, severity, outcome,
                sourceService == null ? "" : sourceService,
                query == null ? "" : query,
                Integer.toString(size));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String exactEnum(
            String value, String fallback, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid("Unsupported " + field + " filter.");
        return normalized;
    }

    private static String normalized(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max
                || normalized.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw invalid("Invalid " + field + " filter.");
        }
        return normalized;
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
