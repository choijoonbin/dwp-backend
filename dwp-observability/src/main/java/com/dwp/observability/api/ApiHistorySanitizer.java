package com.dwp.observability.api;

import java.util.Locale;
import java.util.regex.Pattern;

/** Low-cardinality and privacy-safe normalization for HTTP metadata. */
public final class ApiHistorySanitizer {

    private static final int MAX_PATH_LENGTH = 500;
    private static final Pattern UUID = Pattern.compile(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern INTEGER = Pattern.compile("^[0-9]{1,20}$");
    private static final Pattern LONG_HEX = Pattern.compile("(?i)^[0-9a-f]{13,}$");
    private static final Pattern TOKEN_LIKE = Pattern.compile("^[A-Za-z0-9_-]{25,}$");

    private ApiHistorySanitizer() {
    }

    public static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "/";
        String path = rawPath.split("[?#]", 2)[0].replaceAll("/{2,}", "/");
        StringBuilder normalized = new StringBuilder();
        for (String rawSegment : path.split("/", -1)) {
            if (rawSegment.isEmpty()) continue;
            String segment = rawSegment.split(";", 2)[0];
            normalized.append('/').append(normalizeSegment(segment));
            if (normalized.length() >= MAX_PATH_LENGTH) break;
        }
        if (normalized.isEmpty()) return "/";
        return truncate(normalized.toString(), MAX_PATH_LENGTH);
    }

    public static String normalizeRouteTemplate(Object template, String fallbackPath) {
        if (template == null || String.valueOf(template).isBlank()) {
            return normalizePath(fallbackPath);
        }
        return truncate(String.valueOf(template).split("[?#]", 2)[0], MAX_PATH_LENGTH);
    }

    public static String userAgentFamily(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "UNKNOWN";
        String value = userAgent.toLowerCase(Locale.ROOT);
        if (value.contains("edg/")) return "EDGE";
        if (value.contains("chrome/") || value.contains("chromium/")) return "CHROMIUM";
        if (value.contains("firefox/")) return "FIREFOX";
        if (value.contains("safari/") && !value.contains("chrome/")) return "SAFARI";
        if (value.contains("curl/")) return "CURL";
        if (value.contains("postman")) return "POSTMAN";
        if (value.contains("httpx")) return "HTTPX";
        if (value.contains("java-http-client") || value.contains("reactor-netty")) return "SERVICE";
        return "OTHER";
    }

    public static String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) return value;
        return value.substring(0, maximumLength);
    }

    private static String normalizeSegment(String segment) {
        if (segment.isBlank()) return "{value}";
        if (segment.indexOf('@') >= 0 || segment.toLowerCase(Locale.ROOT).contains("%40")) {
            return "{value}";
        }
        if (UUID.matcher(segment).matches()
                || INTEGER.matcher(segment).matches()
                || LONG_HEX.matcher(segment).matches()) {
            return "{id}";
        }
        if (segment.length() > 64 || TOKEN_LIKE.matcher(segment).matches()) return "{token}";
        return segment;
    }
}
