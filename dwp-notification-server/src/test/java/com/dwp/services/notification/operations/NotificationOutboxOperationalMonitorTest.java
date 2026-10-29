package com.dwp.services.notification.operations;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationOutboxOperationalMonitorTest {

    @Test
    void publishesBacklogRetryDeadOldestAndSchedulerSuccessMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationOutboxOperationalMonitor monitor = monitor(registry);
        Instant now = Instant.now();

        monitor.recordCycle(
                new NotificationOutboxOperationalMonitor.OutboxSnapshot(
                        7, 3, 0, 0, now.minusSeconds(45)),
                true,
                now,
                null);

        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
        assertThat(registry.get("dwp.notification.outbox.backlog").gauge().value())
                .isEqualTo(7);
        assertThat(registry.get("dwp.notification.outbox.retries").gauge().value())
                .isEqualTo(3);
        assertThat(registry.get("dwp.notification.outbox.oldest.age.seconds").gauge().value())
                .isEqualTo(45);
        assertThat(registry.get(
                "dwp.notification.outbox.scheduler.last.success.epoch.seconds")
                .gauge().value()).isEqualTo(now.getEpochSecond());
    }

    @Test
    void deadEventsAndStaleSchedulerBlockReadiness() {
        NotificationOutboxOperationalMonitor dead = monitor(new SimpleMeterRegistry());
        Instant now = Instant.now();
        dead.recordCycle(
                new NotificationOutboxOperationalMonitor.OutboxSnapshot(1, 1, 1, 0, now),
                true,
                now,
                null);
        assertThat(dead.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(dead.health().getDetails()).containsEntry("dead", 1L);

        NotificationOutboxOperationalMonitor stale = new NotificationOutboxOperationalMonitor(
                new SimpleMeterRegistry(), true, Duration.ZERO, Duration.ofSeconds(1),
                Duration.ofMinutes(15), 10_000);
        stale.recordCycle(
                new NotificationOutboxOperationalMonitor.OutboxSnapshot(0, 0, 0, 0, null),
                true,
                now.minusSeconds(10),
                null);
        assertThat(stale.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(stale.health().getDetails()).containsEntry("schedulerStale", true);
    }

    @Test
    void leaseLossHasItsOwnMetricAndBlocksTheCurrentReadinessCycle() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationOutboxOperationalMonitor monitor = monitor(registry);
        Instant now = Instant.now();
        monitor.recordRelay(new NotificationOutboxRelayService.RelayResult(
                1, 0, 1, 0, 1, 0));
        monitor.recordCycle(
                new NotificationOutboxOperationalMonitor.OutboxSnapshot(
                        1, 1, 0, 1, now.minusSeconds(5)),
                false,
                now,
                "LEASE_LOST");

        assertThat(registry.get("dwp.notification.outbox.lease.lost.total")
                .counter().count()).isOne();
        assertThat(monitor.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(monitor.health().getDetails())
                .containsEntry("leaseLostLastCycle", 1L);
    }

    @Test
    void transientDeliveryFailureDoesNotAdvanceSuccessWatermarkAndUsesStaleGrace() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationOutboxOperationalMonitor monitor = monitor(registry);
        Instant now = Instant.now();
        Instant previousSuccess = now.minusSeconds(30);
        var empty = new NotificationOutboxOperationalMonitor.OutboxSnapshot(
                0, 0, 0, 0, null);
        monitor.recordCycle(empty, true, previousSuccess, null);

        monitor.recordCycle(empty, false, now, "EVENT_DELIVERY_FAILED");

        assertThat(registry.get(
                "dwp.notification.outbox.scheduler.last.success.epoch.seconds")
                .gauge().value()).isEqualTo(previousSuccess.getEpochSecond());
        assertThat(monitor.health().getStatus()).isEqualTo(Status.UP);
        assertThat(monitor.health().getDetails())
                .containsEntry("lastFailure", "EVENT_DELIVERY_FAILED")
                .containsEntry("schedulerStale", false);
    }

    private NotificationOutboxOperationalMonitor monitor(SimpleMeterRegistry registry) {
        return new NotificationOutboxOperationalMonitor(
                registry, true, Duration.ZERO, Duration.ofMinutes(2),
                Duration.ofMinutes(15), 10_000);
    }
}
