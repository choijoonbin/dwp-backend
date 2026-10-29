package com.dwp.services.payroll.statutory.pay003;

import java.util.UUID;

/** Canonical validation shared across PAY-003 authority and transport values. */
final class Pay003ValueValidation {

    private Pay003ValueValidation() {
    }

    static void token(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static void digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static String canonicalUuid(String value, String name) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) throw new IllegalArgumentException();
            return canonical;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " is not a canonical UUID");
        }
    }
}
