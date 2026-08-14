package com.dwp.core.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class KafkaDomainEventPublisher implements DomainEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Duration publishTimeout;

    public KafkaDomainEventPublisher(
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            String topic,
            Duration publishTimeout) {
        this.kafka = Objects.requireNonNull(kafka);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        if (topic == null || !topic.matches("[a-zA-Z0-9._-]{3,249}")) {
            throw new IllegalArgumentException("Domain-event topic name is invalid.");
        }
        this.topic = topic;
        this.publishTimeout = publishTimeout == null ? Duration.ofSeconds(5) : publishTimeout;
        if (this.publishTimeout.isZero() || this.publishTimeout.isNegative()
                || this.publishTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Domain-event publish timeout must be between 1ms and 30s.");
        }
    }

    @Override
    public void publish(List<DomainEventEnvelope> events) {
        if (events == null || events.isEmpty()) return;
        for (DomainEventEnvelope event : events) {
            try {
                kafka.send(topic, partitionKey(event), objectMapper.writeValueAsString(event))
                        .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (JsonProcessingException exception) {
                throw new DomainEventPublishException("Domain event serialization failed.", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DomainEventPublishException("Domain event publishing was interrupted.", exception);
            } catch (Exception exception) {
                throw new DomainEventPublishException("Kafka did not acknowledge the domain event.", exception);
            }
        }
    }

    static String partitionKey(DomainEventEnvelope event) {
        return Objects.toString(event.tenantId(), "global") + '|'
                + event.source() + '|'
                + event.aggregateType() + '|'
                + event.aggregateId();
    }

    public static final class DomainEventPublishException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DomainEventPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
