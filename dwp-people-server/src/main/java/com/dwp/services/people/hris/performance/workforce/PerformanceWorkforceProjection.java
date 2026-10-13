package com.dwp.services.people.hris.performance.workforce;

import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Immutable PER-owned view of the closed HRM workforce projection. */
public final class PerformanceWorkforceProjection {

    static final String SNAPSHOT_DIGEST_VERSION = "per-workforce-projection.v1";
    static final String PREVIEW_DIGEST_VERSION = "per-workforce-preview.v1";
    static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    static final Comparator<Member> CANONICAL_MEMBER_ORDER = Comparator
            .comparing((Member value) -> value.assignmentPublicId().toString())
            .thenComparing(value -> value.workerPublicId().toString());

    private PerformanceWorkforceProjection() {
    }

    public enum State {
        READY,
        STALE,
        BLOCKED_DEPENDENCY_GAP,
        QUARANTINED
    }

    public record Member(
            UUID workerPublicId,
            UUID assignmentPublicId,
            String status,
            UUID organizationPublicId,
            UUID managerAssignmentPublicId,
            UUID jobProfilePublicId,
            UUID gradePublicId) {

        public Member {
            Objects.requireNonNull(workerPublicId, "workerPublicId must not be null");
            Objects.requireNonNull(assignmentPublicId, "assignmentPublicId must not be null");
            if (!WorkforceSnapshotV1.STATUS_CODES.contains(status)) {
                throw new IllegalArgumentException("status is outside WorkforceSnapshotV1");
            }
            Objects.requireNonNull(
                    organizationPublicId, "organizationPublicId must not be null");
        }

        static Member from(WorkforceSnapshotV1 value) {
            return new Member(
                    value.workerPublicId(),
                    value.assignmentPublicId(),
                    value.status(),
                    value.organizationPublicId(),
                    value.managerAssignmentPublicId(),
                    value.jobProfilePublicId(),
                    value.gradePublicId());
        }
    }

    public record Snapshot(
            long tenantId,
            Instant asOf,
            long ownerRevision,
            String purposeCode,
            WorkforceSnapshotProjection projection,
            String pageSetDigest,
            String contentHash,
            List<Member> members) {

        public Snapshot {
            if (tenantId <= 0 || ownerRevision <= 0) {
                throw new IllegalArgumentException(
                        "tenantId and ownerRevision must be positive");
            }
            asOf = canonicalAsOf(asOf);
            if (!WorkforceSnapshotQuery.PURPOSE_CODE.equals(purposeCode)) {
                throw new IllegalArgumentException("purposeCode is outside the sealed contract");
            }
            if (projection != WorkforceSnapshotProjection.PERFORMANCE_V1) {
                throw new IllegalArgumentException("projection is outside the sealed contract");
            }
            pageSetDigest = validatedDigest(pageSetDigest, "pageSetDigest");
            contentHash = validatedDigest(contentHash, "contentHash");
            members = List.copyOf(Objects.requireNonNull(members, "members must not be null"));
            verifyCanonicalMembers(members);
            String calculated = snapshotContentHash(
                    tenantId, asOf, ownerRevision, purposeCode, projection, members);
            if (!calculated.equals(contentHash)) {
                throw new IllegalArgumentException("contentHash does not bind the snapshot");
            }
        }
    }

    public record Head(
            long tenantId,
            State state,
            long localRevision,
            UUID projectionRevisionId,
            Snapshot snapshot,
            Long requiredAggregateRevision,
            Long observedAggregateRevision) {

        public Head {
            if (tenantId <= 0 || localRevision < 0) {
                throw new IllegalArgumentException(
                        "tenantId must be positive and localRevision nonnegative");
            }
            Objects.requireNonNull(state, "state must not be null");
            if ((projectionRevisionId == null) != (snapshot == null)) {
                throw new IllegalArgumentException(
                        "projectionRevisionId and snapshot must be present together");
            }
            if ((localRevision == 0) != (snapshot == null)) {
                throw new IllegalArgumentException(
                        "localRevision is zero exactly while the projection is unmaterialized");
            }
            if (snapshot != null && tenantId != snapshot.tenantId()) {
                throw new IllegalArgumentException("snapshot tenant does not match head tenant");
            }
            if (state == State.READY && snapshot == null) {
                throw new IllegalArgumentException("READY requires a stored snapshot");
            }
            boolean gap = state == State.BLOCKED_DEPENDENCY_GAP;
            if (gap != (requiredAggregateRevision != null && observedAggregateRevision != null)) {
                throw new IllegalArgumentException(
                        "gap revisions must be present exactly for BLOCKED_DEPENDENCY_GAP");
            }
            if (gap && (requiredAggregateRevision <= 0
                    || observedAggregateRevision <= requiredAggregateRevision)) {
                throw new IllegalArgumentException("gap revisions are invalid");
            }
        }
    }

    public record PopulationRule(
            boolean includeActiveOnly,
            Set<UUID> organizationPublicIds) {

        public PopulationRule {
            Objects.requireNonNull(
                    organizationPublicIds, "organizationPublicIds must not be null");
            if (organizationPublicIds.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "organizationPublicIds must not contain null");
            }
            organizationPublicIds = Set.copyOf(organizationPublicIds);
        }
    }

    public record Preview(
            UUID projectionRevisionId,
            long localRevision,
            long ownerRevision,
            Instant asOf,
            String contentHash,
            List<Member> members) {

        public Preview {
            Objects.requireNonNull(
                    projectionRevisionId, "projectionRevisionId must not be null");
            if (localRevision <= 0 || ownerRevision <= 0) {
                throw new IllegalArgumentException(
                        "localRevision and ownerRevision must be positive");
            }
            Objects.requireNonNull(asOf, "asOf must not be null");
            contentHash = validatedDigest(contentHash, "contentHash");
            members = List.copyOf(Objects.requireNonNull(members, "members must not be null"));
            verifyCanonicalMembers(members);
        }

        public int participantCount() {
            return members.size();
        }

        public int reviewerAssignmentCount() {
            return (int) members.stream()
                    .map(Member::managerAssignmentPublicId)
                    .filter(Objects::nonNull)
                    .count();
        }
    }

    static Preview preview(Head head, PopulationRule rule) {
        Objects.requireNonNull(head, "head must not be null");
        Objects.requireNonNull(rule, "rule must not be null");
        if (head.state() == State.QUARANTINED) {
            throw new PerformanceWorkforceProjectionException(
                    PerformanceWorkforceProjectionException.Code.PROJECTION_QUARANTINED,
                    "The PER workforce projection is quarantined.");
        }
        if (head.state() != State.READY) {
            throw new PerformanceWorkforceProjectionException(
                    PerformanceWorkforceProjectionException.Code.STALE_WORKFORCE_SNAPSHOT,
                    "The PER workforce projection is stale and must be replayed.");
        }
        if (head.snapshot() == null) {
            throw new PerformanceWorkforceProjectionException(
                    PerformanceWorkforceProjectionException.Code.PROJECTION_NOT_READY,
                    "The PER workforce projection has not been materialized.");
        }
        List<Member> selected = head.snapshot().members().stream()
                .filter(member -> !rule.includeActiveOnly() || "ACTIVE".equals(member.status()))
                .filter(member -> rule.organizationPublicIds().isEmpty()
                        || rule.organizationPublicIds().contains(member.organizationPublicId()))
                .toList();
        String hash = previewContentHash(
                head.projectionRevisionId(), head.localRevision(), head.snapshot(), rule, selected);
        return new Preview(
                head.projectionRevisionId(),
                head.localRevision(),
                head.snapshot().ownerRevision(),
                head.snapshot().asOf(),
                hash,
                selected);
    }

    static String pageSetDigest(List<String> pageDigests) {
        List<String> immutable = List.copyOf(pageDigests);
        immutable.forEach(value -> validatedDigest(value, "pageDigest"));
        return digest(md -> {
            put(md, SNAPSHOT_DIGEST_VERSION + ".pages");
            put(md, immutable.size());
            immutable.forEach(value -> put(md, value));
        });
    }

    static String snapshotContentHash(
            long tenantId,
            Instant asOf,
            long ownerRevision,
            String purposeCode,
            WorkforceSnapshotProjection projection,
            List<Member> members) {
        Instant canonicalAsOf = canonicalAsOf(asOf);
        return digest(md -> {
            put(md, SNAPSHOT_DIGEST_VERSION);
            put(md, tenantId);
            put(md, canonicalAsOf.toString());
            put(md, ownerRevision);
            put(md, purposeCode);
            put(md, projection.name());
            put(md, members.size());
            members.forEach(member -> putMember(md, member));
        });
    }

    static Instant canonicalAsOf(Instant value) {
        return Objects.requireNonNull(value, "asOf must not be null")
                .truncatedTo(ChronoUnit.MICROS);
    }

    static String canonicalPayloadDigest(String eventType, Consumer<MessageDigest> values) {
        return digest(md -> {
            put(md, "per-workforce-invalidation.v1");
            put(md, eventType);
            values.accept(md);
        });
    }

    private static String previewContentHash(
            UUID projectionRevisionId,
            long localRevision,
            Snapshot snapshot,
            PopulationRule rule,
            List<Member> members) {
        List<String> organizations = rule.organizationPublicIds().stream()
                .map(UUID::toString)
                .sorted()
                .toList();
        return digest(md -> {
            put(md, PREVIEW_DIGEST_VERSION);
            put(md, projectionRevisionId.toString());
            put(md, localRevision);
            put(md, snapshot.ownerRevision());
            put(md, snapshot.asOf().toString());
            put(md, rule.includeActiveOnly() ? 1L : 0L);
            put(md, organizations.size());
            organizations.forEach(value -> put(md, value));
            put(md, members.size());
            members.forEach(member -> putMember(md, member));
        });
    }

    static void put(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    static void put(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void putMember(MessageDigest digest, Member member) {
        put(digest, member.workerPublicId().toString());
        put(digest, member.assignmentPublicId().toString());
        put(digest, member.status());
        put(digest, member.organizationPublicId().toString());
        put(digest, nullable(member.managerAssignmentPublicId()));
        put(digest, nullable(member.jobProfilePublicId()));
        put(digest, nullable(member.gradePublicId()));
    }

    private static String digest(Consumer<MessageDigest> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.accept(digest);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String validatedDigest(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return value;
    }

    private static void verifyCanonicalMembers(List<Member> members) {
        Set<UUID> assignments = new HashSet<>();
        Set<String> workerAssignments = new HashSet<>();
        Member previous = null;
        for (Member member : members) {
            Objects.requireNonNull(member, "members must not contain null");
            String key = member.assignmentPublicId() + ":" + member.workerPublicId();
            if (!workerAssignments.add(key) || !assignments.add(member.assignmentPublicId())) {
                throw new IllegalArgumentException("snapshot member keys must be unique");
            }
            if (previous != null && CANONICAL_MEMBER_ORDER.compare(previous, member) >= 0) {
                throw new IllegalArgumentException("snapshot members are not canonically ordered");
            }
            previous = member;
        }
    }

    static List<Member> canonicalMembers(List<WorkforceSnapshotV1> snapshots) {
        List<Member> members = snapshots.stream()
                .map(Member::from)
                .sorted(CANONICAL_MEMBER_ORDER)
                .toList();
        verifyCanonicalMembers(members);
        return members;
    }

    private static String nullable(Object value) {
        return value == null ? null : value.toString();
    }
}
