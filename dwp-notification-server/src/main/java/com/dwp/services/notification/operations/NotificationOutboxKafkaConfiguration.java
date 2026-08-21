package com.dwp.services.notification.operations;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "dwp.notification.outbox",
        name = "provision-topic",
        havingValue = "true")
public class NotificationOutboxKafkaConfiguration {

    @Bean
    NewTopic notificationOutboxTopic(
            @Value("${dwp.notification.outbox.topic:dwp.notification.outbox.v1}")
            String topic,
            @Value("${dwp.notification.outbox.partitions:6}") int partitions,
            @Value("${dwp.notification.outbox.replication-factor:1}")
            short replicationFactor,
            @Value("${dwp.notification.outbox.topic-retention-ms:604800000}")
            long retentionMillis) {
        if (topic.isBlank()
                || partitions < 1
                || replicationFactor < 1
                || retentionMillis < 60_000) {
            throw new IllegalArgumentException("Notification outbox topic configuration is invalid.");
        }
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retentionMillis))
                .build();
    }
}
