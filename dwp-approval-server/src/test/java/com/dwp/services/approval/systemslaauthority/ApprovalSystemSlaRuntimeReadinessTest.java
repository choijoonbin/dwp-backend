package com.dwp.services.approval.systemslaauthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

class ApprovalSystemSlaRuntimeReadinessTest {
    @Test
    void requiresTheSourceAndDurableRelayAsOneExplicitActivation() {
        var dependencies = new AtomicInteger();
        var environment = enabled();
        var readiness = new ApprovalSystemSlaRuntimeReadiness(environment,
                dependencies::incrementAndGet, topic -> assertThat(topic).isEqualTo("dwp.approval.events.v1"));

        assertThat(dependencies).hasValue(1);
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void missingCompanionFlagFailsBeforeAnyKeyOrClientCanBeBorrowed() {
        var dependencies = new AtomicInteger();
        var environment = enabled().withProperty("dwp.approval.integration-relay.enabled", "false");

        assertThatThrownBy(() -> new ApprovalSystemSlaRuntimeReadiness(environment,
                dependencies::incrementAndGet, topic -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integration-relay.enabled=true");
        assertThat(dependencies).hasValue(0);
    }

    @Test
    void malformedOrUnsafeSchedulerAndTopicSettingsFailClosed() {
        for (var environment : new MockEnvironment[] {
                enabled().withProperty("dwp.approval.workflow-quorum.sla.poll-delay-ms", "249"),
                enabled().withProperty("dwp.approval.workflow-quorum.sla.lease-seconds", "301"),
                enabled().withProperty("dwp.approval.integration-relay.topic", " approval.events"),
                enabled().withProperty("dwp.approval.integration-relay.maximum-attempts", "ten") }) {
            assertThatThrownBy(() -> new ApprovalSystemSlaRuntimeReadiness(environment, () -> { }, topic -> { }))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void configuredButMissingBrokerTopicIsNotReady() {
        var readiness = new ApprovalSystemSlaRuntimeReadiness(enabled(), () -> { },
                topic -> { throw new IllegalStateException("missing"); });

        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(readiness.health().getDetails()).containsEntry("reason", "KAFKA_TOPIC_UNAVAILABLE");
    }

    private static MockEnvironment enabled() {
        return new MockEnvironment()
                .withProperty("dwp.approval.system-sla.source.enabled", "true")
                .withProperty("dwp.approval.integration-relay.enabled", "true");
    }
}
