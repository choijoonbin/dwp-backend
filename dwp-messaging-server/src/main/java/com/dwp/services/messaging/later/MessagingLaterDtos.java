package com.dwp.services.messaging.later;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MessagingLaterDtos {

    private MessagingLaterDtos() {
    }

    public enum LaterState {
        SAVED,
        SNOOZED,
        DUE,
        COMPLETED
    }

    public record LaterItem(
            UUID messageId,
            UUID conversationId,
            String conversationName,
            String conversationType,
            String senderName,
            String preview,
            boolean protectedContent,
            boolean messageDeleted,
            OffsetDateTime savedAt,
            LaterState state,
            OffsetDateTime reminderAt,
            OffsetDateTime snoozedUntil,
            OffsetDateTime completedAt,
            long version) {
    }

    public record LaterPage(
            List<LaterItem> items,
            long total,
            int page,
            int pageSize,
            OffsetDateTime generatedAt) {
    }

    public record UpdateLaterRequest(
            @NotBlank String state,
            OffsetDateTime reminderAt,
            OffsetDateTime snoozedUntil,
            @NotNull @Min(0) Long version) {
    }
}
