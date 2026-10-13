package com.dwp.services.people.hris.contracts.workforce.v1;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Closed, salary-free HRM projection line used only for compensation population selection. */
public record CompensationPopulationLine(
        UUID workerPublicId,
        UUID assignmentPublicId,
        UUID organizationPublicId,
        UUID gradePublicId,
        UUID jobProfilePublicId,
        long assignmentOwnerRevision,
        List<String> eligibleComponentCodes) {

    public static final int MAX_ELIGIBLE_COMPONENT_CODES = 1_000;

    public CompensationPopulationLine {
        Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
        Objects.requireNonNull(assignmentPublicId, "assignmentPublicId must not be null");
        Objects.requireNonNull(organizationPublicId, "organizationPublicId must not be null");
        if (assignmentOwnerRevision <= 0) {
            throw new IllegalArgumentException("assignmentOwnerRevision must be positive");
        }
        eligibleComponentCodes = List.copyOf(Objects.requireNonNull(
                eligibleComponentCodes, "eligibleComponentCodes must not be null"));
        if (eligibleComponentCodes.isEmpty()
                || eligibleComponentCodes.size() > MAX_ELIGIBLE_COMPONENT_CODES) {
            throw new IllegalArgumentException(
                    "eligibleComponentCodes must contain between 1 and "
                            + MAX_ELIGIBLE_COMPONENT_CODES + " values");
        }
        if (eligibleComponentCodes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "eligibleComponentCodes must not contain null values");
        }
    }
}
