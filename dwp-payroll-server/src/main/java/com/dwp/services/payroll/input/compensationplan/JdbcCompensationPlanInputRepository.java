package com.dwp.services.payroll.input.compensationplan;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1.ApprovedCompensationPlanLine;
import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.MATERIALIZED_EVENT;
import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.Outcome.APPLIED;
import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.Outcome.QUARANTINED;
import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.Outcome.REPLAYED;

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll-compensation-plan-input.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcCompensationPlanInputRepository
        implements CompensationPlanInputRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    JdbcCompensationPlanInputRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.mapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    public Registration register(
            CompensationPlanInputContracts.Publication publication,
            Instant now) {
        long tenantId = publication.envelope().tenantId();
        bindTenant(tenantId);
        int inserted = jdbc.update("""
                INSERT INTO pay_compensation_plan_event_inbox (
                    tenant_id, event_id, aggregate_id, aggregate_sequence,
                    event_type, schema_version, snapshot_id, snapshot_revision,
                    snapshot_payload_digest, correlation_id, envelope_digest,
                    event_envelope, received_at)
                VALUES (
                    :tenantId, :eventId, :aggregateId, :aggregateSequence,
                    :eventType, 2, :snapshotId, :snapshotRevision,
                    :payloadDigest, :correlationId, :envelopeDigest,
                    CAST(:eventEnvelope AS jsonb), :receivedAt)
                ON CONFLICT (tenant_id, event_id) DO NOTHING
                """, eventParameters(publication, now));
        if (inserted == 1) return Registration.NEW;
        String storedDigest = jdbc.queryForObject("""
                SELECT envelope_digest
                  FROM pay_compensation_plan_event_inbox
                 WHERE tenant_id = :tenantId AND event_id = :eventId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("eventId", publication.envelope().id()), String.class);
        return publication.envelopeDigest().equals(storedDigest)
                ? Registration.IDENTICAL
                : Registration.EVENT_ID_REUSED;
    }

    @Override
    public Optional<CompensationPlanInputContracts.Receipt> receipt(
            long tenantId, UUID sourceEventId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT tenant_id, receipt_id, source_event_id, aggregate_id,
                       aggregate_sequence, snapshot_id, snapshot_revision,
                       snapshot_payload_digest, line_count, outbox_event_id,
                       applied_at
                  FROM pay_compensation_plan_input_receipts
                 WHERE tenant_id = :tenantId AND source_event_id = :sourceEventId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceEventId", sourceEventId),
                (result, row) -> new CompensationPlanInputContracts.Receipt(
                        result.getLong("tenant_id"),
                        result.getObject("receipt_id", UUID.class),
                        result.getObject("source_event_id", UUID.class),
                        result.getObject("aggregate_id", UUID.class),
                        result.getLong("aggregate_sequence"),
                        result.getObject("snapshot_id", UUID.class),
                        result.getLong("snapshot_revision"),
                        result.getString("snapshot_payload_digest"),
                        result.getInt("line_count"),
                        result.getObject("outbox_event_id", UUID.class),
                        result.getObject("applied_at", OffsetDateTime.class).toInstant()))
                .stream().findFirst();
    }

    @Override
    public Optional<Attempt> latestAttempt(long tenantId, UUID sourceEventId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT outcome, failure_code, attempt_number
                  FROM pay_compensation_plan_event_attempts
                 WHERE tenant_id = :tenantId AND event_id = :sourceEventId
                 ORDER BY attempt_number DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceEventId", sourceEventId),
                (result, row) -> new Attempt(
                        result.getString("outcome"),
                        result.getString("failure_code"),
                        result.getInt("attempt_number")))
                .stream().findFirst();
    }

    @Override
    public ApplyResult apply(
            CompensationPlanInputContracts.Publication publication,
            CompensationPlanInputContracts.MaterializedSnapshot materialized,
            String attemptKind,
            String actorType,
            String actorId,
            Instant now) {
        long tenantId = publication.envelope().tenantId();
        UUID eventId = publication.envelope().id();
        UUID aggregateId = publication.event().aggregateId();
        bindTenant(tenantId);
        lock(tenantId, aggregateId);
        Optional<CompensationPlanInputContracts.Receipt> existing =
                receipt(tenantId, eventId);
        if (existing.isPresent()) {
            return new ApplyResult(existing.orElseThrow().result(REPLAYED), false);
        }

        Long latestSequence = jdbc.queryForObject("""
                SELECT MAX(aggregate_sequence)
                  FROM pay_compensation_plan_input_receipts
                 WHERE tenant_id = :tenantId AND aggregate_id = :aggregateId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("aggregateId", aggregateId), Long.class);
        if (latestSequence != null
                && publication.envelope().aggregateSequence() <= latestSequence) {
            return new ApplyResult(quarantineRegistered(
                    publication, attemptKind, actorType, actorId,
                    "STALE_AGGREGATE_SEQUENCE",
                    "A newer or equal aggregate sequence is already applied.", now), false);
        }
        Long latestRevision = jdbc.queryForObject("""
                SELECT MAX(snapshot_revision)
                  FROM pay_compensation_plan_input_receipts
                 WHERE tenant_id = :tenantId AND aggregate_id = :aggregateId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("aggregateId", aggregateId), Long.class);
        long expectedRevision = latestRevision == null ? 1L : latestRevision + 1L;
        if (publication.event().snapshotRevision() != expectedRevision) {
            return new ApplyResult(quarantineRegistered(
                    publication, attemptKind, actorType, actorId,
                    "SNAPSHOT_REVISION_PREDECESSOR_MISSING",
                    "Snapshot revision is not the direct successor of the applied revision.",
                    now), false);
        }

        UUID receiptId = deterministic("receipt", tenantId, eventId);
        UUID outboxEventId = deterministic("outbox", tenantId, eventId);
        CompensationPlanInputContracts.Receipt receipt =
                new CompensationPlanInputContracts.Receipt(
                        tenantId, receiptId, eventId, aggregateId,
                        publication.envelope().aggregateSequence(),
                        materialized.snapshot().snapshotId(),
                        materialized.snapshot().snapshotRevision(),
                        materialized.snapshot().payloadDigest(),
                        materialized.snapshot().lineCount(), outboxEventId, now);
        insertInputs(publication, materialized, now);
        insertReceipt(receipt);
        insertOutbox(publication, receipt);
        insertAttempt(
                tenantId, eventId, attemptKind, actorType, actorId,
                "APPLIED", null, null, now);
        return new ApplyResult(receipt.result(APPLIED), true);
    }

    @Override
    public CompensationPlanInputContracts.ConsumptionResult quarantine(
            CompensationPlanInputContracts.Publication publication,
            String attemptKind,
            String actorType,
            String actorId,
            String failureCode,
            String failureDetail,
            Instant now) {
        bindTenant(publication.envelope().tenantId());
        lock(publication.envelope().tenantId(), publication.event().aggregateId());
        Optional<CompensationPlanInputContracts.Receipt> applied = receipt(
                publication.envelope().tenantId(), publication.envelope().id());
        if (applied.isPresent()) return applied.orElseThrow().result(REPLAYED);
        return quarantineRegistered(
                publication, attemptKind, actorType, actorId,
                failureCode, failureDetail, now);
    }

    @Override
    public CompensationPlanInputContracts.ConsumptionResult quarantineEventIdReuse(
            CompensationPlanInputContracts.Publication publication,
            String actorType,
            String actorId,
            Instant now) {
        long tenantId = publication.envelope().tenantId();
        UUID eventId = publication.envelope().id();
        bindTenant(tenantId);
        // This path intentionally does not return an existing receipt. A second,
        // differently sealed envelope using an applied event ID is a security
        // boundary violation, never an idempotent delivery of the first event.
        UUID storedAggregateId = jdbc.queryForObject("""
                SELECT aggregate_id
                  FROM pay_compensation_plan_event_inbox
                 WHERE tenant_id = :tenantId AND event_id = :eventId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("eventId", eventId), UUID.class);
        lock(tenantId, storedAggregateId);
        String failureDetail = "The source event ID was reused with incoming envelope digest "
                + publication.envelopeDigest() + '.';
        return quarantineRegistered(
                publication, "CONSUME", actorType, actorId,
                "EVENT_ID_REUSED", failureDetail, now);
    }

    @Override
    public DomainEventEnvelope quarantinedEvent(long tenantId, UUID sourceEventId) {
        bindTenant(tenantId);
        Attempt attempt = latestAttempt(tenantId, sourceEventId)
                .filter(value -> "QUARANTINED".equals(value.outcome()))
                .orElseThrow(() -> new IllegalStateException(
                        "Only a currently quarantined PAY-016 event can be replayed."));
        if (attempt.attemptNumber() <= 0) throw new IllegalStateException("invalid attempt");
        String value = jdbc.queryForObject("""
                SELECT event_envelope::text
                  FROM pay_compensation_plan_event_inbox
                 WHERE tenant_id = :tenantId AND event_id = :sourceEventId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceEventId", sourceEventId), String.class);
        try {
            return mapper.readValue(value, DomainEventEnvelope.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored PAY-016 envelope is invalid.", exception);
        }
    }

    @Override
    public void auditReplay(
            long tenantId,
            UUID sourceEventId,
            CompensationPlanInputContracts.ReplayAuthority authority,
            Instant now) {
        bindTenant(tenantId);
        if (receipt(tenantId, sourceEventId).isPresent()) {
            throw new IllegalStateException(
                    "An already applied PAY-016 event cannot be replayed.");
        }
        if (latestAttempt(tenantId, sourceEventId)
                .filter(value -> "QUARANTINED".equals(value.outcome()))
                .isEmpty()) {
            throw new IllegalStateException("PAY-016 replay target is not quarantined.");
        }
        jdbc.update("""
                INSERT INTO pay_compensation_plan_input_replay_audit (
                    tenant_id, audit_id, source_event_id, actor_type, actor_id,
                    authority, reason, prior_outcome, occurred_at)
                VALUES (
                    :tenantId, :auditId, :sourceEventId, :actorType, :actorId,
                    :authority, :reason, 'QUARANTINED', :occurredAt)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("auditId", UUID.randomUUID())
                .addValue("sourceEventId", sourceEventId)
                .addValue("actorType", authority.actorType())
                .addValue("actorId", authority.actorId())
                .addValue("authority", CompensationPlanInputContracts.REPLAY_AUTHORITY)
                .addValue("reason", authority.reason())
                .addValue("occurredAt", at(now)));
    }

    private CompensationPlanInputContracts.ConsumptionResult quarantineRegistered(
            CompensationPlanInputContracts.Publication publication,
            String attemptKind,
            String actorType,
            String actorId,
            String failureCode,
            String failureDetail,
            Instant now) {
        String detail = failureDetail == null ? null
                : failureDetail.substring(0, Math.min(800, failureDetail.length()));
        insertAttempt(
                publication.envelope().tenantId(), publication.envelope().id(),
                attemptKind, actorType, actorId, "QUARANTINED",
                failureCode, detail, now);
        return new CompensationPlanInputContracts.ConsumptionResult(
                QUARANTINED, publication.envelope().id(), null,
                publication.event().snapshotId(),
                publication.event().snapshotRevision(), 0, failureCode);
    }

    private void insertInputs(
            CompensationPlanInputContracts.Publication publication,
            CompensationPlanInputContracts.MaterializedSnapshot materialized,
            Instant now) {
        List<MapSqlParameterSource> rows = materialized.lines().stream()
                .map(line -> inputParameters(publication, materialized, line, now))
                .toList();
        jdbc.batchUpdate("""
                INSERT INTO pay_compensation_plan_inputs (
                    tenant_id, public_id, source_event_id, snapshot_id,
                    snapshot_revision, snapshot_payload_digest, plan_id, cycle_id,
                    approval_receipt_id, approval_revision, source_version,
                    line_sequence, worker_id, assignment_id, component_code,
                    currency, approved_amount, effective_from, effective_to,
                    payload_digest, recorded_at)
                VALUES (
                    :tenantId, :publicId, :sourceEventId, :snapshotId,
                    :snapshotRevision, :snapshotPayloadDigest, :planId, :cycleId,
                    :approvalReceiptId, :approvalRevision, :sourceVersion,
                    :lineSequence, :workerId, :assignmentId, :componentCode,
                    :currency, :approvedAmount, :effectiveFrom, :effectiveTo,
                    :payloadDigest, :recordedAt)
                """, rows.toArray(MapSqlParameterSource[]::new));
    }

    private void insertReceipt(CompensationPlanInputContracts.Receipt receipt) {
        jdbc.update("""
                INSERT INTO pay_compensation_plan_input_receipts (
                    tenant_id, receipt_id, source_event_id, aggregate_id,
                    aggregate_sequence, snapshot_id, snapshot_revision,
                    snapshot_payload_digest, line_count, outbox_event_id, applied_at)
                VALUES (
                    :tenantId, :receiptId, :sourceEventId, :aggregateId,
                    :aggregateSequence, :snapshotId, :snapshotRevision,
                    :payloadDigest, :lineCount, :outboxEventId, :appliedAt)
                """, new MapSqlParameterSource()
                .addValue("tenantId", receipt.tenantId())
                .addValue("receiptId", receipt.receiptId())
                .addValue("sourceEventId", receipt.sourceEventId())
                .addValue("aggregateId", receipt.aggregateId())
                .addValue("aggregateSequence", receipt.aggregateSequence())
                .addValue("snapshotId", receipt.snapshotId())
                .addValue("snapshotRevision", receipt.snapshotRevision())
                .addValue("payloadDigest", receipt.payloadDigest())
                .addValue("lineCount", receipt.lineCount())
                .addValue("outboxEventId", receipt.outboxEventId())
                .addValue("appliedAt", at(receipt.appliedAt())));
    }

    private void insertOutbox(
            CompensationPlanInputContracts.Publication publication,
            CompensationPlanInputContracts.Receipt receipt) {
        Map<String, Object> payload = Map.of(
                "receiptId", receipt.receiptId(),
                "sourceEventId", receipt.sourceEventId(),
                "snapshotId", receipt.snapshotId(),
                "snapshotRevision", receipt.snapshotRevision(),
                "payloadDigest", receipt.payloadDigest(),
                "lineCount", receipt.lineCount());
        jdbc.update("""
                INSERT INTO pay_compensation_plan_input_outbox_events (
                    tenant_id, event_id, receipt_id, aggregate_id,
                    aggregate_sequence, event_type, schema_version,
                    correlation_id, event_payload, occurred_at)
                VALUES (
                    :tenantId, :eventId, :receiptId, :aggregateId,
                    :aggregateSequence, :eventType, 1,
                    :correlationId, CAST(:eventPayload AS jsonb), :occurredAt)
                """, new MapSqlParameterSource()
                .addValue("tenantId", receipt.tenantId())
                .addValue("eventId", receipt.outboxEventId())
                .addValue("receiptId", receipt.receiptId())
                .addValue("aggregateId", receipt.aggregateId())
                .addValue("aggregateSequence", receipt.aggregateSequence())
                .addValue("eventType", MATERIALIZED_EVENT)
                .addValue("correlationId", publication.event().correlationId())
                .addValue("eventPayload", json(payload))
                .addValue("occurredAt", at(receipt.appliedAt())));
    }

    private void insertAttempt(
            long tenantId,
            UUID eventId,
            String attemptKind,
            String actorType,
            String actorId,
            String outcome,
            String failureCode,
            String failureDetail,
            Instant now) {
        Integer current = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number), 0)
                  FROM pay_compensation_plan_event_attempts
                 WHERE tenant_id = :tenantId AND event_id = :eventId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("eventId", eventId), Integer.class);
        jdbc.update("""
                INSERT INTO pay_compensation_plan_event_attempts (
                    tenant_id, attempt_id, event_id, attempt_number,
                    attempt_kind, outcome, failure_code, failure_detail,
                    actor_type, actor_id, occurred_at)
                VALUES (
                    :tenantId, :attemptId, :eventId, :attemptNumber,
                    :attemptKind, :outcome, :failureCode, :failureDetail,
                    :actorType, :actorId, :occurredAt)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", UUID.randomUUID())
                .addValue("eventId", eventId)
                .addValue("attemptNumber", current == null ? 1 : current + 1)
                .addValue("attemptKind", attemptKind)
                .addValue("outcome", outcome)
                .addValue("failureCode", failureCode)
                .addValue("failureDetail", failureDetail)
                .addValue("actorType", actorType)
                .addValue("actorId", actorId)
                .addValue("occurredAt", at(now)));
    }

    private MapSqlParameterSource eventParameters(
            CompensationPlanInputContracts.Publication publication,
            Instant now) {
        return new MapSqlParameterSource()
                .addValue("tenantId", publication.envelope().tenantId())
                .addValue("eventId", publication.envelope().id())
                .addValue("aggregateId", publication.event().aggregateId())
                .addValue("aggregateSequence", publication.event().aggregateVersion())
                .addValue("eventType", publication.envelope().type())
                .addValue("snapshotId", publication.event().snapshotId())
                .addValue("snapshotRevision", publication.event().snapshotRevision())
                .addValue("payloadDigest", publication.event().payloadDigest())
                .addValue("correlationId", publication.event().correlationId())
                .addValue("envelopeDigest", publication.envelopeDigest())
                .addValue("eventEnvelope", publication.envelopeJson())
                .addValue("receivedAt", at(now));
    }

    private MapSqlParameterSource inputParameters(
            CompensationPlanInputContracts.Publication publication,
            CompensationPlanInputContracts.MaterializedSnapshot materialized,
            ApprovedCompensationPlanLine line,
            Instant now) {
        return new MapSqlParameterSource()
                .addValue("tenantId", publication.envelope().tenantId())
                .addValue("publicId", line.lineId())
                .addValue("sourceEventId", publication.envelope().id())
                .addValue("snapshotId", materialized.snapshot().snapshotId())
                .addValue("snapshotRevision", materialized.snapshot().snapshotRevision())
                .addValue("snapshotPayloadDigest", materialized.snapshot().payloadDigest())
                .addValue("planId", materialized.snapshot().planId())
                .addValue("cycleId", materialized.snapshot().cycleId())
                .addValue("approvalReceiptId", materialized.snapshot().approvalReceiptId())
                .addValue("approvalRevision", publication.event().approvalRevision())
                .addValue("sourceVersion", materialized.snapshot().sourceVersion())
                .addValue("lineSequence", line.lineSequence())
                .addValue("workerId", line.workerId())
                .addValue("assignmentId", line.assignmentId())
                .addValue("componentCode", line.componentCode())
                .addValue("currency", line.currency())
                .addValue("approvedAmount", line.approvedAmount())
                .addValue("effectiveFrom", line.effectiveFrom())
                .addValue("effectiveTo", line.effectiveTo())
                .addValue("payloadDigest", line.lineDigest())
                .addValue("recordedAt", at(now));
    }

    private void lock(long tenantId, UUID aggregateId) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(:key, 16016))",
                new MapSqlParameterSource(
                        "key", tenantId + ":" + aggregateId));
    }

    private void bindTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("PAY-016 JSON serialization failed", exception);
        }
    }

    private static OffsetDateTime at(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static UUID deterministic(String kind, long tenantId, UUID eventId) {
        return UUID.nameUUIDFromBytes(
                ("BASE-TFR-PAY-016:" + kind + ':' + tenantId + ':' + eventId)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
