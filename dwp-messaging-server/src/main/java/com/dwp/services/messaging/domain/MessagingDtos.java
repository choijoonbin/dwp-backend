package com.dwp.services.messaging.domain;

import com.dwp.services.messaging.attachment.AttachmentDtos;
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

    public record MentionSummary(
            long userId,
            String displayName,
            String mentionKind) {
    }

    public record ThreadRootPreview(
            UUID messageId,
            String senderName,
            String body,
            OffsetDateTime deletedAt,
            OffsetDateTime createdAt) {
    }

    public record MessageSummary(
            UUID messageId,
            UUID conversationId,
            long sequence,
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
            List<ReactionSummary> reactions,
            int replyCount,
            ThreadRootPreview rootPreview,
            List<AttachmentDtos.AttachmentSummary> attachments,
            List<MentionSummary> mentions) {

        public MessageSummary(
                UUID messageId,
                UUID conversationId,
                long sequence,
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
                List<ReactionSummary> reactions,
                int replyCount,
                ThreadRootPreview rootPreview) {
            this(messageId, conversationId, sequence, senderUserId, senderPersonPublicId,
                    senderName, body, contentType, messageKind, replyToMessageId, editedAt,
                    deletedAt, createdAt, version, reactions, replyCount, rootPreview,
                    List.of(), List.of());
        }

        public MessageSummary(
                UUID messageId,
                UUID conversationId,
                long sequence,
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
                List<ReactionSummary> reactions,
                int replyCount,
                ThreadRootPreview rootPreview,
                List<AttachmentDtos.AttachmentSummary> attachments) {
            this(messageId, conversationId, sequence, senderUserId, senderPersonPublicId,
                    senderName, body, contentType, messageKind, replyToMessageId, editedAt,
                    deletedAt, createdAt, version, reactions, replyCount, rootPreview,
                    attachments, List.of());
        }
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
            UUID lastReadMessageId,
            long lastReadSequence,
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

    public record MessagePage(
            List<MessageSummary> items,
            boolean hasMore,
            Long nextBeforeSequence) {
    }

    public record ThreadResponse(
            MessageSummary root,
            List<MessageSummary> replies,
            long total) {
    }

    public record SavedItemSummary(
            MessageSummary message,
            String conversationName,
            String conversationType,
            OffsetDateTime savedAt) {
    }

    public record SavedItemPage(
            List<SavedItemSummary> items,
            long total,
            int page,
            int pageSize) {
    }

    public record ConversationSettings(
            UUID conversationId,
            String notificationLevel,
            boolean favorite,
            boolean pinned,
            long version) {
    }

    public record SendMessageRequest(
            @NotNull @Size(max = 20_000) String body,
            @NotNull UUID idempotencyKey,
            UUID replyToMessageId,
            @Size(max = 10) List<@NotNull UUID> attachmentIds,
            @Size(max = 50) List<@NotNull @Min(1) Long> mentionedUserIds) {

        public SendMessageRequest(String body, UUID idempotencyKey, UUID replyToMessageId) {
            this(body, idempotencyKey, replyToMessageId, List.of(), List.of());
        }

        public SendMessageRequest {
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            mentionedUserIds = mentionedUserIds == null
                    ? List.of()
                    : mentionedUserIds.stream().distinct().toList();
        }
    }

    public record UpdateMessageRequest(
            @NotBlank @Size(max = 20_000) String body,
            @Min(0) long version) {
    }

    public record ConversationSettingsRequest(
            @NotBlank String notificationLevel,
            boolean favorite,
            boolean pinned,
            @Min(0) long version) {
    }

    public record DirectConversationRequest(
            @NotNull @Min(1) Long targetUserId) {
    }

    public record ReadCursorRequest(
            @NotNull UUID messageId) {
    }

    public record ReadCursorResponse(
            UUID conversationId,
            UUID lastReadMessageId,
            long lastReadSequence,
            OffsetDateTime lastReadAt,
            long version) {
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
