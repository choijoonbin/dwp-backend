package com.dwp.services.people.hris.contracts.workforce.v1;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Authenticated owner claims returned for a non-null issued continuation cursor. */
public record VerifiedWorkforceSnapshotCursor(
        long tenantId,
        String callerModule,
        String purposeCode,
        WorkforceSnapshotProjection projection,
        Instant asOf,
        long ownerRevision,
        String position,
        String cursorTokenDigest) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public VerifiedWorkforceSnapshotCursor {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        if (!VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER.equals(callerModule)) {
            throw new IllegalArgumentException("callerModule must be HRIS-PER");
        }
        if (!WorkforceSnapshotQuery.PURPOSE_CODE.equals(purposeCode)) {
            throw new IllegalArgumentException(
                    "purposeCode must be HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP");
        }
        if (projection != WorkforceSnapshotProjection.PERFORMANCE_V1) {
            throw new IllegalArgumentException("projection must be PERFORMANCE_V1");
        }
        Objects.requireNonNull(asOf, "asOf must not be null");
        if (ownerRevision <= 0) {
            throw new IllegalArgumentException("ownerRevision must be positive");
        }
        position = WorkforceSnapshotPage.validatedPosition(position);
        if (cursorTokenDigest == null || !SHA_256.matcher(cursorTokenDigest).matches()) {
            throw new IllegalArgumentException(
                    "cursorTokenDigest must be a canonical lowercase SHA-256 digest");
        }
    }

    void verifyFor(
            VerifiedWorkforceSnapshotRequest request,
            WorkforceSnapshotPage page) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(page, "page must not be null");
        if (tenantId != request.tenantId()
                || tenantId != page.tenantId()
                || !callerModule.equals(request.callerModule())
                || !callerModule.equals(page.callerModule())
                || !purposeCode.equals(request.purposeCode())
                || !purposeCode.equals(page.purposeCode())
                || projection != request.projection()
                || projection != page.projection()
                || !asOf.equals(request.asOf())
                || !asOf.equals(page.asOf())
                || ownerRevision != page.ownerRevision()
                || !position.equals(page.continuationPosition())
                || !cursorTokenDigest.equals(
                        WorkforceSnapshotQuery.digestToken(page.nextCursor()))) {
            throw new IllegalArgumentException(
                    "issued cursor claims do not bind the request, page, and last canonical key");
        }
    }
}
