package com.dwp.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotentDomainEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DomainEventInboxRepository inbox = mock(DomainEventInboxRepository.class);
    private final DomainEventContractRegistry contracts = mock(DomainEventContractRegistry.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final IdempotentDomainEventConsumer consumer = new IdempotentDomainEventConsumer(
            "dwp-platform-server.worker-projection",
            "worker-1",
            inbox,
            contracts,
            transactions,
            Duration.ofSeconds(30),
            3);

    @BeforeEach
    void transactions() {
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
    }

    @Test
    void duplicateAndOutOfOrderEventsNeverReachHandler() {
        DomainEventEnvelope duplicate = event(1);
        DomainEventEnvelope outOfOrder = event(3);
        AtomicInteger handled = new AtomicInteger();
        when(inbox.begin(anyString(), any(), anyString(), any()))
                .thenReturn(new DomainEventInboxRepository.BeginResult(
                        DomainEventInboxRepository.BeginState.DUPLICATE, null, 1))
                .thenReturn(new DomainEventInboxRepository.BeginResult(
                        DomainEventInboxRepository.BeginState.OUT_OF_ORDER, null, 0));

        assertThat(consumer.consume(duplicate, ignored -> handled.incrementAndGet()).state())
                .isEqualTo(IdempotentDomainEventConsumer.DeliveryState.DUPLICATE);
        assertThat(consumer.consume(outOfOrder, ignored -> handled.incrementAndGet()).state())
                .isEqualTo(IdempotentDomainEventConsumer.DeliveryState.OUT_OF_ORDER);
        assertThat(handled).hasValue(0);
        verify(inbox, never()).complete(anyString(), any(), anyString());
    }

    @Test
    void handlerFailureRollsBackWorkAndSchedulesDurableRetry() {
        DomainEventEnvelope event = event(1);
        when(inbox.begin(anyString(), any(), anyString(), any()))
                .thenReturn(new DomainEventInboxRepository.BeginResult(
                        DomainEventInboxRepository.BeginState.ACQUIRED, "lease-1", 1));
        when(inbox.fail(anyString(), any(), anyString(), any(Integer.class), any(Integer.class), any()))
                .thenReturn(DomainEventInboxRepository.FailureState.RETRYABLE);

        IdempotentDomainEventConsumer.DeliveryResult result = consumer.consume(
                event, ignored -> {
                    throw new IllegalStateException("projection unavailable");
                });

        assertThat(result.state())
                .isEqualTo(IdempotentDomainEventConsumer.DeliveryState.RETRY_SCHEDULED);
        assertThat(result.error()).contains("projection unavailable");
        verify(inbox).fail(
                "dwp-platform-server.worker-projection", event.id(),
                "lease-1", 1, 3, "projection unavailable");
        verify(inbox, never()).complete(anyString(), any(), anyString());
    }

    @Test
    void successfulHandlerAndOffsetAdvanceShareOneTransaction() {
        DomainEventEnvelope event = event(1);
        AtomicInteger handled = new AtomicInteger();
        when(inbox.begin(anyString(), any(), anyString(), any()))
                .thenReturn(new DomainEventInboxRepository.BeginResult(
                        DomainEventInboxRepository.BeginState.ACQUIRED, "lease-2", 1));

        IdempotentDomainEventConsumer.DeliveryResult result =
                consumer.consume(event, ignored -> handled.incrementAndGet());

        assertThat(result.state())
                .isEqualTo(IdempotentDomainEventConsumer.DeliveryState.PROCESSED);
        assertThat(handled).hasValue(1);
        verify(inbox).complete(
                "dwp-platform-server.worker-projection", event, "lease-2");
    }

    private DomainEventEnvelope event(long sequence) {
        return new DomainEventEnvelope(
                "1.0", UUID.randomUUID(), "dwp-people-server",
                "dwp.people.worker-changed", 1, Instant.now(), "worker/7",
                1L, "WORKER", "7", sequence, "corr-7", null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                objectMapper.createObjectNode().put("workerId", 7), Map.of());
    }
}
