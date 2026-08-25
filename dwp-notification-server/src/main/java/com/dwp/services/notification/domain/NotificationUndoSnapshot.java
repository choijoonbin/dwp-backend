package com.dwp.services.notification.domain;

import java.time.Instant;
import java.util.UUID;

record NotificationUndoSnapshot(
        UUID notificationId,
        String inboxState,
        Instant readAt,
        Instant savedAt,
        Instant completedAt,
        Instant snoozedUntil,
        long expectedVersion) {
}
