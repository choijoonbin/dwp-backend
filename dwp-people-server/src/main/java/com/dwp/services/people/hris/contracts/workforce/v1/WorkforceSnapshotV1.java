package com.dwp.services.people.hris.contracts.workforce.v1;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The complete and closed Performance workforce projection for contract v1. */
public record WorkforceSnapshotV1(
        UUID workerPublicId,
        UUID assignmentPublicId,
        String status,
        UUID organizationPublicId,
        UUID managerAssignmentPublicId,
        UUID jobProfilePublicId,
        UUID gradePublicId) {

    public static final List<String> STATUS_CODES =
            List.of("PENDING", "ACTIVE", "INACTIVE", "TERMINATED");

    public WorkforceSnapshotV1 {
        Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
        Objects.requireNonNull(assignmentPublicId, "assignmentPublicId must not be null");
        if (!STATUS_CODES.contains(status)) {
            throw new IllegalArgumentException(
                    "status must be one of the canonical workforce status codes "
                            + STATUS_CODES);
        }
        Objects.requireNonNull(organizationPublicId, "organizationPublicId must not be null");
    }
}
