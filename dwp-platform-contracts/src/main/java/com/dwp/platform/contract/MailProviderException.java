package com.dwp.platform.contract;

import java.time.Instant;

/**
 * Safe provider-operation failure contract. Messages contain only a bounded canonical code;
 * provider bodies, credentials, tokens and opaque cursors must never be attached.
 */
public final class MailProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Category category;
    private final String errorCode;
    private final Instant retryAfter;

    public MailProviderException(Category category, String errorCode, Instant retryAfter) {
        super(validCode(errorCode));
        if (category == null) throw new IllegalArgumentException("category is required");
        if (category == Category.RATE_LIMIT && retryAfter == null) {
            throw new IllegalArgumentException("rate-limit failures require retryAfter");
        }
        this.category = category;
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
    }

    public Category category() {
        return category;
    }

    public String errorCode() {
        return errorCode;
    }

    public Instant retryAfter() {
        return retryAfter;
    }

    private static String validCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_.-]{1,120}")) {
            throw new IllegalArgumentException("errorCode must be a safe canonical code");
        }
        return value;
    }

    public enum Category {
        AUTHENTICATION,
        RATE_LIMIT,
        TRANSIENT,
        PERMANENT,
        RESULT_UNKNOWN
    }
}
