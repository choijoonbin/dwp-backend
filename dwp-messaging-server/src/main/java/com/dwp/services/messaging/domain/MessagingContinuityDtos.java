package com.dwp.services.messaging.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MessagingContinuityDtos {

    private MessagingContinuityDtos() {
    }

    public record DraftResponse(
            UUID conversationId,
            UUID threadRootMessageId,
            String body,
            List<UUID> mentionedPersonPublicIds,
            long version,
            OffsetDateTime updatedAt) {
    }

    public record SaveDraftRequest(
            @NotNull @Size(max = 20_000) String body,
            @Size(max = 50) List<@NotNull UUID> mentionedPersonPublicIds,
            @Min(0) long expectedVersion) {

        public SaveDraftRequest {
            mentionedPersonPublicIds = mentionedPersonPublicIds == null
                    ? List.of()
                    : mentionedPersonPublicIds.stream().distinct().sorted().toList();
        }
    }

    public record ScheduleMessageRequest(
            @NotBlank @Size(max = 20_000) String body,
            @NotNull UUID idempotencyKey,
            UUID replyToMessageId,
            @Size(max = 10) List<@NotNull UUID> attachmentIds,
            @Size(max = 50) List<@NotNull UUID> mentionedPersonPublicIds,
            @NotNull OffsetDateTime scheduledFor,
            @NotBlank @Size(max = 64) String displayTimeZone) {

        public ScheduleMessageRequest {
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            mentionedPersonPublicIds = mentionedPersonPublicIds == null
                    ? List.of()
                    : mentionedPersonPublicIds.stream().distinct().sorted().toList();
        }
    }

    public record ScheduledMessageResponse(
            UUID scheduledMessageId,
            UUID conversationId,
            String body,
            UUID replyToMessageId,
            List<UUID> mentionedPersonPublicIds,
            OffsetDateTime scheduledFor,
            String displayTimeZone,
            String status,
            int attemptCount,
            String failureCode,
            String failureDetail,
            UUID deliveredMessageId,
            OffsetDateTime deliveredAt,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String conversationName,
            OffsetDateTime payloadPurgedAt) {
    }

    public record ScheduledMessagePage(
            List<ScheduledMessageResponse> items,
            int limit,
            boolean hasMore,
            String nextCursor) {
    }
}
