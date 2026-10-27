package com.dwp.services.people.hris.performance.workforce;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dwp.services.people.hris.performance.workforce.PerformanceWorkforceProjectionStore.*;

/** Stores projection revisions, members, and invalidation/replay journal evidence. */
final class PerformanceWorkforceProjectionJournal {

    private final NamedParameterJdbcTemplate jdbc;

    PerformanceWorkforceProjectionJournal(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertRevision(
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

    void insertMembers(
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

    void insertReceipt(
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

    Optional<ReceiptRow> receiptByEvent(long tenantId, UUID eventId) {
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

    boolean hasPendingReplay(long tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = :tenant
                   AND processing_state = 'REPLAY_REQUIRED'
                """, tenantParameters(tenantId), Integer.class);
        return count != null && count > 0;
    }

    PendingProjection pendingProjection(long tenantId) {
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

    long aggregateWatermark(InvalidationEvent event) {
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

    InvalidationResult resultForReceipt(
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

    long expectedBeforeGap(ReceiptRow receipt) {
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

    ReceiptRow receiptRow(ResultSet result, int ignored) throws SQLException {
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

    record ReceiptRow(
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

    record PendingProjection(
            PerformanceWorkforceProjection.State state,
            UUID eventId,
            Long requiredAggregateRevision,
            Long observedAggregateRevision) {
    }

    static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    static MapSqlParameterSource tenantParameters(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows.");
        }
    }

    record HeadRow(
            UUID projectionHeadId,
            long tenantId,
            PerformanceWorkforceProjection.State state,
            long localRevision,
            UUID staleEventId,
            Long blockedExpectedRevision,
            Long blockedObservedRevision) {
    }
}
