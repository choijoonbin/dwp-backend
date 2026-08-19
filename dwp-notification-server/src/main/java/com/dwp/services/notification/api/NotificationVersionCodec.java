package com.dwp.services.notification.api;

import java.util.regex.Pattern;

public final class NotificationVersionCodec {

    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]{0,18}");

    private NotificationVersionCodec() {
    }

    public static String external(long value) {
        if (value < 0) throw new IllegalArgumentException("Version cannot be negative.");
        return Long.toString(value);
    }

    public static long positive(String value, String field) {
        long parsed = nonNegative(value, field);
        if (parsed == 0) throw new IllegalArgumentException(field + " must be positive.");
        return parsed;
    }

    public static long nonNegative(String value, String field) {
        if (value == null || !DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a decimal BIGINT string.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " exceeds BIGINT range.", exception);
        }
    }
}
