package com.dwp.services.notification.domain;

import java.time.Instant;
import java.util.UUID;

record NotificationTriageState(
        long userId,
        String inboxState,
        Instant readAt,
        Instant savedAt,
        Instant completedAt,
        Instant snoozedUntil,
        boolean actionRequired,
        String priority,
        UUID typeVersionId,
        String threadKey,
        long changeVersion,
        long version) {

    NotificationTriageState withReadAt(Instant value) {
        return new NotificationTriageState(
                userId, inboxState, value, savedAt, completedAt, snoozedUntil,
                actionRequired, priority, typeVersionId, threadKey, changeVersion, version);
    }

    NotificationTriageState withSavedAt(Instant value) {
        return new NotificationTriageState(
                userId, inboxState, readAt, value, completedAt, snoozedUntil,
                actionRequired, priority, typeVersionId, threadKey, changeVersion, version);
    }

    NotificationTriageState complete(Instant value) {
        return new NotificationTriageState(
                userId, "DONE", readAt, savedAt, value, null,
                actionRequired, priority, typeVersionId, threadKey, changeVersion, version);
    }

    NotificationTriageState restore() {
        return new NotificationTriageState(
                userId, "ACTIVE", readAt, savedAt, null, null,
                actionRequired, priority, typeVersionId, threadKey, changeVersion, version);
    }

    NotificationTriageState snooze(Instant value) {
        return new NotificationTriageState(
                userId, "ACTIVE", readAt, savedAt, null, value,
                actionRequired, priority, typeVersionId, threadKey, changeVersion, version);
    }

    NotificationTriageState restoreSnapshot(NotificationUndoSnapshot snapshot) {
        return new NotificationTriageState(
                userId,
                snapshot.inboxState(),
                snapshot.readAt(),
                snapshot.savedAt(),
                snapshot.completedAt(),
                snapshot.snoozedUntil(),
                actionRequired,
                priority,
                typeVersionId,
                threadKey,
                changeVersion,
                version);
    }
}
