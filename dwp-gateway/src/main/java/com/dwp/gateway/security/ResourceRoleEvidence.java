package com.dwp.gateway.security;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Canonical wire format for exact-scope application-governance authority evidence. */
public final class ResourceRoleEvidence {

    private static final Pattern RESPONSIBILITY =
            Pattern.compile("[A-Z][A-Z0-9_]{2,49}");
    private static final Pattern RESOURCE_SET_KEY =
            Pattern.compile("[A-Z][A-Z0-9_.-]{2,254}");

    private ResourceRoleEvidence() {
    }

    public static String canonicalOrNull(
            String responsibilityCode,
            String resourceSetKey) {
        if (responsibilityCode == null || resourceSetKey == null) return null;
        String responsibility = responsibilityCode.trim().toUpperCase(Locale.ROOT);
        String resource = resourceSetKey.trim().toUpperCase(Locale.ROOT);
        if (!RESPONSIBILITY.matcher(responsibility).matches()
                || !RESOURCE_SET_KEY.matcher(resource).matches()) {
            return null;
        }
        return responsibility + "@" + resource;
    }

    public static List<String> parseHeaderStrict(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) return List.of();
        TreeSet<String> normalized = new TreeSet<>();
        for (String rawRole : rawHeader.split(",", -1)) {
            String role = rawRole.trim();
            int separator = role.indexOf('@');
            if (separator <= 0
                    || separator != role.lastIndexOf('@')
                    || separator == role.length() - 1) {
                throw invalidEvidence();
            }
            String canonical = canonicalOrNull(
                    role.substring(0, separator),
                    role.substring(separator + 1));
            if (canonical == null) throw invalidEvidence();
            normalized.add(canonical);
        }
        return List.copyOf(normalized);
    }

    private static IllegalArgumentException invalidEvidence() {
        return new IllegalArgumentException("Resource-role evidence is invalid.");
    }
}
