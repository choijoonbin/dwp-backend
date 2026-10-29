package com.dwp.services.messaging.domain;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingConversationCursorTest {

    @Test
    void roundTripsEveryStableSortKeyAndBindsTheFilterFingerprint() {
        UUID conversationId = UUID.fromString("73000000-0000-0000-0000-000000000099");
        OffsetDateTime lastMessageAt = OffsetDateTime.parse("2026-10-28T01:02:03.456789Z");
        MessagingDtos.ConversationSummary conversation = conversation(
                conversationId, true, false, lastMessageAt);

        String encoded = MessagingConversationCursor.encode(
                conversation, 42, 100, "CHANNELS", "risk");
        MessagingConversationCursor.Cursor decoded =
                MessagingConversationCursor.decode(encoded, 42, 100, "CHANNELS", "risk");

        assertThat(decoded).isEqualTo(new MessagingConversationCursor.Cursor(
                true, false, lastMessageAt, conversationId));
        assertThatThrownBy(() -> MessagingConversationCursor.decode(
                encoded, 42, 100, "ALL", "risk"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> MessagingConversationCursor.decode(
                encoded, 42, 100, "CHANNELS", "other"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> MessagingConversationCursor.decode(
                encoded, 43, 100, "CHANNELS", "risk"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> MessagingConversationCursor.decode(
                encoded, 42, 101, "CHANNELS", "risk"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsMalformedOrNonCanonicalTokensAndSupportsNullActivity() {
        MessagingDtos.ConversationSummary conversation = conversation(
                UUID.fromString("73000000-0000-0000-0000-000000000100"), false, true, null);
        String encoded = MessagingConversationCursor.encode(conversation, 42, 100, "ALL", "");

        assertThat(MessagingConversationCursor.decode(
                encoded, 42, 100, "ALL", "").lastMessageAt())
                .isNull();
        for (String value : List.of("not-base64!", " " + encoded, encoded + "=")) {
            assertThatThrownBy(() -> MessagingConversationCursor.decode(
                    value, 42, 100, "ALL", ""))
                    .isInstanceOf(BaseException.class);
        }
    }

    private MessagingDtos.ConversationSummary conversation(
            UUID id, boolean pinned, boolean favorite, OffsetDateTime lastMessageAt) {
        return new MessagingDtos.ConversationSummary(
                id, "cursor:" + id, "CHANNEL", "Cursor", null,
                "PRIVATE", "INTERNAL", null, null, "ACTIVE", 2, 0,
                favorite, pinned, null, lastMessageAt, 0);
    }
}
