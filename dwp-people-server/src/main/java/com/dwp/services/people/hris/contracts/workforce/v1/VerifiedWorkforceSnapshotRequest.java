package com.dwp.services.people.hris.contracts.workforce.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owner-verified authority and decoded cursor state accepted by the provider only. */
public record VerifiedWorkforceSnapshotRequest(
        long tenantId,
        long tenantGrantRevision,
        long policyRevision,
        String callerModule,
        UUID correlationId,
        String purposeCode,
        WorkforceSnapshotProjection projection,
        Instant asOf,
        int limit,
        Long cursorOwnerRevision,
        String verifiedCursorPosition,
        String cursorTokenDigest) {

    public static final String PERFORMANCE_CALLER = "HRIS-PER";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public VerifiedWorkforceSnapshotRequest {
        if (tenantId <= 0 || tenantGrantRevision <= 0 || policyRevision <= 0) {
            throw new IllegalArgumentException(
                    "tenantId, tenantGrantRevision, and policyRevision must be positive");
        }
        if (!PERFORMANCE_CALLER.equals(callerModule)) {
            throw new IllegalArgumentException("callerModule must be HRIS-PER");
        }
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        if (!WorkforceSnapshotQuery.PURPOSE_CODE.equals(purposeCode)) {
            throw new IllegalArgumentException(
                    "purposeCode must be HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP");
        }
        if (projection != WorkforceSnapshotProjection.PERFORMANCE_V1) {
            throw new IllegalArgumentException("projection must be PERFORMANCE_V1");
        }
        Objects.requireNonNull(asOf, "asOf must not be null");
        if (limit < 1 || limit > WorkforceSnapshotQuery.MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside the contract range");
        }
        boolean firstPage = cursorOwnerRevision == null
                && verifiedCursorPosition == null && cursorTokenDigest == null;
        boolean nextPage = cursorOwnerRevision != null && cursorOwnerRevision > 0
                && verifiedCursorPosition != null && !verifiedCursorPosition.isBlank()
                && verifiedCursorPosition.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= WorkforceSnapshotQuery.MAX_CURSOR_LENGTH
                && cursorTokenDigest != null && SHA_256.matcher(cursorTokenDigest).matches();
        if (!firstPage && !nextPage) {
            throw new IllegalArgumentException(
                    "verified cursor revision, position, and digest must be all absent or valid");
        }
        if (nextPage) {
            verifiedCursorPosition = WorkforceSnapshotContract.validatedCursorPosition(
                    verifiedCursorPosition);
        }
    }

    void verifyDerivedFrom(WorkforceSnapshotQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        if (!purposeCode.equals(query.purposeCode())
                || projection != query.projection()
                || !asOf.equals(query.asOf())
                || limit != query.limit()
                || !Objects.equals(cursorTokenDigest, query.cursorTokenDigest())) {
            throw new IllegalArgumentException(
                    "verified request does not derive from the supplied query");
        }
    }
}
