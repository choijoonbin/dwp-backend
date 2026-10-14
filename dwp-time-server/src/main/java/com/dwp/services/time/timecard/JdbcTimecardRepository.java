package com.dwp.services.time.timecard;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.timecard.TimecardModels.CommandKind;
import com.dwp.services.time.timecard.TimecardModels.PopulationKind;
import com.dwp.services.time.timecard.TimecardModels.StoredTimecardReceipt;
import com.dwp.services.time.timecard.TimecardModels.TimeCommandReceiptV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardCommand;
import com.dwp.services.time.timecard.TimecardModels.TimecardCorrectionEntryV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardMaterialization;
import com.dwp.services.time.timecard.TimecardModels.TimecardRecord;
import com.dwp.services.time.timecard.TimecardModels.TimecardState;
import com.dwp.services.time.timecard.TimecardModels.TimecardV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic JDBC owner repository for CAS, receipts, corrections, and decision outbox facts. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.time.timecard.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class JdbcTimecardRepository implements TimecardRepository {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('dwp.tenant_id', ?, true)";
    private static final String CORRECTION_POLICY_DIGEST = TimecardDigests.sha256(
            "TIMECARD_CORRECTION_POLICY_V1");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final TimecardStateMachine states = new TimecardStateMachine();

    public JdbcTimecardRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(transactionManager, "transactionManager");
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(15);
    }

    @Override
    public Optional<TimecardRecord> find(long tenantId, UUID timecardId) {
        requireTenant(tenantId);
        Objects.requireNonNull(timecardId, "timecardId");
        return inTenant(tenantId, () -> card(tenantId, timecardId, false).map(CardRow::record));
    }

    @Override
    public TimeCommandReceiptV1 execute(TimecardCommand command) {
        Objects.requireNonNull(command, "command");
        return inTenant(command.authority().tenantId(), () -> executeInTenant(command));
    }

    @Override
    public Optional<StoredTimecardReceipt> findReceipt(long tenantId, UUID receiptId) {
        requireTenant(tenantId);
        Objects.requireNonNull(receiptId, "receiptId");
        return inTenant(tenantId, () -> receipts("""
                SELECT r.public_id, r.command_type, r.originating_action,
                       r.originating_population_kind,
                       r.originating_required_permission,
                       r.timecard_public_id, r.status, r.request_digest,
                       r.accepted_at, r.completed_at, r.result_ref,
                       r.subject_principal_public_id, c.worker_public_id,
                       r.population_scope_digest, r.field_policy_revision,
                       r.purpose_code, r.authorization_revision
                  FROM tme_timecard_command_receipts r
                  JOIN tme_timecards c ON c.tenant_id = r.tenant_id
                   AND c.public_id = r.timecard_public_id
                 WHERE r.tenant_id = ? AND r.public_id = ?
                """, tenantId, receiptId).stream().findFirst());
    }

    @Override
    public TimecardRecord materialize(TimecardMaterialization materialization) {
        Objects.requireNonNull(materialization, "materialization");
        return inTenant(materialization.tenantId(), () -> materializeInTenant(materialization));
    }

    private TimeCommandReceiptV1 executeInTenant(TimecardCommand command) {
        long tenantId = command.authority().tenantId();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", ignored -> {},
                "tim-timecard-idem:" + tenantId + ":"
                        + command.authority().subjectPrincipalPublicId() + ":"
                        + command.kind().originatingAction() + ":" + command.idempotencyKey());

        Optional<ExistingReceipt> existing = existingReceipt(command);
        if (existing.isPresent()) {
            ExistingReceipt replay = existing.get();
            if (!replay.requestDigest().equals(command.requestDigest())
                    || !replay.timecardId().equals(command.timecardId())
                    || !replay.commandType().equals(command.kind().commandType())
                    || replay.originatingPopulationKind()
                            != command.authority().populationKind()
                    || !replay.originatingRequiredPermission().equals(
                            command.kind().requiredPermission(
                                    command.authority().populationKind()))) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The Idempotency-Key is already sealed to a different Timecard command.");
            }
            return replay.receipt();
        }

        CardRow card = card(tenantId, command.timecardId(), true)
                .orElseThrow(JdbcTimecardRepository::opaqueNotFound);
        authorizeLocked(command, card);
        if (card.periodClosed()) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The Timecard period is closed.");
        }
        if (card.version() != command.expectedVersion()) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The Timecard version changed.");
        }

        UUID receiptId = UUID.randomUUID();
        long nextVersion = card.version() + 1;
        TimecardState nextState;
        int nextTotal = card.totalMinutes();
        long nextLedgerRevision = card.ledgerRevision();
        UUID correctionId = null;
        if (command.kind() == CommandKind.CORRECT) {
            nextState = states.correct(card.state());
            long delta = command.correctionEntries().stream()
                    .mapToLong(TimecardCorrectionEntryV1::minutes).sum();
            long candidate = (long) card.totalMinutes() + delta;
            if (candidate < 0 || candidate > Integer.MAX_VALUE) {
                throw new BaseException(ErrorCode.VALIDATION_ERROR,
                        "The corrected Timecard total is outside its supported range.");
            }
            nextTotal = (int) candidate;
            correctionId = TimecardDigests.stableUuid(
                    "TimecardCorrection.v1", tenantId, card.timecardId(), nextVersion, receiptId);
            nextLedgerRevision = appendCorrections(command, card, nextVersion, correctionId);
        } else if (command.kind() == CommandKind.SUBMIT) {
            nextState = states.submit(card.state());
        } else {
            nextState = states.decide(card.state(), Objects.requireNonNull(command.decision()));
        }

        int updated = jdbc.update("""
                UPDATE tme_timecards
                   SET state = ?, total_minutes = ?, ledger_revision = ?,
                       submitted_by_subject_public_id = CASE WHEN ? = 'SUBMIT'
                           THEN ? ELSE submitted_by_subject_public_id END,
                       latest_correction_maker_subject_public_id = CASE WHEN ? = 'CORRECT'
                           THEN ? ELSE latest_correction_maker_subject_public_id END,
                       decided_by_subject_public_id = CASE WHEN ? = 'DECIDE'
                           THEN ? ELSE decided_by_subject_public_id END,
                       decided_at = CASE WHEN ? = 'DECIDE' THEN ? ELSE decided_at END,
                       row_version = ?, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND public_id = ? AND row_version = ?
                """, nextState.name(), nextTotal, nextLedgerRevision,
                command.kind().name(), command.authority().subjectPrincipalPublicId(),
                command.kind().name(), command.authority().subjectPrincipalPublicId(),
                command.kind().name(), command.authority().subjectPrincipalPublicId(),
                command.kind().name(), Timestamp.from(command.occurredAt()),
                nextVersion, Timestamp.from(command.occurredAt()), command.authority().actorId(),
                tenantId, card.timecardId(), command.expectedVersion());
        if (updated != 1) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The Timecard version changed.");
        }

        String resultRef = "timecard:" + card.timecardId() + ":version:" + nextVersion;
        TimeCommandReceiptV1 receipt = new TimeCommandReceiptV1(
                receiptId, command.kind().commandType(), command.kind().originatingAction(),
                card.timecardId(), "SUCCEEDED", command.requestDigest(),
                command.occurredAt(), command.occurredAt(), resultRef);
        insertReceipt(command, card, receipt, nextVersion);
        insertVersion(command, card, receiptId, nextVersion, nextState,
                nextTotal, nextLedgerRevision, correctionId);
        if (command.kind() == CommandKind.DECIDE) {
            UUID decisionId = insertDecision(command, card, receiptId, nextVersion, nextState);
            insertDecisionOutbox(command, card, receiptId, decisionId,
                    nextVersion, nextState, nextLedgerRevision);
        }
        return receipt;
    }

    private Optional<ExistingReceipt> existingReceipt(TimecardCommand command) {
        List<ExistingReceipt> found = jdbc.query("""
                SELECT public_id, command_type, originating_action,
                       originating_population_kind, originating_required_permission,
                       timecard_public_id, status, request_digest,
                       accepted_at, completed_at, result_ref
                  FROM tme_timecard_command_receipts
                 WHERE tenant_id = ? AND subject_principal_public_id = ?
                   AND originating_action = ? AND idempotency_key = ?
                """, (row, ignored) -> {
                    UUID receiptId = row.getObject("public_id", UUID.class);
                    UUID timecardId = row.getObject("timecard_public_id", UUID.class);
                    String commandType = row.getString("command_type");
                    String action = row.getString("originating_action");
                    String digest = row.getString("request_digest").trim();
                    return new ExistingReceipt(
                            timecardId, commandType,
                            PopulationKind.valueOf(
                                    row.getString("originating_population_kind")),
                            row.getString("originating_required_permission"), digest,
                            new TimeCommandReceiptV1(
                                    receiptId, commandType, action, timecardId,
                                    row.getString("status"), digest,
                                    row.getTimestamp("accepted_at").toInstant(),
                                    instant(row.getTimestamp("completed_at")),
                                    row.getString("result_ref")));
                }, command.authority().tenantId(),
                command.authority().subjectPrincipalPublicId(),
                command.kind().originatingAction(), command.idempotencyKey());
        return found.stream().findFirst();
    }

    private void authorizeLocked(TimecardCommand command, CardRow card) {
        if (!command.authority().authorizedWorkerPublicIds().contains(card.workerId())) {
            throw opaqueNotFound();
        }
        if (command.kind() == CommandKind.DECIDE) {
            UUID subject = command.authority().subjectPrincipalPublicId();
            if (command.authority().actorWorkerPublicId().equals(card.workerId())
                    || subject.equals(card.submittedBy())
                    || subject.equals(card.latestCorrectionMaker())) {
                throw new BaseException(ErrorCode.SOD_CONFLICT,
                        "The worker, submitter, or latest correction maker cannot decide this Timecard.");
            }
        }
    }

    private long appendCorrections(
            TimecardCommand command, CardRow card, long version, UUID correctionId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> {},
                "tim-ledger:" + command.authority().tenantId() + ":" + card.workerId());
        long lastRevision = card.ledgerRevision();
        int sequence = 0;
        for (TimecardCorrectionEntryV1 entry : command.correctionEntries()) {
            if (entry.workDate().isBefore(card.periodStart())
                    || entry.workDate().isAfter(card.periodEnd())) {
                throw new BaseException(ErrorCode.VALIDATION_ERROR,
                        "Correction entries must fall within the Timecard period.");
            }
            lastRevision = allocateLedgerRevision(
                    command.authority().tenantId(), card.workerId());
            UUID ledgerEntryId = TimecardDigests.stableUuid(
                    "TimecardCorrectionLedger.v1", correctionId, ++sequence);
            jdbc.update("""
                    INSERT INTO tme_time_ledger_entries (
                        public_id, tenant_id, worker_public_id, local_work_date, pay_code,
                        unit, quantity, source_type, source_public_id,
                        interpretation_attempt_public_id, reverses_entry_public_id,
                        reason_code, ledger_revision, input_digest, rule_digest,
                        posted_at, posted_by, correlation_id)
                    VALUES (?, ?, ?, ?, ?, 'MINUTE', ?, 'MANUAL_CORRECTION', ?,
                            NULL, NULL, ?, ?, ?, ?, ?, ?, ?)
                    """, ledgerEntryId, command.authority().tenantId(), card.workerId(),
                    entry.workDate(), entry.payCode(), entry.minutes(), correctionId,
                    command.reasonCode(), lastRevision, command.requestDigest(),
                    CORRECTION_POLICY_DIGEST, Timestamp.from(command.occurredAt()),
                    command.authority().actorId(), command.authority().correlationId());
            jdbc.update("""
                    INSERT INTO tme_timecard_lines (
                        public_id, tenant_id, timecard_public_id, timecard_version,
                        line_sequence, ledger_entry_public_id, work_date, pay_code,
                        minutes, source_type, correction_public_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL_CORRECTION', ?, ?)
                    """, TimecardDigests.stableUuid("TimecardLine.v1", correctionId, sequence),
                    command.authority().tenantId(), card.timecardId(), version, sequence,
                    ledgerEntryId, entry.workDate(), entry.payCode(), entry.minutes(),
                    correctionId, Timestamp.from(command.occurredAt()));
        }
        return lastRevision;
    }

    private long allocateLedgerRevision(long tenantId, UUID workerId) {
        Long revision = jdbc.queryForObject("""
                INSERT INTO tme_time_ledger_revision_counters (
                    tenant_id, worker_public_id, current_revision)
                VALUES (?, ?, 1)
                ON CONFLICT (tenant_id, worker_public_id)
                DO UPDATE SET current_revision =
                    tme_time_ledger_revision_counters.current_revision + 1
                RETURNING current_revision
                """, Long.class, tenantId, workerId);
        return Objects.requireNonNull(revision, "ledger revision allocation returned null");
    }

    private void insertReceipt(
            TimecardCommand command,
            CardRow card,
            TimeCommandReceiptV1 receipt,
            long version) {
        jdbc.update("""
                INSERT INTO tme_timecard_command_receipts (
                    public_id, tenant_id, idempotency_key, command_type,
                    originating_action, originating_population_kind,
                    originating_required_permission, actor_id,
                    subject_principal_public_id,
                    worker_public_id, population_public_id, population_revision,
                    grant_revision, population_scope_digest, field_policy_revision,
                    purpose_code, authorization_revision, request_digest,
                    timecard_public_id, aggregate_revision, status, result_ref,
                    correlation_id, accepted_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'SUCCEEDED', ?, ?, ?, ?)
                """, receipt.receiptId(), command.authority().tenantId(),
                command.idempotencyKey(), command.kind().commandType(),
                command.kind().originatingAction(), command.authority().populationKind().name(),
                command.kind().requiredPermission(command.authority().populationKind()),
                command.authority().actorId(),
                command.authority().subjectPrincipalPublicId(), card.workerId(),
                command.authority().populationPublicId(), command.authority().populationRevision(),
                command.authority().grantRevision(), command.authority().populationScopeDigest(),
                command.authority().fieldPolicyRevision(), command.authority().purposeCode(),
                command.authority().authorizationRevision(), command.requestDigest(),
                card.timecardId(), version, receipt.resultRef(),
                command.authority().correlationId(), Timestamp.from(receipt.acceptedAt()),
                Timestamp.from(receipt.completedAt()));
    }

    private void insertVersion(
            TimecardCommand command,
            CardRow card,
            UUID receiptId,
            long version,
            TimecardState state,
            int total,
            long ledgerRevision,
            UUID correctionId) {
        jdbc.update("""
                INSERT INTO tme_timecard_versions (
                    public_id, tenant_id, timecard_public_id, version, state,
                    total_minutes, ledger_revision, transition_type, reason_code,
                    correction_public_id, command_receipt_public_id,
                    actor_id, actor_subject_public_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, TimecardDigests.stableUuid(
                        "TimecardVersion.v1", command.authority().tenantId(),
                        card.timecardId(), version),
                command.authority().tenantId(), card.timecardId(), version,
                state.name(), total, ledgerRevision, command.kind().name(),
                command.reasonCode(), correctionId, receiptId,
                command.authority().actorId(), command.authority().subjectPrincipalPublicId(),
                Timestamp.from(command.occurredAt()));
    }

    private UUID insertDecision(
            TimecardCommand command,
            CardRow card,
            UUID receiptId,
            long version,
            TimecardState state) {
        UUID decisionId = TimecardDigests.stableUuid(
                "TimecardDecision.v1", command.authority().tenantId(),
                card.timecardId(), version);
        jdbc.update("""
                INSERT INTO tme_timecard_decisions (
                    public_id, tenant_id, timecard_public_id, timecard_version,
                    decision, resulting_state, reason_code, restricted_comment,
                    decided_at, decided_by, decided_by_subject_public_id,
                    command_receipt_public_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, decisionId, command.authority().tenantId(), card.timecardId(), version,
                command.decision().name(), state.name(), command.reasonCode(),
                command.decisionComment(), Timestamp.from(command.occurredAt()),
                command.authority().actorId(), command.authority().subjectPrincipalPublicId(),
                receiptId);
        return decisionId;
    }

    private void insertDecisionOutbox(
            TimecardCommand command,
            CardRow card,
            UUID receiptId,
            UUID decisionId,
            long version,
            TimecardState state,
            long ledgerRevision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timecardId", card.timecardId());
        payload.put("workerId", card.workerId());
        payload.put("status", state.name());
        payload.put("ledgerRevision", ledgerRevision);
        payload.put("decisionAt", command.occurredAt().toString());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(
                    "TimecardDecisionRecorded.v1 payload serialization failed", impossible);
        }
        UUID eventId = TimecardDigests.stableUuid(
                "TimecardDecisionRecorded.v1", command.authority().tenantId(),
                card.timecardId(), version);
        String payloadDigest = TimecardDigests.sha256(
                "TimecardDecisionRecorded.v1", card.timecardId(), card.workerId(),
                state, ledgerRevision, command.occurredAt());
        jdbc.update("""
                INSERT INTO tme_timecard_outbox_events (
                    tenant_id, event_id, decision_public_id, aggregate_type,
                    aggregate_public_id, aggregate_sequence, schema_name,
                    schema_version, partition_key, payload, payload_digest,
                    correlation_id, causation_id, occurred_at)
                VALUES (?, ?, ?, 'TIMECARD', ?, ?, 'TimecardDecisionRecorded.v1',
                        1, ?, CAST(? AS JSONB), ?, ?, ?, ?)
                """, command.authority().tenantId(), eventId, decisionId,
                card.timecardId(), version,
                command.authority().tenantId() + ":" + card.timecardId(),
                json, payloadDigest, command.authority().correlationId(), receiptId,
                Timestamp.from(command.occurredAt()));
    }

    private TimecardRecord materializeInTenant(TimecardMaterialization value) {
        jdbc.update("""
                INSERT INTO tme_timecards (
                    public_id, tenant_id, worker_public_id, period_start, period_end,
                    state, total_minutes, ledger_revision, interpretation_run_public_id,
                    period_closed, row_version, created_at, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, FALSE, 0, ?, ?, ?)
                """, value.timecardId(), value.tenantId(), value.workerId(),
                value.periodStart(), value.periodEnd(), value.totalMinutes(),
                value.ledgerRevision(), value.interpretationRunPublicId(),
                Timestamp.from(value.occurredAt()), Timestamp.from(value.occurredAt()),
                value.actorId());
        jdbc.update("""
                INSERT INTO tme_timecard_versions (
                    public_id, tenant_id, timecard_public_id, version, state,
                    total_minutes, ledger_revision, transition_type,
                    actor_id, actor_subject_public_id, occurred_at)
                VALUES (?, ?, ?, 0, 'OPEN', ?, ?, 'MATERIALIZE', ?, ?, ?)
                """, TimecardDigests.stableUuid(
                        "TimecardVersion.v1", value.tenantId(), value.timecardId(), 0),
                value.tenantId(), value.timecardId(), value.totalMinutes(),
                value.ledgerRevision(), value.actorId(), value.actorSubjectPublicId(),
                Timestamp.from(value.occurredAt()));
        jdbc.update("""
                INSERT INTO tme_time_ledger_revision_counters (
                    tenant_id, worker_public_id, current_revision)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, worker_public_id)
                DO UPDATE SET current_revision = GREATEST(
                    tme_time_ledger_revision_counters.current_revision,
                    EXCLUDED.current_revision)
                """, value.tenantId(), value.workerId(), value.ledgerRevision());
        return card(value.tenantId(), value.timecardId(), false)
                .orElseThrow(() -> new IllegalStateException("materialized Timecard is missing"))
                .record();
    }

    private Optional<CardRow> card(long tenantId, UUID timecardId, boolean lock) {
        List<CardRow> found = jdbc.query("""
                SELECT public_id, worker_public_id, period_start, period_end, state,
                       total_minutes, ledger_revision, row_version,
                       submitted_by_subject_public_id,
                       latest_correction_maker_subject_public_id, period_closed
                  FROM tme_timecards
                 WHERE tenant_id = ? AND public_id = ?
                """ + (lock ? " FOR UPDATE" : ""),
                (row, ignored) -> new CardRow(
                        row.getObject("public_id", UUID.class),
                        row.getObject("worker_public_id", UUID.class),
                        row.getDate("period_start").toLocalDate(),
                        row.getDate("period_end").toLocalDate(),
                        TimecardState.valueOf(row.getString("state")),
                        row.getInt("total_minutes"), row.getLong("ledger_revision"),
                        row.getLong("row_version"),
                        row.getObject("submitted_by_subject_public_id", UUID.class),
                        row.getObject("latest_correction_maker_subject_public_id", UUID.class),
                        row.getBoolean("period_closed")),
                tenantId, timecardId);
        return found.stream().findFirst();
    }

    private List<StoredTimecardReceipt> receipts(String sql, Object... arguments) {
        return jdbc.query(sql, (row, ignored) -> {
            UUID receiptId = row.getObject("public_id", UUID.class);
            UUID timecardId = row.getObject("timecard_public_id", UUID.class);
            String digest = row.getString("request_digest").trim();
            TimeCommandReceiptV1 receipt = new TimeCommandReceiptV1(
                    receiptId, row.getString("command_type"),
                    row.getString("originating_action"), timecardId,
                    row.getString("status"), digest,
                    row.getTimestamp("accepted_at").toInstant(),
                    instant(row.getTimestamp("completed_at")), row.getString("result_ref"));
            return new StoredTimecardReceipt(
                    receipt, row.getObject("subject_principal_public_id", UUID.class),
                    row.getObject("worker_public_id", UUID.class),
                    row.getString("population_scope_digest").trim(),
                    row.getLong("field_policy_revision"), row.getString("purpose_code"),
                    row.getLong("authorization_revision"),
                    PopulationKind.valueOf(row.getString("originating_population_kind")),
                    row.getString("originating_required_permission"));
        }, arguments);
    }

    private <T> T inTenant(long tenantId, Supplier<T> work) {
        requireTenant(tenantId);
        T result = transactions.execute(status -> {
            String expected = Long.toString(tenantId);
            String configured = jdbc.queryForObject(SET_TENANT_SQL, String.class, expected);
            if (!expected.equals(configured)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return work.get();
        });
        return Objects.requireNonNull(result, "Timecard transaction returned null");
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }

    private static BaseException opaqueNotFound() {
        return new BaseException(ErrorCode.NOT_FOUND, "Timecard resource was not found.");
    }

    private record ExistingReceipt(
            UUID timecardId,
            String commandType,
            PopulationKind originatingPopulationKind,
            String originatingRequiredPermission,
            String requestDigest,
            TimeCommandReceiptV1 receipt) {}

    private record CardRow(
            UUID timecardId,
            UUID workerId,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd,
            TimecardState state,
            int totalMinutes,
            long ledgerRevision,
            long version,
            UUID submittedBy,
            UUID latestCorrectionMaker,
            boolean periodClosed) {

        TimecardRecord record() {
            return new TimecardRecord(new TimecardV1(
                    timecardId, workerId, periodStart, periodEnd, state,
                    totalMinutes, ledgerRevision, version),
                    submittedBy, latestCorrectionMaker, periodClosed);
        }
    }
}
