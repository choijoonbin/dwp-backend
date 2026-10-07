package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Untrusted People-owner data; no synthetic or independent principal-worker link ledger. */
public record NativeSelfContextSetV1(long tenantId, UUID personPublicId, long personVersion,
                                     PersonState personState, Instant asOf, boolean complete,
                                     List<EmploymentContext> contexts) {
    public NativeSelfContextSetV1 {
        contexts = contexts == null ? null : List.copyOf(contexts);
    }

    public enum PersonState { ACTIVE, INACTIVE }

    public record EmploymentContext(Worker worker, WorkRelationship relationship, Assignment assignment) {
        public SelfContextSelectorV1 selector() {
            return new SelfContextSelectorV1(worker.publicId(), relationship.publicId(), assignment.publicId());
        }
    }

    public record Worker(UUID publicId, UUID personPublicId, long version, String status) {
    }

    public record WorkRelationship(UUID publicId, UUID workerPublicId, UUID legalEmployerPublicId,
                                   long version, LocalDate startDate, LocalDate endDate) {
    }

    public record Assignment(UUID publicId, UUID workRelationshipPublicId, long version, String status,
                             LocalDate effectiveStartDate, LocalDate effectiveEndDate, ZoneId workZone) {
    }
}
