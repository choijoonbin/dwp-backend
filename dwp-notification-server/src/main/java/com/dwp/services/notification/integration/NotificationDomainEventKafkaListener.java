package com.dwp.services.notification.integration;

import com.dwp.services.notification.domain.DirectNotificationMaterializer;
import com.dwp.services.notification.domain.NotificationTargetLifecycleService;
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
    private final NotificationTargetLifecycleService targetLifecycleService;

    public NotificationDomainEventKafkaListener(
            NotificationDomainEventTranslator translator,
            DirectNotificationMaterializer materializer,
            NotificationTargetLifecycleService targetLifecycleService) {
        this.translator = translator;
        this.materializer = materializer;
        this.targetLifecycleService = targetLifecycleService;
    }

    @KafkaListener(
            topics = "${dwp.notification.domain-events.topic:dwp.domain-events.v1}",
            groupId = "${dwp.notification.domain-events.group-id:"
                    + "dwp-notification-domain-events-v1}",
            containerFactory = "notificationDomainEventKafkaListenerContainerFactory")
    public void receive(String payload) {
        NotificationDomainEventTranslator.TranslationBatch batch =
                translator.translateBatch(payload);
        batch.notifications().forEach(translation -> materializer.materialize(
                translation.actor(), translation.request(), translation.correlationId()));
        batch.targetChanges().forEach(translation -> targetLifecycleService.apply(
                translation.actor(), translation.change()));
    }
}
