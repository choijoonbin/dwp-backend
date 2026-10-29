package com.dwp.services.messaging.operations;

import com.dwp.core.event.DomainEventOutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagingDeliveryReadinessOperationsTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    private DomainEventOutboxRelay relay;
    private MessagingDeliveryReadinessOperations operations;

    @BeforeEach
    void setUp() {
        relay = mock(DomainEventOutboxRelay.class);
        operations = new MessagingDeliveryReadinessOperations(
                mock(JdbcTemplate.class), relay, true, Duration.ofMinutes(5), 100, CLOCK);
    }

    @Test
    void stoppedRelayBlocksEvenAnEmptyQueue() {
        when(relay.isRunning()).thenReturn(false);

        var status = operations.classifyOutbox(
                new MessagingDeliveryReadinessOperations.OutboxEvidence(0, 0, 0, null), NOW);

        assertThat(status.effective()).isFalse();
        assertThat(status.state()).isEqualTo("BLOCKED");
        assertThat(status.reasonCode()).isEqualTo("NOTIFICATION_OUTBOX_RELAY_NOT_RUNNING");
    }

    @Test
    void deadLettersAndStaleBacklogFailClosed() {
        when(relay.isRunning()).thenReturn(true);

        var dead = operations.classifyOutbox(
                new MessagingDeliveryReadinessOperations.OutboxEvidence(1, 0, 2, NOW), NOW);
        var stale = operations.classifyOutbox(
                new MessagingDeliveryReadinessOperations.OutboxEvidence(
                        1, 0, 0, NOW.minusMinutes(6)), NOW);

        assertThat(dead.reasonCode()).isEqualTo("NOTIFICATION_OUTBOX_DEAD_LETTERS");
        assertThat(dead.pendingWorkCount()).isEqualTo(3);
        assertThat(stale.reasonCode()).isEqualTo("NOTIFICATION_OUTBOX_BACKLOG_STALE");
        assertThat(stale.effective()).isFalse();
    }

    @Test
    void retryAndLargeCurrentBacklogAreDegradedWithoutClaimingDeliveryFailure() {
        when(relay.isRunning()).thenReturn(true);

        var retrying = operations.classifyOutbox(
                new MessagingDeliveryReadinessOperations.OutboxEvidence(
                        3, 1, 0, NOW.minusSeconds(30)), NOW);
        var high = operations.classifyOutbox(
                new MessagingDeliveryReadinessOperations.OutboxEvidence(
                        100, 0, 0, NOW.minusSeconds(30)), NOW);

        assertThat(retrying)
                .extracting(
                        MessagingDeliveryReadinessOperations.NotificationOutboxStatus::effective,
                        MessagingDeliveryReadinessOperations.NotificationOutboxStatus::state,
                        MessagingDeliveryReadinessOperations.NotificationOutboxStatus::reasonCode)
                .containsExactly(true, "DEGRADED", "NOTIFICATION_OUTBOX_RETRYING");
        assertThat(high.reasonCode()).isEqualTo("NOTIFICATION_OUTBOX_BACKLOG_HIGH");
    }

    @Test
    void runningRelayAndCurrentQueueAreReady() {
        when(relay.isRunning()).thenReturn(true);

        var status = operations.classifyOutbox(
                new MessagingDeliveryReadinessOperations.OutboxEvidence(
                        2, 0, 0, NOW.minusSeconds(10)), NOW);

        assertThat(status.effective()).isTrue();
        assertThat(status.state()).isEqualTo("READY");
        assertThat(status.reasonCode()).isNull();
    }
}
