package com.dwp.services.people.hris.performance.workforce;

import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class PerformanceWorkforceTestSupport {

    static final long TENANT = 900_001L;
    static final Instant AS_OF = Instant.parse("2030-01-01T00:00:00Z");
    static final long OWNER_REVISION = 42L;
    static final UUID ORGANIZATION = uuid("18200000-0000-0000-0000-000000000101");
    static final UUID MANAGER_ASSIGNMENT = uuid("12000000-0000-0000-0000-000000000010");
    static final WorkforceSnapshotV1 MEMBER_ONE = new WorkforceSnapshotV1(
            uuid("10000000-0000-0000-0000-000000000001"),
            uuid("11000000-0000-0000-0000-000000000001"),
            "ACTIVE",
            ORGANIZATION,
            MANAGER_ASSIGNMENT,
            uuid("13000000-0000-0000-0000-000000000001"),
            uuid("14000000-0000-0000-0000-000000000001"));
    static final WorkforceSnapshotV1 MEMBER_TWO = new WorkforceSnapshotV1(
            uuid("10000000-0000-0000-0000-000000000002"),
            uuid("11000000-0000-0000-0000-000000000002"),
            "ACTIVE",
            ORGANIZATION,
            MANAGER_ASSIGNMENT,
            uuid("13000000-0000-0000-0000-000000000002"),
            uuid("14000000-0000-0000-0000-000000000002"));

    private PerformanceWorkforceTestSupport() {
    }

    static WorkforceSnapshotPage page(
            long tenantId,
            long ownerRevision,
            List<WorkforceSnapshotV1> members,
            String nextCursor) {
        return WorkforceSnapshotPage.verified(
                tenantId,
                "HRIS-PER",
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                AS_OF,
                ownerRevision,
                members,
                nextCursor);
    }

    static PerformanceWorkforceProjection.Snapshot snapshot() {
        List<PerformanceWorkforceProjection.Member> members =
                PerformanceWorkforceProjection.canonicalMembers(
                        List.of(MEMBER_ONE, MEMBER_TWO));
        String contentHash = PerformanceWorkforceProjection.snapshotContentHash(
                TENANT,
                AS_OF,
                OWNER_REVISION,
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                members);
        return new PerformanceWorkforceProjection.Snapshot(
                TENANT,
                AS_OF,
                OWNER_REVISION,
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                PerformanceWorkforceProjection.pageSetDigest(List.of("a".repeat(64))),
                contentHash,
                members);
    }

    static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}

final class InMemoryPerformanceWorkforceProjectionStore
        implements PerformanceWorkforceProjectionStore {

    private final Map<Long, PerformanceWorkforceProjection.Head> heads = new HashMap<>();
    private final Map<SubjectKey, Long> watermarks = new HashMap<>();
    private final Map<EventKey, Receipt> receipts = new HashMap<>();
    private InvalidationEvent lastEvent;

    @Override
    public synchronized PerformanceWorkforceProjection.Head replaceFromOwner(
            PerformanceWorkforceProjection.Snapshot snapshot,
            ReplayFence replayFence) {
        PerformanceWorkforceProjection.Head current = heads.get(snapshot.tenantId());
        if (current != null
                && current.state() == PerformanceWorkforceProjection.State.QUARANTINED) {
            throw new PerformanceWorkforceProjectionException(
                    PerformanceWorkforceProjectionException.Code.PROJECTION_QUARANTINED,
                    "The projection is quarantined.");
        }
        if (replayFence == null && hasPendingReplay(snapshot.tenantId())) {
            throw replayNotPending();
        }
        if (replayFence != null) {
            if (replayFence.tenantId() != snapshot.tenantId()) {
                throw replayNotPending();
            }
            for (UUID coveredEventId : replayFence.coveredEventIds()) {
                Receipt covered = receipts.get(
                        new EventKey(snapshot.tenantId(), coveredEventId));
                if (!isReplayPending(covered)) {
                    throw replayNotPending();
                }
            }
        }

        long localRevision = Optional.ofNullable(current)
                .map(PerformanceWorkforceProjection.Head::localRevision)
                .orElse(0L) + 1L;
        UUID projectionRevisionId = UUID.nameUUIDFromBytes((
                snapshot.tenantId() + ":" + localRevision + ":" + snapshot.contentHash())
                .getBytes(StandardCharsets.UTF_8));

        if (replayFence != null) {
            for (UUID coveredEventId : replayFence.coveredEventIds()) {
                Receipt covered = receipts.get(
                        new EventKey(snapshot.tenantId(), coveredEventId));
                covered.replayed = true;
                if (covered.event.aggregateRevision() != null) {
                    watermarks.merge(
                            SubjectKey.from(covered.event),
                            covered.event.aggregateRevision(),
                            Math::max);
                }
            }
        }

        PendingProjection pending = pendingProjection(snapshot.tenantId());
        PerformanceWorkforceProjection.Head updated = new PerformanceWorkforceProjection.Head(
                snapshot.tenantId(),
                pending.state(),
                localRevision,
                projectionRevisionId,
                snapshot,
                pending.requiredAggregateRevision(),
                pending.observedAggregateRevision());
        heads.put(snapshot.tenantId(), updated);
        return updated;
    }

    @Override
    public synchronized ReplayFence beginReplay(long tenantId, UUID replayEventId) {
        PerformanceWorkforceProjection.Head current = heads.get(tenantId);
        if (current != null
                && current.state() == PerformanceWorkforceProjection.State.QUARANTINED) {
            throw new PerformanceWorkforceProjectionException(
                    PerformanceWorkforceProjectionException.Code.PROJECTION_QUARANTINED,
                    "The projection is quarantined.");
        }
        Receipt requested = receipts.get(new EventKey(tenantId, replayEventId));
        if (!isReplayPending(requested)) {
            throw replayNotPending();
        }
        Set<UUID> coveredEventIds = new HashSet<>();
        for (Map.Entry<EventKey, Receipt> entry : receipts.entrySet()) {
            if (entry.getKey().tenantId() == tenantId && isReplayPending(entry.getValue())) {
                coveredEventIds.add(entry.getKey().eventId());
            }
        }
        return new ReplayFence(tenantId, replayEventId, coveredEventIds);
    }

    @Override
    public synchronized Optional<PerformanceWorkforceProjection.Head> head(long tenantId) {
        return Optional.ofNullable(heads.get(tenantId));
    }

    @Override
    public synchronized InvalidationResult invalidate(InvalidationEvent event) {
        lastEvent = event;
        EventKey eventKey = new EventKey(event.tenantId(), event.eventId());
        Receipt existing = receipts.get(eventKey);
        if (existing != null) {
            if (!existing.event.payloadHash().equals(event.payloadHash())) {
                InvalidationResult quarantined = result(
                        event,
                        InvalidationOutcome.QUARANTINED,
                        true,
                        state(event.tenantId(), PerformanceWorkforceProjection.State.QUARANTINED),
                        null,
                        null);
                existing.conflictingPayloadHash = event.payloadHash();
                existing.result = quarantined;
                return quarantined;
            }
            if (existing.replayed) {
                return result(
                        event,
                        InvalidationOutcome.REPLAYED,
                        true,
                        currentState(event.tenantId()),
                        null,
                        null);
            }
            InvalidationResult prior = existing.result;
            return result(
                    event,
                    prior.outcome(),
                    true,
                    currentState(event.tenantId()),
                    prior.requiredAggregateRevision(),
                    prior.observedAggregateRevision());
        }
        if (currentState(event.tenantId())
                == PerformanceWorkforceProjection.State.QUARANTINED) {
            throw new PerformanceWorkforceProjectionException(
                    PerformanceWorkforceProjectionException.Code.PROJECTION_QUARANTINED,
                    "The projection is quarantined.");
        }

        InvalidationResult result;
        if (event.aggregateRevision() == null) {
            result = result(
                    event,
                    InvalidationOutcome.STALE,
                    false,
                    state(event.tenantId(), PerformanceWorkforceProjection.State.STALE),
                    null,
                    null);
        } else {
            SubjectKey subjectKey = SubjectKey.from(event);
            long expected = watermarks.getOrDefault(subjectKey, 0L) + 1L;
            if (event.aggregateRevision() < expected) {
                result = result(
                        event,
                        InvalidationOutcome.IGNORED_OLDER_REVISION,
                        false,
                        currentState(event.tenantId()),
                        null,
                        null);
            } else if (event.aggregateRevision() > expected) {
                result = result(
                        event,
                        InvalidationOutcome.BLOCKED_DEPENDENCY_GAP,
                        false,
                        state(
                                event.tenantId(),
                                PerformanceWorkforceProjection.State.BLOCKED_DEPENDENCY_GAP,
                                expected,
                                event.aggregateRevision()),
                        expected,
                        event.aggregateRevision());
            } else {
                watermarks.put(subjectKey, event.aggregateRevision());
                result = result(
                        event,
                        InvalidationOutcome.STALE,
                        false,
                        state(event.tenantId(), PerformanceWorkforceProjection.State.STALE),
                        null,
                        null);
            }
        }
        receipts.put(eventKey, new Receipt(event, result));
        return result;
    }

    InvalidationEvent lastEvent() {
        return lastEvent;
    }

    private boolean hasPendingReplay(long tenantId) {
        return receipts.entrySet().stream()
                .anyMatch(entry -> entry.getKey().tenantId() == tenantId
                        && isReplayPending(entry.getValue()));
    }

    private PendingProjection pendingProjection(long tenantId) {
        Receipt stale = null;
        for (Map.Entry<EventKey, Receipt> entry : receipts.entrySet()) {
            if (entry.getKey().tenantId() != tenantId || !isReplayPending(entry.getValue())) {
                continue;
            }
            Receipt pending = entry.getValue();
            if (pending.result.outcome() == InvalidationOutcome.BLOCKED_DEPENDENCY_GAP) {
                return new PendingProjection(
                        PerformanceWorkforceProjection.State.BLOCKED_DEPENDENCY_GAP,
                        pending.result.requiredAggregateRevision(),
                        pending.result.observedAggregateRevision());
            }
            stale = pending;
        }
        return stale == null
                ? new PendingProjection(
                PerformanceWorkforceProjection.State.READY, null, null)
                : new PendingProjection(
                PerformanceWorkforceProjection.State.STALE, null, null);
    }

    private static boolean isReplayPending(Receipt receipt) {
        return receipt != null
                && !receipt.replayed
                && (receipt.result.outcome() == InvalidationOutcome.STALE
                || receipt.result.outcome() == InvalidationOutcome.BLOCKED_DEPENDENCY_GAP);
    }

    private static PerformanceWorkforceProjectionException replayNotPending() {
        return new PerformanceWorkforceProjectionException(
                PerformanceWorkforceProjectionException.Code.EVENT_REPLAY_NOT_PENDING,
                "The event does not have a pending owner replay.");
    }

    private PerformanceWorkforceProjection.State currentState(long tenantId) {
        return Optional.ofNullable(heads.get(tenantId))
                .map(PerformanceWorkforceProjection.Head::state)
                .orElse(PerformanceWorkforceProjection.State.STALE);
    }

    private PerformanceWorkforceProjection.State state(
            long tenantId,
            PerformanceWorkforceProjection.State next) {
        return state(tenantId, next, null, null);
    }

    private PerformanceWorkforceProjection.State state(
            long tenantId,
            PerformanceWorkforceProjection.State next,
            Long requiredRevision,
            Long observedRevision) {
        PerformanceWorkforceProjection.Head current = heads.get(tenantId);
        PerformanceWorkforceProjection.Head updated = new PerformanceWorkforceProjection.Head(
                tenantId,
                next,
                current == null ? 0L : current.localRevision(),
                current == null ? null : current.projectionRevisionId(),
                current == null ? null : current.snapshot(),
                requiredRevision,
                observedRevision);
        heads.put(tenantId, updated);
        return next;
    }

    private InvalidationResult result(
            InvalidationEvent event,
            InvalidationOutcome outcome,
            boolean duplicate,
            PerformanceWorkforceProjection.State projectionState,
            Long requiredRevision,
            Long observedRevision) {
        return new InvalidationResult(
                event.eventId(),
                outcome,
                duplicate,
                projectionState,
                requiredRevision,
                observedRevision);
    }

    private record EventKey(long tenantId, UUID eventId) {
    }

    private record PendingProjection(
            PerformanceWorkforceProjection.State state,
            Long requiredAggregateRevision,
            Long observedAggregateRevision) {
    }

    private record SubjectKey(
            long tenantId,
            SubjectType subjectType,
            UUID subjectPublicId) {

        private static SubjectKey from(InvalidationEvent event) {
            return new SubjectKey(
                    event.tenantId(), event.subjectType(), event.subjectPublicId());
        }
    }

    private static final class Receipt {
        private final InvalidationEvent event;
        private InvalidationResult result;
        private boolean replayed;
        @SuppressWarnings("unused")
        private String conflictingPayloadHash;

        private Receipt(InvalidationEvent event, InvalidationResult result) {
            this.event = event;
            this.result = result;
        }
    }
}
