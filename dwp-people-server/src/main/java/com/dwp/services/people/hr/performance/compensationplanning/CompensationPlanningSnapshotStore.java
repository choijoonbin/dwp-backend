package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningJdbcValues.*;

/** Persists and reads immutable approved compensation snapshots. */
final class CompensationPlanningSnapshotStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final java.util.function.IntConsumer requireCas;

    CompensationPlanningSnapshotStore(NamedParameterJdbcTemplate jdbc,
            java.util.function.IntConsumer requireCas) {
        this.jdbc = jdbc;
        this.requireCas = requireCas;
    }

    long insertSnapshot(
            long tenantId,
            UUID subjectId,
            long cycleInternalId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long expectedVersion,
            UUID approvalReceiptId,
            UUID snapshotId,
            long snapshotRevision,
            List<CompensationPlanningDtos.SnapshotLine> lines,
            String payloadDigest,
            UUID correlationId,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_cmp_cycles
                   SET aggregate_version = aggregate_version + 1,
                       status = 'PUBLISHED', updated_at = :now,
                       correlation_id = :correlationId
                 WHERE tenant_id = :tenantId
                   AND prf_cmp_cycles_internal_id = :cycleInternalId
                   AND aggregate_version = :expectedVersion
                   AND status = 'APPROVED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycleInternalId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId));
        requireCas.accept(updated);
        Long internalId = jdbc.queryForObject("""
                INSERT INTO hris_performance.prf_cmp_approved_snapshots (
                    tenant_id, public_id, aggregate_version,
                    created_at, created_by, updated_at, correlation_id,
                    compensation_cycle_id, approval_receipt_public_id,
                    snapshot_revision, source_version, effective_from,
                    effective_to, line_count, payload_digest)
                VALUES (
                    :tenantId, :snapshotId, 1,
                    :now, :subjectId, :now, :correlationId,
                    :cycleInternalId, :approvalReceiptId,
                    :snapshotRevision, :sourceVersion, :effectiveFrom,
                    :effectiveTo, :lineCount, :payloadDigest)
                RETURNING prf_cmp_approved_snapshots_internal_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("snapshotId", snapshotId)
                .addValue("subjectId", subjectId)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId)
                .addValue("cycleInternalId", cycleInternalId)
                .addValue("approvalReceiptId", approvalReceiptId)
                .addValue("snapshotRevision", snapshotRevision)
                .addValue("sourceVersion", expectedVersion)
                .addValue("effectiveFrom", Date.valueOf(effectiveFrom))
                .addValue("effectiveTo", date(effectiveTo))
                .addValue("lineCount", lines.size())
                .addValue("payloadDigest", payloadDigest), Long.class);
        for (CompensationPlanningDtos.SnapshotLine line : lines) {
            jdbc.update("""
                    INSERT INTO hris_performance.prf_cmp_approved_snapshot_lines (
                        tenant_id, public_id, aggregate_version,
                        created_at, created_by, updated_at, correlation_id,
                        approved_compensation_snapshot_id, line_sequence,
                        worker_public_id, assignment_public_id, component_code,
                        currency_code, approved_amount, effective_from,
                        effective_to, line_digest)
                    VALUES (
                        :tenantId, :lineId, 1,
                        :now, :subjectId, :now, :correlationId,
                        :snapshotInternalId, :lineSequence,
                        :workerId, :assignmentId, :componentCode,
                        :currency, :approvedAmount, :effectiveFrom,
                        :effectiveTo, :lineDigest)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("lineId", line.lineId())
                    .addValue("subjectId", subjectId)
                    .addValue("now", timestamp(now))
                    .addValue("correlationId", correlationId)
                    .addValue("snapshotInternalId", internalId)
                    .addValue("lineSequence", line.lineSequence())
                    .addValue("workerId", line.workerId())
                    .addValue("assignmentId", line.assignmentId())
                    .addValue("componentCode", line.componentCode())
                    .addValue("currency", line.currency())
                    .addValue("approvedAmount", PostedMoney.decimal(line.approvedAmount()))
                    .addValue("effectiveFrom", Date.valueOf(line.effectiveFrom()))
                    .addValue("effectiveTo", date(line.effectiveTo()))
                    .addValue("lineDigest", line.lineDigest()));
        }
        return expectedVersion + 1;
    }

    Optional<CompensationPlanningDtos.ApprovedSnapshot> snapshot(
            long tenantId,
            UUID snapshotId) {
        List<SnapshotHeader> headers = jdbc.query("""
                SELECT snapshot.prf_cmp_approved_snapshots_internal_id,
                       snapshot.public_id, snapshot.approval_receipt_public_id,
                       snapshot.snapshot_revision, snapshot.source_version,
                       snapshot.effective_from, snapshot.effective_to,
                       snapshot.line_count, snapshot.payload_digest,
                       snapshot.correlation_id, snapshot.created_at,
                       cycle.public_id AS cycle_public_id,
                       cycle.aggregate_version,
                       receipt.applied_aggregate_version AS approval_revision
                  FROM hris_performance.prf_cmp_approved_snapshots snapshot
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = snapshot.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id = snapshot.compensation_cycle_id
                  JOIN hris_performance.prf_command_receipts receipt
                    ON receipt.tenant_id = snapshot.tenant_id
                   AND receipt.command_receipt_id = snapshot.approval_receipt_public_id
                 WHERE snapshot.tenant_id = :tenantId
                   AND snapshot.public_id = :snapshotId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("snapshotId", snapshotId), (rs, rowNum) -> new SnapshotHeader(
                rs.getLong("prf_cmp_approved_snapshots_internal_id"),
                uuid(rs, "public_id"), uuid(rs, "approval_receipt_public_id"),
                rs.getLong("approval_revision"), uuid(rs, "cycle_public_id"),
                rs.getLong("snapshot_revision"), rs.getLong("source_version"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                rs.getInt("line_count"), rs.getString("payload_digest"),
                rs.getLong("aggregate_version"), uuid(rs, "correlation_id"),
                instant(rs, "created_at")));
        if (headers.size() != 1) return Optional.empty();
        SnapshotHeader header = headers.getFirst();
        List<CompensationPlanningDtos.SnapshotLine> lines = jdbc.query("""
                SELECT public_id, line_sequence, worker_public_id,
                       assignment_public_id, component_code, currency_code,
                       approved_amount, effective_from, effective_to, line_digest
                  FROM hris_performance.prf_cmp_approved_snapshot_lines
                 WHERE tenant_id = :tenantId
                   AND approved_compensation_snapshot_id = :snapshotInternalId
                 ORDER BY line_sequence
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("snapshotInternalId", header.internalId()), (rs, rowNum) ->
                new CompensationPlanningDtos.SnapshotLine(
                        uuid(rs, "public_id"), rs.getInt("line_sequence"), null,
                        uuid(rs, "worker_public_id"), uuid(rs, "assignment_public_id"),
                        rs.getString("component_code"), rs.getString("currency_code"),
                        money(rs.getBigDecimal("approved_amount")),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getString("line_digest")));
        return Optional.of(new CompensationPlanningDtos.ApprovedSnapshot(
                header.approvalReceiptId(), header.approvalRevision(), header.cycleId(),
                header.effectiveFrom(), header.effectiveTo(), header.lineCount(), lines,
                header.payloadDigest(), header.cycleId(), header.snapshotId(),
                header.snapshotRevision(), header.sourceVersion(), header.aggregateVersion(),
                header.correlationId(), header.occurredAt()));
    }

    Optional<CompensationPlanningDtos.ApprovedSnapshot> snapshotForCycle(
            long tenantId,
            UUID cycleId) {
        List<UUID> ids = jdbc.query("""
                SELECT snapshot.public_id
                  FROM hris_performance.prf_cmp_approved_snapshots snapshot
                  JOIN hris_performance.prf_cmp_cycles cycle
                    ON cycle.tenant_id = snapshot.tenant_id
                   AND cycle.prf_cmp_cycles_internal_id = snapshot.compensation_cycle_id
                 WHERE snapshot.tenant_id = :tenantId
                   AND cycle.public_id = :cycleId
                 ORDER BY snapshot.snapshot_revision DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleId", cycleId), (rs, rowNum) -> uuid(rs, "public_id"));
        return ids.isEmpty() ? Optional.empty() : snapshot(tenantId, ids.getFirst());
    }

    long nextSnapshotRevision(long tenantId, long cycleInternalId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(snapshot_revision), 0) + 1
                  FROM hris_performance.prf_cmp_approved_snapshots
                 WHERE tenant_id = :tenantId
                   AND compensation_cycle_id = :cycleInternalId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("cycleInternalId", cycleInternalId), Long.class);
        return value == null ? 1 : value;
    }

    private record SnapshotHeader(
            long internalId,
            UUID snapshotId,
            UUID approvalReceiptId,
            long approvalRevision,
            UUID cycleId,
            long snapshotRevision,
            long sourceVersion,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            int lineCount,
            String payloadDigest,
            long aggregateVersion,
            UUID correlationId,
            Instant occurredAt) {
    }
}
