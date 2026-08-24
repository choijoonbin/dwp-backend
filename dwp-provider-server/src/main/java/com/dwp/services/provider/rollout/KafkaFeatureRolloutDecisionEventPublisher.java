package com.dwp.services.provider.rollout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(
        name = "dwp.provider.product-surface-rollout.publisher-enabled",
        havingValue = "true")
public class KafkaFeatureRolloutDecisionEventPublisher
        implements FeatureRolloutDecisionEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Duration timeout;

    public KafkaFeatureRolloutDecisionEventPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            @Value("${dwp.provider.product-surface-rollout.topic:"
                    + "dwp.feature-rollout.decision.changed.v1}") String topic,
            @Value("${dwp.provider.product-surface-rollout.publish-timeout:10s}")
                    Duration timeout) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("A rollout invalidation topic is required");
        }
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Invalid rollout publisher timeout");
        }
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topic = topic.trim();
        this.timeout = timeout;
    }

    @Override
    public void publish(List<FeatureRolloutDecisionOutboxRepository.DecisionEvent> events) {
        for (FeatureRolloutDecisionOutboxRepository.DecisionEvent event : events) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic, event.flagKey(), serialize(event));
            header(record, "dwp-event-id", event.eventId().toString());
            header(record, "dwp-event-type", "feature-rollout.decision.changed");
            try {
                kafka.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Rollout invalidation publication was interrupted", exception);
            } catch (ExecutionException | TimeoutException exception) {
                throw new IllegalStateException(
                        "Rollout invalidation publication failed", exception);
            }
        }
    }

    private String serialize(FeatureRolloutDecisionOutboxRepository.DecisionEvent event) {
        RolloutDecisionChanged payload = new RolloutDecisionChanged(
                event.eventId(), event.tenantScope(), event.authTenantId(), event.flagKey(),
                FeatureRolloutInternalEvaluationService.opaque(event.opaqueRevision()),
                event.state(), event.createdAt());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Rollout invalidation event serialization failed", exception);
        }
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    record RolloutDecisionChanged(
            UUID eventId,
            String tenantScope,
            Long tenantId,
            String flagKey,
            String opaqueRevision,
            String state,
            Instant occurredAt) {
    }
}
