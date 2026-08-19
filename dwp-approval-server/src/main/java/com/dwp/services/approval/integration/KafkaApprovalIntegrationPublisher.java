package com.dwp.services.approval.integration;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@ConditionalOnProperty(
        name = "dwp.approval.integration-relay.enabled",
        havingValue = "true")
public class KafkaApprovalIntegrationPublisher implements ApprovalIntegrationPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaApprovalIntegrationPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${dwp.approval.integration-relay.topic:dwp.approval.events.v1}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    public void publish(ApprovalIntegrationOutboxRepository.PendingEvent event) {
        String key = event.requestId() == null
                ? event.eventId().toString()
                : event.requestId().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, event.payload());
        header(record, "dwp-event-id", event.eventId().toString());
        header(record, "dwp-event-type", event.eventType());
        header(record, "dwp-tenant-id", Long.toString(event.tenantId()));
        try {
            kafka.send(record).get(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Approval event publication was interrupted.", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Approval event publication failed.", exception);
        }
    }

    private void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
