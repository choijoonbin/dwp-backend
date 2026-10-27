package com.dwp.platform.contracts.hris.separation.v1;

import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofViews.*;
import static com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofValues.*;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Canonical normalization and validation for contract values. */
final class Hrm013WorkloadProofModelValidation {

    private Hrm013WorkloadProofModelValidation() {
    }

    static void headerFields(
            Hrm013WorkloadProofValues values,
            String name,
            String value) {
        if (name == null || value == null || name.isBlank()
                || !HEADER_NAME.matcher(name).matches()
                || values.hasLineBreak(name) || values.hasLineBreak(value)) {
            throw new IllegalArgumentException("header must be a valid single-line field");
        }
    }

    static void verifiedSigningKeyFields(
            Hrm013WorkloadProofValues values,
            PublicKey publicKey,
            String keyId,
            String workloadId) {
        Objects.requireNonNull(publicKey, "publicKey");
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("keyId is not canonical");
        }
        values.requireServiceId(workloadId, "workloadId");
    }

    static void drainClaimsFields(
            Hrm013WorkloadProofValues values,
            UUID drainRootWorkItemId,
            Instant drainRootCreatedAt,
            String drainRootPayloadSha256,
            Enum<?> drainContinuationKind,
            UUID drainContinuationEventId,
            long drainContinuationRevision,
            String drainContinuationPayloadSha256) {
        Objects.requireNonNull(drainRootWorkItemId, "drainRootWorkItemId");
        Objects.requireNonNull(drainRootCreatedAt, "drainRootCreatedAt");
        Objects.requireNonNull(drainContinuationKind, "drainContinuationKind");
        Objects.requireNonNull(drainContinuationEventId, "drainContinuationEventId");
        values.requireDigest(drainRootPayloadSha256, "drainRootPayloadSha256");
        values.requireDigest(drainContinuationPayloadSha256,
                "drainContinuationPayloadSha256");
        if (drainContinuationRevision < 0) {
            throw new IllegalArgumentException(
                    "drainContinuationRevision must be non-negative");
        }
    }

    static void resolverAuthorityFields(
            Hrm013WorkloadProofValues values,
            Enum<?> resolutionMode,
            long tenantGrantRevision,
            long authorizationRevision) {
        Objects.requireNonNull(resolutionMode, "resolutionMode");
        boolean probe = "PROBE_MISSING".equals(resolutionMode.name());
        if ((probe && (tenantGrantRevision != 0 || authorizationRevision != 0))
                || (!probe && (tenantGrantRevision < 1 || authorizationRevision < 1))) {
            throw new IllegalArgumentException("resolver revision pair does not match mode");
        }
    }
}
