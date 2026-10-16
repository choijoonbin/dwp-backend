package com.dwp.services.people.hris.performance.home;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-side changed-projection queue for Performance Home publication.
 *
 * <p>The production adapter must lock and return one unpublished owner change from
 * {@link #lockNext()} in the caller transaction. {@link #markPublished(PublicationReceipt)} must
 * update that same locked row using the expected source version. This makes the outbox write and
 * candidate acknowledgement one transaction and prevents timer polling from republishing an
 * unchanged projection.
 */
public interface PerformanceHomePublicationCandidates {

    Optional<Candidate> lockNext();

    void markPublished(PublicationReceipt receipt);

    record Candidate(
            UUID candidateId,
            long expectedSourceVersion,
            UUID correlationId,
            PerformanceHomeAuthorityPort.AuthorityDecision authority) {

        public Candidate {
            Objects.requireNonNull(candidateId, "candidateId");
            if (expectedSourceVersion < 1) {
                throw new IllegalArgumentException("Expected source version must be positive.");
            }
            Objects.requireNonNull(correlationId, "correlationId");
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
                throw new IllegalArgumentException("Performance Home publication receipt is invalid.");
            }
        }
    }
}
