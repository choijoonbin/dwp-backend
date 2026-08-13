package com.dwp.core.event;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Objects;

/** Executes handler work atomically and records retries in separate durable transactions. */
public class IdempotentDomainEventConsumer {

    private final String consumerName;
    private final String workerId;
    private final DomainEventInboxRepository inbox;
    private final DomainEventContractRegistry contracts;
    private final TransactionTemplate transactions;
    private final Duration lease;
    private final int maximumAttempts;

    public IdempotentDomainEventConsumer(
            String consumerName,
            String workerId,
            DomainEventInboxRepository inbox,
            DomainEventContractRegistry contracts,
            PlatformTransactionManager transactionManager,
            Duration lease,
            int maximumAttempts) {
        this.consumerName = required(consumerName, "consumerName");
        this.workerId = required(workerId, "workerId");
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.contracts = Objects.requireNonNull(contracts, "contracts");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.lease = lease.isNegative() || lease.isZero() ? Duration.ofSeconds(30) : lease;
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be positive.");
        }
        this.maximumAttempts = maximumAttempts;
    }

    public DeliveryResult consume(
            DomainEventEnvelope event,
            DomainEventHandler handler) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(handler, "handler");
        try {
            contracts.requireCompatible(event);
        } catch (RuntimeException incompatible) {
            transactions.executeWithoutResult(status ->
                    inbox.quarantine(consumerName, event, incompatible.getMessage()));
            return new DeliveryResult(
                    DeliveryState.QUARANTINED, 0, incompatible.getMessage());
        }

        DomainEventInboxRepository.BeginResult begin = transactions.execute(status ->
                inbox.begin(consumerName, event, workerId, lease));
        if (begin == null) {
            throw new IllegalStateException("Domain-event begin transaction returned no outcome.");
        }
        if (begin.state() != DomainEventInboxRepository.BeginState.ACQUIRED) {
            return new DeliveryResult(map(begin.state()), begin.attempt(), null);
        }

        try {
            transactions.executeWithoutResult(status -> {
                handler.handle(event);
                inbox.complete(consumerName, event, begin.lockToken());
            });
            return new DeliveryResult(DeliveryState.PROCESSED, begin.attempt(), null);
        } catch (RuntimeException failure) {
            DomainEventInboxRepository.FailureState failed = transactions.execute(status ->
                    inbox.fail(
                            consumerName,
                            event.id(),
                            begin.lockToken(),
                            begin.attempt(),
                            maximumAttempts,
                            failure.getMessage()));
            DeliveryState state = failed == DomainEventInboxRepository.FailureState.DEAD
                    ? DeliveryState.DEAD
                    : DeliveryState.RETRY_SCHEDULED;
            return new DeliveryResult(state, begin.attempt(), failure.getMessage());
        }
    }

    private static DeliveryState map(DomainEventInboxRepository.BeginState state) {
        return switch (state) {
            case DUPLICATE -> DeliveryState.DUPLICATE;
            case OUT_OF_ORDER -> DeliveryState.OUT_OF_ORDER;
            case DEFERRED -> DeliveryState.DEFERRED;
            case BUSY -> DeliveryState.BUSY;
            case DEAD -> DeliveryState.DEAD;
            case PAYLOAD_CONFLICT -> DeliveryState.QUARANTINED;
            case ACQUIRED -> throw new IllegalArgumentException("ACQUIRED must be processed.");
        };
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value;
    }

    @FunctionalInterface
    public interface DomainEventHandler {
        void handle(DomainEventEnvelope event);
    }

    public enum DeliveryState {
        PROCESSED,
        DUPLICATE,
        OUT_OF_ORDER,
        DEFERRED,
        BUSY,
        RETRY_SCHEDULED,
        DEAD,
        QUARANTINED
    }

    public record DeliveryResult(DeliveryState state, int attempt, String error) {
    }
}
