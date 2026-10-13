package com.dwp.services.people.hris.contracts.workforce.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Untrusted compensation-population query; all tenant and caller authority is owner-resolved. */
public record CompensationPopulationSnapshotQuery(
        UUID snapshotPublicId,
        long ownerRevision,
        Instant asOf,
        WorkforceSnapshotProjection projection,
        String purposeCode,
        int limit,
        String cursorToken) {

    public static final String PURPOSE_CODE = "HRIS_PER_COMPENSATION_POPULATION";

    public CompensationPopulationSnapshotQuery {
        Objects.requireNonNull(snapshotPublicId, "snapshotPublicId must not be null");
        if (ownerRevision <= 0) {
            throw new IllegalArgumentException("ownerRevision must be positive");
        }
        Objects.requireNonNull(asOf, "asOf must not be null");
        if (projection != WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1) {
            throw new IllegalArgumentException(
                    "projection must be COMPENSATION_POPULATION_V1");
        }
        if (!PURPOSE_CODE.equals(purposeCode)) {
            throw new IllegalArgumentException(
                    "purposeCode must be HRIS_PER_COMPENSATION_POPULATION");
        }
        if (limit < 1 || limit > WorkforceSnapshotQuery.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + WorkforceSnapshotQuery.MAX_LIMIT);
        }
        if (cursorToken != null) {
            cursorToken = WorkforceSnapshotQuery.validatedToken(cursorToken, "cursorToken");
        }
    }

    public String cursorTokenDigest() {
        return WorkforceSnapshotQuery.digestToken(cursorToken);
    }
}
