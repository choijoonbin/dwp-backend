package com.dwp.services.people.hris.contracts.workforce.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authenticated owner claims for a compensation-population continuation cursor. */
public record VerifiedCompensationPopulationCursor(
        long tenantId,
        String callerModule,
        UUID snapshotPublicId,
        long ownerRevision,
        Instant asOf,
        WorkforceSnapshotProjection projection,
        String purposeCode,
        String fullPopulationDigest,
        String position,
        String cursorTokenDigest) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public VerifiedCompensationPopulationCursor {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        if (!VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER.equals(callerModule)) {
            throw new IllegalArgumentException("callerModule must be HRIS-PER");
        }
        Objects.requireNonNull(snapshotPublicId, "snapshotPublicId must not be null");
        if (ownerRevision <= 0) {
            throw new IllegalArgumentException("ownerRevision must be positive");
        }
        Objects.requireNonNull(asOf, "asOf must not be null");
        if (projection != WorkforceSnapshotProjection.COMPENSATION_POPULATION_V1) {
            throw new IllegalArgumentException(
                    "projection must be COMPENSATION_POPULATION_V1");
        }
        if (!CompensationPopulationSnapshotQuery.PURPOSE_CODE.equals(purposeCode)) {
            throw new IllegalArgumentException(
                    "purposeCode must be HRIS_PER_COMPENSATION_POPULATION");
        }
        if (!isDigest(fullPopulationDigest)) {
            throw new IllegalArgumentException(
                    "fullPopulationDigest must be a canonical lowercase SHA-256 digest");
        }
        position = WorkforceSnapshotContract.validatedCursorPosition(position);
        if (!isDigest(cursorTokenDigest)) {
            throw new IllegalArgumentException(
                    "cursorTokenDigest must be a canonical lowercase SHA-256 digest");
        }
    }

    void verifyFor(
            VerifiedCompensationPopulationRequest request,
            CompensationPopulationSnapshotPage page) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(page, "page must not be null");
        if (tenantId != request.tenantId()
                || !callerModule.equals(request.callerModule())
                || !snapshotPublicId.equals(request.snapshotPublicId())
                || !snapshotPublicId.equals(page.snapshotPublicId())
                || ownerRevision != request.ownerRevision()
                || ownerRevision != page.ownerRevision()
                || !asOf.equals(request.asOf())
                || !asOf.equals(page.asOf())
                || projection != request.projection()
                || projection != page.projection()
                || !purposeCode.equals(request.purposeCode())
                || !purposeCode.equals(page.purposeCode())
                || !fullPopulationDigest.equals(page.fullPopulationDigest())
                || !position.equals(page.continuationPosition())
                || !cursorTokenDigest.equals(
                        WorkforceSnapshotQuery.digestToken(page.nextCursor()))) {
            throw new IllegalArgumentException(
                    "issued compensation cursor claims do not bind the request, page, "
                            + "population digest, and last canonical key");
        }
    }

    private static boolean isDigest(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }
}
