package com.dwp.services.time.commandreceipt;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable provider-neutral authority assembled before receipt owner discovery. */
public record VerifiedCommandReceiptRequest(
        long tenantId,
        UUID correlationId,
        String decisionRevision,
        Instant revalidateAt,
        Map<String, Object> providerEvidence) {

    public VerifiedCommandReceiptRequest {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
        Objects.requireNonNull(correlationId, "correlationId");
        if (decisionRevision == null
                || !decisionRevision.matches("^psr-[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("decisionRevision is invalid");
        }
        Objects.requireNonNull(revalidateAt, "revalidateAt");
        providerEvidence = Map.copyOf(Objects.requireNonNull(
                providerEvidence, "providerEvidence"));
        if (providerEvidence.isEmpty()
                || providerEvidence.entrySet().stream().anyMatch(entry ->
                    entry.getKey() == null || entry.getKey().isBlank()
                            || entry.getValue() == null)) {
            throw new IllegalArgumentException("providerEvidence is invalid");
        }
    }

    public <T> Optional<T> providerAuthority(String providerId, Class<T> type) {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(type, "type");
        Object evidence = providerEvidence.get(providerId);
        return type.isInstance(evidence) ? Optional.of(type.cast(evidence)) : Optional.empty();
    }
}
