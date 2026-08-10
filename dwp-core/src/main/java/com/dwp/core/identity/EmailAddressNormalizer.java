package com.dwp.core.identity;

import java.util.Locale;

public final class EmailAddressNormalizer {

    private static final int MAXIMUM_LENGTH = 255;

    private EmailAddressNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static String requireValid(String value) {
        String normalized = normalize(value);
        if (normalized == null || normalized.length() > MAXIMUM_LENGTH) {
            throw new IllegalArgumentException("A company email is required and must be at most 255 characters.");
        }
        int separator = normalized.lastIndexOf('@');
        if (separator <= 0
                || separator == normalized.length() - 1
                || normalized.indexOf('@') != separator
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("The company email format is invalid.");
        }
        return normalized;
    }
}
