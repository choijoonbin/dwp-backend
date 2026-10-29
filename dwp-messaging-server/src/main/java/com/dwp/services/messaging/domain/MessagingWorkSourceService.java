package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;

import java.util.UUID;

/** Projects an authorized, immutable excerpt for downstream Work provenance. */
final class MessagingWorkSourceService {

    private static final int EXCERPT_LIMIT = 500;

    private final MessagingQueryRepository queries;
    private final MessagingMessageQueryRepository messageQueries;

    MessagingWorkSourceService(
            MessagingQueryRepository queries,
            MessagingMessageQueryRepository messageQueries) {
        this.queries = queries;
        this.messageQueries = messageQueries;
    }

    MessagingDtos.WorkSourceMessage source(UUID conversationId, UUID messageId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        MessagingDtos.ConversationSummary conversation = queries.conversation(
                        subject.tenantId(), subject.userId(), conversationId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The conversation was not found."));
        MessagingMessageAccess access = messageQueries.access(
                        subject.tenantId(), conversationId, subject.userId(), messageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The message was not found."));
        MessagingDtos.MessageSummary message = messageQueries.message(
                        subject.tenantId(), conversationId, subject.userId(), messageId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The message was not found."));
        if (access.deletedAt() != null || message.deletedAt() != null
                || message.body() == null || message.body().isBlank()
                || "SYSTEM".equals(message.messageKind())) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The message is not available as a Work source.");
        }
        String excerpt = message.body().strip().replaceAll("\\s+", " ");
        if (excerpt.length() > EXCERPT_LIMIT) excerpt = excerpt.substring(0, EXCERPT_LIMIT);
        return new MessagingDtos.WorkSourceMessage(
                conversationId, messageId, bounded(conversation.name(), 200),
                bounded(message.senderName(), 200), message.createdAt(), message.editedAt(),
                excerpt, message.version());
    }

    private String bounded(String value, int limit) {
        if (value == null) return null;
        String result = value.strip();
        return result.length() <= limit ? result : result.substring(0, limit);
    }
}
