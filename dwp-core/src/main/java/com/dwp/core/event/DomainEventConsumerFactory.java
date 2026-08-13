package com.dwp.core.event;

import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.UUID;

/** Creates named consumers with the shared delivery and recovery contract. */
public class DomainEventConsumerFactory {

    private final DomainEventInboxRepository inbox;
    private final DomainEventContractRegistry contracts;
    private final PlatformTransactionManager transactionManager;
    private final String serviceName;
    private final String serviceInstance;
    private final Duration lease;
    private final int maximumAttempts;

    public DomainEventConsumerFactory(
            DomainEventInboxRepository inbox,
            DomainEventContractRegistry contracts,
            PlatformTransactionManager transactionManager,
            String serviceName,
            String serviceInstance,
            Duration lease,
            int maximumAttempts) {
        this.inbox = inbox;
        this.contracts = contracts;
        this.transactionManager = transactionManager;
        this.serviceName = serviceName;
        this.serviceInstance = serviceInstance;
        this.lease = lease;
        this.maximumAttempts = maximumAttempts;
    }

    public IdempotentDomainEventConsumer create(String consumerKey) {
        String consumerName = serviceName + '.' + consumerKey;
        String workerId = serviceName + ':' + serviceInstance + ':' + UUID.randomUUID();
        return new IdempotentDomainEventConsumer(
                consumerName,
                workerId,
                inbox,
                contracts,
                transactionManager,
                lease,
                maximumAttempts);
    }
}
