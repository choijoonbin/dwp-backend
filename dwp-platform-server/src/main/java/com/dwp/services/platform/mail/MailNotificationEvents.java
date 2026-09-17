package com.dwp.services.platform.mail;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
class MailNotificationEvents {

    static final String SOURCE = "urn:dwp:platform:mail";
    static final String NEW_MAIL_RECEIVED = "mail.message.received.v1";
    static final String SHARED_INBOX_ASSIGNED = "mail.shared-inbox.assigned.v1";
    static final String FOLLOW_UP_DUE = "mail.follow-up.due.v1";
    static final String NEW_MAIL_TYPE = "MAIL.NEW_MESSAGE";
    static final String SHARED_ASSIGNMENT_TYPE = "MAIL.SHARED_ASSIGNMENT";
    static final String FOLLOW_UP_DUE_TYPE = "MAIL.FOLLOW_UP_DUE";

    private final DomainEventRecorder recorder;
    private final ObjectMapper json;
    private final MailNotificationPreferenceRepository preferences;

    MailNotificationEvents(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper json,
            MailNotificationPreferenceRepository preferences) {
        this.recorder = recorder;
        this.json = json;
        this.preferences = preferences;
        contracts.register(NEW_MAIL_RECEIVED, 1, 1);
        contracts.register(SHARED_INBOX_ASSIGNED, 1, 1);
        contracts.register(FOLLOW_UP_DUE, 1, 1);
    }

    Optional<UUID> newMailReceived(long tenantId, UUID threadId, UUID messageId) {
        requireIdentity(tenantId, threadId, messageId);
        var target = preferences.newMailTarget(tenantId, threadId, messageId).orElse(null);
        if (target == null || !preferences.notifyNewMail(tenantId, target.userId())) {
            return Optional.empty();
        }
        ObjectNode data = json.createObjectNode();
        addIntent(
                data,
                NEW_MAIL_TYPE,
                target.userId(),
                "DIRECT",
                threadId,
                "mail-message:" + messageId,
                "/mail/inbox?thread=" + threadId,
                false,
                null,
                Map.of("threadId", threadId.toString(), "messageId", messageId.toString()));
        return Optional.of(record(
                NEW_MAIL_RECEIVED,
                tenantId,
                "MAIL_MESSAGE",
                messageId,
                1,
                target.occurredAt(),
                "mail-inbound:" + messageId,
                data));
    }

    Optional<UUID> sharedInboxAssigned(
            long tenantId,
            long actorUserId,
            UUID threadId,
            long assignedUserId,
            long resultingVersion,
            String correlationId) {
        requireUser(tenantId, actorUserId);
        requireUser(tenantId, assignedUserId);
        if (threadId == null || resultingVersion < 1) {
            throw new IllegalArgumentException("Shared assignment identity is required.");
        }
        if (actorUserId == assignedUserId) return Optional.empty();
        var target = preferences.assignmentTarget(
                tenantId, threadId, assignedUserId, resultingVersion).orElse(null);
        if (target == null
                || !preferences.notifySharedAssignment(tenantId, assignedUserId)) {
            return Optional.empty();
        }
        ObjectNode data = json.createObjectNode();
        addIntent(
                data,
                SHARED_ASSIGNMENT_TYPE,
                assignedUserId,
                "ASSIGNEE",
                threadId,
                "mail-thread:" + threadId,
                "/mail/shared?threadId=" + threadId,
                true,
                "user:" + actorUserId,
                Map.of(
                        "threadId", threadId.toString(),
                        "sharedInboxId", target.sharedInboxId().toString()));
        return Optional.of(record(
                SHARED_INBOX_ASSIGNED,
                tenantId,
                "MAIL_THREAD",
                threadId,
                resultingVersion,
                target.occurredAt(),
                correlation(correlationId, "mail-assignment:" + threadId + ':' + resultingVersion),
                data));
    }

    Optional<UUID> followUpDue(MailFollowUpDueNotificationRepository.DueFollowUp followUp) {
        if (followUp == null) {
            throw new IllegalArgumentException("A due follow-up is required.");
        }
        requireUser(followUp.tenantId(), followUp.ownerUserId());
        if (!preferences.notifyFollowUpDue(
                followUp.tenantId(), followUp.ownerUserId())) {
            return Optional.empty();
        }
        long sequence = Math.addExact(followUp.version(), 1L);
        ObjectNode data = json.createObjectNode();
        addIntent(
                data,
                FOLLOW_UP_DUE_TYPE,
                followUp.ownerUserId(),
                "OWNER",
                followUp.threadId(),
                "mail-follow-up:" + followUp.followUpId(),
                "/mail/follow-up?threadId=" + followUp.threadId(),
                true,
                null,
                Map.of(
                        "threadId", followUp.threadId().toString(),
                        "followUpId", followUp.followUpId().toString(),
                        "dueAt", followUp.expectedReplyAt().toString()));
        ObjectNode intent = (ObjectNode) data.path("notificationIntents").get(0);
        intent.put("dueAt", followUp.expectedReplyAt().toInstant().toString());
        return Optional.of(record(
                FOLLOW_UP_DUE,
                followUp.tenantId(),
                "MAIL_FOLLOW_UP",
                followUp.followUpId(),
                sequence,
                followUp.expectedReplyAt(),
                "mail-follow-up-due:" + followUp.followUpId() + ':' + sequence,
                data));
    }

    private void addIntent(
            ObjectNode data,
            String typeKey,
            long recipientUserId,
            String reasonCode,
            UUID threadId,
            String subjectReference,
            String targetReference,
            boolean actionRequired,
            String actorReference,
            Map<String, String> variables) {
        ObjectNode intent = data.putArray("notificationIntents").addObject()
                .put("typeKey", typeKey)
                .put("threadKey", "mail-thread:" + threadId)
                .put("locale", "ko-KR")
                .put("reasonCode", reasonCode)
                .put("subjectReference", subjectReference)
                .put("targetReference", targetReference)
                .put("actionRequired", actionRequired);
        if (actorReference != null) intent.put("actorReference", actorReference);
        intent.putArray("recipientUserIds").add(recipientUserId);
        intent.putArray("contexts").addObject()
                .put("kind", "THREAD")
                .put("key", "mail-thread:" + threadId)
                .put("matchable", true);
        ObjectNode variableNode = intent.putObject("variables");
        variables.forEach(variableNode::put);
    }

    private UUID record(
            String type,
            long tenantId,
            String aggregateType,
            UUID aggregateId,
            long sequence,
            OffsetDateTime occurredAt,
            String correlationId,
            ObjectNode data) {
        if (sequence < 1 || occurredAt == null) {
            throw new IllegalArgumentException("Mail notification ordering evidence is required.");
        }
        UUID eventId = UUID.nameUUIDFromBytes(String.join("\u001f",
                SOURCE,
                type,
                Long.toString(tenantId),
                aggregateType,
                aggregateId.toString(),
                Long.toString(sequence)).getBytes(StandardCharsets.UTF_8));
        return recorder.record(new DomainEventEnvelope(
                "1.0",
                eventId,
                SOURCE,
                type,
                1,
                occurredAt.toInstant(),
                aggregateType + '/' + aggregateId,
                tenantId,
                aggregateType,
                aggregateId.toString(),
                sequence,
                correlationId,
                null,
                null,
                data,
                Map.of()));
    }

    private void requireIdentity(long tenantId, UUID first, UUID second) {
        if (tenantId < 1 || first == null || second == null) {
            throw new IllegalArgumentException("Mail notification identity is required.");
        }
    }

    private void requireUser(long tenantId, long userId) {
        if (tenantId < 1 || userId < 1) {
            throw new IllegalArgumentException("Mail notification user identity is required.");
        }
    }

    private String correlation(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
