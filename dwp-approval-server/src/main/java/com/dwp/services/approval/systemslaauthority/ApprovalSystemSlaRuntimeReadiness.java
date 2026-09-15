package com.dwp.services.approval.systemslaauthority;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaAdmin;

/** Activation contract for the scheduled SYSTEM_SLA producer and its durable Kafka relay. */
public final class ApprovalSystemSlaRuntimeReadiness implements HealthIndicator {
    private static final String SLA = "dwp.approval.workflow-quorum.sla.";
    private static final String SOURCE = "dwp.approval.system-sla.source.";
    private static final String RELAY = "dwp.approval.integration-relay.";

    @FunctionalInterface
    interface BrokerProbe {
        void requireTopic(String topic) throws Exception;
    }

    private final String topic;
    private final BrokerProbe broker;

    public ApprovalSystemSlaRuntimeReadiness(Environment environment, Runnable requireDependencies,
            KafkaAdmin kafkaAdmin) {
        this(environment, requireDependencies, topic -> requireTopic(kafkaAdmin, topic));
    }

    ApprovalSystemSlaRuntimeReadiness(Environment environment, Runnable requireDependencies,
            BrokerProbe broker) {
        requireTrue(environment, SOURCE + "enabled");
        requireTrue(environment, RELAY + "enabled");
        bounded(environment, SLA + "batch-size", 50, 1, 100);
        bounded(environment, SLA + "lease-seconds", 30, 1, 300);
        bounded(environment, SLA + "poll-delay-ms", 2000, 250, 60000);
        bounded(environment, RELAY + "batch-size", 50, 1, 200);
        bounded(environment, RELAY + "maximum-attempts", 10, 1, 100);
        bounded(environment, RELAY + "poll-delay-ms", 2000, 250, 60000);
        topic = topic(environment.getProperty(RELAY + "topic", "dwp.approval.events.v1"));
        if (requireDependencies == null || broker == null) throw invalid("SYSTEM_SLA dependencies are required");
        requireDependencies.run();
        this.broker = broker;
    }

    @Override
    public Health health() {
        try {
            broker.requireTopic(topic);
            return Health.up().withDetail("contract", "DWP_APPROVAL_SYSTEM_SLA_V1")
                    .withDetail("topic", topic).build();
        } catch (Exception unavailable) {
            return Health.down().withDetail("contract", "DWP_APPROVAL_SYSTEM_SLA_V1")
                    .withDetail("reason", "KAFKA_TOPIC_UNAVAILABLE").build();
        }
    }

    String topic() {
        return topic;
    }

    private static void requireTopic(KafkaAdmin kafkaAdmin, String topic) throws Exception {
        if (kafkaAdmin == null) throw invalid("Kafka admin is required");
        Map<String, Object> properties = kafkaAdmin.getConfigurationProperties();
        try (AdminClient client = AdminClient.create(properties)) {
            var descriptions = client.describeTopics(Set.of(topic)).allTopicNames().get(3, TimeUnit.SECONDS);
            if (!descriptions.containsKey(topic) || descriptions.get(topic).partitions().isEmpty()) {
                throw invalid("SYSTEM_SLA Kafka topic is not provisioned");
            }
        }
    }

    private static void requireTrue(Environment environment, String property) {
        if (environment == null || !"true".equals(environment.getProperty(property))) {
            throw invalid("SYSTEM_SLA activation requires " + property + "=true");
        }
    }

    private static int bounded(Environment environment, String property, int fallback, int minimum,
            int maximum) {
        String raw = environment.getProperty(property);
        if (raw == null) return fallback;
        if (!raw.matches("0|[1-9][0-9]{0,8}")) throw invalid("Invalid SYSTEM_SLA integer: " + property);
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) throw invalid("SYSTEM_SLA integer is outside its bound: " + property);
            return value;
        } catch (NumberFormatException malformed) {
            throw invalid("Invalid SYSTEM_SLA integer: " + property);
        }
    }

    static String topic(String value) {
        if (value == null || value.length() > 249 || !value.matches("[A-Za-z0-9._-]+")
                || ".".equals(value) || "..".equals(value)) throw invalid("Invalid SYSTEM_SLA Kafka topic");
        return value;
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }
}
