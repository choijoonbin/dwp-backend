package com.dwp.services.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

class ApprovalSlaNotificationReadinessTest {
    @Test
    void validatesDependenciesAndBothProvisionedKafkaTopics() {
        var dependencies = new AtomicInteger();
        var readiness = new ApprovalSlaNotificationReadiness(new MockEnvironment(),
                dependencies::incrementAndGet,
                topics -> assertThat(topics).isEqualTo(Set.of("dwp.approval.events.v1",
                        "dwp.approval.events.v1.DLT")));

        assertThat(dependencies).hasValue(1);
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void rejectsMalformedOrAliasedKafkaBindings() {
        var aliased = new MockEnvironment()
                .withProperty("dwp.notification.approval-sla.topic", "dwp.approval.events.v1")
                .withProperty("dwp.notification.approval-sla.dead-letter-topic", "dwp.approval.events.v1");
        var malformed = new MockEnvironment()
                .withProperty("dwp.notification.approval-sla.group-id", " bad group");

        assertThatThrownBy(() -> new ApprovalSlaNotificationReadiness(aliased, () -> { }, topics -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must differ");
        assertThatThrownBy(() -> new ApprovalSlaNotificationReadiness(malformed, () -> { }, topics -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Kafka group");
    }

    @Test
    void unavailableInputOrDeadLetterTopicMakesTheConsumerNotReady() {
        var readiness = new ApprovalSlaNotificationReadiness(new MockEnvironment(), () -> { }, topics -> {
            throw new IllegalStateException("missing topic");
        });

        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(readiness.health().getDetails()).containsEntry("reason", "KAFKA_TOPICS_UNAVAILABLE");
    }
}
