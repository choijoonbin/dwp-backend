package com.dwp.services.auth.hrisservicegrant;

import java.util.Objects;

/** Exact carried rotation-readiness bytes shared across service and persistence ports. */
public record ReadinessItem(
        byte[] receiptBytes, byte[] proofClaimBytes, String readinessProof) {
    public ReadinessItem {
        receiptBytes = receiptBytes == null ? null : receiptBytes.clone();
        proofClaimBytes = proofClaimBytes == null ? null : proofClaimBytes.clone();
        Objects.requireNonNull(receiptBytes, "receiptBytes");
        Objects.requireNonNull(proofClaimBytes, "proofClaimBytes");
        Objects.requireNonNull(readinessProof, "readinessProof");
    }

    @Override
    public byte[] receiptBytes() {
        return receiptBytes.clone();
    }

    @Override
    public byte[] proofClaimBytes() {
        return proofClaimBytes.clone();
    }
}
