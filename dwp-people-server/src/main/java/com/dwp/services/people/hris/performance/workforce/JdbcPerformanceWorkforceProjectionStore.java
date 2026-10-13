package com.dwp.services.people.hris.performance.workforce;

import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL persistence for the tenant-scoped PER workforce projection. */
@Repository
class JdbcPerformanceWorkforceProjectionStore
        implements PerformanceWorkforceProjectionStore {

    private static final String PURPOSE_CODE = WorkforceSnapshotQuery.PURPOSE_CODE;
    private static final String PROJECTION_CODE =
            WorkforceSnapshotProjection.PERFORMANCE_V1.name();

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcPerformanceWorkforceProjectionStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager must not be null"));
    }

    @Override
    public PerformanceWorkforceProjection.Head replaceFromOwner(
            PerformanceWorkforceProjection.Snapshot snapshot,
            ReplayFence replayFence) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return inTenantTransaction(snapshot.tenantId(), () ->
                replaceFromOwnerInTransaction(snapshot, replayFence));
    }

    @Override
    public ReplayFence beginReplay(long tenantId, UUID replayEventId) {
        requireTenant(tenantId);
        Objects.requireNonNull(replayEventId, "replayEventId must not be null");
        return inTenantTransaction(tenantId, () ->
                beginReplayInTransaction(tenantId, replayEventId));
    }

    @Override
    public Optional<PerformanceWorkforceProjection.Head> head(long tenantId) {
        requireTenant(tenantId);
        return inTenantTransaction(tenantId, () -> unlockedHead(tenantId)
                .map(this::materializeHead));
    }

    @Override
    public InvalidationResult invalidate(InvalidationEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return inTenantTransaction(event.tenantId(), () ->
                invalidateInTransaction(event));
    }

    private PerformanceWorkforceProjection.Head replaceFromOwnerInTransaction(
            PerformanceWorkforceProjection.Snapshot snapshot,
            ReplayFence replayFence) {
        HeadRow current = ensureAndLockHead(snapshot.tenantId());
        if (current.state() == PerformanceWorkforceProjection.State.QUARANTINED) {
            throw projectionError(
                    PerformanceWorkforceProjectionException.Code.PROJECTION_QUARANTINED,
                    "The PER workforce projection is quarantined and cannot be replaced.");
        }
        if (replayFence != null && replayFence.tenantId() != snapshot.tenantId()) {
            throw replayNotPending("The replay fence belongs to another tenant.");
        }
        if (replayFence == null && hasPendingReplay(snapshot.tenantId())) {
            throw replayNotPending(
                    "Pending invalidations require an event-bound owner replay.");
        }
        if (replayFence != null) {
            ReceiptRow replay = receiptByEvent(
                    snapshot.tenantId(), replayFence.replayEventId())
                    .orElseThrow(() -> replayNotPending(
                            "The requested invalidation event is not pending replay."));
            boolean replayableOutcome = InvalidationOutcome.STALE.name()
                    .equals(replay.outcomeCode())
                    || InvalidationOutcome.BLOCKED_DEPENDENCY_GAP.name()
                    .equals(replay.outcomeCode());
            if (!"REPLAY_REQUIRED".equals(replay.processingState())
                    || !replayableOutcome) {
                throw replayNotPending(
                        "The requested invalidation event is not pending replay.");
            }
        }

        long nextLocalRevision = current.localRevision() + 1;
        UUID projectionRevisionId = UUID.randomUUID();
        Long replayOfLocalRevision = replayFence != null && current.localRevision() > 0
                ? current.localRevision()
                : null;
        insertRevision(
                current.projectionHeadId(),
                projectionRevisionId,
                nextLocalRevision,
                snapshot,
                replayOfLocalRevision);
        insertMembers(
                current.projectionHeadId(),
                nextLocalRevision,
                snapshot.tenantId(),
                snapshot.members());

        if (replayFence != null) {
            int replayed = jdbc.update("""
                    UPDATE hris_performance.prf_workforce_projection_inbox
                       SET processing_state = 'PROCESSED',
                           outcome_code = 'REPLAYED',
                           resulting_projection_state = :currentState,
                           processed_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenant
                       AND event_id IN (:coveredEvents)
                       AND processing_state = 'REPLAY_REQUIRED'
                    """, tenantParameters(snapshot.tenantId())
                    .addValue("currentState", current.state().name())
                    .addValue("coveredEvents", replayFence.coveredEventIds()));
            if (replayed != replayFence.coveredEventIds().size()) {
                throw replayNotPending(
                        "The replay fence changed before owner materialization completed.");
            }
        }

        PendingProjection pending = pendingProjection(snapshot.tenantId());
        if (replayFence != null) {
            int finalized = jdbc.update("""
                    UPDATE hris_performance.prf_workforce_projection_inbox
                       SET resulting_projection_state = :resultingState
                     WHERE tenant_id = :tenant
                       AND event_id IN (:coveredEvents)
                       AND processing_state = 'PROCESSED'
                       AND outcome_code = 'REPLAYED'
                    """, tenantParameters(snapshot.tenantId())
                    .addValue("resultingState", pending.state().name())
                    .addValue("coveredEvents", replayFence.coveredEventIds()));
            if (finalized != replayFence.coveredEventIds().size()) {
                throw replayNotPending(
                        "The replay receipt set changed before finalization.");
            }
        }
        int advanced = jdbc.update("""
                UPDATE hris_performance.prf_workforce_projection_heads
                   SET snapshot_as_of = :asOf,
                       projection_state = :state,
                       current_local_revision = :localRevision,
                       current_owner_revision = :ownerRevision,
                       current_content_hash = :contentHash,
                       stale_event_id = :staleEvent,
                       blocked_expected_revision = :blockedExpected,
                       blocked_observed_revision = :blockedObserved,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenant
                   AND projection_head_id = :head
                """, new MapSqlParameterSource()
                .addValue("tenant", snapshot.tenantId())
                .addValue("head", current.projectionHeadId())
                .addValue("asOf", Timestamp.from(snapshot.asOf()))
                .addValue("state", pending.state().name())
                .addValue("localRevision", nextLocalRevision)
                .addValue("ownerRevision", snapshot.ownerRevision())
                .addValue("contentHash", snapshot.contentHash())
                .addValue("staleEvent", pending.eventId())
                .addValue("blockedExpected", pending.requiredAggregateRevision())
                .addValue("blockedObserved", pending.observedAggregateRevision()));
        requireSingleRow(advanced, "PER workforce projection head advance");

        markReadyPreviewsStale(snapshot.tenantId());

        return new PerformanceWorkforceProjection.Head(
                snapshot.tenantId(),
                pending.state(),
                nextLocalRevision,
                projectionRevisionId,
                snapshot,
                pending.requiredAggregateRevision(),
                pending.observedAggregateRevision());
    }

    private ReplayFence beginReplayInTransaction(long tenantId, UUID replayEventId) {
        HeadRow current = ensureAndLockHead(tenantId);
        if (current.state() == PerformanceWorkforceProjection.State.QUARANTINED) {
            throw projectionError(
                    PerformanceWorkforceProjectionException.Code.PROJECTION_QUARANTINED,
                    "The PER workforce projection is quarantined and cannot be replayed.");
        }
        ReceiptRow requested = receiptByEvent(tenantId, replayEventId)
                .orElseThrow(() -> replayNotPending(
                        "The requested invalidation event is not pending replay."));
        if (!"REPLAY_REQUIRED".equals(requested.processingState())
                || !(InvalidationOutcome.STALE.name().equals(requested.outcomeCode())
                || InvalidationOutcome.BLOCKED_DEPENDENCY_GAP.name()
                .equals(requested.outcomeCode()))) {
            throw replayNotPending(
                    "The requested invalidation event is not pending replay.");
        }
        List<UUID> covered = jdbc.queryForList("""
                SELECT event_id
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = :tenant
                   AND processing_state = 'REPLAY_REQUIRED'
                 ORDER BY first_received_at, event_id
                """, tenantParameters(tenantId), UUID.class);
        return new ReplayFence(tenantId, replayEventId, Set.copyOf(covered));
    }

    private InvalidationResult invalidateInTransaction(InvalidationEvent event) {
        HeadRow current = ensureAndLockHead(event.tenantId());
        Optional<ReceiptRow> receiptForEvent = receiptByEvent(
                event.tenantId(), event.eventId());
        if (receiptForEvent.isPresent()) {
            ReceiptRow stored = receiptForEvent.get();
            if (stored.payloadHash().equals(event.payloadHash())) {
                return resultForReceipt(event.eventId(), stored, true, current);
            }
            return quarantineConflict(event, stored, true);
        }
        if (current.state() == PerformanceWorkforceProjection.State.QUARANTINED) {
            throw projectionError(
                    PerformanceWorkforceProjectionException.Code.PROJECTION_QUARANTINED,
                    "The PER workforce projection is quarantined and rejects new invalidations.");
        }

        if (event.aggregateRevision() != null) {
            long watermark = aggregateWatermark(event);
            if (event.aggregateRevision() <= watermark) {
                return ignoreOlderRevision(event, current);
            }
            long expected = watermark + 1;
            if (event.aggregateRevision() > expected) {
                return blockDependencyGap(event, current, expected);
            }
        }
        return markProjectionStale(event, current);
    }

    private InvalidationResult ignoreOlderRevision(
            InvalidationEvent event,
            HeadRow current) {
        insertReceipt(
                event,
                "IGNORED",
                InvalidationOutcome.IGNORED_OLDER_REVISION,
                current.state(),
                true);
        return new InvalidationResult(
                event.eventId(),
                InvalidationOutcome.IGNORED_OLDER_REVISION,
                false,
                current.state(),
                null,
                null);
    }

    private InvalidationResult blockDependencyGap(
            InvalidationEvent event,
            HeadRow current,
            long expectedRevision) {
        PerformanceWorkforceProjection.State resultingState = current.state()
                == PerformanceWorkforceProjection.State.QUARANTINED
                ? PerformanceWorkforceProjection.State.QUARANTINED
                : PerformanceWorkforceProjection.State.BLOCKED_DEPENDENCY_GAP;
        if (resultingState
                == PerformanceWorkforceProjection.State.BLOCKED_DEPENDENCY_GAP) {
            int updated = jdbc.update("""
                    UPDATE hris_performance.prf_workforce_projection_heads
                       SET projection_state = 'BLOCKED_DEPENDENCY_GAP',
                           stale_event_id = :event,
                           blocked_expected_revision = :expected,
                           blocked_observed_revision = :observed,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenant
                       AND projection_head_id = :head
                    """, tenantParameters(event.tenantId())
                    .addValue("head", current.projectionHeadId())
                    .addValue("event", event.eventId())
                    .addValue("expected", expectedRevision)
                    .addValue("observed", event.aggregateRevision()));
            requireSingleRow(updated, "PER workforce dependency-gap block");
        }
        insertReceipt(
                event,
                "REPLAY_REQUIRED",
                InvalidationOutcome.BLOCKED_DEPENDENCY_GAP,
                resultingState,
                false);
        markReadyPreviewsStale(event.tenantId());
        return new InvalidationResult(
                event.eventId(),
                InvalidationOutcome.BLOCKED_DEPENDENCY_GAP,
                false,
                resultingState,
                expectedRevision,
                event.aggregateRevision());
    }

    private InvalidationResult markProjectionStale(
            InvalidationEvent event,
            HeadRow current) {
        PerformanceWorkforceProjection.State resultingState = switch (current.state()) {
            case READY, STALE -> PerformanceWorkforceProjection.State.STALE;
            case BLOCKED_DEPENDENCY_GAP ->
                    PerformanceWorkforceProjection.State.BLOCKED_DEPENDENCY_GAP;
            case QUARANTINED -> PerformanceWorkforceProjection.State.QUARANTINED;
        };
        if (resultingState == PerformanceWorkforceProjection.State.STALE) {
            int updated = jdbc.update("""
                    UPDATE hris_performance.prf_workforce_projection_heads
                       SET projection_state = 'STALE',
                           stale_event_id = :event,
                           blocked_expected_revision = NULL,
                           blocked_observed_revision = NULL,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenant
                       AND projection_head_id = :head
                    """, tenantParameters(event.tenantId())
                    .addValue("head", current.projectionHeadId())
                    .addValue("event", event.eventId()));
            requireSingleRow(updated, "PER workforce projection invalidation");
        }
        insertReceipt(
                event,
                "REPLAY_REQUIRED",
                InvalidationOutcome.STALE,
                resultingState,
                false);
        markReadyPreviewsStale(event.tenantId());
        return new InvalidationResult(
                event.eventId(),
                InvalidationOutcome.STALE,
                false,
                resultingState,
                null,
                null);
    }

    private InvalidationResult quarantineConflict(
            InvalidationEvent event,
            ReceiptRow stored,
            boolean duplicate) {
        int quarantined = jdbc.update("""
                UPDATE hris_performance.prf_workforce_projection_inbox
                   SET conflicting_payload_hash = :conflictingHash,
                       processing_state = 'QUARANTINED',
                       outcome_code = 'QUARANTINED',
                       resulting_projection_state = 'QUARANTINED',
                       processed_at = CURRENT_TIMESTAMP,
                       conflict_detected_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenant
                   AND inbox_receipt_id = :receipt
                """, tenantParameters(event.tenantId())
                .addValue("receipt", stored.inboxReceiptId())
                .addValue("conflictingHash", event.payloadHash()));
        requireSingleRow(quarantined, "PER workforce inbox quarantine");

        int headQuarantined = jdbc.update("""
                UPDATE hris_performance.prf_workforce_projection_heads
                   SET projection_state = 'QUARANTINED',
                       stale_event_id = :event,
                       blocked_expected_revision = NULL,
                       blocked_observed_revision = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenant
                """, tenantParameters(event.tenantId())
                .addValue("event", event.eventId()));
        requireSingleRow(headQuarantined, "PER workforce projection quarantine");
        markReadyPreviewsStale(event.tenantId());
        return new InvalidationResult(
                event.eventId(),
                InvalidationOutcome.QUARANTINED,
                duplicate,
                PerformanceWorkforceProjection.State.QUARANTINED,
                null,
                null);
    }

    private void insertRevision(
            UUID projectionHeadId,
            UUID projectionRevisionId,
            long localRevision,
            PerformanceWorkforceProjection.Snapshot snapshot,
            Long replayOfLocalRevision) {
        int inserted = jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_revisions (
                    projection_revision_id,
                    tenant_id,
                    projection_head_id,
                    local_revision,
                    owner_revision,
                    snapshot_as_of,
                    purpose_code,
                    projection_code,
                    owner_page_digest,
                    content_hash,
                    member_count,
                    replay_of_local_revision)
                VALUES (
                    :revision,
                    :tenant,
                    :head,
                    :localRevision,
                    :ownerRevision,
                    :asOf,
                    :purpose,
                    :projection,
                    :pageDigest,
                    :contentHash,
                    :memberCount,
                    :replayOf)
                """, tenantParameters(snapshot.tenantId())
                .addValue("revision", projectionRevisionId)
                .addValue("head", projectionHeadId)
                .addValue("localRevision", localRevision)
                .addValue("ownerRevision", snapshot.ownerRevision())
                .addValue("asOf", Timestamp.from(snapshot.asOf()))
                .addValue("purpose", snapshot.purposeCode())
                .addValue("projection", snapshot.projection().name())
                .addValue("pageDigest", snapshot.pageSetDigest())
                .addValue("contentHash", snapshot.contentHash())
                .addValue("memberCount", snapshot.members().size())
                .addValue("replayOf", replayOfLocalRevision));
        requireSingleRow(inserted, "PER workforce projection revision insert");
    }

    private void insertMembers(
            UUID projectionHeadId,
            long localRevision,
            long tenantId,
            List<PerformanceWorkforceProjection.Member> members) {
        if (members.isEmpty()) {
            return;
        }
        SqlParameterSource[] batches = members.stream()
                .map(member -> tenantParameters(tenantId)
                        .addValue("head", projectionHeadId)
                        .addValue("localRevision", localRevision)
                        .addValue("worker", member.workerPublicId())
                        .addValue("assignment", member.assignmentPublicId())
                        .addValue("status", member.status())
                        .addValue("organization", member.organizationPublicId())
                        .addValue("manager", member.managerAssignmentPublicId())
                        .addValue("jobProfile", member.jobProfilePublicId())
                        .addValue("grade", member.gradePublicId()))
                .toArray(SqlParameterSource[]::new);
        int[] inserted = jdbc.batchUpdate("""
                INSERT INTO hris_performance.prf_workforce_projection_members (
                    tenant_id,
                    projection_head_id,
                    local_revision,
                    worker_public_id,
                    assignment_public_id,
                    workforce_status,
                    organization_public_id,
                    manager_assignment_public_id,
                    job_profile_public_id,
                    grade_public_id)
                VALUES (
                    :tenant,
                    :head,
                    :localRevision,
                    :worker,
                    :assignment,
                    :status,
                    :organization,
                    :manager,
                    :jobProfile,
                    :grade)
                """, batches);
        if (inserted.length != members.size()) {
            throw new IllegalStateException(
                    "PER workforce projection member batch was incomplete.");
        }
        for (int result : inserted) {
            if (result == 0 || result == Statement.EXECUTE_FAILED) {
                throw new IllegalStateException(
                        "PER workforce projection member insert failed.");
            }
        }
    }

    private void insertReceipt(
            InvalidationEvent event,
            String processingState,
            InvalidationOutcome outcome,
            PerformanceWorkforceProjection.State resultingState,
            boolean processed) {
        UUID organizationRevision = event.subjectType() == SubjectType.ORGANIZATION
                ? event.subjectPublicId()
                : null;
        int inserted = jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_inbox (
                    tenant_id,
                    event_id,
                    event_type,
                    aggregate_type,
                    aggregate_public_id,
                    aggregate_revision,
                    organization_revision_public_id,
                    effective_date,
                    payload_hash,
                    processing_state,
                    outcome_code,
                    resulting_projection_state,
                    processed_at)
                VALUES (
                    :tenant,
                    :event,
                    :eventType,
                    :aggregateType,
                    :aggregate,
                    :aggregateRevision,
                    :organizationRevision,
                    :effectiveDate,
                    :payloadHash,
                    :processingState,
                    :outcome,
                    :resultingState,
                    :processedAt)
                """, tenantParameters(event.tenantId())
                .addValue("event", event.eventId())
                .addValue("eventType", event.eventType())
                .addValue("aggregateType", event.subjectType().name())
                .addValue("aggregate", event.subjectPublicId())
                .addValue("aggregateRevision", event.aggregateRevision())
                .addValue("organizationRevision", organizationRevision)
                .addValue("effectiveDate", event.effectiveDate())
                .addValue("payloadHash", event.payloadHash())
                .addValue("processingState", processingState)
                .addValue("outcome", outcome.name())
                .addValue("resultingState", resultingState.name())
                .addValue("processedAt", processed ? Timestamp.from(Instant.now()) : null));
        requireSingleRow(inserted, "PER workforce inbox insert");
    }

    private Optional<ReceiptRow> receiptByEvent(long tenantId, UUID eventId) {
        return jdbc.query("""
                SELECT inbox_receipt_id,
                       tenant_id,
                       event_id,
                       aggregate_type,
                       aggregate_public_id,
                       aggregate_revision,
                       payload_hash,
                       processing_state,
                       outcome_code,
                       resulting_projection_state
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = :tenant
                   AND event_id = :event
                 FOR UPDATE
                """, tenantParameters(tenantId).addValue("event", eventId),
                this::receiptRow).stream().findFirst();
    }

    private boolean hasPendingReplay(long tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = :tenant
                   AND processing_state = 'REPLAY_REQUIRED'
                """, tenantParameters(tenantId), Integer.class);
        return count != null && count > 0;
    }

    private PendingProjection pendingProjection(long tenantId) {
        List<ReceiptRow> pending = jdbc.query("""
                SELECT inbox_receipt_id,
                       tenant_id,
                       event_id,
                       aggregate_type,
                       aggregate_public_id,
                       aggregate_revision,
                       payload_hash,
                       processing_state,
                       outcome_code,
                       resulting_projection_state
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = :tenant
                   AND processing_state = 'REPLAY_REQUIRED'
                 ORDER BY CASE
                              WHEN outcome_code = 'BLOCKED_DEPENDENCY_GAP' THEN 0
                              ELSE 1
                          END,
                          first_received_at,
                          event_id
                 LIMIT 1
                """, tenantParameters(tenantId), this::receiptRow);
        if (pending.isEmpty()) {
            return new PendingProjection(
                    PerformanceWorkforceProjection.State.READY, null, null, null);
        }
        ReceiptRow first = pending.getFirst();
        if (InvalidationOutcome.BLOCKED_DEPENDENCY_GAP.name()
                .equals(first.outcomeCode())) {
            return new PendingProjection(
                    PerformanceWorkforceProjection.State.BLOCKED_DEPENDENCY_GAP,
                    first.eventId(),
                    expectedBeforeGap(first),
                    first.aggregateRevision());
        }
        return new PendingProjection(
                PerformanceWorkforceProjection.State.STALE,
                first.eventId(),
                null,
                null);
    }

    private long aggregateWatermark(InvalidationEvent event) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(aggregate_revision), 0)
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = :tenant
                   AND aggregate_type = :aggregateType
                   AND aggregate_public_id = :aggregate
                """, tenantParameters(event.tenantId())
                .addValue("aggregateType", event.subjectType().name())
                .addValue("aggregate", event.subjectPublicId()), Long.class);
        return value == null ? 0 : value;
    }

    private InvalidationResult resultForReceipt(
            UUID requestedEventId,
            ReceiptRow receipt,
            boolean duplicate,
            HeadRow current) {
        InvalidationOutcome outcome = InvalidationOutcome.valueOf(receipt.outcomeCode());
        Long expected = null;
        Long observed = null;
        if (outcome == InvalidationOutcome.BLOCKED_DEPENDENCY_GAP) {
            observed = receipt.aggregateRevision();
            if (current.state()
                    == PerformanceWorkforceProjection.State.BLOCKED_DEPENDENCY_GAP
                    && current.blockedExpectedRevision() != null
                    && Objects.equals(current.staleEventId(), receipt.eventId())) {
                expected = current.blockedExpectedRevision();
                observed = current.blockedObservedRevision();
            } else {
                expected = expectedBeforeGap(receipt);
            }
        }
        return new InvalidationResult(
                requestedEventId,
                outcome,
                duplicate,
                current.state(),
                expected,
                observed);
    }

    private long expectedBeforeGap(ReceiptRow receipt) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(aggregate_revision), 0) + 1
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = :tenant
                   AND aggregate_type = :aggregateType
                   AND aggregate_public_id = :aggregate
                   AND aggregate_revision < :observed
                   AND outcome_code <> 'IGNORED_OLDER_REVISION'
                """, tenantParameters(receipt.tenantId())
                .addValue("aggregateType", receipt.aggregateType())
                .addValue("aggregate", receipt.aggregatePublicId())
                .addValue("observed", receipt.aggregateRevision()), Long.class);
        return value == null ? 1 : value;
    }

    private HeadRow ensureAndLockHead(long tenantId) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_heads (
                    tenant_id,
                    purpose_code,
                    projection_code,
                    projection_state)
                VALUES (:tenant, :purpose, :projection, 'STALE')
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenantParameters(tenantId)
                .addValue("purpose", PURPOSE_CODE)
                .addValue("projection", PROJECTION_CODE));
        List<HeadRow> rows = jdbc.query("""
                SELECT projection_head_id,
                       tenant_id,
                       projection_state,
                       current_local_revision,
                       stale_event_id,
                       blocked_expected_revision,
                       blocked_observed_revision
                  FROM hris_performance.prf_workforce_projection_heads
                 WHERE tenant_id = :tenant
                 FOR UPDATE
                """, tenantParameters(tenantId), this::headRow);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "PER workforce projection head could not be locked.");
        }
        return rows.getFirst();
    }

    private Optional<HeadRow> unlockedHead(long tenantId) {
        return jdbc.query("""
                SELECT projection_head_id,
                       tenant_id,
                       projection_state,
                       current_local_revision,
                       stale_event_id,
                       blocked_expected_revision,
                       blocked_observed_revision
                  FROM hris_performance.prf_workforce_projection_heads
                 WHERE tenant_id = :tenant
                """, tenantParameters(tenantId), this::headRow)
                .stream().findFirst();
    }

    private PerformanceWorkforceProjection.Head materializeHead(HeadRow head) {
        if (head.localRevision() == 0) {
            return new PerformanceWorkforceProjection.Head(
                    head.tenantId(),
                    head.state(),
                    0,
                    null,
                    null,
                    head.blockedExpectedRevision(),
                    head.blockedObservedRevision());
        }
        List<RevisionRow> revisions = jdbc.query("""
                SELECT projection_revision_id,
                       owner_revision,
                       snapshot_as_of,
                       purpose_code,
                       projection_code,
                       owner_page_digest,
                       content_hash
                  FROM hris_performance.prf_workforce_projection_revisions
                 WHERE tenant_id = :tenant
                   AND projection_head_id = :head
                   AND local_revision = :localRevision
                """, tenantParameters(head.tenantId())
                .addValue("head", head.projectionHeadId())
                .addValue("localRevision", head.localRevision()),
                this::revisionRow);
        if (revisions.size() != 1) {
            throw new IllegalStateException(
                    "The current PER workforce projection revision is missing.");
        }
        RevisionRow revision = revisions.getFirst();
        List<PerformanceWorkforceProjection.Member> members = jdbc.query("""
                SELECT worker_public_id,
                       assignment_public_id,
                       workforce_status,
                       organization_public_id,
                       manager_assignment_public_id,
                       job_profile_public_id,
                       grade_public_id
                  FROM hris_performance.prf_workforce_projection_members
                 WHERE tenant_id = :tenant
                   AND projection_head_id = :head
                   AND local_revision = :localRevision
                 ORDER BY assignment_public_id, worker_public_id
                """, tenantParameters(head.tenantId())
                .addValue("head", head.projectionHeadId())
                .addValue("localRevision", head.localRevision()),
                this::memberRow);
        PerformanceWorkforceProjection.Snapshot snapshot =
                new PerformanceWorkforceProjection.Snapshot(
                        head.tenantId(),
                        revision.snapshotAsOf(),
                        revision.ownerRevision(),
                        revision.purposeCode(),
                        WorkforceSnapshotProjection.valueOf(revision.projectionCode()),
                        revision.ownerPageDigest(),
                        revision.contentHash(),
                        members);
        return new PerformanceWorkforceProjection.Head(
                head.tenantId(),
                head.state(),
                head.localRevision(),
                revision.projectionRevisionId(),
                snapshot,
                head.blockedExpectedRevision(),
                head.blockedObservedRevision());
    }

    private void markReadyPreviewsStale(long tenantId) {
        jdbc.update("""
                UPDATE hris_performance.prf_population_previews
                   SET preview_state = 'STALE',
                       aggregate_version = aggregate_version + 1
                 WHERE tenant_id = :tenant
                   AND preview_state = 'READY'
                """, tenantParameters(tenantId));
    }

    private <T> T inTenantTransaction(long tenantId, Supplier<T> operation) {
        T result = transactions.execute(status -> {
            establishTenant(tenantId);
            return operation.get();
        });
        if (result == null) {
            throw new IllegalStateException(
                    "PER workforce projection transaction returned no result.");
        }
        return result;
    }

    private void establishTenant(long tenantId) {
        requireTenant(tenantId);
        String applied = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                tenantParameters(tenantId),
                String.class);
        if (!Long.toString(tenantId).equals(applied)) {
            throw new IllegalStateException(
                    "PER workforce projection tenant scope was not established.");
        }
    }

    private HeadRow headRow(ResultSet result, int ignored) throws SQLException {
        return new HeadRow(
                result.getObject("projection_head_id", UUID.class),
                result.getLong("tenant_id"),
                PerformanceWorkforceProjection.State.valueOf(
                        result.getString("projection_state")),
                result.getLong("current_local_revision"),
                result.getObject("stale_event_id", UUID.class),
                nullableLong(result, "blocked_expected_revision"),
                nullableLong(result, "blocked_observed_revision"));
    }

    private ReceiptRow receiptRow(ResultSet result, int ignored) throws SQLException {
        return new ReceiptRow(
                result.getObject("inbox_receipt_id", UUID.class),
                result.getLong("tenant_id"),
                result.getObject("event_id", UUID.class),
                result.getString("aggregate_type"),
                result.getObject("aggregate_public_id", UUID.class),
                nullableLong(result, "aggregate_revision"),
                result.getString("payload_hash"),
                result.getString("processing_state"),
                result.getString("outcome_code"),
                result.getString("resulting_projection_state"));
    }

    private RevisionRow revisionRow(ResultSet result, int ignored) throws SQLException {
        return new RevisionRow(
                result.getObject("projection_revision_id", UUID.class),
                result.getLong("owner_revision"),
                result.getTimestamp("snapshot_as_of").toInstant(),
                result.getString("purpose_code"),
                result.getString("projection_code"),
                result.getString("owner_page_digest"),
                result.getString("content_hash"));
    }

    private PerformanceWorkforceProjection.Member memberRow(
            ResultSet result,
            int ignored) throws SQLException {
        return new PerformanceWorkforceProjection.Member(
                result.getObject("worker_public_id", UUID.class),
                result.getObject("assignment_public_id", UUID.class),
                result.getString("workforce_status"),
                result.getObject("organization_public_id", UUID.class),
                result.getObject("manager_assignment_public_id", UUID.class),
                result.getObject("job_profile_public_id", UUID.class),
                result.getObject("grade_public_id", UUID.class));
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static MapSqlParameterSource tenantParameters(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows.");
        }
    }

    private static PerformanceWorkforceProjectionException replayNotPending(
            String message) {
        return projectionError(
                PerformanceWorkforceProjectionException.Code.EVENT_REPLAY_NOT_PENDING,
                message);
    }

    private static PerformanceWorkforceProjectionException projectionError(
            PerformanceWorkforceProjectionException.Code code,
            String message) {
        return new PerformanceWorkforceProjectionException(code, message);
    }

    private record HeadRow(
            UUID projectionHeadId,
            long tenantId,
            PerformanceWorkforceProjection.State state,
            long localRevision,
            UUID staleEventId,
            Long blockedExpectedRevision,
            Long blockedObservedRevision) {
    }

    private record ReceiptRow(
            UUID inboxReceiptId,
            long tenantId,
            UUID eventId,
            String aggregateType,
            UUID aggregatePublicId,
            Long aggregateRevision,
            String payloadHash,
            String processingState,
            String outcomeCode,
            String resultingState) {
    }

    private record RevisionRow(
            UUID projectionRevisionId,
            long ownerRevision,
            Instant snapshotAsOf,
            String purposeCode,
            String projectionCode,
            String ownerPageDigest,
            String contentHash) {
    }

    private record PendingProjection(
            PerformanceWorkforceProjection.State state,
            UUID eventId,
            Long requiredAggregateRevision,
            Long observedAggregateRevision) {
    }
}
