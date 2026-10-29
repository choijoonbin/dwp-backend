package com.dwp.services.notification.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationDomainEventKafkaConfigurationTest {

    @Test
    void acceptsDistinctCanonicalInputAndDeadLetterTopics() {
        NotificationDomainEventKafkaConfiguration.validateTopics(
                "dwp.domain-events.v1", "dwp.domain-events.v1.DLT");
    }

    @Test
    void rejectsEqualInputAndDeadLetterTopics() {
        assertThatThrownBy(() -> NotificationDomainEventKafkaConfiguration.validateTopics(
                "dwp.domain-events.v1", "dwp.domain-events.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void rejectsBlankOrWhitespacePaddedTopics() {
        assertThatThrownBy(() -> NotificationDomainEventKafkaConfiguration.validateTopics(
                "dwp.domain-events.v1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical Kafka topic");
        assertThatThrownBy(() -> NotificationDomainEventKafkaConfiguration.validateTopics(
                " dwp.domain-events.v1 ", "dwp.domain-events.v1.DLT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical Kafka topic");
    }
}
