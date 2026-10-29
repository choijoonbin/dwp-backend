package com.dwp.services.messaging.attention;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MessagingAttentionDtos {

    private MessagingAttentionDtos() {
    }

    public enum ActivityKind {
        MENTION,
        THREAD_REPLY,
        REACTION,
        SYSTEM,
        SCHEDULE_FAILED
    }

    public record ActivityItem(
            String id,
            ActivityKind kind,
            UUID conversationId,
            String conversationName,
            String conversationType,
            @Schema(nullable = true) UUID messageId,
            @Schema(nullable = true) UUID rootMessageId,
            String actorName,
            @Schema(nullable = true) String preview,
            boolean protectedContent,
            OffsetDateTime occurredAt) {
    }

    public record ActivityResponse(
            OffsetDateTime generatedAt,
            List<ActivityItem> items) {
    }

    public record ThreadItem(
            UUID rootMessageId,
            UUID conversationId,
            String conversationName,
            String conversationType,
            String rootSenderName,
            String rootPreview,
            boolean rootDeleted,
            boolean protectedContent,
            long replyCount,
            UUID latestReplyMessageId,
            String latestReplySenderName,
            OffsetDateTime latestReplyAt) {
    }

    @Schema(name = "MessagingAttentionThreadResponse")
    public record ThreadResponse(
            OffsetDateTime generatedAt,
            List<ThreadItem> items) {
    }
}
