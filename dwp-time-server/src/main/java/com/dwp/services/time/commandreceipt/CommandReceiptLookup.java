package com.dwp.services.time.commandreceipt;

import java.util.Objects;
import java.util.UUID;

/** Tenant-scoped input shared by every command-receipt provider. */
public record CommandReceiptLookup(
        long tenantId,
        UUID receiptId,
        UUID correlationId,
        Object authorityEvidence) {

    public CommandReceiptLookup {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(authorityEvidence, "authorityEvidence");
    }

    /** Returns the sealed route authority in the provider-specific representation. */
    public <T> T requireAuthority(Class<T> authorityType) {
        Objects.requireNonNull(authorityType, "authorityType");
        if (!authorityType.isInstance(authorityEvidence)) {
            throw new IllegalStateException(
                    "The command-receipt authority evidence is incompatible with the provider.");
        }
        return authorityType.cast(authorityEvidence);
    }
}
