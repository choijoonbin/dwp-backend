package com.dwp.services.notification.integration;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaAdmin;

/** Activation and broker-topic readiness for the Approval SYSTEM_SLA consumer. */
public final class ApprovalSlaNotificationReadiness implements HealthIndicator {
    private static final String PREFIX = "dwp.notification.approval-sla.";

    @FunctionalInterface
    interface BrokerProbe {
        void requireTopics(Set<String> topics) throws Exception;
    }

    private final String topic;
    private final String deadLetterTopic;
    private final BrokerProbe broker;

    public ApprovalSlaNotificationReadiness(Environment environment, Runnable requireDependencies,
            KafkaAdmin kafkaAdmin) {
        this(environment, requireDependencies, topics -> requireTopics(kafkaAdmin, topics));
    }

    ApprovalSlaNotificationReadiness(Environment environment, Runnable requireDependencies,
            BrokerProbe broker) {
        topic = topic(environment.getProperty(PREFIX + "topic", "dwp.approval.events.v1"));
        deadLetterTopic = topic(environment.getProperty(PREFIX + "dead-letter-topic",
                "dwp.approval.events.v1.DLT"));
        String group = identifier(environment.getProperty(PREFIX + "group-id",
                "dwp-notification-approval-system-sla-v1"));
        if (topic.equals(deadLetterTopic)) throw invalid("SYSTEM_SLA input and dead-letter topics must differ");
        if (requireDependencies == null || broker == null || group.isEmpty()) {
            throw invalid("SYSTEM_SLA notification dependencies are required");
        }
        requireDependencies.run();
        this.broker = broker;
    }

    @Override
    public Health health() {
        try {
            broker.requireTopics(Set.of(topic, deadLetterTopic));
            return Health.up().withDetail("contract", "DWP_NOTIFICATION_APPROVAL_SYSTEM_SLA_V1")
                    .withDetail("topic", topic).withDetail("deadLetterTopic", deadLetterTopic).build();
        } catch (Exception unavailable) {
            return Health.down().withDetail("contract", "DWP_NOTIFICATION_APPROVAL_SYSTEM_SLA_V1")
                    .withDetail("reason", "KAFKA_TOPICS_UNAVAILABLE").build();
        }
    }

    String topic() {
        return topic;
    }

    String deadLetterTopic() {
        return deadLetterTopic;
    }

    private static void requireTopics(KafkaAdmin kafkaAdmin, Set<String> topics) throws Exception {
        if (kafkaAdmin == null) throw invalid("Kafka admin is required");
        Map<String, Object> properties = kafkaAdmin.getConfigurationProperties();
        try (AdminClient client = AdminClient.create(properties)) {
            var descriptions = client.describeTopics(topics).allTopicNames().get(3, TimeUnit.SECONDS);
            if (!descriptions.keySet().containsAll(topics)
                    || descriptions.values().stream().anyMatch(description -> description.partitions().isEmpty())) {
                throw invalid("SYSTEM_SLA Kafka topics are not provisioned");
            }
        }
    }

    static String topic(String value) {
        if (value == null || value.length() > 249 || !value.matches("[A-Za-z0-9._-]+")
                || ".".equals(value) || "..".equals(value)) throw invalid("Invalid SYSTEM_SLA Kafka topic");
        return value;
    }

    private static String identifier(String value) {
        if (value == null || value.length() > 255 || !value.matches("[A-Za-z0-9._-]+")) {
            throw invalid("Invalid SYSTEM_SLA Kafka group");
        }
        return value;
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }
}
