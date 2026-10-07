package com.dwp.services.people.hris.contracts.workforce.v1;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** An owner-revisioned, content-addressed page of workforce snapshots. */
public record WorkforceSnapshotPage(
        long tenantId,
        String callerModule,
        String purposeCode,
        WorkforceSnapshotProjection projection,
        Instant asOf,
        long ownerRevision,
        String payloadDigest,
        List<WorkforceSnapshotV1> snapshots,
        String nextCursor) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Comparator<WorkforceSnapshotV1> CANONICAL_ORDER = Comparator
            .comparing((WorkforceSnapshotV1 value) -> value.assignmentPublicId().toString())
            .thenComparing(value -> value.workerPublicId().toString());

    public static final String ORDERING_VERSION =
            "assignmentPublicId-asc-workerPublicId-asc.v1";

    public WorkforceSnapshotPage {
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
        if (payloadDigest == null || !SHA_256.matcher(payloadDigest).matches()) {
            throw new IllegalArgumentException("payloadDigest must be a canonical lowercase SHA-256 digest");
        }
        snapshots = List.copyOf(Objects.requireNonNull(snapshots, "snapshots must not be null"));
        verifyCanonicalSnapshots(snapshots);
        if (nextCursor != null) {
            nextCursor = WorkforceSnapshotQuery.validatedToken(nextCursor, "nextCursor");
            if (snapshots.isEmpty()) {
                throw new IllegalArgumentException(
                        "nextCursor requires a last emitted canonical snapshot key");
            }
        }
        String calculated = calculatePayloadDigest(
                tenantId, callerModule, purposeCode, projection, asOf,
                ownerRevision, snapshots, nextCursor);
        if (!calculated.equals(payloadDigest)) {
            throw new IllegalArgumentException("payloadDigest does not verify the canonical page");
        }
    }

    public static WorkforceSnapshotPage verified(
            long tenantId,
            String callerModule,
            String purposeCode,
            WorkforceSnapshotProjection projection,
            Instant asOf,
            long ownerRevision,
            List<WorkforceSnapshotV1> snapshots,
            String nextCursor) {
        List<WorkforceSnapshotV1> immutable = List.copyOf(snapshots);
        String digest = calculatePayloadDigest(
                tenantId, callerModule, purposeCode, projection, asOf,
                ownerRevision, immutable, nextCursor);
        return new WorkforceSnapshotPage(
                tenantId, callerModule, purposeCode, projection, asOf,
                ownerRevision, digest, immutable, nextCursor);
    }

    void verifyFor(VerifiedWorkforceSnapshotRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (tenantId != request.tenantId()
                || !callerModule.equals(request.callerModule())
                || !purposeCode.equals(request.purposeCode())
                || projection != request.projection()
                || !asOf.equals(request.asOf())
                || (request.cursorOwnerRevision() != null
                && request.cursorOwnerRevision() != ownerRevision)) {
            throw new IllegalArgumentException(
                    "page does not match tenant, caller, purpose, projection, asOf, or owner revision");
        }
        if (snapshots.size() > request.limit()) {
            throw new IllegalArgumentException("page snapshot count exceeds the requested limit");
        }
        if (request.verifiedCursorPosition() != null && !snapshots.isEmpty()
                && canonicalPosition(snapshots.getFirst())
                .compareTo(request.verifiedCursorPosition()) <= 0) {
            throw new IllegalArgumentException(
                    "page does not continue after the authenticated cursor position");
        }
    }

    /** Canonical last-emitted key that an owner cursor codec must authenticate. */
    public String continuationPosition() {
        return snapshots.isEmpty() ? null : canonicalPosition(snapshots.getLast());
    }

    public static String calculatePayloadDigest(
            long tenantId,
            String callerModule,
            String purposeCode,
            WorkforceSnapshotProjection projection,
            Instant asOf,
            long ownerRevision,
            List<WorkforceSnapshotV1> snapshots,
            String nextCursor) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            put(digest, WorkforceSnapshotContract.VERSION);
            put(digest, ORDERING_VERSION);
            put(digest, tenantId);
            put(digest, callerModule);
            put(digest, purposeCode);
            put(digest, projection.name());
            put(digest, asOf.toString());
            put(digest, ownerRevision);
            put(digest, snapshots.size());
            for (WorkforceSnapshotV1 snapshot : snapshots) {
                put(digest, snapshot.workerPublicId().toString());
                put(digest, snapshot.assignmentPublicId().toString());
                put(digest, snapshot.status());
                put(digest, snapshot.organizationPublicId().toString());
                put(digest, nullable(snapshot.managerAssignmentPublicId()));
                put(digest, nullable(snapshot.jobProfilePublicId()));
                put(digest, nullable(snapshot.gradePublicId()));
            }
            if (nextCursor == null) {
                put(digest, (String) null);
            } else {
                put(digest, nextCursor);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String validatedPosition(String value) {
        return WorkforceSnapshotContract.validatedCursorPosition(value);
    }

    static String canonicalPosition(WorkforceSnapshotV1 snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return snapshot.assignmentPublicId() + ":" + snapshot.workerPublicId();
    }

    private static void verifyCanonicalSnapshots(List<WorkforceSnapshotV1> snapshots) {
        Set<java.util.UUID> assignments = new HashSet<>();
        Set<String> workerAssignments = new HashSet<>();
        WorkforceSnapshotV1 previous = null;
        for (WorkforceSnapshotV1 snapshot : snapshots) {
            Objects.requireNonNull(snapshot, "snapshots must not contain null");
            String position = canonicalPosition(snapshot);
            if (!workerAssignments.add(position)) {
                throw new IllegalArgumentException(
                        "worker and assignment key must be unique within a page");
            }
            if (!assignments.add(snapshot.assignmentPublicId())) {
                throw new IllegalArgumentException(
                        "assignmentPublicId must be unique within a page");
            }
            if (previous != null && CANONICAL_ORDER.compare(previous, snapshot) >= 0) {
                throw new IllegalArgumentException(
                        "snapshots must follow " + ORDERING_VERSION);
            }
            previous = snapshot;
        }
    }

    private static String nullable(Object value) {
        return value == null ? null : value.toString();
    }

    private static void put(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void put(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
