package com.dwp.services.platform.mail;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Shared post-materialization hook for sandbox and future external-provider inbound mail. */
@Service
class MailInboundMessageService {

    private final MailDeliveryRepository deliveries;
    private final MailNotificationEvents notificationEvents;

    MailInboundMessageService(
            MailDeliveryRepository deliveries,
            MailNotificationEvents notificationEvents) {
        this.deliveries = deliveries;
        this.notificationEvents = notificationEvents;
    }

    @Transactional
    int inboundMessageMaterialized(
            Long tenantId, UUID threadId, UUID messageId, OffsetDateTime receivedAt) {
        if (tenantId == null || threadId == null || messageId == null || receivedAt == null) {
            throw new IllegalArgumentException("Inbound message identity and time are required.");
        }
        int replied = deliveries.markFollowUpsReplied(
                tenantId, threadId, messageId, receivedAt);
        notificationEvents.newMailReceived(tenantId, threadId, messageId);
        return replied;
    }
}
