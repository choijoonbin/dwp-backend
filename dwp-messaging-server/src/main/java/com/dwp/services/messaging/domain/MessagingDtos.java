package com.dwp.services.messaging.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MessagingDtos {

    private MessagingDtos() {
    }

    public record PersonSummary(
            long userId,
            UUID personPublicId,
            String emailAddress,
            String displayName,
            String jobTitle,
            String organizationName,
            String presenceState) {
    }

    public record ReactionSummary(
            String emoji,
            int count,
            boolean mine) {
    }

    public record MessageSummary(
            UUID messageId,
            UUID conversationId,
            long senderUserId,
            UUID senderPersonPublicId,
            String senderName,
            String body,
            String contentType,
            String messageKind,
            UUID replyToMessageId,
            OffsetDateTime editedAt,
            OffsetDateTime deletedAt,
            OffsetDateTime createdAt,
            long version,
            List<ReactionSummary> reactions) {
    }

    public record MemberSummary(
            long userId,
            UUID personPublicId,
            String displayName,
            String emailAddress,
            String jobTitle,
            String organizationName,
            String presenceState,
            String memberRole,
            String membershipSource,
            String notificationLevel,
            boolean favorite,
            boolean pinned,
            OffsetDateTime lastReadAt) {
    }

    public record ConversationSummary(
            UUID conversationId,
            String conversationKey,
            String conversationType,
            String name,
            String topic,
            String visibility,
            String dataClassification,
            String linkedSpaceKey,
            String linkedSpaceName,
            String lifecycleState,
            int memberCount,
            int unreadCount,
            boolean favorite,
            boolean pinned,
            MessageSummary lastMessage,
            OffsetDateTime lastMessageAt,
            long version) {
    }

    public record ConversationDetail(
            ConversationSummary conversation,
            List<MemberSummary> members,
            List<MessageSummary> messages,
            RealtimeStatus realtime) {
    }

    public record RealtimeStatus(
            String mode,
            String endpoint,
            String state,
            String detail) {
    }

    public record HomeMetrics(
            int unreadConversations,
            int mentions,
            int spaceChannels,
            int directMessages,
            int savedItems) {
    }

    public record HomeResponse(
            OffsetDateTime generatedAt,
            HomeMetrics metrics,
            List<ConversationSummary> priority,
            List<ConversationSummary> spaces,
            List<PersonSummary> people) {
    }

    public record ConversationPage(
            List<ConversationSummary> items,
            long total,
            int page,
            int pageSize) {
    }

    public record SendMessageRequest(
            @NotBlank @Size(max = 20_000) String body,
            @NotNull UUID idempotencyKey,
            UUID replyToMessageId) {
    }

    public record DirectConversationRequest(
            @NotNull @Min(1) Long targetUserId) {
    }

    public record ReadCursorRequest(
            @NotNull UUID messageId) {
    }

    public record ReactionRequest(
            @NotBlank @Size(max = 40) String emoji) {
    }

    public record TenantPolicy(
            boolean directMessagesEnabled,
            boolean spaceMessagingEnabled,
            boolean allowMessageEdit,
            boolean allowMessageDelete,
            boolean aiAssistanceEnabled,
            boolean aiAutoExecuteEnabled,
            int retentionDays,
            int maximumAttachmentMb,
            long version) {
    }

    public record TenantPolicyRequest(
            boolean directMessagesEnabled,
            boolean spaceMessagingEnabled,
            boolean allowMessageEdit,
            boolean allowMessageDelete,
            boolean aiAssistanceEnabled,
            @Min(30) @Max(3650) int retentionDays,
            @Min(1) @Max(1024) int maximumAttachmentMb,
            @Min(0) long version) {
    }

    public record AdminMetrics(
            int activeConversations,
            int spaceLinkedConversations,
            int activeMembers,
            int retainedMessages,
            int restrictedConversations) {
    }

    public record AdminOverview(
            OffsetDateTime generatedAt,
            AdminMetrics metrics,
            TenantPolicy policy,
            List<ConversationSummary> governedConversations) {
    }
}
