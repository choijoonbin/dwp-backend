package com.dwp.services.platform.apihistory;

import com.dwp.observability.api.ApiHistorySanitizer;

import java.time.Instant;
import java.util.Locale;

public record ApiHistoryCriteria(
        Long tenantId,
        ApiHistoryWindow window,
        String observationPoint,
        String serviceName,
        String httpMethod,
        String outcome,
        String query,
        Instant from,
        Instant to) {

    public static ApiHistoryCriteria of(
            Long tenantId,
            ApiHistoryWindow window,
            String observationPoint,
            String serviceName,
            String httpMethod,
            String outcome,
            String query,
            Instant now) {
        ApiHistoryWindow resolvedWindow = window == null ? ApiHistoryWindow.H24 : window;
        return new ApiHistoryCriteria(
                tenantId,
                resolvedWindow,
                enumFilter(observationPoint, "GATEWAY", "GATEWAY", "SERVICE", "ALL"),
                clean(serviceName, 120),
                upper(httpMethod, 12),
                enumFilter(outcome, "ALL", "SUCCESS", "REDIRECTION", "CLIENT_ERROR", "SERVER_ERROR", "ALL"),
                clean(query, 160),
                now.minus(resolvedWindow.duration()),
                now);
    }

    public String fingerprint() {
        return String.join("|",
                window.name(),
                observationPoint,
                nullToEmpty(serviceName),
                nullToEmpty(httpMethod),
                outcome,
                nullToEmpty(query));
    }

    private static String enumFilter(String value, String fallback, String... allowed) {
        String normalized = value == null ? fallback : value.trim().toUpperCase(Locale.ROOT);
        for (String item : allowed) if (item.equals(normalized)) return normalized;
        return fallback;
    }

    private static String upper(String value, int max) {
        String cleaned = clean(value, max);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return ApiHistorySanitizer.truncate(value.trim(), max);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
