package com.dwp.services.people.hris.contracts.workforce.v1;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owner-revisioned, content-addressed page of the closed compensation population projection. */
public record CompensationPopulationSnapshotPage(
        UUID snapshotPublicId,
        long ownerRevision,
        Instant asOf,
        WorkforceSnapshotProjection projection,
        String purposeCode,
        String fullPopulationDigest,
        String pageDigest,
        List<CompensationPopulationLine> lines,
        String nextCursor) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Comparator<CompensationPopulationLine> CANONICAL_ORDER = Comparator
            .comparing((CompensationPopulationLine value) ->
                    value.assignmentPublicId().toString())
            .thenComparing(value -> value.workerPublicId().toString());

    public static final String ORDERING_VERSION =
            "assignmentPublicId-asc-workerPublicId-asc.v1";
    public static final String CONTENT_SCHEMA_ID = "CompensationPopulationContent.v1";

    public CompensationPopulationSnapshotPage {
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
        requireDigest(fullPopulationDigest, "fullPopulationDigest");
        requireDigest(pageDigest, "pageDigest");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines must not be null"));
        if (lines.size() > WorkforceSnapshotQuery.MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "lines must contain at most " + WorkforceSnapshotQuery.MAX_LIMIT + " values");
        }
        verifyCanonicalLines(lines);
        if (nextCursor != null) {
            nextCursor = WorkforceSnapshotQuery.validatedToken(nextCursor, "nextCursor");
            if (lines.isEmpty()) {
                throw new IllegalArgumentException(
                        "nextCursor requires a last emitted canonical population key");
            }
        }
        String calculated = calculatePageDigest(
                snapshotPublicId, ownerRevision, asOf, projection, purposeCode,
                fullPopulationDigest, lines, nextCursor);
        if (!calculated.equals(pageDigest)) {
            throw new IllegalArgumentException(
                    "pageDigest does not verify the canonical compensation population page");
        }
    }

    public static CompensationPopulationSnapshotPage verified(
            UUID snapshotPublicId,
            long ownerRevision,
            Instant asOf,
            WorkforceSnapshotProjection projection,
            String purposeCode,
            String fullPopulationDigest,
            List<CompensationPopulationLine> lines,
            String nextCursor) {
        List<CompensationPopulationLine> immutable = List.copyOf(lines);
        String digest = calculatePageDigest(
                snapshotPublicId, ownerRevision, asOf, projection, purposeCode,
                fullPopulationDigest, immutable, nextCursor);
        return new CompensationPopulationSnapshotPage(
                snapshotPublicId, ownerRevision, asOf, projection, purposeCode,
                fullPopulationDigest, digest, immutable, nextCursor);
    }

    void verifyFor(VerifiedCompensationPopulationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!snapshotPublicId.equals(request.snapshotPublicId())
                || ownerRevision != request.ownerRevision()
                || !asOf.equals(request.asOf())
                || projection != request.projection()
                || !purposeCode.equals(request.purposeCode())) {
            throw new IllegalArgumentException(
                    "compensation page does not match snapshot, owner revision, asOf, "
                            + "projection, or purpose");
        }
        if (request.cursorFullPopulationDigest() != null
                && !fullPopulationDigest.equals(request.cursorFullPopulationDigest())) {
            throw new IllegalArgumentException(
                    "fullPopulationDigest changed during compensation pagination");
        }
        if (lines.size() > request.limit()) {
            throw new IllegalArgumentException("page line count exceeds the requested limit");
        }
        if (request.verifiedCursorPosition() != null && !lines.isEmpty()
                && canonicalPosition(lines.getFirst())
                .compareTo(request.verifiedCursorPosition()) <= 0) {
            throw new IllegalArgumentException(
                    "page does not continue after the authenticated compensation cursor position");
        }
        if (request.verifiedCursorPosition() != null && !lines.isEmpty()
                && lines.getFirst().assignmentPublicId().toString()
                .compareTo(cursorAssignmentPublicId(request.verifiedCursorPosition())) <= 0) {
            throw new IllegalArgumentException(
                    "assignmentPublicId must remain unique across compensation pages");
        }
    }

    /** Canonical last-emitted key that the owner cursor codec must authenticate. */
    public String continuationPosition() {
        return lines.isEmpty() ? null : canonicalPosition(lines.getLast());
    }

    public static String calculatePageDigest(
            UUID snapshotPublicId,
            long ownerRevision,
            Instant asOf,
            WorkforceSnapshotProjection projection,
            String purposeCode,
            String fullPopulationDigest,
            List<CompensationPopulationLine> lines,
            String nextCursor) {
        return calculateDigest(
                snapshotPublicId, ownerRevision,
                asOf, projection, purposeCode, fullPopulationDigest, lines, nextCursor);
    }

    /** Digest of the complete canonical CompensationPopulationContent.v1 value. */
    public static String calculateFullPopulationDigest(
            Instant asOf,
            long ownerRevision,
            List<CompensationPopulationLine> completeLines) {
        Objects.requireNonNull(asOf, "asOf must not be null");
        if (ownerRevision <= 0) {
            throw new IllegalArgumentException("ownerRevision must be positive");
        }
        List<CompensationPopulationLine> immutable = List.copyOf(
                Objects.requireNonNull(completeLines, "completeLines must not be null"));
        if (immutable.isEmpty() || immutable.size() > 100_000) {
            throw new IllegalArgumentException(
                    "completeLines must contain between 1 and 100000 values");
        }
        verifyCanonicalLines(immutable);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    CompensationPopulationCanonicalJson.fullPopulationContent(
                            asOf, ownerRevision, immutable)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String canonicalPosition(CompensationPopulationLine line) {
        Objects.requireNonNull(line, "line must not be null");
        return line.assignmentPublicId() + ":" + line.workerPublicId();
    }

    private static String cursorAssignmentPublicId(String position) {
        String validated = WorkforceSnapshotContract.validatedCursorPosition(position);
        return validated.substring(0, validated.indexOf(':'));
    }

    private static String calculateDigest(
            UUID snapshotPublicId,
            long ownerRevision,
            Instant asOf,
            WorkforceSnapshotProjection projection,
            String purposeCode,
            String fullPopulationDigest,
            List<CompensationPopulationLine> lines,
            String nextCursor) {
        Objects.requireNonNull(snapshotPublicId, "snapshotPublicId must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");
        Objects.requireNonNull(projection, "projection must not be null");
        Objects.requireNonNull(purposeCode, "purposeCode must not be null");
        requireDigest(fullPopulationDigest, "fullPopulationDigest");
        List<CompensationPopulationLine> immutable = List.copyOf(
                Objects.requireNonNull(lines, "lines must not be null"));
        verifyCanonicalLines(immutable);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    CompensationPopulationCanonicalJson.page(
                            snapshotPublicId, ownerRevision, asOf, projection, purposeCode,
                            fullPopulationDigest, immutable, nextCursor)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void verifyCanonicalLines(List<CompensationPopulationLine> lines) {
        Set<UUID> assignments = new HashSet<>();
        Set<String> workerAssignments = new HashSet<>();
        CompensationPopulationLine previous = null;
        for (CompensationPopulationLine line : lines) {
            Objects.requireNonNull(line, "lines must not contain null");
            String position = canonicalPosition(line);
            if (!workerAssignments.add(position)) {
                throw new IllegalArgumentException(
                        "worker and assignment key must be unique within the population");
            }
            if (!assignments.add(line.assignmentPublicId())) {
                throw new IllegalArgumentException(
                        "assignmentPublicId must be unique within the population");
            }
            if (previous != null && CANONICAL_ORDER.compare(previous, line) >= 0) {
                throw new IllegalArgumentException("lines must follow " + ORDERING_VERSION);
            }
            previous = line;
        }
    }

    private static void requireDigest(String value, String fieldName) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be a canonical lowercase SHA-256 digest");
        }
    }

}
