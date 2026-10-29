package com.dwp.services.notification.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalNotificationKafkaConfigurationTest {

    @Test
    void interpretsMaximumAttemptsAsInitialDeliveryPlusBoundedRetries() {
        assertThat(ApprovalNotificationKafkaConfiguration.retryCount(4)).isEqualTo(3);
        assertThat(ApprovalNotificationKafkaConfiguration.retryCount(1)).isZero();
    }

    @Test
    void rejectsAnUnboundedOrEmptyRetryBudget() {
        assertThatThrownBy(() -> ApprovalNotificationKafkaConfiguration.retryCount(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void acceptsDistinctCanonicalInputAndDeadLetterTopics() {
        ApprovalNotificationKafkaConfiguration.validateTopics(
                "dwp.approval.events.v1", "dwp.approval.events.v1.DLT");
    }

    @Test
    void rejectsEqualInputAndDeadLetterTopics() {
        assertThatThrownBy(() -> ApprovalNotificationKafkaConfiguration.validateTopics(
                "dwp.approval.events.v1", "dwp.approval.events.v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void rejectsBlankOrWhitespacePaddedTopics() {
        assertThatThrownBy(() -> ApprovalNotificationKafkaConfiguration.validateTopics(
                " ", "dwp.approval.events.v1.DLT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical Kafka topic");
        assertThatThrownBy(() -> ApprovalNotificationKafkaConfiguration.validateTopics(
                "dwp.approval.events.v1", " dwp.approval.events.v1.DLT "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical Kafka topic");
    }
}
