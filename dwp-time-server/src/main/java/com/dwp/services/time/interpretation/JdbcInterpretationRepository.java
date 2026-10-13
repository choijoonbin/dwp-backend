package com.dwp.services.time.interpretation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.interpretation.InterpretationModels.ClockFactType;
import com.dwp.services.time.interpretation.InterpretationModels.ClockInputFact;
import com.dwp.services.time.interpretation.InterpretationModels.EntryCode;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretCommand;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAppend;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationCommandReceipt;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationEntry;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationInputs;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationQuery;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationRun;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationSummary;
import com.dwp.services.time.interpretation.InterpretationModels.PublishedRuleVersion;
import com.dwp.services.time.interpretation.InterpretationModels.RuleOutput;
import com.dwp.services.time.interpretation.InterpretationModels.RunStatus;
import com.dwp.services.time.interpretation.InterpretationModels.TraceStep;
import com.dwp.services.time.interpretation.InterpretationModels.WorkerMinuteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL adapter for BASE-TFR-TIM-010 immutable interpretation evidence. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.time.interpretation.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class JdbcInterpretationRepository implements InterpretationRepository {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('dwp.tenant_id', ?, true)";
    private static final TypeReference<List<RuleOutput>> RULE_OUTPUTS = new TypeReference<>() {};
    private static final String RECEIPT_SELECT = """
            SELECT public_id, subject_principal_public_id, population_scope_digest,
                   field_policy_revision, purpose_code, authorization_revision, status,
                   correlation_id, request_digest, created_at, result_run_public_id,
                   result_digest
              FROM tme_interpretation_command_receipts
             WHERE tenant_id = ? AND subject_principal_public_id = ?
               AND command_type = 'INTERPRETATION_RUN' AND idempotency_key = ?
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public JdbcInterpretationRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(15);
    }

    @Override
    public Optional<InterpretationCommandReceipt> findReceipt(
            long tenantId, UUID subjectPrincipalPublicId, UUID idempotencyKey) {
        requireTenant(tenantId);
        Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return inTenant(tenantId, () -> exact(jdbc.query(
                RECEIPT_SELECT,
                JdbcInterpretationRepository::mapReceipt,
                tenantId,
                subjectPrincipalPublicId,
                idempotencyKey)));
    }

    @Override
    public InterpretationInputs loadInputs(long tenantId, InterpretCommand command) {
        requireTenant(tenantId);
        Objects.requireNonNull(command, "command");
        return inTenant(tenantId, () -> new InterpretationInputs(
                loadClockFacts(tenantId, command),
                loadRuleVersion(tenantId, command.periodStart(), command.periodEnd())));
    }

    @Override
    public Optional<InterpretationRun> latestSucceeded(
            long tenantId, List<UUID> workerIds, InterpretCommand command) {
        requireTenant(tenantId);
        Objects.requireNonNull(workerIds, "workerIds");
        Objects.requireNonNull(command, "command");
        return inTenant(tenantId, () -> {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("periodStart", command.periodStart())
                    .addValue("periodEnd", command.periodEnd())
                    .addValue("workerScopeDigest", workerScopeDigest(tenantId, workerIds));
            List<UUID> runs = namedJdbc.queryForList("""
                    SELECT r.public_id
                      FROM tme_interpretation_runs r
                     WHERE r.tenant_id = :tenantId AND r.status = 'SUCCEEDED'
                       AND r.ledger_delta_applied
                       AND r.period_start = :periodStart AND r.period_end = :periodEnd
                       AND r.worker_scope_digest = :workerScopeDigest
                     ORDER BY r.attempt_no DESC, r.created_at DESC, r.public_id DESC
                     LIMIT 1
                    """, parameters, UUID.class);
            return runs.isEmpty() ? Optional.empty() : readRun(tenantId, runs.getFirst());
        });
    }

    @Override
    public InterpretationCommandReceipt append(InterpretationAppend append) {
        Objects.requireNonNull(append, "append");
        long tenantId = append.authority().tenantId();
        try {
            return inTenant(tenantId, () -> appendInTransaction(append));
        } catch (DataIntegrityViolationException conflict) {
            Optional<InterpretationCommandReceipt> existing = findReceipt(
                    tenantId,
                    append.authority().subjectPrincipalPublicId(),
                    append.idempotencyKey());
            if (existing.isPresent()
                    && existing.get().requestDigest().equals(append.requestDigest())) {
                return replayed(existing.get());
            }
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Interpretation evidence conflicts with an existing immutable fact.",
                    conflict);
        }
    }

    @Override
    public Optional<InterpretationRun> findRun(long tenantId, UUID runPublicId) {
        requireTenant(tenantId);
        Objects.requireNonNull(runPublicId, "runPublicId");
        return inTenant(tenantId, () -> readRun(tenantId, runPublicId));
    }

    @Override
    public List<InterpretationSummary> query(InterpretationQuery query) {
        Objects.requireNonNull(query, "query");
        return inTenant(query.tenantId(), () -> queryInTransaction(query));
    }

    private InterpretationCommandReceipt appendInTransaction(InterpretationAppend append) {
        var authority = append.authority();
        lockScope(append.run());
        Optional<InterpretationCommandReceipt> existing = exact(jdbc.query(
                RECEIPT_SELECT + " FOR UPDATE",
                JdbcInterpretationRepository::mapReceipt,
                authority.tenantId(),
                authority.subjectPrincipalPublicId(),
                append.idempotencyKey()));
        if (existing.isPresent()) {
            if (!existing.get().requestDigest().equals(append.requestDigest())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            }
            return replayed(existing.get());
        }
        InterpretationRun candidate = append.run();
        List<UUID> workers = candidate.results().stream()
                .map(WorkerMinuteResult::workerPublicId).sorted().toList();
        InterpretationRun run = InterpretationRunReconciler.reconcile(
                candidate,
                latestSucceeded(
                        candidate.tenantId(),
                        workers,
                        new InterpretCommand(
                                workers,
                                candidate.periodStart(),
                                candidate.periodEnd(),
                                candidate.asOf()))
                        .orElse(null));
        long runInternalId = jdbc.queryForObject("""
                INSERT INTO tme_interpretation_runs (
                    public_id, tenant_id, subject_principal_public_id,
                    period_start, period_end, as_of, run_scope, worker_scope_digest, status,
                    rule_version_public_id, rule_version_revision,
                    input_snapshot_digest, rule_snapshot_digest, output_digest,
                    requested_idempotency_key, attempt_no, ledger_delta_applied,
                    supersedes_run_public_id,
                    correlation_id, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING interpretation_run_id
                """, Long.class,
                run.runPublicId(), run.tenantId(), run.subjectPrincipalPublicId(),
                run.periodStart(), run.periodEnd(), Timestamp.from(run.asOf()),
                authority.populationKind().name(), workerScopeDigest(run.tenantId(), workers),
                run.status().name(),
                run.ruleVersionPublicId(), run.ruleVersionRevision(), run.inputDigest(),
                run.ruleDigest(), run.outputDigest(), append.idempotencyKey(), run.attemptNo(),
                run.ledgerDeltaApplied(), run.supersedesRunPublicId(), run.correlationId(),
                Timestamp.from(run.createdAt()), authority.actorId());
        UUID attemptId = InterpretationDigests.stableUuid(
                "InterpretationAttempt.v1", run.runPublicId(), run.attemptNo());
        long attemptInternalId = jdbc.queryForObject("""
                INSERT INTO tme_interpretation_attempts (
                    public_id, tenant_id, interpretation_run_id, attempt_no, status,
                    output_digest, started_at, finished_at)
                VALUES (?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?)
                RETURNING interpretation_attempt_id
                """, Long.class,
                attemptId, run.tenantId(), runInternalId, run.attemptNo(), run.outputDigest(),
                Timestamp.from(run.createdAt()), Timestamp.from(run.createdAt()));
        insertSegments(run, attemptInternalId);
        insertLedger(run, attemptId, authority.actorId());
        insertTrace(run, attemptInternalId);

        UUID receiptId = InterpretationDigests.stableUuid(
                "InterpretationReceipt.v1",
                run.tenantId(),
                run.subjectPrincipalPublicId(),
                append.idempotencyKey());
        jdbc.update("""
                INSERT INTO tme_interpretation_command_receipts (
                    public_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id,
                    subject_principal_public_id, population_public_id, population_revision,
                    grant_revision, population_scope_digest, field_policy_revision,
                    purpose_code, authorization_revision, request_digest,
                    result_run_public_id, result_digest, status, correlation_id, created_at)
                VALUES (?, ?, ?, 'INTERPRETATION_RUN', 'TIME_INTERPRET_RUN',
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'SUCCEEDED', ?, ?)
                """,
                receiptId, run.tenantId(), append.idempotencyKey(), authority.actorId(),
                authority.subjectPrincipalPublicId(), authority.populationPublicId(),
                authority.populationRevision(), authority.grantRevision(),
                authority.populationScopeDigest(), authority.fieldPolicyRevision(),
                authority.purposeCode(), authority.authorizationRevision(), append.requestDigest(),
                run.runPublicId(), run.outputDigest(), run.correlationId(),
                Timestamp.from(run.createdAt()));
        insertOutbox(run, runInternalId, receiptId);
        return new InterpretationCommandReceipt(
                receiptId,
                InterpretationService.RECEIPT_COMMAND_TYPE,
                InterpretationService.RECEIPT_ACTION,
                authority.subjectPrincipalPublicId(),
                authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                authority.purposeCode(),
                authority.authorizationRevision(),
                "SUCCEEDED",
                run.correlationId(),
                append.requestDigest(),
                run.createdAt(),
                (long) run.attemptNo(),
                "/api/time/v1/interpretation-runs/" + run.runPublicId() + "/trace",
                run.runPublicId(),
                run.outputDigest(),
                false);
    }

    private void insertSegments(InterpretationRun run, long attemptInternalId) {
        run.results().stream().sorted(Comparator.comparing(WorkerMinuteResult::workerPublicId))
                .forEach(worker -> {
                    Map<EntryCode, Integer> values = Map.of(
                            EntryCode.REGULAR_MINUTES, worker.regularMinutes(),
                            EntryCode.OVERTIME_MINUTES, worker.overtimeMinutes(),
                            EntryCode.UNPAID_BREAK_MINUTES, worker.unpaidBreakMinutes());
                    int sequence = 0;
                    for (EntryCode code : values.keySet().stream()
                            .sorted(Comparator.comparing(Enum::name)).toList()) {
                        UUID segmentId = InterpretationDigests.stableUuid(
                                "InterpretationSegment.v1", run.runPublicId(),
                                worker.workerPublicId(), code);
                    jdbc.update("""
                            INSERT INTO tme_interpreted_segments (
                                public_id, tenant_id, interpretation_attempt_id,
                                worker_public_id, segment_sequence, segment_type, minutes,
                                input_digest, rule_trace_digest, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            segmentId, run.tenantId(), attemptInternalId,
                            worker.workerPublicId(), ++sequence, code.name(), values.get(code),
                            run.inputDigest(), run.ruleDigest(), Timestamp.from(run.createdAt()));
                    }
                });
    }

    private void insertLedger(InterpretationRun run, UUID attemptId, long actorId) {
        Map<UUID, Long> revisions = new LinkedHashMap<>();
        run.entries().stream().map(InterpretationEntry::workerPublicId).distinct().sorted()
                .forEach(workerId -> {
                    jdbc.query(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                            ignored -> {},
                            "tim-ledger:" + run.tenantId() + ":" + workerId);
                    Long current = jdbc.queryForObject("""
                            SELECT COALESCE(MAX(ledger_revision), 0)
                              FROM tme_time_ledger_entries
                             WHERE tenant_id = ? AND worker_public_id = ?
                            """, Long.class, run.tenantId(), workerId);
                    revisions.put(workerId, current == null ? 0L : current);
                });
        for (InterpretationEntry entry : run.entries()) {
            long revision = revisions.compute(
                    entry.workerPublicId(), (ignored, current) -> current + 1);
            boolean reversal = entry.reversesEntryPublicId() != null;
            jdbc.update("""
                    INSERT INTO tme_time_ledger_entries (
                        public_id, tenant_id, worker_public_id, local_work_date, pay_code,
                        unit, quantity, source_type, source_public_id,
                        interpretation_attempt_public_id, reverses_entry_public_id,
                        reason_code, ledger_revision, input_digest, rule_digest,
                        posted_at, posted_by, correlation_id)
                    VALUES (?, ?, ?, ?, ?, 'MINUTE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    entry.entryPublicId(), run.tenantId(), entry.workerPublicId(),
                    run.periodStart(), entry.entryCode().name(), entry.minutes(),
                    reversal ? "REVERSAL" : "INTERPRETATION", run.runPublicId(), attemptId,
                    entry.reversesEntryPublicId(),
                    reversal ? "CLOCK_INPUT_REINTERPRETED" : null,
                    revision, run.inputDigest(), run.ruleDigest(), Timestamp.from(run.createdAt()),
                    actorId, run.correlationId());
        }
    }

    private void insertTrace(InterpretationRun run, long attemptInternalId) {
        for (TraceStep step : run.traceSteps()) {
            jdbc.update("""
                    INSERT INTO tme_interpretation_trace_steps (
                        tenant_id, interpretation_attempt_id, sequence,
                        rule_code, input_minutes, output_minutes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    run.tenantId(), attemptInternalId, step.sequence(), step.ruleCode(),
                    step.inputMinutes(), step.outputMinutes());
        }
    }

    private void insertOutbox(InterpretationRun run, long runInternalId, UUID receiptId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.runPublicId());
        payload.put("periodStart", run.periodStart());
        payload.put("periodEnd", run.periodEnd());
        payload.put("inputDigest", run.inputDigest());
        payload.put("ruleDigest", run.ruleDigest());
        payload.put("outputDigest", run.outputDigest());
        payload.put("attemptNo", run.attemptNo());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("TimeInterpreted payload serialization failed", failure);
        }
        UUID eventId = InterpretationDigests.stableUuid(
                "TimeInterpreted.v1", run.runPublicId(), run.attemptNo(), run.outputDigest());
        String payloadDigest = InterpretationDigests.sha256(
                "TimeInterpreted.v1",
                run.runPublicId(),
                run.periodStart(),
                run.periodEnd(),
                run.inputDigest(),
                run.ruleDigest(),
                run.outputDigest(),
                run.attemptNo());
        jdbc.update("""
                INSERT INTO tme_interpretation_outbox_events (
                    tenant_id, event_id, aggregate_type, aggregate_public_id,
                    aggregate_sequence, schema_name, schema_version, payload, payload_digest,
                    correlation_id, causation_id, occurred_at)
                VALUES (?, ?, 'InterpretationRun', ?, ?, 'TimeInterpreted', 1,
                        CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                run.tenantId(), eventId, run.runPublicId(), runInternalId, json, payloadDigest,
                run.correlationId(), receiptId, Timestamp.from(run.createdAt()));
    }

    private List<ClockInputFact> loadClockFacts(long tenantId, InterpretCommand command) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("workers", command.workerIds())
                .addValue("periodStart", command.periodStart())
                .addValue("periodEnd", command.periodEnd())
                .addValue("asOf", Timestamp.from(command.asOf()));
        return namedJdbc.query("""
                SELECT e.event_order, e.public_id, e.tenant_id, e.worker_public_id,
                       e.event_type, e.occurred_at, e.source_time_zone,
                       e.source_utc_offset_minutes, e.time_zone_rule_version,
                       e.payload_digest
                  FROM tme_clock_events e
                 WHERE e.tenant_id = :tenantId AND e.worker_public_id IN (:workers)
                   AND e.event_type IN ('IN','OUT','BREAK_START','BREAK_END')
                   AND e.source_local_datetime::date BETWEEN :periodStart AND :periodEnd
                   AND e.occurred_at <= :asOf AND e.received_at <= :asOf
                   AND NOT EXISTS (
                       SELECT 1 FROM tme_clock_events v
                        WHERE v.tenant_id = e.tenant_id AND v.event_type = 'VOID'
                          AND v.voids_event_public_id = e.public_id
                          AND v.occurred_at <= :asOf AND v.received_at <= :asOf)
                 ORDER BY e.worker_public_id, e.occurred_at, e.event_order, e.public_id
                """, parameters, (row, ignored) -> new ClockInputFact(
                row.getLong("event_order"),
                row.getObject("public_id", UUID.class),
                row.getLong("tenant_id"),
                row.getObject("worker_public_id", UUID.class),
                ClockFactType.valueOf(row.getString("event_type")),
                row.getTimestamp("occurred_at").toInstant(),
                row.getString("source_time_zone"),
                row.getInt("source_utc_offset_minutes"),
                row.getString("time_zone_rule_version"),
                row.getString("payload_digest").trim()));
    }

    private PublishedRuleVersion loadRuleVersion(
            long tenantId, LocalDate periodStart, LocalDate periodEnd) {
        List<PublishedRuleVersion> rules = jdbc.query("""
                SELECT public_id, tenant_id, revision, effective_from, effective_to,
                       registry_id, registry_version, rule_digest, rule_payload::text
                  FROM tme_interpretation_rule_versions
                 WHERE tenant_id = ? AND lifecycle_state = 'PUBLISHED'
                   AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY revision DESC
                 LIMIT 2
                """, (row, ignored) -> mapRule(row), tenantId, periodStart, periodEnd);
        if (rules.size() != 1) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exactly one effective typed interpretation rule is required.");
        }
        return rules.getFirst();
    }

    private PublishedRuleVersion mapRule(ResultSet row) throws SQLException {
        try {
            return new PublishedRuleVersion(
                    row.getLong("tenant_id"),
                    row.getObject("public_id", UUID.class),
                    row.getLong("revision"),
                    row.getObject("effective_from", LocalDate.class),
                    row.getObject("effective_to", LocalDate.class),
                    row.getString("registry_id"),
                    row.getInt("registry_version"),
                    row.getString("rule_digest").trim(),
                    objectMapper.readValue(row.getString("rule_payload"), RULE_OUTPUTS));
        } catch (JsonProcessingException failure) {
            throw new SQLException("Published typed rule payload is invalid", failure);
        }
    }

    private Optional<InterpretationRun> readRun(long tenantId, UUID runId) {
        List<JdbcInterpretationRunRow> rows = jdbc.query("""
                SELECT r.interpretation_run_id, r.public_id, r.tenant_id,
                       r.subject_principal_public_id, r.period_start, r.period_end, r.as_of,
                       r.rule_version_public_id, r.rule_version_revision,
                       r.input_snapshot_digest, r.rule_snapshot_digest, r.output_digest,
                       r.attempt_no, r.status, r.ledger_delta_applied,
                       r.supersedes_run_public_id,
                       r.correlation_id, r.created_at, a.interpretation_attempt_id
                  FROM tme_interpretation_runs r
                  JOIN tme_interpretation_attempts a
                    ON a.tenant_id = r.tenant_id
                   AND a.interpretation_run_id = r.interpretation_run_id
                   AND a.attempt_no = r.attempt_no
                 WHERE r.tenant_id = ? AND r.public_id = ?
                """, JdbcInterpretationRunRow::map, tenantId, runId);
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) throw new IllegalStateException("Interpretation run is ambiguous");
        JdbcInterpretationRunRow row = rows.getFirst();
        Map<UUID, int[]> totals = new LinkedHashMap<>();
        jdbc.query("""
                SELECT worker_public_id, segment_type, minutes
                  FROM tme_interpreted_segments
                 WHERE tenant_id = ? AND interpretation_attempt_id = ?
                 ORDER BY worker_public_id, segment_sequence
                """, result -> {
            int[] minutes = totals.computeIfAbsent(
                    result.getObject("worker_public_id", UUID.class), ignored -> new int[3]);
            switch (EntryCode.valueOf(result.getString("segment_type"))) {
                case REGULAR_MINUTES -> minutes[0] += result.getInt("minutes");
                case OVERTIME_MINUTES -> minutes[1] += result.getInt("minutes");
                case UNPAID_BREAK_MINUTES -> minutes[2] += result.getInt("minutes");
                default -> throw new IllegalStateException("Unexpected interpretation segment");
            }
        }, tenantId, row.attemptInternalId());
        List<WorkerMinuteResult> results = totals.entrySet().stream()
                .map(entry -> new WorkerMinuteResult(
                        entry.getKey(), entry.getValue()[0] + entry.getValue()[1],
                        entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
                .toList();
        List<InterpretationEntry> entries = jdbc.query("""
                SELECT public_id, worker_public_id, pay_code, quantity,
                       reverses_entry_public_id
                  FROM tme_time_ledger_entries
                 WHERE tenant_id = ? AND interpretation_attempt_public_id =
                       (SELECT public_id FROM tme_interpretation_attempts
                         WHERE tenant_id = ? AND interpretation_attempt_id = ?)
                 ORDER BY time_ledger_entry_id
                """, (result, ignored) -> new InterpretationEntry(
                result.getObject("public_id", UUID.class),
                result.getObject("worker_public_id", UUID.class),
                EntryCode.valueOf(result.getString("pay_code")),
                result.getBigDecimal("quantity").intValueExact(),
                result.getObject("reverses_entry_public_id", UUID.class)),
                tenantId, tenantId, row.attemptInternalId());
        List<TraceStep> trace = jdbc.query("""
                SELECT sequence, rule_code, input_minutes, output_minutes
                  FROM tme_interpretation_trace_steps
                 WHERE tenant_id = ? AND interpretation_attempt_id = ?
                 ORDER BY sequence
                """, (result, ignored) -> new TraceStep(
                result.getInt("sequence"),
                result.getString("rule_code"),
                result.getInt("input_minutes"),
                result.getInt("output_minutes")), tenantId, row.attemptInternalId());
        return Optional.of(row.toRun(results, entries, trace));
    }

    private List<InterpretationSummary> queryInTransaction(InterpretationQuery query) {
        InterpretationSummaryQuery.Prepared prepared = InterpretationSummaryQuery.prepare(query);
        return namedJdbc.query(prepared.sql(), prepared.parameters(), (row, ignored) -> {
            int regular = row.getInt("regular_minutes");
            int overtime = row.getInt("overtime_minutes");
            return new InterpretationSummary(
                    row.getObject("public_id", UUID.class),
                    row.getObject("worker_public_id", UUID.class),
                    row.getObject("period_start", LocalDate.class),
                    row.getObject("period_end", LocalDate.class),
                    RunStatus.valueOf(row.getString("status")),
                    row.getObject("rule_version_public_id", UUID.class),
                    row.getLong("rule_version_revision"),
                    regular + overtime,
                    regular,
                    overtime,
                    row.getInt("attempt_no"),
                    row.getInt("trace_item_count"),
                    row.getString("input_snapshot_digest").trim(),
                    row.getString("rule_snapshot_digest").trim(),
                    row.getString("output_digest").trim(),
                    row.getTimestamp("created_at").toInstant());
        });
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
        return Objects.requireNonNull(result, "interpretation transaction returned null");
    }

    private static InterpretationCommandReceipt mapReceipt(ResultSet row, int ignored)
            throws SQLException {
        UUID runId = row.getObject("result_run_public_id", UUID.class);
        return new InterpretationCommandReceipt(
                row.getObject("public_id", UUID.class),
                InterpretationService.RECEIPT_COMMAND_TYPE,
                InterpretationService.RECEIPT_ACTION,
                row.getObject("subject_principal_public_id", UUID.class),
                row.getString("population_scope_digest").trim(),
                row.getLong("field_policy_revision"),
                row.getString("purpose_code"),
                row.getLong("authorization_revision"),
                row.getString("status"),
                row.getObject("correlation_id", UUID.class),
                row.getString("request_digest").trim(),
                row.getTimestamp("created_at").toInstant(),
                null,
                "/api/time/v1/interpretation-runs/" + runId + "/trace",
                runId,
                row.getString("result_digest").trim(),
                false);
    }

    private static <T> Optional<T> exact(List<T> values) {
        if (values.size() > 1) throw new IllegalStateException("Expected at most one row");
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    private static InterpretationCommandReceipt replayed(InterpretationCommandReceipt receipt) {
        return new InterpretationCommandReceipt(
                receipt.receiptId(), receipt.commandType(), receipt.originatingAction(),
                receipt.subjectPrincipalPublicId(), receipt.populationScopeDigest(),
                receipt.fieldPolicyRevision(), receipt.purposeCode(),
                receipt.authorizationRevision(), receipt.status(), receipt.correlationId(),
                receipt.requestDigest(), receipt.createdAt(), receipt.aggregateVersion(),
                receipt.resultRef(), receipt.runPublicId(), receipt.outputDigest(), true);
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }

    private static String workerScopeDigest(long tenantId, List<UUID> workers) {
        return InterpretationDigests.sha256(
                "InterpretationWorkerScope.v1", tenantId, workers.stream().sorted().toList());
    }

    private void lockScope(InterpretationRun run) {
        run.results().stream().map(WorkerMinuteResult::workerPublicId).sorted().forEach(worker ->
                jdbc.query(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                        ignored -> {},
                        "tim-interpretation:" + run.tenantId() + ":" + worker));
    }
}
