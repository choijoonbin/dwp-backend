package com.dwp.services.notification.integration;

import com.dwp.services.notification.domain.DirectNotificationMaterializer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "dwp.notification.approval-pilot.enabled",
        havingValue = "true")
public class ApprovalNotificationKafkaListener {

    private final ApprovalNotificationEventTranslator translator;
    private final DirectNotificationMaterializer materializer;

    public ApprovalNotificationKafkaListener(
            ApprovalNotificationEventTranslator translator,
            DirectNotificationMaterializer materializer) {
        this.translator = translator;
        this.materializer = materializer;
    }

    @KafkaListener(
            topics = "${dwp.notification.approval-pilot.topic:dwp.approval.events.v1}",
            groupId = "${dwp.notification.approval-pilot.group-id:"
                    + "dwp-notification-approval-pilot-v1}")
    public void receive(ConsumerRecord<String, String> record) {
        translator.translate(record).ifPresent(translation -> materializer.materialize(
                translation.actor(), translation.request(), translation.correlationId()));
    }
}
