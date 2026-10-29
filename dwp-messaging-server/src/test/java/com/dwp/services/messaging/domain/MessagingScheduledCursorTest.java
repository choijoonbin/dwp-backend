package com.dwp.services.messaging.domain;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingScheduledCursorTest {

    @Test
    void cursorRoundTripsTheStableSortKeyAndBindsTheStatusFilter() {
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-28T00:00:00Z");
        var item = new MessagingContinuityDtos.ScheduledMessageResponse(
                id, UUID.randomUUID(), "body", null, List.of(), createdAt.plusDays(1),
                "UTC", "PENDING", 0, null, null, null, null, 0,
                createdAt, createdAt, "Conversation", null);

        String cursor = MessagingScheduledCursor.encode(item, 71, 100, "PENDING");

        assertThat(MessagingScheduledCursor.decode(cursor, 71, 100, "PENDING"))
                .isEqualTo(new MessagingScheduledCursor.Cursor(createdAt, id));
        assertThatThrownBy(() -> MessagingScheduledCursor.decode(cursor, 72, 100, "PENDING"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> MessagingScheduledCursor.decode(cursor, 71, 101, "PENDING"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> MessagingScheduledCursor.decode(cursor, 71, 100, "FAILED"))
                .isInstanceOf(BaseException.class);
    }
}
