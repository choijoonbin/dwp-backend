package com.dwp.services.notification.domain;

import java.time.Instant;

final class NotificationTriagePolicy {

    private static final long MAX_SNOOZE_SECONDS = 366L * 24 * 60 * 60;

    private NotificationTriagePolicy() {
    }

    static NotificationTriageState transition(
            NotificationTriageState current,
            String action,
            Instant snoozedUntil,
            Instant now) {
        return switch (action) {
            case "READ" -> current.readAt() == null ? current.withReadAt(now) : current;
            case "UNREAD" -> current.readAt() != null ? current.withReadAt(null) : current;
            case "SAVE" -> current.savedAt() == null ? current.withSavedAt(now) : current;
            case "UNSAVE" -> current.savedAt() != null ? current.withSavedAt(null) : current;
            case "COMPLETE" -> "DONE".equals(current.inboxState())
                    ? current
                    : current.complete(now);
            case "RESTORE" -> "DONE".equals(current.inboxState())
                    ? current.restore()
                    : current;
            case "SNOOZE" -> {
                if (snoozedUntil == null
                        || !snoozedUntil.isAfter(now)
                        || snoozedUntil.isAfter(now.plusSeconds(MAX_SNOOZE_SECONDS))) {
                    throw new IllegalArgumentException("Snooze time is outside the allowed range.");
                }
                yield snoozedUntil.equals(current.snoozedUntil())
                        ? current
                        : current.snooze(snoozedUntil);
            }
            default -> throw new IllegalArgumentException("Unsupported notification action.");
        };
    }
}
