package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable owner command contracts shared by orchestration and persistence. */
public final class Hrm013OwnerContracts {
    private Hrm013OwnerContracts() {
    }

    public record CaseCreation(
            long tenantId, UUID casePublicId, long workerId,
            long workRelationshipId, String separationType, String reasonCode,
            LocalDate effectiveDate, LocalDate lastWorkingDate,
            String inputSnapshotHash, UUID taskTemplatePublicId,
            long taskTemplateRevision, String taskTemplateHash,
            UUID makerPrincipalPublicId, UUID correlationId,
            long actorId, Instant createdAt) {
        public CaseCreation {
            if (tenantId < 1 || workerId < 1 || workRelationshipId < 1
                    || taskTemplateRevision < 1 || actorId < 1) {
                throw new IllegalArgumentException("positive case values required");
            }
            Objects.requireNonNull(casePublicId);
            Objects.requireNonNull(effectiveDate);
            Objects.requireNonNull(lastWorkingDate);
            Objects.requireNonNull(taskTemplatePublicId);
            Objects.requireNonNull(makerPrincipalPublicId);
            Objects.requireNonNull(correlationId);
            Objects.requireNonNull(createdAt);
            if (separationType == null || separationType.isBlank()
                    || reasonCode == null || reasonCode.isBlank()
                    || inputSnapshotHash == null
                    || !inputSnapshotHash.matches("[0-9a-f]{64}")
                    || taskTemplateHash == null
                    || !taskTemplateHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid case values");
            }
        }
    }

    public record PlannedDispatch(
            Hrm013TaskDispatchV1.Profile profile,
            Hrm013TaskDispatchV1.DispatchBody body,
            boolean mandatory, boolean irreversible) {
        public PlannedDispatch {
            Objects.requireNonNull(profile);
            Objects.requireNonNull(body);
        }
    }
}
