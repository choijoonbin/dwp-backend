package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MessagingService {

    private static final Set<String> SCOPES = Set.of("ALL", "FAVORITES", "SPACES", "DIRECT", "CHANNELS");

    private final MessagingQueryRepository queries;
    private final MessagingCommandRepository commands;

    public MessagingService(MessagingQueryRepository queries, MessagingCommandRepository commands) {
        this.queries = queries;
        this.commands = commands;
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

    @Transactional
    public MessagingDtos.ConversationDetail createDirectConversation(
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
        return conversation(conversationId);
    }

    @Transactional
    public MessagingDtos.ConversationDetail sendMessage(
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
        UUID messageId = commands.insertMessage(
                subject.tenantId(),
                subject.userId(),
                conversationId,
                request.idempotencyKey(),
                senderName(subject),
                subject.personPublicId(),
                request.body(),
                request.replyToMessageId());
        commands.audit(
                subject.tenantId(), subject.userId(), "messaging.message.created",
                "MSG_MESSAGE", messageId.toString(), correlationId,
                Map.of("conversationId", conversationId));
        return conversation(conversationId);
    }

    @Transactional
    public MessagingDtos.ConversationDetail markRead(
            UUID conversationId,
            MessagingDtos.ReadCursorRequest request) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        if (commands.markRead(subject.tenantId(), subject.userId(), conversationId, request.messageId()) == 0) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND,
                    "The read cursor message was not found in this conversation.");
        }
        return conversation(conversationId);
    }

    @Transactional
    public MessagingDtos.ConversationDetail addReaction(
            UUID conversationId,
            UUID messageId,
            MessagingDtos.ReactionRequest request) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        commands.react(subject.tenantId(), subject.userId(), messageId, request.emoji());
        return conversation(conversationId);
    }

    @Transactional
    public MessagingDtos.ConversationDetail removeReaction(
            UUID conversationId,
            UUID messageId,
            String emoji) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        visibleConversation(subject.tenantId(), subject.userId(), conversationId);
        commands.removeReaction(subject.tenantId(), subject.userId(), messageId, emoji);
        return conversation(conversationId);
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
                        "REST",
                        "/api/messaging/v1/realtime",
                        "READY_FOR_WEBSOCKET_GATEWAY",
                        "Durable reads and commands are active; live fanout can attach to this contract."));
    }

    private boolean canModerate(long tenantId, UUID conversationId, long userId) {
        return queries.members(tenantId, conversationId).stream()
                .anyMatch(member -> member.userId() == userId
                        && ("OWNER".equals(member.memberRole())
                        || "MODERATOR".equals(member.memberRole())));
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
