package com.dwp.services.meeting.videomeeting.domain;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class MeetingInvitationDeliveryModels {

    private static final String IDENTITY_VERSION = "meeting-invitation-notification-v1";

    private MeetingInvitationDeliveryModels() {
    }

    public record Event(
            UUID eventId,
            long tenantId,
            UUID meetingId,
            String eventType,
            OffsetDateTime occurredAt,
            UUID dispatchFence,
            OffsetDateTime leaseExpiresAt) {
    }

    public record Claim(
            Event event,
            long recipientUserId,
            UUID sourceEventId,
            int attemptCount,
            UUID deliveryFence,
            OffsetDateTime deliveryLeaseExpiresAt) {
    }

    static UUID sourceEventId(UUID eventId, long tenantId, long recipientUserId) {
        String identity = IDENTITY_VERSION + "|" + tenantId + "|" + eventId
                + "|" + recipientUserId;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.US_ASCII));
    }
}
