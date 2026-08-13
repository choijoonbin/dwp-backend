package com.dwp.core.event;

import java.util.List;

/** Transport adapter boundary. A broker implementation is selected only after D-07 approval. */
@FunctionalInterface
public interface DomainEventPublisher {

    DomainEventPublisher NOOP = events -> {
    };

    void publish(List<DomainEventEnvelope> events);
}
