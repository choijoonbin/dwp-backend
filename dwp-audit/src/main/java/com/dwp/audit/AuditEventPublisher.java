package com.dwp.audit;

import java.util.List;

@FunctionalInterface
public interface AuditEventPublisher {

    AuditEventPublisher NOOP = events -> DeliveryResult.ACCEPTED;

    DeliveryResult publish(List<AuditEvent> events);

    enum DeliveryResult {
        ACCEPTED,
        REJECTED,
        RETRYABLE_FAILURE
    }
}
