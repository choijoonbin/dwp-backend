package com.dwp.services.notification.operations;

import com.dwp.audit.AuditEvent;
import com.dwp.audit.AuditEventPublisher;
import com.dwp.audit.AuditEventPublisher.DeliveryResult;
import com.dwp.services.notification.operations.NotificationAttentionAuditOutboxRepository.AttentionAuditEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationAttentionAuditRelayService {

    private static final Logger log = LoggerFactory.getLogger(
            NotificationAttentionAuditRelayService.class);

    private final NotificationAttentionAuditRelayTransaction transactions;
    private final AuditEventPublisher publisher;
    private final String leaseOwner;
    private final String serviceInstance;
    private final String environment;
    private final Duration leaseDuration;
    private final Duration publishedRetention;
    private final int maximumAttempts;
    private final int batchSize;
    private final Clock clock;
    private final Counter publishedCounter;
    private final Counter retryCounter;
    private final Counter deadCounter;
    private final Counter leaseLostCounter;

    @Autowired
    public NotificationAttentionAuditRelayService(
            NotificationAttentionAuditRelayTransaction transactions,
            AuditEventPublisher publisher,
            MeterRegistry meterRegistry,
            @Value("${dwp.notification.attention-audit.instance-id:${HOSTNAME:local}}")
            String instanceId,
            @Value("${DWP_ENVIRONMENT:local}") String environment,
            @Value("${dwp.notification.attention-audit.lease-duration:PT30S}")
            Duration leaseDuration,
            @Value("${dwp.notification.attention-audit.published-retention:P30D}")
            Duration publishedRetention,
            @Value("${dwp.notification.attention-audit.maximum-attempts:20}")
            int maximumAttempts,
            @Value("${dwp.notification.attention-audit.batch-size:100}") int batchSize) {
        this(
                transactions, publisher, meterRegistry, instanceId, environment,
                leaseDuration, publishedRetention, maximumAttempts, batchSize,
                Clock.systemUTC());
    }

    NotificationAttentionAuditRelayService(
            NotificationAttentionAuditRelayTransaction transactions,
            AuditEventPublisher publisher,
            MeterRegistry meterRegistry,
            String instanceId,
            String environment,
            Duration leaseDuration,
            Duration publishedRetention,
            int maximumAttempts,
            int batchSize,
            Clock clock) {
        if (instanceId == null || instanceId.isBlank()
                || environment == null || environment.isBlank()
                || leaseDuration.isZero() || leaseDuration.isNegative()
                || publishedRetention.compareTo(Duration.ofDays(1)) < 0
                || maximumAttempts < 3 || maximumAttempts > 100
                || batchSize < 1 || batchSize > 200) {
            throw new IllegalArgumentException("Attention audit relay configuration is invalid.");
        }
        this.transactions = transactions;
        this.publisher = publisher;
        this.leaseOwner = instanceId + ":" + UUID.randomUUID();
        this.serviceInstance = instanceId;
        this.environment = environment;
        this.leaseDuration = leaseDuration;
        this.publishedRetention = publishedRetention;
        this.maximumAttempts = maximumAttempts;
        this.batchSize = batchSize;
        this.clock = clock;
        this.publishedCounter = counter(
                meterRegistry, "dwp.notification.attention.audit.published",
                "Attention audit facts accepted by the canonical audit store");
        this.retryCounter = counter(
                meterRegistry, "dwp.notification.attention.audit.retried",
                "Attention audit deliveries scheduled for retry");
        this.deadCounter = counter(
                meterRegistry, "dwp.notification.attention.audit.dead",
                "Permanently rejected or retry-exhausted attention audit facts");
        this.leaseLostCounter = counter(
                meterRegistry, "dwp.notification.attention.audit.lease.lost",
                "Attention audit delivery completions rejected after lease loss");
    }

    public RelayResult relayTenant(long tenantId, Instant now) {
        String leaseToken = leaseOwner + ":" + UUID.randomUUID();
        List<AttentionAuditEvent> leased = transactions.lease(
                tenantId, leaseToken, now, now.plus(leaseDuration), batchSize);
        RelayAccumulator result = new RelayAccumulator();
        publishOrIsolate(leased, leaseToken, now, result);
        int cleaned = transactions.cleanupPublished(
                tenantId, now.minus(publishedRetention), batchSize);
        long deadBacklog = transactions.deadCount(tenantId);
        publishedCounter.increment(result.published);
        retryCounter.increment(result.retried);
        deadCounter.increment(result.dead);
        leaseLostCounter.increment(result.leaseLost);
        return new RelayResult(
                leased.size(), result.published, result.retried,
                result.dead, result.leaseLost, cleaned, deadBacklog);
    }

    private void publishOrIsolate(
            List<AttentionAuditEvent> events,
            String leaseToken,
            Instant now,
            RelayAccumulator result) {
        if (events.isEmpty()) return;
        DeliveryResult delivery;
        try {
            delivery = publisher.publish(events.stream().map(this::auditEvent).toList());
        } catch (RuntimeException exception) {
            fail(events, leaseToken, now, exception.getClass().getSimpleName(), false, result);
            return;
        }
        if (delivery == DeliveryResult.ACCEPTED) {
            for (AttentionAuditEvent event : events) {
                if (transactions.markPublished(
                        event.tenantId(), event.eventId(), leaseToken, Instant.now(clock))) {
                    result.published++;
                } else {
                    result.leaseLost++;
                    log.warn(
                            "Attention audit publish completion lost lease; tenantId={} eventId={}",
                            event.tenantId(), event.eventId());
                }
            }
            return;
        }
        if (delivery == DeliveryResult.REJECTED && events.size() > 1) {
            int midpoint = events.size() / 2;
            publishOrIsolate(events.subList(0, midpoint), leaseToken, now, result);
            publishOrIsolate(events.subList(midpoint, events.size()), leaseToken, now, result);
            return;
        }
        fail(
                events, leaseToken, now,
                delivery == DeliveryResult.REJECTED
                        ? "Canonical audit store permanently rejected this event"
                        : "Canonical audit store is temporarily unavailable",
                delivery == DeliveryResult.REJECTED,
                result);
    }

    private void fail(
            List<AttentionAuditEvent> events,
            String leaseToken,
            Instant now,
            String error,
            boolean poison,
            RelayAccumulator result) {
        for (AttentionAuditEvent event : events) {
            int attempt = poison ? maximumAttempts : event.attemptCount();
            boolean marked = transactions.markFailed(
                    event.tenantId(), event.eventId(), leaseToken, attempt,
                    maximumAttempts, now.plus(backoff(event.eventId(), attempt)), error);
            if (!marked) {
                result.leaseLost++;
            } else if (attempt >= maximumAttempts) {
                result.dead++;
            } else {
                result.retried++;
            }
        }
    }

    private AuditEvent auditEvent(AttentionAuditEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subjectVersion", event.subjectVersion());
        if (event.scopeKind() != null) metadata.put("scopeKind", event.scopeKind());
        if (event.effect() != null) metadata.put("effect", event.effect());
        return AuditEvent.builder()
                .eventId(event.eventId())
                .occurredAt(event.occurredAt())
                .tenantId(event.tenantId())
                .category("ADMIN_CHANGE")
                .action(action(event.eventType()))
                .outcome("SUCCESS")
                .severity("TEST_DELIVERY_CREATED".equals(event.eventType()) ? "LOW" : "MEDIUM")
                .riskScore("TEST_DELIVERY_CREATED".equals(event.eventType()) ? 10 : 35)
                .actorType("USER")
                .actorId(Long.toString(event.userId()))
                .sourceService("dwp-notification-server")
                .sourceModule("notification-attention")
                .sourceInstance(serviceInstance)
                .environment(environment)
                .targetType(event.subjectType())
                .targetId(event.subjectId().toString())
                .metadata(metadata)
                .retentionClass("EXTENDED")
                .build()
                .sanitized();
    }

    private String action(String eventType) {
        return switch (eventType) {
            case "RULE_CREATED" -> "notification.attention.rule.created";
            case "RULE_UPDATED" -> "notification.attention.rule.updated";
            case "RULE_DELETED" -> "notification.attention.rule.deleted";
            case "POLICY_LOCKED" -> "notification.attention.rule.policy_locked";
            case "TEST_DELIVERY_CREATED" -> "notification.attention.test_delivery.created";
            default -> throw new IllegalArgumentException("Unsupported attention audit event type.");
        };
    }

    private Duration backoff(UUID eventId, int attempt) {
        long exponent = Math.min(Math.max(attempt - 1, 0), 8);
        long seconds = Math.min(300, 1L << exponent);
        long jitterMillis = Math.floorMod(eventId.hashCode(), 1000);
        return Duration.ofSeconds(seconds).plusMillis(jitterMillis);
    }

    private Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    private static final class RelayAccumulator {
        private int published;
        private int retried;
        private int dead;
        private int leaseLost;
    }

    public record RelayResult(
            int leased,
            int published,
            int retried,
            int dead,
            int leaseLost,
            int cleaned,
            long deadBacklog) {
    }
}
