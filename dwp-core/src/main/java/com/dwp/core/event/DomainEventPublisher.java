package com.dwp.core.event;

import java.util.List;

/** Transport adapter boundary implemented by an approved broker integration. */
@FunctionalInterface
public interface DomainEventPublisher {

    DomainEventPublisher NOOP = events -> {
    };

    void publish(List<DomainEventEnvelope> events);
}
