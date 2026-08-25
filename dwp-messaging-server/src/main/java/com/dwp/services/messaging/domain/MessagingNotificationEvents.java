package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
class MessagingNotificationEvents {

    static final String MESSAGE_SENT = "messaging.message.sent.v1";
    static final String MESSAGE_DELETED = "messaging.message.deleted.v1";
    static final String DIRECT_MESSAGE = "MESSAGING.DIRECT_MESSAGE";
    static final String CHANNEL_MESSAGE = "MESSAGING.CHANNEL_MESSAGE";
    static final String MENTION = "MESSAGING.MENTION";
    static final String THREAD_REPLY = "MESSAGING.THREAD_REPLY";

    private static final String SOURCE = "urn:dwp:messaging";
    private static final String AGGREGATE = "MESSAGING_CONVERSATION";
    private static final int MAXIMUM_PREVIEW_LENGTH = 180;

    private final DomainEventRecorder recorder;
    private final ObjectMapper objectMapper;
    private final MessagingQueryRepository queries;

    @Autowired
    MessagingNotificationEvents(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper objectMapper,
            MessagingQueryRepository queries) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        this.queries = queries;
        contracts.register(MESSAGE_SENT, 1, 1);
        contracts.register(MESSAGE_DELETED, 1, 1);
    }

    private MessagingNotificationEvents() {
        this.recorder = null;
        this.objectMapper = null;
        this.queries = null;
    }

    static MessagingNotificationEvents disabled() {
        return new MessagingNotificationEvents();
    }

    void messageCreated(
            MessagingRequestContext.Subject subject,
            MessagingDtos.ConversationSummary conversation,
            MessagingCommandRepository.MessageInsertResult result,
            MessagingDtos.SendMessageRequest request,
            Long replyParentSenderUserId,
            String correlationId) {
        if (recorder == null || objectMapper == null || queries == null) return;

        List<MessagingDtos.MemberSummary> members =
                queries.members(subject.tenantId(), conversation.conversationId());
        Set<Long> activeMemberIds = members.stream()
                .map(MessagingDtos.MemberSummary::userId)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        if (!activeMemberIds.containsAll(request.mentionedUserIds())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A mentioned user is not an active conversation member.");
        }

        ObjectNode data = objectMapper.createObjectNode()
                .put("conversationId", conversation.conversationId().toString())
                .put("messageId", result.messageId().toString())
                .put("messageSequence", result.sequence())
                .put("conversationType", conversation.conversationType())
                .put("dataClassification", conversation.dataClassification());
        ArrayNode intents = data.putArray("notificationIntents");
        String preview = safePreview(request.body(), conversation.dataClassification());

        if ("DIRECT".equals(conversation.conversationType())) {
            List<Long> recipients = allEligibleRecipients(members, subject.userId());
            addIntent(
                    intents,
                    DIRECT_MESSAGE,
                    recipients,
                    "DIRECT",
                    "messaging-conversation:" + conversation.conversationId(),
                    subject,
                    conversation,
                    result.messageId(),
                    preview);
        } else {
            Set<Long> mentioned = new LinkedHashSet<>(request.mentionedUserIds());
            List<Long> mentionRecipients = eligibleRecipients(
                    members, subject.userId(), mentioned);
            addIntent(
                    intents,
                    MENTION,
                    mentionRecipients,
                    "MENTION",
                    "messaging-conversation:" + conversation.conversationId(),
                    subject,
                    conversation,
                    result.messageId(),
                    preview);

            Set<Long> higherPriorityRecipients = new LinkedHashSet<>(mentionRecipients);
            if (replyParentSenderUserId != null
                    && replyParentSenderUserId != subject.userId()
                    && !mentioned.contains(replyParentSenderUserId)) {
                List<Long> replyRecipient = eligibleRecipients(
                        members, subject.userId(), Set.of(replyParentSenderUserId));
                higherPriorityRecipients.addAll(replyRecipient);
                addIntent(
                        intents,
                        THREAD_REPLY,
                        replyRecipient,
                        "DIRECT",
                        "messaging-thread:" + request.replyToMessageId(),
                        subject,
                        conversation,
                        result.messageId(),
                        preview);
            }

            addIntent(
                    intents,
                    CHANNEL_MESSAGE,
                    allMessageRecipients(members, subject.userId(), higherPriorityRecipients),
                    "SUBSCRIBED",
                    "messaging-conversation:" + conversation.conversationId(),
                    subject,
                    conversation,
                    result.messageId(),
                    preview);
        }

        if (intents.isEmpty()) return;
        String resolvedCorrelation = correlationId == null || correlationId.isBlank()
                ? "messaging:" + result.messageId()
                : correlationId.trim();
        recorder.record(DomainEventEnvelope.create(
                SOURCE,
                MESSAGE_SENT,
                1,
                subject.tenantId(),
                AGGREGATE,
                conversation.conversationId().toString(),
                result.sequence(),
                resolvedCorrelation,
                null,
                null,
                data));
    }

    void messageDeleted(
            MessagingRequestContext.Subject subject,
            MessagingDtos.ConversationSummary conversation,
            UUID messageId,
            long version,
            String correlationId) {
        if (recorder == null || objectMapper == null) return;
        ObjectNode data = objectMapper.createObjectNode()
                .put("conversationId", conversation.conversationId().toString())
                .put("messageId", messageId.toString());
        data.putArray("notificationTargetChanges")
                .addObject()
                .put("ownerAppKey", "messaging")
                .put("targetReference", messageRoute(conversation, messageId))
                .put("state", "DELETED")
                .put("reason", "SOURCE_DELETED");
        String resolvedCorrelation = correlationId == null || correlationId.isBlank()
                ? "messaging-delete:" + messageId
                : correlationId.trim();
        recorder.record(DomainEventEnvelope.create(
                SOURCE,
                MESSAGE_DELETED,
                1,
                subject.tenantId(),
                "MESSAGING_MESSAGE",
                messageId.toString(),
                version,
                resolvedCorrelation,
                null,
                null,
                data));
    }

    private List<Long> eligibleRecipients(
            List<MessagingDtos.MemberSummary> members,
            long senderUserId,
            Set<Long> requestedRecipients) {
        if (requestedRecipients.isEmpty()) return List.of();
        return members.stream()
                .filter(member -> member.userId() != senderUserId)
                .filter(member -> !"MUTE".equals(member.notificationLevel()))
                .filter(member -> requestedRecipients.contains(member.userId()))
                .map(MessagingDtos.MemberSummary::userId)
                .distinct()
                .toList();
    }

    private List<Long> allEligibleRecipients(
            List<MessagingDtos.MemberSummary> members,
            long senderUserId) {
        return members.stream()
                .filter(member -> member.userId() != senderUserId)
                .filter(member -> !"MUTE".equals(member.notificationLevel()))
                .map(MessagingDtos.MemberSummary::userId)
                .distinct()
                .toList();
    }

    private List<Long> allMessageRecipients(
            List<MessagingDtos.MemberSummary> members,
            long senderUserId,
            Set<Long> excludedRecipients) {
        return members.stream()
                .filter(member -> member.userId() != senderUserId)
                .filter(member -> "ALL".equals(member.notificationLevel()))
                .filter(member -> !excludedRecipients.contains(member.userId()))
                .map(MessagingDtos.MemberSummary::userId)
                .distinct()
                .toList();
    }

    private void addIntent(
            ArrayNode intents,
            String typeKey,
            List<Long> recipients,
            String reasonCode,
            String threadKey,
            MessagingRequestContext.Subject subject,
            MessagingDtos.ConversationSummary conversation,
            UUID messageId,
            String preview) {
        if (recipients.isEmpty()) return;
        ObjectNode intent = intents.addObject()
                .put("typeKey", typeKey)
                .put("threadKey", threadKey)
                .put("locale", "ko-KR")
                .put("reasonCode", reasonCode)
                .put("actorReference", "user:" + subject.userId())
                .put("subjectReference", "messaging-message:" + messageId)
                .put("targetReference", messageRoute(conversation, messageId))
                .put("actionRequired", false);
        ArrayNode recipientIds = intent.putArray("recipientUserIds");
        recipients.forEach(recipientIds::add);
        intent.putObject("variables")
                .put("senderName", subject.displayName())
                .put("conversationName", conversation.name())
                .put("conversationId", conversation.conversationId().toString())
                .put("messageId", messageId.toString())
                .put("messagePreview", preview);
    }

    private String safePreview(String body, String dataClassification) {
        String classification = dataClassification == null
                ? "INTERNAL"
                : dataClassification.toUpperCase(Locale.ROOT);
        if ("CONFIDENTIAL".equals(classification) || "RESTRICTED".equals(classification)) {
            return "보호된 대화에 새 메시지가 도착했습니다.";
        }
        String normalized = body == null
                ? ""
                : body.replaceAll("<[^>]*>", " ")
                        .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                        .replaceAll("\\s+", " ")
                        .trim();
        if (normalized.isBlank()) return "새 메시지가 도착했습니다.";
        return normalized.length() <= MAXIMUM_PREVIEW_LENGTH
                ? normalized
                : normalized.substring(0, MAXIMUM_PREVIEW_LENGTH - 1) + "…";
    }

    private String messageRoute(
            MessagingDtos.ConversationSummary conversation,
            UUID messageId) {
        String view = "DIRECT".equals(conversation.conversationType()) ? "direct" : "inbox";
        return "/messages/" + view
                + "?conversation=" + conversation.conversationId()
                + "&message=" + messageId;
    }
}
