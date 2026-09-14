package com.dwp.services.notification.integration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

public final class ApprovalSlaNotificationKafkaListener {
    private final ApprovalSlaNotificationConsumer consumer;
    public ApprovalSlaNotificationKafkaListener(ApprovalSlaNotificationConsumer consumer) { this.consumer = consumer; }

    @KafkaListener(topics = "${dwp.notification.approval-sla.topic:dwp.approval.events.v1}",
            groupId = "${dwp.notification.approval-sla.group-id:dwp-notification-approval-system-sla-v1}",
            containerFactory = "approvalSlaKafkaListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        String type = ApprovalSlaNotificationContract.header(record, "dwp-event-type");
        if (ApprovalSlaNotificationContract.EVENT_TYPES.contains(type))
            consumer.deliver(ApprovalSlaNotificationContract.translate(record));
    }
}
