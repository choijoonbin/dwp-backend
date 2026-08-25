package com.dwp.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Opaque, deterministic wire token proving that an exact capability came from
 * a scoped authority assignment. The resolved capability remains the semantic
 * authority; this token only binds it to a resource-set key on trusted headers.
 */
public final class ScopedAuthorityToken {

    private static final String PREFIX = "SCOPED_";

    private ScopedAuthorityToken() {
    }

    public static String responsibilityCode(
            String capabilityContractKey,
            String resolvedCapabilityCode) {
        String contractKey = canonicalContract(capabilityContractKey);
        String canonical = canonicalCapability(resolvedCapabilityCode);
        try {
            String digest = HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest((contractKey + '\n' + canonical)
                                    .getBytes(StandardCharsets.UTF_8)));
            return PREFIX + digest.substring(0, 40);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static String wireToken(
            String capabilityContractKey,
            String resolvedCapabilityCode,
            String resourceSetKey) {
        String canonicalSet = resourceSetKey == null
                ? "" : resourceSetKey.trim().toUpperCase(Locale.ROOT);
        if (!canonicalSet.matches("[A-Z][A-Z0-9_]{2,79}")) {
            throw new IllegalArgumentException("resourceSetKey is not canonical.");
        }
        return responsibilityCode(capabilityContractKey, resolvedCapabilityCode)
                + '@' + canonicalSet;
    }

    /** Returns the exact resource sets carried by trusted tokens for one capability. */
    public static Set<String> matchingResourceSetKeys(
            Collection<String> wireTokens,
            String capabilityContractKey,
            String resolvedCapabilityCode) {
        if (wireTokens == null || wireTokens.isEmpty()) return Set.of();
        String expected = responsibilityCode(capabilityContractKey, resolvedCapabilityCode);
        Set<String> result = new TreeSet<>();
        for (String value : wireTokens) {
            if (value == null) continue;
            String canonical = value.trim().toUpperCase(Locale.ROOT);
            int separator = canonical.indexOf('@');
            if (separator <= 0 || separator != canonical.lastIndexOf('@')
                    || !expected.equals(canonical.substring(0, separator))) {
                continue;
            }
            String resourceSetKey = canonical.substring(separator + 1);
            if (resourceSetKey.matches("[A-Z][A-Z0-9_]{2,79}")) {
                result.add(resourceSetKey);
            }
        }
        return Set.copyOf(result);
    }

    private static String canonicalContract(String value) {
        String canonical = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!canonical.matches("[a-z][a-z0-9.-]{2,179}")) {
            throw new IllegalArgumentException("capabilityContractKey is not canonical.");
        }
        return canonical;
    }

    private static String canonicalCapability(String value) {
        String canonical = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!canonical.matches(
                "[A-Z][A-Z0-9._-]{2,254}:[A-Z][A-Z0-9_]{1,49}")) {
            throw new IllegalArgumentException("resolvedCapabilityCode is not canonical.");
        }
        return canonical;
    }
}
