package com.dwp.services.notification.integration;

import com.dwp.services.notification.domain.DirectNotificationMaterializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "dwp.notification.domain-events.enabled",
        havingValue = "true")
public class NotificationDomainEventKafkaListener {

    private final NotificationDomainEventTranslator translator;
    private final DirectNotificationMaterializer materializer;

    public NotificationDomainEventKafkaListener(
            NotificationDomainEventTranslator translator,
            DirectNotificationMaterializer materializer) {
        this.translator = translator;
        this.materializer = materializer;
    }

    @KafkaListener(
            topics = "${dwp.notification.domain-events.topic:dwp.domain-events.v1}",
            groupId = "${dwp.notification.domain-events.group-id:"
                    + "dwp-notification-domain-events-v1}",
            containerFactory = "notificationDomainEventKafkaListenerContainerFactory")
    public void receive(String payload) {
        translator.translate(payload).forEach(translation -> materializer.materialize(
                translation.actor(), translation.request(), translation.correlationId()));
    }
}
