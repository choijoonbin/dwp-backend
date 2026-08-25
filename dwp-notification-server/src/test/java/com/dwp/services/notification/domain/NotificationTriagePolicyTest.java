package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTriagePolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-24T03:00:00Z");

    @Test
    void readSaveCompleteAndRestorePreserveIndependentDimensions() {
        NotificationTriageState initial = state(null, null, null, null);

        NotificationTriageState read = transition(initial, "READ", null);
        NotificationTriageState saved = transition(read, "SAVE", null);
        NotificationTriageState completed = transition(saved, "COMPLETE", null);
        NotificationTriageState restored = transition(completed, "RESTORE", null);

        assertThat(completed.inboxState()).isEqualTo("DONE");
        assertThat(completed.readAt()).isEqualTo(NOW);
        assertThat(completed.savedAt()).isEqualTo(NOW);
        assertThat(completed.completedAt()).isEqualTo(NOW);
        assertThat(restored.inboxState()).isEqualTo("ACTIVE");
        assertThat(restored.readAt()).isEqualTo(NOW);
        assertThat(restored.savedAt()).isEqualTo(NOW);
        assertThat(restored.completedAt()).isNull();
    }

    @Test
    void snoozePreservesReadAndSavedWhileReopeningCompletedWork() {
        Instant earlier = NOW.minusSeconds(60);
        Instant later = NOW.plusSeconds(3600);
        NotificationTriageState completed = state(earlier, earlier, earlier, null);

        NotificationTriageState snoozed = transition(completed, "SNOOZE", later);

        assertThat(snoozed.inboxState()).isEqualTo("ACTIVE");
        assertThat(snoozed.readAt()).isEqualTo(earlier);
        assertThat(snoozed.savedAt()).isEqualTo(earlier);
        assertThat(snoozed.completedAt()).isNull();
        assertThat(snoozed.snoozedUntil()).isEqualTo(later);
    }

    @Test
    void unreadAndUnsaveOnlyClearTheirOwnDimension() {
        Instant earlier = NOW.minusSeconds(60);
        Instant later = NOW.plusSeconds(3600);
        NotificationTriageState initial = state(earlier, earlier, null, later);

        NotificationTriageState unread = transition(initial, "UNREAD", null);
        NotificationTriageState unsaved = transition(unread, "UNSAVE", null);

        assertThat(unread.readAt()).isNull();
        assertThat(unread.savedAt()).isEqualTo(earlier);
        assertThat(unread.snoozedUntil()).isEqualTo(later);
        assertThat(unsaved.savedAt()).isNull();
        assertThat(unsaved.snoozedUntil()).isEqualTo(later);
    }

    @Test
    void bulkUndoRestoresTheExactIndependentStateSnapshot() {
        Instant readAt = NOW.minusSeconds(300);
        Instant savedAt = NOW.minusSeconds(240);
        Instant completedAt = NOW.minusSeconds(180);
        NotificationTriageState current = state(NOW, null, null, NOW.plusSeconds(3600));
        NotificationUndoSnapshot snapshot = new NotificationUndoSnapshot(
                UUID.randomUUID(),
                "DONE",
                readAt,
                savedAt,
                completedAt,
                null,
                current.version());

        NotificationTriageState restored = current.restoreSnapshot(snapshot);

        assertThat(restored.inboxState()).isEqualTo("DONE");
        assertThat(restored.readAt()).isEqualTo(readAt);
        assertThat(restored.savedAt()).isEqualTo(savedAt);
        assertThat(restored.completedAt()).isEqualTo(completedAt);
        assertThat(restored.snoozedUntil()).isNull();
        assertThat(restored.actionRequired()).isEqualTo(current.actionRequired());
        assertThat(restored.priority()).isEqualTo(current.priority());
    }

    private NotificationTriageState transition(
            NotificationTriageState state,
            String action,
            Instant snoozedUntil) {
        return NotificationTriagePolicy.transition(state, action, snoozedUntil, NOW);
    }

    private NotificationTriageState state(
            Instant readAt,
            Instant savedAt,
            Instant completedAt,
            Instant snoozedUntil) {
        return new NotificationTriageState(
                17L,
                completedAt == null ? "ACTIVE" : "DONE",
                readAt,
                savedAt,
                completedAt,
                snoozedUntil,
                true,
                "HIGH",
                4L,
                3L);
    }
}
