package com.dwp.services.payroll.home;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.home.PayrollHomePorts.ResolvedPayrollHomeAuthority;

/**
 * Owner-side changed-projection queue for Payroll Home publication.
 *
 * <p>The production adapter must lock one unpublished owner change in the caller transaction and
 * acknowledge it with an expected-version update in that same transaction. Repeated timer polls
 * must not return an already published, unchanged projection.
 *
 * <p>This port is deliberately package-private because its authority payload is an internal
 * Payroll Home aggregate type. The production adapter belongs in this owner package; exposing the
 * port without also publishing that aggregate would create an unusable public API.
 */
interface PayrollHomePublicationCandidates {

    Optional<Candidate> lockNext();

    void markPublished(PublicationReceipt receipt);

    record Candidate(
            UUID candidateId,
            long expectedSourceVersion,
            ResolvedPayrollHomeAuthority authority) {

        public Candidate {
            Objects.requireNonNull(candidateId, "candidateId");
            if (expectedSourceVersion < 1) {
                throw new IllegalArgumentException("Expected source version must be positive.");
            }
            Objects.requireNonNull(authority, "authority");
        }
    }

    record PublicationReceipt(
            UUID candidateId,
            long sourceVersion,
            String payloadRef,
            String payloadDigest) {

        public PublicationReceipt {
            Objects.requireNonNull(candidateId, "candidateId");
            if (sourceVersion < 1 || payloadRef == null || payloadRef.isBlank()
                    || payloadDigest == null || !payloadDigest.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("Payroll Home publication receipt is invalid.");
            }
        }
    }
}
