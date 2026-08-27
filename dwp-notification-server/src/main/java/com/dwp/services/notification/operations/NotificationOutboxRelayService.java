package com.dwp.services.notification.operations;

import com.dwp.services.notification.operations.NotificationOutboxRepository.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class NotificationOutboxRelayService {

    private final NotificationOutboxRelayTransaction transactions;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final String leaseOwner;
    private final Duration leaseDuration;
    private final Duration sendTimeout;
    private final Duration publishedRetention;
    private final int maximumAttempts;
    private final int batchSize;

    public NotificationOutboxRelayService(
            NotificationOutboxRelayTransaction transactions,
            KafkaTemplate<String, String> kafka,
            ObjectMapper objectMapper,
            @Value("${dwp.notification.outbox.topic:dwp.notification.outbox.v1}") String topic,
            @Value("${dwp.notification.outbox.instance-id:local}") String instanceId,
            @Value("${dwp.notification.outbox.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${dwp.notification.outbox.send-timeout:PT5S}") Duration sendTimeout,
            @Value("${dwp.notification.outbox.published-retention:P7D}")
            Duration publishedRetention,
            @Value("${dwp.notification.outbox.maximum-attempts:12}") int maximumAttempts,
            @Value("${dwp.notification.outbox.batch-size:50}") int batchSize) {
        if (topic.isBlank()
                || leaseDuration.isZero() || leaseDuration.isNegative()
                || sendTimeout.isZero() || sendTimeout.isNegative()
                || publishedRetention.isNegative()
                || maximumAttempts < 1
                || batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("Notification outbox configuration is invalid.");
        }
        this.transactions = transactions;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.leaseOwner = instanceId + ":" + UUID.randomUUID();
        this.leaseDuration = leaseDuration;
        this.sendTimeout = sendTimeout;
        this.publishedRetention = publishedRetention;
        this.maximumAttempts = maximumAttempts;
        this.batchSize = batchSize;
    }

    public RelayResult relayTenant(long tenantId, Instant now) {
        String leaseToken = leaseOwner + ":" + UUID.randomUUID();
        var events = transactions.lease(
                tenantId, leaseToken, now, now.plus(leaseDuration), batchSize);
        int published = 0;
        int failed = 0;
        int dead = 0;
        for (OutboxEvent event : events) {
            try {
                kafka.send(topic, event.eventKey(), envelope(event))
                        .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!transactions.markPublished(
                        tenantId, event.outboxId(), leaseToken, Instant.now())) {
                    throw new IllegalStateException("Notification outbox lease was lost.");
                }
                published++;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (markFailed(event, leaseToken, exception, now)) dead++;
                failed++;
                break;
            } catch (Exception exception) {
                if (markFailed(event, leaseToken, exception, now)) dead++;
                failed++;
            }
        }
        int cleaned = transactions.cleanupPublished(
                tenantId, now.minus(publishedRetention), batchSize);
        return new RelayResult(events.size(), published, failed, dead, cleaned);
    }

    private boolean markFailed(
            OutboxEvent event,
            String leaseToken,
            Exception exception,
            Instant now) {
        int attempt = event.attemptCount();
        boolean marked = transactions.markFailed(
                event.tenantId(),
                event.outboxId(),
                leaseToken,
                attempt,
                maximumAttempts,
                now.plus(backoff(event.outboxId(), attempt)),
                exception.getClass().getSimpleName() + ": " + exception.getMessage());
        return marked && attempt >= maximumAttempts;
    }

    private Duration backoff(UUID outboxId, int attempt) {
        long exponent = Math.min(Math.max(attempt - 1, 0), 8);
        long seconds = Math.min(300, 1L << exponent);
        long jitterMillis = Math.floorMod(outboxId.hashCode(), 1000);
        return Duration.ofSeconds(seconds).plusMillis(jitterMillis);
    }

    private String envelope(OutboxEvent event) {
        try {
            JsonNode data = objectMapper.readTree(event.payload());
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("specversion", "1.0");
            envelope.put("id", event.outboxId().toString());
            envelope.put("source", "urn:dwp:notification-server");
            envelope.put("type", event.eventType());
            envelope.put("subject", event.aggregateType() + "/" + event.aggregateId());
            envelope.put("time", event.occurredAt().toString());
            envelope.put("datacontenttype", "application/json");
            envelope.put("tenantid", Long.toString(event.tenantId()));
            envelope.put("eventkey", event.eventKey());
            envelope.put("data", data);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Notification outbox payload is invalid.", exception);
        }
    }

    public record RelayResult(
            int leased,
            int published,
            int failed,
            int dead,
            int cleaned) {
    }
}
