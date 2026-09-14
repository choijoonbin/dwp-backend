package com.dwp.services.notification.integration;

import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.security.NotificationRequestContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable producer snapshot, not a claim of current Approval runtime authority. */
public record ApprovalSlaNotificationPlan(
        UUID eventId, NotificationRequestContext.Actor actor, String eventType,
        String canonicalEnvelope, String envelopeSha256, String originalEnvelopeSha256,
        String recipientSnapshotSha256, String sourcePinsSha256,
        UUID requestId, Instant occurredAt, Map<String, Object> pins, List<Recipient> recipients) {
    public static final int CHUNK_SIZE = 100;

    public ApprovalSlaNotificationPlan {
        pins = Map.copyOf(pins);
        recipients = List.copyOf(recipients);
    }

    public int chunkCount() { return (recipients.size() + CHUNK_SIZE - 1) / CHUNK_SIZE; }

    public List<Recipient> chunk(int index) {
        if (index < 0 || index >= chunkCount()) throw new IllegalArgumentException("Invalid SLA chunk.");
        int start = index * CHUNK_SIZE;
        return recipients.subList(start, Math.min(start + CHUNK_SIZE, recipients.size()));
    }

    public UUID childEventId(Recipient recipient) {
        if (!recipients.contains(recipient)) throw new IllegalArgumentException("Unknown SLA recipient.");
        return UUID.nameUUIDFromBytes(("dwp:approval:sla:v1:" + eventId + ":"
                + recipient.userId() + ":" + recipient.taskId()).getBytes(StandardCharsets.UTF_8));
    }

    public DirectMaterializationRequest request(Recipient recipient) {
        Map<String, Object> variables = new LinkedHashMap<>(pins);
        variables.put("requestId", requestId.toString());
        variables.put("taskId", recipient.taskId().toString());
        variables.put("taskVersion", recipient.taskVersion());
        variables.put("personPublicId", recipient.personPublicId().toString());
        variables.put("sourceEventId", eventId.toString());
        UUID child = childEventId(recipient);
        return new DirectMaterializationRequest(child, eventType, 1,
                "Approval.Quorum.SlaWarning".equals(eventType) ? "APPROVAL.SLA_WARNING" : "APPROVAL.SLA_BREACHED",
                List.of(recipient.userId()), "approval-sla:" + eventId + ":" + recipient.userId(),
                "ko-KR", "DIRECT", null, "approval-request:" + requestId,
                "/approvals/inbox?task=" + recipient.taskId(), occurredAt, null, true, Map.copyOf(variables));
    }

    public record Recipient(long userId, UUID personPublicId, UUID taskId, long taskVersion) { }
}
