package com.dwp.services.platform.auditcontrol;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public record AuditCriteria(
        Long tenantId,
        AuditWindow window,
        String category,
        String severity,
        String outcome,
        String sourceService,
        String actor,
        String query,
        Instant from,
        Instant to) {

    private static final Set<String> CATEGORIES = Set.of(
            "ALL", "ADMIN_CHANGE", "AUTHENTICATION", "AUTHORIZATION", "DATA_ACCESS",
            "DATA_EXPORT", "PROVISIONING", "AI_ACTION", "POLICY_DENIED", "SYSTEM_EVENT");
    private static final Set<String> SEVERITIES = Set.of(
            "ALL", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> OUTCOMES = Set.of("ALL", "SUCCESS", "DENIED", "FAILED");

    public static AuditCriteria of(
            Long tenantId,
            AuditWindow window,
            String category,
            String severity,
            String outcome,
            String sourceService,
            String actor,
            String query,
            Instant now) {
        AuditWindow resolved = window == null ? AuditWindow.D7 : window;
        return new AuditCriteria(
                tenantId,
                resolved,
                member(category, "ALL", CATEGORIES),
                member(severity, "ALL", SEVERITIES),
                member(outcome, "ALL", OUTCOMES),
                clean(sourceService, 120),
                clean(actor, 160),
                clean(query, 200),
                now.minus(resolved.duration()),
                now);
    }

    private static String member(String value, String fallback, Set<String> allowed) {
        String normalized = value == null ? fallback : value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.trim();
        return cleaned.substring(0, Math.min(cleaned.length(), max));
    }
}
