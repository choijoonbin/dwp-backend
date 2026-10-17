package com.dwp.services.time.commandreceipt;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Privacy-minimized evidence for fail-closed provider-registry failures. */
public record CommandReceiptAuditEvent(
        long tenantId,
        UUID receiptId,
        UUID correlationId,
        Failure failure,
        List<String> providerIds) {

    public enum Failure {
        MULTIPLE_OWNERS,
        PROVIDER_LOOKUP_ERROR,
        PROVIDER_LOOKUP_TIMEOUT,
        OWNER_READ_ERROR,
        OWNER_READ_TIMEOUT,
        REGISTRY_INTERRUPTED
    }

    public CommandReceiptAuditEvent {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(failure, "failure");
        providerIds = List.copyOf(Objects.requireNonNull(providerIds, "providerIds"));
        if (providerIds.isEmpty()) {
            throw new IllegalArgumentException("providerIds must not be empty");
        }
    }
}
