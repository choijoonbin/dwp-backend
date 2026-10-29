package com.dwp.platform.contract;

import java.time.Instant;

/**
 * Safe, typed sync failure contract for provider adapters. The message is always the bounded
 * error code; credentials, response bodies, cursors, and provider tokens must not be included.
 */
public final class MailSyncProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Category category;
    private final String errorCode;
    private final Instant retryAfter;
    private final String resetCursor;

    public MailSyncProviderException(
            Category category,
            String errorCode,
            Instant retryAfter,
            String resetCursor) {
        super(validCode(errorCode));
        if (category == null) throw new IllegalArgumentException("category is required");
        if (category == Category.RATE_LIMIT && retryAfter == null) {
            throw new IllegalArgumentException("rate-limit failures require retryAfter");
        }
        if (category == Category.CURSOR_RESET
                && (resetCursor == null || resetCursor.isBlank())) {
            throw new IllegalArgumentException(
                    "cursor reset failures require a provider full-sync seed");
        }
        this.category = category;
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
        this.resetCursor = resetCursor;
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

    /** Opaque provider seed. It may be persisted only in the fenced account cursor column. */
    public String resetCursor() {
        return resetCursor;
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
        CURSOR_RESET,
        TRANSIENT,
        PERMANENT
    }
}
