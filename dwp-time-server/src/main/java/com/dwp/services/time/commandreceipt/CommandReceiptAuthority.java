package com.dwp.services.time.commandreceipt;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Common envelope around a provider-native, sealed receipt authority projection. */
public record CommandReceiptAuthority(
        long tenantId,
        UUID correlationId,
        String decisionRevision,
        Instant revalidateAt,
        Object providerEvidence) {

    public CommandReceiptAuthority {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
        Objects.requireNonNull(correlationId, "correlationId");
        if (decisionRevision == null
                || !decisionRevision.matches("^psr-[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("decisionRevision is invalid");
        }
        Objects.requireNonNull(revalidateAt, "revalidateAt");
        Objects.requireNonNull(providerEvidence, "providerEvidence");
    }
}
