package com.dwp.services.platform.mail;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Shared post-materialization hook for sandbox and future external-provider inbound mail. */
@Service
class MailInboundMessageService {

    private final MailDeliveryRepository deliveries;
    private final MailNotificationEvents notificationEvents;
    private final MailIncomingRuleRepository incomingRules;

    @Autowired
    MailInboundMessageService(
            MailDeliveryRepository deliveries,
            MailNotificationEvents notificationEvents,
            MailIncomingRuleRepository incomingRules) {
        this.deliveries = deliveries;
        this.notificationEvents = notificationEvents;
        this.incomingRules = incomingRules;
    }

    MailInboundMessageService(
            MailDeliveryRepository deliveries,
            MailNotificationEvents notificationEvents) {
        this(deliveries, notificationEvents, null);
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
        if (incomingRules != null) incomingRules.enqueue(tenantId, threadId, messageId);
        return replied;
    }
}
