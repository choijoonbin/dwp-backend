package com.dwp.services.notification.operations;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationOutboxKafkaConfigurationTest {

    @Test
    void provisionsTheInternalRelayTopicWithBoundedRetention() {
        NotificationOutboxKafkaConfiguration configuration =
                new NotificationOutboxKafkaConfiguration();

        NewTopic topic = configuration.notificationOutboxTopic(
                "dwp.notification.outbox.v1", 6, (short) 1, 604_800_000);

        assertThat(topic.name()).isEqualTo("dwp.notification.outbox.v1");
        assertThat(topic.numPartitions()).isEqualTo(6);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
        assertThat(topic.configs())
                .containsEntry(TopicConfig.CLEANUP_POLICY_CONFIG, "delete")
                .containsEntry(TopicConfig.RETENTION_MS_CONFIG, "604800000");
    }
}
