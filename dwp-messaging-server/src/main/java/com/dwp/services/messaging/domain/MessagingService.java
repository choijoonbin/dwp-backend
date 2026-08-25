package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.attachment.AttachmentService;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MessagingService {

    private static final Set<String> SCOPES = Set.of("ALL", "FAVORITES", "SPACES", "DIRECT", "CHANNELS");
    private static final Set<String> NOTIFICATION_LEVELS =
            Set.of("DEFAULT", "ALL", "MENTIONS", "MUTE");

    private final MessagingQueryRepository queries;
    private final MessagingCommandRepository commands;
    private final MessagingMessageQueryRepository messageQueries;
    private final MessagingInteractionCommandRepository interactions;
    private final MessagingEventRecorder events;
    private final MessagingNotificationEvents notificationEvents;
    private final AttachmentService attachments;

    @Autowired
    public MessagingService(
            MessagingQueryRepository queries,
            MessagingCommandRepository commands,
            MessagingMessageQueryRepository messageQueries,
            MessagingInteractionCommandRepository interactions,
            MessagingEventRecorder events,
            MessagingNotificationEvents notificationEvents,
            AttachmentService attachments) {
        this.queries = queries;
        this.commands = commands;
        this.messageQueries = messageQueries;
        this.interactions = interactions;
        this.events = events;
        this.notificationEvents = notificationEvents;
        this.attachments = attachments;
    }

    MessagingService(
            MessagingQueryRepository queries,
            MessagingCommandRepository commands,
            MessagingMessageQueryRepository messageQueries,
            MessagingInteractionCommandRepository interactions,
            MessagingEventRecorder events) {
        this(
                queries,
                commands,
                messageQueries,
                interactions,
                events,
                MessagingNotificationEvents.disabled(),
                null);
    }

    @Transactional(readOnly = true)
    public MessagingDtos.HomeResponse home() {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        long tenantId = subject.tenantId();
        long userId = subject.userId();
        return new MessagingDtos.HomeResponse(
                OffsetDateTime.now(),
                queries.metrics(tenantId, userId),
                queries.conversations(tenantId, userId, "ALL", "", 0, 8),
                queries.conversations(tenantId, userId, "SPACES", "", 0, 6),
                queries.people(tenantId, userId, "", 8));
    }

    @Transactional(readOnly = true)
    public MessagingDtos.ConversationPage conversations(
            String scope,
            String query,
            int page,
            int pageSize) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        String resolvedScope = scope(scope);
        String resolvedQuery = query(query);
        int resolvedPage = Math.max(0, page);
        int resolvedPageSize = Math.max(1, Math.min(100, pageSize));
        return new MessagingDtos.ConversationPage(
                queries.conversations(
                        subject.tenantId(), subject.userId(), resolvedScope,
                        resolvedQuery, resolvedPage, resolvedPageSize),
                queries.conversationCount(
                        subject.tenantId(), subject.userId(), resolvedScope, resolvedQuery),
                resolvedPage,
                resolvedPageSize);
    }

    @Transactional(readOnly = true)
    public MessagingDtos.ConversationDetail conversation(UUID conversationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        MessagingDtos.ConversationSummary conversation = visibleConversation(
                subject.tenantId(), subject.userId(), conversationId);
        return detail(subject.tenantId(), subject.userId(), conversation);
    }

    @Transactional(readOnly = true)
    public MessagingDtos.MessagePage messages(
            UUID conversationId, Long beforeSequence, int limit) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        if (beforeSequence != null && beforeSequence < 1) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The message cursor must be a positive conversation sequence.");
        }
        int resolvedLimit = Math.max(1, Math.min(100, limit));
        return messageQueries.messagePage(
                subject.tenantId(), conversationId, subject.userId(),
                beforeSequence, resolvedLimit);
    }

    @Transactional
    public MessagingDtos.ConversationSummary createDirectConversation(
            MessagingDtos.DirectConversationRequest request,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        if (request.targetUserId() == subject.userId()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A direct conversation requires another active person.");
        }
        queries.person(subject.tenantId(), request.targetUserId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The target person was not found."));
        UUID conversationId = commands.directConversation(
                subject.tenantId(), subject.userId(), request.targetUserId());
        commands.audit(
                subject.tenantId(), subject.userId(), "messaging.direct.opened",
                "MSG_CONVERSATION", conversationId.toString(), correlationId,
                Map.of("targetUserId", request.targetUserId()));
        return visibleConversation(subject.tenantId(), subject.userId(), conversationId);
    }

    @Transactional
    public MessagingDtos.MessageSummary sendMessage(
            UUID conversationId,
            MessagingDtos.SendMessageRequest request,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        MessagingDtos.ConversationSummary conversation = visibleConversation(
                subject.tenantId(), subject.userId(), conversationId);
        if ("ANNOUNCEMENT".equals(conversation.conversationType())
                && !canModerate(subject.tenantId(), conversationId, subject.userId())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Only moderators can post to announcement conversations.");
        }
        List<UUID> attachmentIds = request.attachmentIds();
        var replay = attachmentIds.isEmpty()
                ? commands.replayMessage(
                        subject.tenantId(), subject.userId(), conversationId,
                        request.idempotencyKey(), request.body(), request.replyToMessageId())
                : commands.replayMessage(
                        subject.tenantId(), subject.userId(), conversationId,
                        request.idempotencyKey(), request.body(), request.replyToMessageId(), attachmentIds);
        if (replay.isPresent()) {
            return message(subject, conversationId, replay.orElseThrow().messageId());
        }
        MessagingMessageAccess replyParent = validateReplyParent(
                subject, conversationId, request.replyToMessageId());
        if (!attachmentIds.isEmpty()) {
            if (attachments == null) throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Attachment support is unavailable.");
            attachments.requireAttachable(conversationId, subject.userId(), attachmentIds);
        }
        MessagingCommandRepository.MessageInsertResult result = attachmentIds.isEmpty()
                ? commands.insertMessage(
                        subject.tenantId(), subject.userId(), conversationId,
                        request.idempotencyKey(), senderName(subject), subject.personPublicId(),
                        request.body(), request.replyToMessageId())
                : commands.insertMessage(
                        subject.tenantId(), subject.userId(), conversationId,
                        request.idempotencyKey(), senderName(subject), subject.personPublicId(),
                        request.body(), request.replyToMessageId(), attachmentIds);
        if (result.created()) {
            if (!attachmentIds.isEmpty()) {
                attachments.attachToMessage(
                        subject.tenantId(), conversationId, subject.userId(),
                        result.messageId(), attachmentIds);
            }
            commands.audit(
                    subject.tenantId(), subject.userId(), "messaging.message.created",
                    "MSG_MESSAGE", result.messageId().toString(), correlationId,
                    Map.of("conversationId", conversationId));
            events.conversationEvent(
                    subject, "messaging.message.created", conversationId, result.messageId(),
                    messagePayload(0, result.sequence(), request.replyToMessageId()));
            notificationEvents.messageCreated(
                    subject,
                    conversation,
                    result,
                    request,
                    replyParent == null ? null : replyParent.senderUserId(),
                    correlationId);
        }
        return message(subject, conversationId, result.messageId());
    }

    @Transactional
    public MessagingDtos.MessageSummary updateMessage(
            UUID conversationId,
            UUID messageId,
            MessagingDtos.UpdateMessageRequest request,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        MessagingMessageAccess access = visibleMessage(subject, conversationId, messageId);
        if (!access.isAuthor(subject.userId())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Only the message author can edit its content.");
        }
        if (!queries.policy(subject.tenantId()).allowMessageEdit()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Message editing is disabled by the tenant policy.");
        }
        requireMutable(access, request.version());
        if (interactions.editMessage(
                subject.tenantId(), subject.userId(), conversationId, messageId,
                request.body(), request.version()) == 0) {
            throw versionConflict();
        }
        interactions.touchConversation(subject.tenantId(), subject.userId(), conversationId);
        commands.audit(
                subject.tenantId(), subject.userId(), "messaging.message.updated",
                "MSG_MESSAGE", messageId.toString(), correlationId,
                Map.of("conversationId", conversationId, "version", request.version() + 1));
        events.conversationEvent(
                subject, "messaging.message.updated", conversationId, messageId,
                Map.of("version", request.version() + 1));
        return message(subject, conversationId, messageId);
    }

    @Transactional
    public MessagingDtos.MessageSummary deleteMessage(
            UUID conversationId,
            UUID messageId,
            long version,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        MessagingDtos.ConversationSummary conversation =
                visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        MessagingMessageAccess access = visibleMessage(subject, conversationId, messageId);
        if (!access.isAuthor(subject.userId()) && !access.canModerate()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Only the message author or a conversation moderator can delete it.");
        }
        if (!queries.policy(subject.tenantId()).allowMessageDelete()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Message deletion is disabled by the tenant policy.");
        }
        requireMutable(access, version);
        if (interactions.softDeleteMessage(
                subject.tenantId(), conversationId, messageId, version) == 0) {
            throw versionConflict();
        }
        interactions.touchConversation(subject.tenantId(), subject.userId(), conversationId);
        commands.audit(
                subject.tenantId(), subject.userId(), "messaging.message.deleted",
                "MSG_MESSAGE", messageId.toString(), correlationId,
                Map.of("conversationId", conversationId, "version", version + 1));
        events.conversationEvent(
                subject, "messaging.message.deleted", conversationId, messageId,
                Map.of("version", version + 1));
        notificationEvents.messageDeleted(
                subject, conversation, messageId, version + 1, correlationId);
        return message(subject, conversationId, messageId);
    }

    @Transactional
    public MessagingDtos.ReadCursorResponse markRead(
            UUID conversationId,
            MessagingDtos.ReadCursorRequest request) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        MessagingCommandRepository.ReadCursorState cursor = commands.markRead(
                        subject.tenantId(), subject.userId(), conversationId, request.messageId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "The read cursor message was not found in this conversation."));
        if (cursor.advanced()) {
            events.privateEvent(
                    subject, "messaging.read-cursor.updated", conversationId,
                    cursor.currentMessageId(), Map.of("messageSequence", cursor.currentSequence()));
        }
        return new MessagingDtos.ReadCursorResponse(
                conversationId,
                cursor.currentMessageId(),
                cursor.currentSequence(),
                cursor.currentReadAt(),
                cursor.currentVersion());
    }

    @Transactional(readOnly = true)
    public MessagingDtos.ThreadResponse thread(
            UUID conversationId, UUID rootMessageId, int limit) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        MessagingMessageAccess rootAccess = visibleMessage(subject, conversationId, rootMessageId);
        if (!rootAccess.isRoot()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Thread replies must be requested with the root message identifier.");
        }
        int resolvedLimit = Math.max(1, Math.min(200, limit));
        return new MessagingDtos.ThreadResponse(
                message(subject, conversationId, rootMessageId),
                messageQueries.replies(
                        subject.tenantId(), conversationId, subject.userId(), rootMessageId, resolvedLimit),
                messageQueries.replyCount(subject.tenantId(), conversationId, rootMessageId));
    }

    @Transactional
    public MessagingDtos.MessageSummary addReaction(
            UUID conversationId,
            UUID messageId,
            MessagingDtos.ReactionRequest request) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        MessagingMessageAccess access = visibleMessage(subject, conversationId, messageId);
        requireNotDeleted(access);
        if (commands.react(subject.tenantId(), subject.userId(), messageId, request.emoji()) > 0) {
            events.conversationEvent(
                    subject, "messaging.reaction.added", conversationId, messageId,
                    Map.of("emoji", request.emoji().trim()));
        }
        return message(subject, conversationId, messageId);
    }

    @Transactional
    public MessagingDtos.MessageSummary removeReaction(
            UUID conversationId,
            UUID messageId,
            String emoji) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        visibleMessage(subject, conversationId, messageId);
        if (commands.removeReaction(subject.tenantId(), subject.userId(), messageId, emoji) > 0) {
            events.conversationEvent(
                    subject, "messaging.reaction.removed", conversationId, messageId,
                    Map.of("emoji", emoji.trim()));
        }
        return message(subject, conversationId, messageId);
    }

    @Transactional(readOnly = true)
    public MessagingDtos.SavedItemPage savedItems(int page, int pageSize) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        int resolvedPage = Math.max(0, page);
        int resolvedPageSize = Math.max(1, Math.min(100, pageSize));
        return messageQueries.savedItems(
                subject.tenantId(), subject.userId(), resolvedPage, resolvedPageSize);
    }

    @Transactional
    public MessagingDtos.SavedItemSummary saveMessage(UUID conversationId, UUID messageId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        MessagingMessageAccess access = visibleMessage(subject, conversationId, messageId);
        requireNotDeleted(access);
        if (interactions.saveMessage(
                subject.tenantId(), subject.userId(), conversationId, messageId) > 0) {
            events.privateEvent(
                    subject, "messaging.saved-item.created", conversationId, messageId, Map.of());
        }
        return messageQueries.savedItem(subject.tenantId(), subject.userId(), messageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The saved message was not found."));
    }

    @Transactional
    public void unsaveMessage(UUID conversationId, UUID messageId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        visibleMessage(subject, conversationId, messageId);
        if (interactions.unsaveMessage(
                subject.tenantId(), subject.userId(), conversationId, messageId) > 0) {
            events.privateEvent(
                    subject, "messaging.saved-item.deleted", conversationId, messageId, Map.of());
        }
    }

    @Transactional(readOnly = true)
    public MessagingDtos.ConversationSettings conversationSettings(UUID conversationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        return messageQueries.settings(subject.tenantId(), conversationId, subject.userId());
    }

    @Transactional
    public MessagingDtos.ConversationSettings updateConversationSettings(
            UUID conversationId, MessagingDtos.ConversationSettingsRequest request) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        String notificationLevel = request.notificationLevel().trim().toUpperCase(Locale.ROOT);
        if (!NOTIFICATION_LEVELS.contains(notificationLevel)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Notification level must be DEFAULT, ALL, MENTIONS, or MUTE.");
        }
        MessagingDtos.ConversationSettingsRequest normalized =
                new MessagingDtos.ConversationSettingsRequest(
                        notificationLevel, request.favorite(), request.pinned(), request.version());
        if (interactions.updateSettings(
                subject.tenantId(), subject.userId(), conversationId, normalized) == 0) {
            throw versionConflict();
        }
        events.privateEvent(
                subject, "messaging.conversation-settings.updated", conversationId, null,
                Map.of(
                        "notificationLevel", notificationLevel,
                        "favorite", request.favorite(),
                        "pinned", request.pinned(),
                        "version", request.version() + 1));
        return messageQueries.settings(subject.tenantId(), conversationId, subject.userId());
    }

    @Transactional(readOnly = true)
    public List<MessagingDtos.PersonSummary> people(String query, int limit) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        return queries.people(subject.tenantId(), subject.userId(), query(query), Math.min(Math.max(limit, 1), 50));
    }

    @Transactional(readOnly = true)
    public MessagingDtos.AdminOverview adminOverview() {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        return new MessagingDtos.AdminOverview(
                OffsetDateTime.now(),
                queries.adminMetrics(subject.tenantId()),
                queries.policy(subject.tenantId()),
                queries.conversations(subject.tenantId(), subject.userId(), "CHANNELS", "", 0, 12));
    }

    @Transactional
    public MessagingDtos.TenantPolicy updatePolicy(
            MessagingDtos.TenantPolicyRequest request,
            String correlationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        if (commands.updatePolicy(subject.tenantId(), subject.userId(), request) == 0) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Messaging policy was changed by another administrator.");
        }
        commands.audit(
                subject.tenantId(), subject.userId(), "messaging.policy.updated",
                "MSG_TENANT_POLICY", String.valueOf(subject.tenantId()), correlationId,
                Map.of("retentionDays", request.retentionDays()));
        events.tenantEvent(
                subject, "messaging.tenant-policy.updated",
                Map.of("version", request.version() + 1));
        return queries.policy(subject.tenantId());
    }

    private MessagingDtos.ConversationSummary visibleConversation(
            long tenantId,
            long userId,
            UUID conversationId) {
        return queries.conversation(tenantId, userId, conversationId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The conversation was not found."));
    }

    private MessagingDtos.ConversationDetail detail(
            long tenantId,
            long userId,
            MessagingDtos.ConversationSummary conversation) {
        return new MessagingDtos.ConversationDetail(
                conversation,
                queries.members(tenantId, conversation.conversationId()),
                queries.messages(tenantId, conversation.conversationId(), userId, 80),
                new MessagingDtos.RealtimeStatus(
                        "SSE",
                        "/api/messaging/v1/stream",
                        "ACTIVE",
                        "Durable event replay supports Last-Event-ID reconnect recovery."));
    }

    private boolean canModerate(long tenantId, UUID conversationId, long userId) {
        return queries.members(tenantId, conversationId).stream()
                .anyMatch(member -> member.userId() == userId
                        && ("OWNER".equals(member.memberRole())
                        || "MODERATOR".equals(member.memberRole())));
    }

    private MessagingMessageAccess validateReplyParent(
            MessagingRequestContext.Subject subject,
            UUID conversationId,
            UUID replyToMessageId) {
        if (replyToMessageId == null) return null;
        MessagingMessageAccess parent = visibleMessage(subject, conversationId, replyToMessageId);
        if (!parent.isRoot()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A thread reply must reference the thread root message.");
        }
        requireNotDeleted(parent);
        return parent;
    }

    private MessagingMessageAccess visibleMessage(
            MessagingRequestContext.Subject subject,
            UUID conversationId,
            UUID messageId) {
        return messageQueries.access(
                        subject.tenantId(), conversationId, subject.userId(), messageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "The message was not found in this conversation."));
    }

    private MessagingDtos.MessageSummary message(
            MessagingRequestContext.Subject subject,
            UUID conversationId,
            UUID messageId) {
        return messageQueries.message(
                        subject.tenantId(), conversationId, subject.userId(), messageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "The message was not found in this conversation."));
    }

    private void requireMutable(MessagingMessageAccess access, long expectedVersion) {
        requireNotDeleted(access);
        if (expectedVersion < 0 || access.version() != expectedVersion) throw versionConflict();
    }

    private void requireNotDeleted(MessagingMessageAccess access) {
        if (access.deletedAt() != null) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "The message has already been deleted.");
        }
    }

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The message or conversation settings changed before this request completed.");
    }

    private Map<String, Object> messagePayload(
            long version, long messageSequence, UUID replyToMessageId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", version);
        payload.put("messageSequence", messageSequence);
        if (replyToMessageId != null) payload.put("replyToMessageId", replyToMessageId);
        return Map.copyOf(payload);
    }

    private String scope(String value) {
        String normalized = value == null || value.isBlank()
                ? "ALL"
                : value.trim().toUpperCase(Locale.ROOT);
        return SCOPES.contains(normalized) ? normalized : "ALL";
    }

    private String query(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    private String senderName(MessagingRequestContext.Subject subject) {
        if (subject.displayName() != null && !subject.displayName().isBlank()) {
            return subject.displayName();
        }
        return queries.person(subject.tenantId(), subject.userId())
                .map(MessagingDtos.PersonSummary::displayName)
                .orElse("User " + subject.userId());
    }
}
