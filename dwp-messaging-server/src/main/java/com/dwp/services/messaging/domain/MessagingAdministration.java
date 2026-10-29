package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.operations.MessagingOperationalReadiness;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.security.MessagingRequestContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Owns tenant policy administration and fail-closed operational readiness projection. */
final class MessagingAdministration {

    private final MessagingQueryRepository queries;
    private final MessagingCommandRepository commands;
    private final MessagingEventRecorder events;
    private final MessagingOperationalReadiness operationalReadiness;

    MessagingAdministration(
            MessagingQueryRepository queries,
            MessagingCommandRepository commands,
            MessagingEventRecorder events,
            MessagingOperationalReadiness operationalReadiness) {
        this.queries = queries;
        this.commands = commands;
        this.events = events;
        this.operationalReadiness = operationalReadiness;
    }

    MessagingDtos.AdminOverview overview() {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        return new MessagingDtos.AdminOverview(
                OffsetDateTime.now(),
                queries.adminMetrics(subject.tenantId()),
                queries.policy(subject.tenantId()),
                queries.adminConversations(subject.tenantId(), 12)
                        .stream()
                        .map(this::metadataOnly)
                        .toList(),
                operationalReadiness == null
                        ? new MessagingDtos.RuntimeReadiness(
                                OffsetDateTime.now(), "BLOCKED", List.of())
                        : operationalReadiness.snapshot(subject.tenantId()));
    }

    MessagingDtos.TenantPolicy updatePolicy(
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

    private MessagingDtos.ConversationSummary metadataOnly(
            MessagingDtos.ConversationSummary conversation) {
        MessagingDtos.MessageSummary message = conversation.lastMessage();
        MessagingDtos.MessageSummary redacted = message == null ? null
                : new MessagingDtos.MessageSummary(
                        message.messageId(), message.conversationId(), message.sequence(),
                        message.senderUserId(), message.senderPersonPublicId(), message.senderName(),
                        "", message.contentType(), message.messageKind(), message.replyToMessageId(),
                        message.editedAt(), message.deletedAt(), message.createdAt(), message.version(),
                        List.of(), message.replyCount(), null, List.of(), List.of());
        return new MessagingDtos.ConversationSummary(
                conversation.conversationId(), conversation.conversationKey(),
                conversation.conversationType(), conversation.name(), conversation.topic(),
                conversation.visibility(), conversation.dataClassification(),
                conversation.linkedSpaceKey(), conversation.linkedSpaceName(),
                conversation.lifecycleState(), conversation.memberCount(), conversation.unreadCount(),
                conversation.favorite(), conversation.pinned(), redacted,
                conversation.lastMessageAt(), conversation.version());
    }
}
