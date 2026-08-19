package com.dwp.services.notification.cursor;

import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationCursorCodecTest {

    private final NotificationCursorCodec codec = new NotificationCursorCodec(
            "a-test-cursor-secret-that-is-long-enough", Duration.ofHours(1));
    private final NotificationRequestContext.Actor actor =
            new NotificationRequestContext.Actor(7, 11L, Set.of(), Set.of(), false, null);

    @Test
    void roundTripsOpaqueInboxCursor() {
        Instant activity = Instant.parse("2026-08-19T00:00:00Z");
        UUID notificationId = UUID.fromString("93af7315-2271-462e-a819-3d238a28830f");

        NotificationCursorCodec.InboxCursor inbox = codec.decodeInbox(
                actor, codec.encodeInbox(actor, activity, notificationId));
        assertThat(inbox.lastActivityAt()).isEqualTo(activity);
        assertThat(inbox.notificationId()).isEqualTo(notificationId);
    }

    @Test
    void rejectsTamperingAndCrossUserReplay() {
        String token = codec.encodeInbox(
                actor,
                Instant.parse("2026-08-19T00:00:00Z"),
                UUID.fromString("93af7315-2271-462e-a819-3d238a28830f"));
        NotificationRequestContext.Actor anotherUser =
                new NotificationRequestContext.Actor(7, 12L, Set.of(), Set.of(), false, null);

        assertThatThrownBy(() -> codec.decodeInbox(actor, token + "x"))
                .isInstanceOf(NotificationException.class);
        assertThatThrownBy(() -> codec.decodeInbox(anotherUser, token))
                .isInstanceOf(NotificationException.class);
    }
}
