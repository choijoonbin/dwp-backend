package com.dwp.services.people.hris.contracts.workforce.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owner-verified authority and authenticated cursor state for compensation population pages. */
public record VerifiedCompensationPopulationRequest(
        long tenantId,
        long tenantGrantRevision,
        long policyRevision,
        String callerModule,
        UUID correlationId,
        UUID snapshotPublicId,
        long ownerRevision,
        Instant asOf,
        WorkforceSnapshotProjection projection,
        String purposeCode,
        int limit,
        String cursorFullPopulationDigest,
        String verifiedCursorPosition,
        String cursorTokenDigest) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public VerifiedCompensationPopulationRequest {
        if (tenantId <= 0 || tenantGrantRevision <= 0 || policyRevision <= 0) {
            throw new IllegalArgumentException(
                    "tenantId, tenantGrantRevision, and policyRevision must be positive");
        }
        if (!VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER.equals(callerModule)) {
            throw new IllegalArgumentException("callerModule must be HRIS-PER");
        }
        Objects.requireNonNull(correlationId, "correlationId must not be null");
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
        if (limit < 1 || limit > WorkforceSnapshotQuery.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside the contract range");
        }
        boolean firstPage = cursorFullPopulationDigest == null
                && verifiedCursorPosition == null && cursorTokenDigest == null;
        boolean nextPage = isDigest(cursorFullPopulationDigest)
                && verifiedCursorPosition != null
                && isDigest(cursorTokenDigest);
        if (!firstPage && !nextPage) {
            throw new IllegalArgumentException(
                    "verified compensation cursor digest, position, and token digest "
                            + "must be all absent or valid");
        }
        if (nextPage) {
            verifiedCursorPosition = WorkforceSnapshotContract.validatedCursorPosition(
                    verifiedCursorPosition);
        }
    }

    void verifyDerivedFrom(CompensationPopulationSnapshotQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (!snapshotPublicId.equals(query.snapshotPublicId())
                || ownerRevision != query.ownerRevision()
                || !asOf.equals(query.asOf())
                || projection != query.projection()
                || !purposeCode.equals(query.purposeCode())
                || limit != query.limit()
                || !Objects.equals(cursorTokenDigest, query.cursorTokenDigest())) {
            throw new IllegalArgumentException(
                    "verified compensation request does not derive from the supplied query");
        }
    }

    private static boolean isDigest(String value) {
        return value != null && SHA_256.matcher(value).matches();
    }
}
