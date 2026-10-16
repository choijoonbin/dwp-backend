package com.dwp.services.time.home;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-side changed-projection queue for Time Home publication.
 *
 * <p>The production adapter must lock one unpublished owner change in the caller transaction and
 * acknowledge it with an expected-version update in that same transaction. It must never return
 * an unchanged projection merely because the scheduler polled again.
 */
public interface TimeHomePublicationCandidates {

    Optional<Candidate> lockNext();

    void markPublished(PublicationReceipt receipt);

    record Candidate(
            UUID candidateId,
            long expectedSourceVersion,
            TimeHomeCurrentAuthorityPort.CurrentAuthority authority) {

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
                throw new IllegalArgumentException("Time Home publication receipt is invalid.");
            }
        }
    }
}
