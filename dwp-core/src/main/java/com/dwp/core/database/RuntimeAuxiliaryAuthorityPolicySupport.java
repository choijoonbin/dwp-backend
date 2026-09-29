package com.dwp.core.database;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonicalizes declaration-only auxiliary authority policy values. */
final class RuntimeAuxiliaryAuthorityPolicySupport {
    private RuntimeAuxiliaryAuthorityPolicySupport() {
    }

    static Set<String> canonicalRoleSet(String name, Set<String> values) {
        Objects.requireNonNull(values, name + " must not be null");
        Set<String> canonical = new LinkedHashSet<>();
        for (String value : values) {
            requireIdentifier(name, value);
            if (!canonical.add(value)) {
                throw new IllegalArgumentException(name + " must be unique");
            }
        }
        return Set.copyOf(canonical);
    }

    static String canonicalOptionalRole(String name, String value) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!value.isEmpty()) {
            requireIdentifier(name, value);
        }
        return value;
    }

    static Map<String, String> canonicalSchemaOwners(Map<String, String> values) {
        Objects.requireNonNull(values, "protectedSchemaOwners must not be null");
        Map<String, String> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            requireIdentifier("protected schema", entry.getKey());
            requireIdentifier("protected schema owner", entry.getValue());
            canonical.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(canonical);
    }

    private static void requireIdentifier(String name, String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
    }
}
