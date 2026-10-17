package com.dwp.services.time.close;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.contracts.hris.xcon.v1.Xcon009TimePeriodClosedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon010TimePeriodReopenedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon011PayrollTimeHandoffReadyV1;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.CloseBlocker;
import com.dwp.services.time.close.TimeCloseModels.CloseCommand;
import com.dwp.services.time.close.TimeCloseModels.CloseEvidence;
import com.dwp.services.time.close.TimeCloseModels.ClosePeriod;
import com.dwp.services.time.close.TimeCloseModels.CloseState;
import com.dwp.services.time.close.TimeCloseModels.CommandKind;
import com.dwp.services.time.close.TimeCloseModels.HandoffRecord;
import com.dwp.services.time.close.TimeCloseModels.HandoffState;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffOutcomeV1;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffStatusV1;
import com.dwp.services.time.close.TimeCloseModels.PersistedClosedResult;
import com.dwp.services.time.close.TimeCloseModels.PlannedCloseCommand;
import com.dwp.services.time.close.TimeCloseModels.ResultLine;
import com.dwp.services.time.close.TimeCloseModels.SourceEvidence;
import com.dwp.services.time.close.TimeCloseModels.StoredReceipt;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseCommandReceiptV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic tenant-scoped owner persistence for close, reopen, handoff and outcomes. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class JdbcTimeCloseRepository implements TimeCloseRepository {

    private static final String SET_TENANT =
            "SELECT set_config('dwp.tenant_id', ?, true)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final TimeCloseEvidenceFence evidenceFence;
    private final Clock clock;

    @Autowired
    public JdbcTimeCloseRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this(jdbc, json, transactionManager, Clock.systemUTC());
    }

    JdbcTimeCloseRepository(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        evidenceFence = new TimeCloseEvidenceFence(jdbc);
        transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(30);
    }

    @Override
    public Optional<ClosePeriod> findPeriod(long tenantId, UUID periodId) {
        requireTenant(tenantId);
        Objects.requireNonNull(periodId, "periodId");
        return inTenant(tenantId, () -> period(tenantId, periodId, false));
    }

    @Override
    public Optional<PersistedClosedResult> findResult(long tenantId, UUID resultId) {
        requireTenant(tenantId);
        Objects.requireNonNull(resultId, "resultId");
        return inTenant(tenantId, () -> result(tenantId, resultId));
    }

    @Override
    public Optional<HandoffRecord> findHandoff(long tenantId, UUID handoffId) {
        requireTenant(tenantId);
        Objects.requireNonNull(handoffId, "handoffId");
        return inTenant(tenantId, () -> handoff(tenantId, handoffId, false));
    }

    @Override
    public Optional<StoredReceipt> findReceipt(long tenantId, UUID receiptId) {
        requireTenant(tenantId);
        Objects.requireNonNull(receiptId, "receiptId");
        return inTenant(tenantId, () -> receipts(
                "WHERE tenant_id = ? AND public_id = ?", tenantId, receiptId)
                .stream().findFirst());
    }

    @Override
    public Optional<StoredReceipt> findReceipt(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String routeContractKey,
            String idempotencyKey) {
        requireTenant(tenantId);
        Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
        Objects.requireNonNull(routeContractKey, "routeContractKey");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return inTenant(tenantId, () -> receiptByIdempotency(
                tenantId, subjectPrincipalPublicId, routeContractKey, idempotencyKey));
    }

    @Override
    public List<TimeCloseIaQueryModels.CloseProjectionRow> queryProjectionRows(
            long tenantId,
            UUID legalEntityPublicId,
            UUID timeGroupPublicId,
            String closeScope,
            Instant asOf) {
        requireTenant(tenantId);
        Objects.requireNonNull(legalEntityPublicId, "legalEntityPublicId");
        Objects.requireNonNull(timeGroupPublicId, "timeGroupPublicId");
        Objects.requireNonNull(asOf, "asOf");
        if (closeScope != null && !Set.of("DAY", "MONTH").contains(closeScope)) {
            throw new IllegalArgumentException("closeScope");
        }
        return inTenant(tenantId, () -> jdbc.query("""
                SELECT p.public_id, p.close_scope, p.period_start, p.period_end,
                       p.status, p.close_revision, p.row_version,
                       p.created_at AS period_created_at,
                       p.updated_at AS period_updated_at,
                       COALESCE(a.blocker_count, 0) AS blocker_count,
                       COALESCE(cr.line_count, 0) AS impact_count,
                       rc.public_id AS receipt_public_id,
                       rc.status AS receipt_status,
                       rc.created_at AS receipt_created_at,
                       rc.completed_at AS receipt_completed_at,
                       rc.aggregate_version AS receipt_revision,
                       h.public_id AS handoff_public_id,
                       h.status AS handoff_status,
                       h.created_at AS handoff_created_at,
                       h.updated_at AS handoff_updated_at,
                       h.row_version AS handoff_revision
                  FROM tme_close_periods p
                  LEFT JOIN tme_closed_time_results cr
                    ON cr.tenant_id=p.tenant_id
                   AND cr.close_period_id=p.close_period_id
                   AND cr.public_id=p.latest_result_public_id
                  LEFT JOIN LATERAL (
                       SELECT ca.blocker_count
                         FROM tme_close_attempts ca
                        WHERE ca.tenant_id=p.tenant_id
                          AND ca.close_period_id=p.close_period_id
                          AND ca.completed_at <= ?
                        ORDER BY ca.attempt_no DESC
                        LIMIT 1
                  ) a ON TRUE
                  LEFT JOIN LATERAL (
                       SELECT r.public_id, r.status, r.created_at, r.completed_at,
                              r.aggregate_version
                         FROM tme_close_command_receipts r
                        WHERE r.tenant_id=p.tenant_id
                          AND r.aggregate_public_id=p.public_id
                          AND r.created_at <= ?
                        ORDER BY r.created_at DESC, r.public_id DESC
                        LIMIT 1
                  ) rc ON TRUE
                  LEFT JOIN LATERAL (
                       SELECT ph.public_id, ph.status, ph.created_at, ph.updated_at,
                              ph.row_version
                         FROM tme_payroll_handoffs ph
                        WHERE ph.tenant_id=p.tenant_id
                          AND ph.close_period_id=p.close_period_id
                          AND ph.created_at <= ?
                        ORDER BY ph.created_at DESC, ph.public_id DESC
                        LIMIT 1
                  ) h ON TRUE
                 WHERE p.tenant_id=?
                   AND p.legal_entity_public_id=?
                   AND p.time_group_public_id=?
                   AND (? IS NULL OR p.close_scope=?)
                   AND p.created_at <= ?
                 ORDER BY p.public_id
                """, (row, ignored) -> new TimeCloseIaQueryModels.CloseProjectionRow(
                        row.getObject("public_id", UUID.class),
                        row.getString("close_scope"),
                        row.getDate("period_start").toLocalDate(),
                        row.getDate("period_end").toLocalDate(),
                        row.getString("status"), row.getLong("close_revision"),
                        row.getLong("row_version"),
                        row.getTimestamp("period_created_at").toInstant(),
                        row.getTimestamp("period_updated_at").toInstant(),
                        row.getInt("blocker_count"),
                        row.getInt("impact_count"),
                        row.getObject("receipt_public_id", UUID.class),
                        row.getString("receipt_status"),
                        instant(row.getTimestamp("receipt_created_at")),
                        instant(row.getTimestamp("receipt_completed_at")),
                        nullableLong(row, "receipt_revision"),
                        row.getObject("handoff_public_id", UUID.class),
                        row.getString("handoff_status"),
                        instant(row.getTimestamp("handoff_created_at")),
                        instant(row.getTimestamp("handoff_updated_at")),
                        nullableLong(row, "handoff_revision")),
                Timestamp.from(asOf), Timestamp.from(asOf), Timestamp.from(asOf),
                tenantId, legalEntityPublicId, timeGroupPublicId, closeScope, closeScope,
                Timestamp.from(asOf)));
    }

    @Override
    public TimeCloseCommandReceiptV1 execute(PlannedCloseCommand planned) {
        Objects.requireNonNull(planned, "planned");
        long tenantId = planned.command().authority().tenantId();
        return inTenant(tenantId, () -> executeInTenant(planned));
    }

    @Override
    public PayrollTimeHandoffStatusV1 applyOutcome(
            long tenantId,
            UUID handoffId,
            String outcomeCommandDigest,
            PayrollTimeHandoffOutcomeV1 outcome) {
        requireTenant(tenantId);
        Objects.requireNonNull(handoffId, "handoffId");
        TimeCloseModels.requireDigest(outcomeCommandDigest, "outcomeCommandDigest");
        Objects.requireNonNull(outcome, "outcome");
        OutcomeApplication applied = inTenant(tenantId, () -> applyOutcomeInTenant(
                tenantId, handoffId, outcomeCommandDigest, outcome));
        if (applied.conflict()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The Payroll receiver outcome conflicts with sealed lineage.");
        }
        return applied.projection();
    }

    private TimeCloseCommandReceiptV1 executeInTenant(PlannedCloseCommand planned) {
        CloseCommand command = planned.command();
        long tenantId = command.authority().tenantId();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", ignored -> {},
                "tim-close-idem:" + tenantId + ":"
                        + command.authority().subjectPrincipalPublicId() + ":"
                        + command.authority().routeContractKey() + ":"
                        + command.idempotencyKey());
        Optional<StoredReceipt> existing = receiptByIdempotency(
                tenantId, command.authority().subjectPrincipalPublicId(),
                command.authority().routeContractKey(), command.idempotencyKey());
        if (existing.isPresent()) {
            StoredReceipt replay = existing.get();
            if (!replay.receipt().requestDigest().equals(command.requestDigest())
                    || !replay.aggregatePublicId().equals(command.periodId())
                    || !replay.receipt().commandType().equals(command.kind().commandType())
                    || !replay.receipt().originatingAction()
                            .equals(command.kind().originatingAction())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "The idempotency key is already sealed to another command.");
            }
            if (!replay.receipt().populationScopeDigest()
                            .equals(command.authority().populationScopeDigest())
                    || replay.populationRevision()
                            != command.authority().populationRevision()
                    || replay.grantRevision() != command.authority().grantRevision()
                    || replay.receipt().fieldPolicyRevision()
                            != command.authority().fieldPolicyRevision()
                    || replay.receipt().authorizationRevision()
                            != command.authority().authorizationRevision()
                    || !replay.requiredPermission().equals(requiredPermission(command.kind()))) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "The original command authority is no longer current.");
            }
            return replay.receipt();
        }

        ClosePeriod locked = period(tenantId, command.periodId(), true)
                .orElseThrow(JdbcTimeCloseRepository::opaqueNotFound);
        if (!locked.legalEntityPublicId()
                        .equals(command.authority().legalEntityPublicId())
                || !locked.timeGroupPublicId()
                        .equals(command.authority().timeGroupPublicId())) {
            throw opaqueNotFound();
        }
        if (locked.version() != command.expectedVersion()) {
            throw new TimeCloseBoundaryFailure(
                    412, "PRECONDITION_FAILED", false, locked.version(),
                    "The close period aggregate version changed.");
        }
        assertState(command.kind(), locked);
        Instant now = clock.instant();
        if (command.kind() == CommandKind.VALIDATE
                || command.kind() == CommandKind.CLOSE) {
            evidenceFence.lockAndVerify(
                    command, locked, Objects.requireNonNull(planned.evidence()), now);
        }

        UUID receiptId = UUID.randomUUID();
        insertAcceptedReceipt(command, receiptId, now);

        long aggregateVersion = locked.version();
        String resultRef;
        if (command.kind() == CommandKind.VALIDATE) {
            UUID attemptId = insertAttempt(
                    command, locked, planned.evidence(), null, receiptId, now);
            resultRef = "close-validation:" + attemptId;
        } else if (command.kind() == CommandKind.CLOSE) {
            aggregateVersion = close(planned, locked, receiptId, now);
            UUID resultId = period(tenantId, command.periodId(), false)
                    .orElseThrow().latestResultId();
            resultRef = "closed-time-result:" + resultId;
        } else if (command.kind() == CommandKind.REOPEN) {
            aggregateVersion = reopen(planned, locked, receiptId, now);
            resultRef = "close-period:" + command.periodId()
                    + ":version:" + aggregateVersion;
        } else {
            UUID handoffId = handoff(planned, locked, receiptId, now);
            resultRef = "payroll-handoff:" + handoffId;
        }

        int terminal = jdbc.update("""
                UPDATE tme_close_command_receipts
                   SET status = 'SUCCEEDED', aggregate_version = ?,
                       result_ref = ?, completed_at = ?
                 WHERE tenant_id = ? AND public_id = ? AND status = 'ACCEPTED'
                """, aggregateVersion, resultRef, Timestamp.from(now),
                tenantId, receiptId);
        if (terminal != 1) throw new IllegalStateException("receipt progression was lost");
        return receiptById(tenantId, receiptId).orElseThrow().receipt();
    }

    private long close(
            PlannedCloseCommand planned,
            ClosePeriod period,
            UUID receiptId,
            Instant now) {
        CloseCommand command = planned.command();
        CloseEvidence evidence = Objects.requireNonNull(planned.evidence());
        int fencing = jdbc.update("""
                UPDATE tme_close_periods
                   SET status='CLOSING', row_version=row_version+1,
                       updated_at=?, updated_by=?
                 WHERE tenant_id=? AND public_id=? AND row_version=?
                """, Timestamp.from(now), command.authority().actorId(),
                command.authority().tenantId(), period.publicId(), period.version());
        if (fencing != 1) throw versionConflict(period.version());

        long nextCloseRevision = period.closeRevision() + 1;
        UUID resultId = UUID.randomUUID();
        insertResult(command, period, evidence, resultId, nextCloseRevision, now);
        insertAttempt(command, period, evidence, planned.approval(), receiptId, now);
        long finalVersion = period.version() + 2;
        int closed = jdbc.update("""
                UPDATE tme_close_periods
                   SET status='CLOSED', close_revision=?, ledger_revision=?,
                       latest_result_public_id=?, closed_by_subject_public_id=?,
                       close_approval_actor_public_id=?, closed_at=?,
                       row_version=?, updated_at=?, updated_by=?
                 WHERE tenant_id=? AND public_id=?
                   AND status='CLOSING' AND row_version=?
                """, nextCloseRevision, evidence.ledgerRevisionTo(), resultId,
                command.authority().subjectPrincipalPublicId(),
                planned.approval().approvalActorSubjectId(), Timestamp.from(now),
                finalVersion, Timestamp.from(now), command.authority().actorId(),
                command.authority().tenantId(), period.publicId(), period.version() + 1);
        if (closed != 1) throw versionConflict(period.version() + 1);
        Xcon009TimePeriodClosedV1 event = new Xcon009TimePeriodClosedV1(
                period.publicId(), nextCloseRevision, evidence.ledgerRevisionTo(),
                period.periodEnd(), period.periodStart(), evidence.ruleDigest(),
                period.timeGroupPublicId(), evidence.sourceDigest());
        insertEvent(command, receiptId, "TimePeriodClosed.v1", "ClosePeriod",
                period.publicId(), nextCloseRevision, event, now);
        return finalVersion;
    }

    private long reopen(
            PlannedCloseCommand planned,
            ClosePeriod period,
            UUID receiptId,
            Instant now) {
        CloseCommand command = planned.command();
        int requested = jdbc.update("""
                UPDATE tme_close_periods
                   SET status='REOPEN_REQUESTED',
                       latest_correction_maker_subject_public_id=?,
                       row_version=row_version+1,
                       updated_at=?, updated_by=?
                 WHERE tenant_id=? AND public_id=? AND row_version=?
                """, command.authority().subjectPrincipalPublicId(),
                Timestamp.from(now), command.authority().actorId(),
                command.authority().tenantId(), period.publicId(), period.version());
        if (requested != 1) throw versionConflict(period.version());
        insertAttempt(command, period, null, planned.approval(), receiptId, now);
        long finalVersion = period.version() + 2;
        int reopened = jdbc.update("""
                UPDATE tme_close_periods
                   SET status='REOPENED', row_version=?, updated_at=?, updated_by=?
                 WHERE tenant_id=? AND public_id=?
                   AND status='REOPEN_REQUESTED' AND row_version=?
                """, finalVersion, Timestamp.from(now), command.authority().actorId(),
                command.authority().tenantId(), period.publicId(), period.version() + 1);
        if (reopened != 1) throw versionConflict(period.version() + 1);
        long newRevision = period.closeRevision() + 1;
        Xcon010TimePeriodReopenedV1 event = new Xcon010TimePeriodReopenedV1(
                period.publicId(), newRevision, period.closeRevision(), command.reasonCode());
        insertEvent(command, receiptId, "TimePeriodReopened.v1", "ClosePeriod",
                period.publicId(), newRevision, event, now);
        return finalVersion;
    }

    private UUID handoff(
            PlannedCloseCommand planned,
            ClosePeriod period,
            UUID receiptId,
            Instant now) {
        CloseCommand command = planned.command();
        Xcon008ClosedTimeResultV1 result = planned.selectedResult().result();
        UUID handoffId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tme_payroll_handoffs (
                    public_id, tenant_id, closed_time_result_id, close_period_id,
                    close_period_public_id, close_revision,
                    payroll_entity_public_id, pay_period_public_id, status,
                    worker_count, line_count, source_digest, rule_digest,
                    payload_digest, correlation_id, row_version, created_at,
                    created_by_subject_public_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                """, handoffId, command.authority().tenantId(),
                planned.selectedResult().internalId(), period.internalId(), period.publicId(),
                result.closeRevision(), result.payrollEntityId(), result.payPeriodId(),
                result.workerCount(), result.lineCount(), result.sourceDigest(),
                result.ruleDigest(), result.payloadDigest(),
                command.authority().correlationId(), Timestamp.from(now),
                command.authority().subjectPrincipalPublicId(), Timestamp.from(now));
        Xcon011PayrollTimeHandoffReadyV1 event = new Xcon011PayrollTimeHandoffReadyV1(
                period.publicId(), result.closeRevision(), result.closedTimeResultId(),
                handoffId, result.lineCount(), result.payPeriodId(), result.payloadDigest(),
                result.payrollEntityId(), result.ruleDigest(), result.sourceDigest(),
                result.workerCount());
        insertEvent(command, receiptId, "PayrollTimeHandoffReady.v1", "PayrollHandoff",
                handoffId, 1, event, now);
        return handoffId;
    }

    private void insertResult(
            CloseCommand command,
            ClosePeriod period,
            CloseEvidence evidence,
            UUID resultId,
            long closeRevision,
            Instant now) {
        Set<UUID> workers = new HashSet<>();
        evidence.lines().forEach(line -> workers.add(line.workerId()));
        TimeCloseResultIntegrity.verifyEvidence(evidence.lines(), evidence.sourceDigest());
        List<Xcon008ClosedTimeResultV1.ClosedTimeLine> xconLines = evidence.lines().stream()
                .map(ResultLine::toXcon)
                .toList();
        String payloadDigest = TimeCloseResultIntegrity.payloadDigest(
                period.publicId(), closeRevision, resultId,
                evidence.ledgerRevisionFrom(), evidence.ledgerRevisionTo(),
                period.payPeriodId(), period.payrollEntityId(), evidence.ruleDigest(),
                evidence.sourceDigest(), period.latestResultId(), workers.size(), xconLines);
        long resultInternalId = jdbc.queryForObject("""
                INSERT INTO tme_closed_time_results (
                    public_id, tenant_id, close_period_id, close_revision,
                    payroll_entity_public_id, pay_period_public_id,
                    ledger_revision_from, ledger_revision_to, worker_count, line_count,
                    source_digest, rule_digest, payload_digest,
                    supersedes_result_public_id, created_at,
                    created_by_subject_public_id, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING closed_time_result_id
                """, Long.class, resultId, command.authority().tenantId(),
                period.internalId(), closeRevision, period.payrollEntityId(),
                period.payPeriodId(), evidence.ledgerRevisionFrom(),
                evidence.ledgerRevisionTo(), workers.size(), evidence.lines().size(),
                evidence.sourceDigest(), evidence.ruleDigest(), payloadDigest,
                period.latestResultId(), Timestamp.from(now),
                command.authority().subjectPrincipalPublicId(),
                command.authority().correlationId());
        int lineNo = 0;
        for (ResultLine line : evidence.lines()) {
            long lineId = jdbc.queryForObject("""
                    INSERT INTO tme_closed_time_result_lines (
                        public_id, tenant_id, closed_time_result_id, line_no,
                        worker_public_id, assignment_public_id, pay_code, unit,
                        quantity, currency_code, source_entry_count, source_entry_digest)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING closed_time_result_line_id
                    """, Long.class, UUID.randomUUID(), command.authority().tenantId(),
                    resultInternalId, ++lineNo, line.workerId(), line.assignmentId(),
                    line.payCode(), line.unit(), line.quantity(),
                    line.currency() == null ? "XXX" : line.currency(),
                    line.sources().size(), line.sourceEntryDigest());
            int sourceNo = 0;
            for (SourceEvidence source : line.sources()) {
                jdbc.update("""
                        INSERT INTO tme_closed_time_result_sources (
                            tenant_id, closed_time_result_line_id, source_sequence,
                            ledger_entry_public_id, ledger_entry_posted_at,
                            ledger_revision, contribution_quantity,
                            contribution_unit, entry_digest)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, command.authority().tenantId(), lineId, ++sourceNo,
                        source.ledgerEntryId(), Timestamp.from(source.postedAt()),
                        source.ledgerRevision(), source.quantity(), source.unit(),
                        source.entryDigest());
            }
        }
    }

    private UUID insertAttempt(
            CloseCommand command,
            ClosePeriod period,
            CloseEvidence evidence,
            TimeCloseModels.ApprovalEvidence approval,
            UUID receiptId,
            Instant now) {
        int next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_no), 0) + 1
                  FROM tme_close_attempts
                 WHERE tenant_id=? AND close_period_id=?
                """, Integer.class, command.authority().tenantId(), period.internalId());
        UUID attemptId = UUID.randomUUID();
        List<CloseBlocker> blockers = evidence == null ? List.of() : evidence.blockers();
        String status = blockers.isEmpty() ? "SUCCEEDED" : "BLOCKED";
        String blockerJson = json(blockers);
        String input = TimeCloseDigests.sha256(
                "TimeCloseAttemptInput.v1", command.requestDigest(),
                evidence == null ? "" : evidence.sourceDigest(),
                evidence == null ? "" : evidence.ruleDigest());
        String output = TimeCloseDigests.sha256(
                "TimeCloseAttemptOutput.v1", status, blockerJson,
                evidence == null ? "" : evidence.sourceDigest(),
                evidence == null ? "" : evidence.ruleDigest());
        jdbc.update("""
                INSERT INTO tme_close_attempts (
                    public_id, tenant_id, close_period_id,
                    command_receipt_public_id, attempt_no, requested_action, status,
                    expected_row_version, blocker_snapshot, blocker_count,
                    input_digest, output_digest, approval_receipt_public_id,
                    approval_actor_subject_public_id, requested_at, completed_at,
                    requested_by_subject_public_id, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, attemptId, command.authority().tenantId(), period.internalId(),
                receiptId, next, command.kind().name(), status,
                command.expectedVersion(), blockerJson, blockers.size(), input, output,
                approval == null ? null : approval.approvalReceiptId(),
                approval == null ? null : approval.approvalActorSubjectId(),
                Timestamp.from(now), Timestamp.from(now),
                command.authority().subjectPrincipalPublicId(),
                command.authority().correlationId());
        return attemptId;
    }

    private void insertAcceptedReceipt(CloseCommand command, UUID receiptId, Instant now) {
        jdbc.update("""
                INSERT INTO tme_close_command_receipts (
                    public_id, tenant_id, idempotency_key, route_contract_key,
                    command_type, originating_action, originating_population_kind,
                    actor_id, subject_principal_public_id, population_public_id,
                    population_revision, grant_revision, population_scope_digest,
                    field_policy_revision, purpose_code, authorization_revision,
                    required_permission, request_digest, aggregate_public_id,
                    status, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'TARGET_POPULATION', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, 'ACCEPTED', ?, ?)
                """, receiptId, command.authority().tenantId(), command.idempotencyKey(),
                command.authority().routeContractKey(), command.kind().commandType(),
                command.kind().originatingAction(), command.authority().actorId(),
                command.authority().subjectPrincipalPublicId(),
                command.authority().populationPublicId(),
                command.authority().populationRevision(),
                command.authority().grantRevision(),
                command.authority().populationScopeDigest(),
                command.authority().fieldPolicyRevision(), command.kind().purpose(),
                command.authority().authorizationRevision(), requiredPermission(command.kind()),
                command.requestDigest(), command.periodId(),
                command.authority().correlationId(), Timestamp.from(now));
    }

    private void insertEvent(
            CloseCommand command,
            UUID receiptId,
            String type,
            String aggregateType,
            UUID aggregateId,
            long sequence,
            Object data,
            Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specVersion", "1.0");
        envelope.put("id", eventId);
        envelope.put("source", "dwp-time-server");
        envelope.put("type", type);
        envelope.put("schemaVersion", 1);
        envelope.put("time", occurredAt);
        envelope.put("tenantId", command.authority().tenantId());
        envelope.put("aggregateType", aggregateType);
        envelope.put("aggregateId", aggregateId);
        envelope.put("aggregateSequence", sequence);
        envelope.put("correlationId", command.authority().correlationId());
        envelope.put("causationId", receiptId);
        envelope.put("data", data);
        String payload = json(envelope);
        String payloadDigest = jdbc.queryForObject("""
                SELECT encode(sha256(convert_to(CAST(? AS jsonb)::TEXT, 'UTF8')), 'hex')
                """, String.class, payload);
        jdbc.update("""
                INSERT INTO tme_close_outbox_events (
                    tenant_id, event_id, aggregate_type, aggregate_public_id,
                    aggregate_sequence, schema_name, schema_version, partition_key,
                    payload, payload_digest, correlation_id, causation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """, command.authority().tenantId(), eventId, aggregateType,
                aggregateId, sequence, type,
                command.authority().tenantId() + "|dwp-time-server|"
                        + aggregateType + "|" + aggregateId,
                payload, payloadDigest,
                command.authority().correlationId(), receiptId,
                Timestamp.from(occurredAt));
    }

    private OutcomeApplication applyOutcomeInTenant(
            long tenantId,
            UUID handoffId,
            String digest,
            PayrollTimeHandoffOutcomeV1 outcome) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", ignored -> {},
                "tim-close-outcome:" + tenantId + ":" + handoffId);
        HandoffRecord locked = handoff(tenantId, handoffId, true)
                .orElseThrow(JdbcTimeCloseRepository::opaqueNotFound);
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT outcome_command_digest, outcome
                  FROM tme_payroll_handoff_outcomes
                 WHERE tenant_id=? AND handoff_public_id=?
                   AND receiver_receipt_public_id=? AND receiver_receipt_revision=?
                """, tenantId, handoffId, outcome.receiverReceiptId(),
                outcome.receiverReceiptRevision());
        if (!existing.isEmpty()) {
            String prior = ((String) existing.getFirst()
                    .get("outcome_command_digest")).trim();
            if (prior.equals(digest)) return new OutcomeApplication(locked.projection(), false);
            quarantine(tenantId, handoffId, "OUTCOME_DIGEST_CONFLICT", digest,
                    outcome.correlationId());
            return new OutcomeApplication(locked.projection(), true);
        }
        PayrollTimeHandoffStatusV1 current = locked.projection();
        if (!TimeCloseHandoffRecovery.accepts(locked, outcome)) {
            quarantine(tenantId, handoffId, "OUTCOME_LINEAGE_CONFLICT", digest,
                    outcome.correlationId());
            return new OutcomeApplication(current, true);
        }
        jdbc.update("""
                INSERT INTO tme_payroll_handoff_outcomes (
                    tenant_id, handoff_public_id, receiver_receipt_public_id,
                    receiver_receipt_revision, outcome_command_digest, outcome,
                    correlation_id, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, handoffId, outcome.receiverReceiptId(),
                outcome.receiverReceiptRevision(), digest, outcome.outcome().name(),
                outcome.correlationId(), Timestamp.from(clock.instant()));
        int updated = jdbc.update("""
                UPDATE tme_payroll_handoffs
                   SET status=?, receiver_receipt_public_id=?,
                       receiver_receipt_revision=?, receiver_receipt_digest=?,
                       receiver_reason_code=?, outcome_at=?, row_version=row_version+1,
                       updated_at=?
                 WHERE tenant_id=? AND public_id=? AND row_version=?
                """, outcome.outcome().name(), outcome.receiverReceiptId(),
                outcome.receiverReceiptRevision(), outcome.receiverReceiptDigest(),
                outcome.reasonCode(), Timestamp.from(outcome.outcomeAt()),
                Timestamp.from(clock.instant()), tenantId, handoffId, current.version());
        if (updated != 1) throw versionConflict(current.version());
        return new OutcomeApplication(
                handoff(tenantId, handoffId, false).orElseThrow().projection(), false);
    }

    private void quarantine(
            long tenantId,
            UUID handoffId,
            String reason,
            String evidenceDigest,
            UUID correlationId) {
        jdbc.update("""
                INSERT INTO tme_close_quarantine (
                    public_id, tenant_id, handoff_public_id, reason_code,
                    evidence_digest, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, handoffId, reason,
                evidenceDigest, correlationId, Timestamp.from(clock.instant()));
    }

    private Optional<ClosePeriod> period(long tenantId, UUID periodId, boolean lock) {
        List<ClosePeriod> rows = jdbc.query("""
                SELECT close_period_id, tenant_id, public_id, close_scope,
                       legal_entity_public_id, time_group_public_id,
                       payroll_entity_public_id, pay_period_public_id,
                       period_start, period_end, status, close_revision,
                       ledger_revision, latest_result_public_id,
                       closed_by_subject_public_id,
                       close_approval_actor_public_id,
                       latest_correction_maker_subject_public_id, row_version
                  FROM tme_close_periods
                 WHERE tenant_id=? AND public_id=?
                """ + (lock ? " FOR UPDATE" : ""), (row, ignored) -> new ClosePeriod(
                        row.getLong("close_period_id"), row.getLong("tenant_id"),
                        row.getObject("public_id", UUID.class), row.getString("close_scope"),
                        row.getObject("legal_entity_public_id", UUID.class),
                        row.getObject("time_group_public_id", UUID.class),
                        row.getObject("payroll_entity_public_id", UUID.class),
                        row.getObject("pay_period_public_id", UUID.class),
                        row.getDate("period_start").toLocalDate(),
                        row.getDate("period_end").toLocalDate(),
                        CloseState.valueOf(row.getString("status")),
                        row.getLong("close_revision"), nullableLong(row, "ledger_revision"),
                        row.getObject("latest_result_public_id", UUID.class),
                        row.getObject("closed_by_subject_public_id", UUID.class),
                        row.getObject("close_approval_actor_public_id", UUID.class),
                        row.getObject("latest_correction_maker_subject_public_id", UUID.class),
                        row.getLong("row_version")), tenantId, periodId);
        return rows.stream().findFirst();
    }

    private Optional<PersistedClosedResult> result(long tenantId, UUID resultId) {
        List<ResultHeader> headers = jdbc.query("""
                SELECT closed_time_result_id, public_id, close_period_id,
                       close_revision, payroll_entity_public_id, pay_period_public_id,
                       ledger_revision_from, ledger_revision_to, worker_count, line_count,
                       source_digest, rule_digest, payload_digest,
                       supersedes_result_public_id, created_at,
                       (SELECT public_id FROM tme_close_periods p
                         WHERE p.tenant_id=r.tenant_id
                           AND p.close_period_id=r.close_period_id) AS close_period_public_id
                  FROM tme_closed_time_results r
                 WHERE tenant_id=? AND public_id=?
                """, (row, ignored) -> new ResultHeader(
                        row.getLong("closed_time_result_id"),
                        row.getObject("public_id", UUID.class),
                        row.getObject("close_period_public_id", UUID.class),
                        row.getLong("close_revision"),
                        row.getObject("payroll_entity_public_id", UUID.class),
                        row.getObject("pay_period_public_id", UUID.class),
                        row.getLong("ledger_revision_from"),
                        row.getLong("ledger_revision_to"), row.getInt("worker_count"),
                        row.getInt("line_count"), row.getString("source_digest").trim(),
                        row.getString("rule_digest").trim(),
                        row.getString("payload_digest").trim(),
                        row.getObject("supersedes_result_public_id", UUID.class),
                        row.getTimestamp("created_at").toInstant()), tenantId, resultId);
        if (headers.isEmpty()) return Optional.empty();
        ResultHeader header = headers.getFirst();
        List<ResultSourceRow> rows = jdbc.query("""
                SELECT l.closed_time_result_line_id, l.line_no,
                       l.worker_public_id, l.assignment_public_id, l.pay_code,
                       l.quantity, l.currency_code, l.source_entry_count,
                       l.source_entry_digest, l.unit, s.source_sequence,
                       s.ledger_entry_public_id, s.ledger_entry_posted_at,
                       s.ledger_revision, s.contribution_quantity,
                       s.contribution_unit, s.entry_digest
                  FROM tme_closed_time_result_lines l
                  JOIN tme_closed_time_result_sources s
                    ON s.tenant_id=l.tenant_id
                   AND s.closed_time_result_line_id=l.closed_time_result_line_id
                 WHERE l.tenant_id=? AND l.closed_time_result_id=?
                 ORDER BY l.line_no, s.source_sequence
                """, (row, ignored) -> new ResultSourceRow(
                        row.getLong("closed_time_result_line_id"), row.getInt("line_no"),
                        row.getObject("worker_public_id", UUID.class),
                        row.getObject("assignment_public_id", UUID.class),
                        row.getString("pay_code"), row.getBigDecimal("quantity"),
                        row.getString("currency_code").trim(),
                        row.getInt("source_entry_count"),
                        row.getString("source_entry_digest").trim(), row.getString("unit"),
                        row.getInt("source_sequence"),
                        row.getObject("ledger_entry_public_id", UUID.class),
                        row.getTimestamp("ledger_entry_posted_at").toInstant(),
                        row.getLong("ledger_revision"),
                        row.getBigDecimal("contribution_quantity"),
                        row.getString("contribution_unit"),
                        row.getString("entry_digest").trim()), tenantId, header.internalId());
        List<ResultLine> verifiedLines = new ArrayList<>(header.lineCount());
        int rowIndex = 0;
        for (int expectedLine = 1; expectedLine <= header.lineCount(); expectedLine++) {
            if (rowIndex >= rows.size() || rows.get(rowIndex).lineNo() != expectedLine) {
                throw TimeCloseResultIntegrity.corrupt(
                        "Closed-time result line sequence is not contiguous.");
            }
            ResultSourceRow first = rows.get(rowIndex);
            List<SourceEvidence> sources = new ArrayList<>(first.sourceCount());
            for (int expectedSource = 1; expectedSource <= first.sourceCount(); expectedSource++) {
                if (rowIndex >= rows.size()) {
                    throw TimeCloseResultIntegrity.corrupt(
                            "Closed-time result source count is mismatched.");
                }
                ResultSourceRow source = rows.get(rowIndex++);
                if (source.lineId() != first.lineId()
                        || source.lineNo() != expectedLine
                        || source.sourceSequence() != expectedSource) {
                    throw TimeCloseResultIntegrity.corrupt(
                            "Closed-time result source sequence is not contiguous.");
                }
                sources.add(new SourceEvidence(
                        source.ledgerEntryId(), source.postedAt(), source.ledgerRevision(),
                        source.contributionQuantity(), source.contributionUnit(),
                        source.entryDigest()));
            }
            String currency = "XXX".equals(first.currency()) ? null : first.currency();
            verifiedLines.add(new ResultLine(
                    first.workerId(), first.assignmentId(), first.payCode(), first.quantity(),
                    first.unit(), currency, first.sourceEntryDigest(), sources));
        }
        if (rowIndex != rows.size() || verifiedLines.size() != header.lineCount()) {
            throw TimeCloseResultIntegrity.corrupt(
                    "Closed-time result contains trailing or missing evidence.");
        }
        TimeCloseResultIntegrity.verifyEvidence(verifiedLines, header.sourceDigest());
        long workerCount = verifiedLines.stream().map(ResultLine::workerId).distinct().count();
        if (workerCount != header.workerCount()) {
            throw TimeCloseResultIntegrity.corrupt(
                    "Closed-time result worker count is mismatched.");
        }
        List<Xcon008ClosedTimeResultV1.ClosedTimeLine> lines = verifiedLines.stream()
                .map(ResultLine::toXcon)
                .toList();
        String payloadDigest = TimeCloseResultIntegrity.payloadDigest(
                header.closePeriodId(), header.closeRevision(), header.publicId(),
                header.ledgerFrom(), header.ledgerTo(), header.payPeriodId(),
                header.payrollEntityId(), header.ruleDigest(), header.sourceDigest(),
                header.supersedesId(), header.workerCount(), lines);
        if (!payloadDigest.equals(header.payloadDigest())) {
            throw TimeCloseResultIntegrity.corrupt(
                    "Closed-time result payload digest is mismatched.");
        }
        Xcon008ClosedTimeResultV1 snapshot = new Xcon008ClosedTimeResultV1(
                header.closePeriodId(), header.closeRevision(), header.publicId(),
                header.ledgerFrom(), header.ledgerTo(), header.lineCount(), lines,
                header.payPeriodId(), header.payloadDigest(), header.payrollEntityId(),
                header.ruleDigest(), header.sourceDigest(), header.supersedesId(),
                header.workerCount());
        return Optional.of(new PersistedClosedResult(
                header.internalId(), snapshot, header.createdAt()));
    }

    private Optional<HandoffRecord> handoff(long tenantId, UUID handoffId, boolean lock) {
        List<HandoffRecord> rows = jdbc.query("""
                SELECT payroll_handoff_id, tenant_id, public_id,
                       close_period_public_id, r.public_id AS result_public_id,
                       h.close_revision, h.payroll_entity_public_id,
                       h.pay_period_public_id, h.worker_count, h.line_count,
                       h.source_digest, h.rule_digest, h.payload_digest, h.status,
                       h.correlation_id,
                       h.receiver_receipt_public_id, h.receiver_receipt_revision,
                       h.receiver_receipt_digest, h.receiver_reason_code,
                       h.outcome_at, h.created_at, h.updated_at, h.row_version
                  FROM tme_payroll_handoffs h
                  JOIN tme_closed_time_results r
                    ON r.tenant_id=h.tenant_id
                   AND r.closed_time_result_id=h.closed_time_result_id
                 WHERE h.tenant_id=? AND h.public_id=?
                """ + (lock ? " FOR UPDATE OF h" : ""), (row, ignored) ->
                        new HandoffRecord(
                                row.getLong("payroll_handoff_id"),
                                row.getLong("tenant_id"),
                                row.getObject("correlation_id", UUID.class),
                                new PayrollTimeHandoffStatusV1(
                                        row.getObject("public_id", UUID.class),
                                        row.getObject("close_period_public_id", UUID.class),
                                        row.getObject("result_public_id", UUID.class),
                                        row.getLong("close_revision"),
                                        row.getObject("payroll_entity_public_id", UUID.class),
                                        row.getObject("pay_period_public_id", UUID.class),
                                        row.getInt("worker_count"), row.getInt("line_count"),
                                        row.getString("source_digest").trim(),
                                        row.getString("rule_digest").trim(),
                                        row.getString("payload_digest").trim(),
                                        HandoffState.valueOf(row.getString("status")),
                                        row.getObject("receiver_receipt_public_id", UUID.class),
                                        nullableLong(row, "receiver_receipt_revision"),
                                        trim(row.getString("receiver_receipt_digest")),
                                        row.getString("receiver_reason_code"),
                                        instant(row.getTimestamp("outcome_at")),
                                        row.getTimestamp("created_at").toInstant(),
                                        row.getTimestamp("updated_at").toInstant(),
                                        row.getLong("row_version"))), tenantId, handoffId);
        return rows.stream().findFirst();
    }

    private Optional<StoredReceipt> receiptByIdempotency(
            long tenantId, UUID subject, String route, String key) {
        return receipts("""
                WHERE tenant_id=? AND subject_principal_public_id=?
                  AND route_contract_key=? AND idempotency_key=?
                """, tenantId, subject, route, key).stream().findFirst();
    }

    private Optional<StoredReceipt> receiptById(long tenantId, UUID receiptId) {
        return receipts("WHERE tenant_id=? AND public_id=?", tenantId, receiptId)
                .stream().findFirst();
    }

    private List<StoredReceipt> receipts(String predicate, Object... arguments) {
        return jdbc.query("""
                SELECT public_id, idempotency_key, route_contract_key,
                       command_type, originating_action,
                       subject_principal_public_id, population_public_id,
                       population_revision, grant_revision, population_scope_digest,
                       field_policy_revision, purpose_code, authorization_revision,
                       required_permission, request_digest, aggregate_public_id,
                       aggregate_version, status, result_ref, correlation_id, created_at
                  FROM tme_close_command_receipts
                """ + predicate, (row, ignored) -> {
                    TimeCloseCommandReceiptV1 receipt = new TimeCloseCommandReceiptV1(
                            row.getObject("public_id", UUID.class),
                            row.getString("command_type"), row.getString("originating_action"),
                            row.getObject("subject_principal_public_id", UUID.class),
                            row.getString("population_scope_digest").trim(),
                            row.getLong("field_policy_revision"),
                            row.getString("purpose_code"),
                            row.getLong("authorization_revision"), row.getString("status"),
                            row.getObject("correlation_id", UUID.class),
                            row.getString("request_digest").trim(),
                            row.getTimestamp("created_at").toInstant(),
                            nullableLong(row, "aggregate_version"), row.getString("result_ref"));
                    return new StoredReceipt(
                            receipt, row.getString("idempotency_key"),
                            row.getString("route_contract_key"),
                            row.getObject("population_public_id", UUID.class),
                            row.getLong("population_revision"), row.getLong("grant_revision"),
                            row.getString("required_permission"),
                            row.getObject("aggregate_public_id", UUID.class));
                }, arguments);
    }

    private static void assertState(CommandKind kind, ClosePeriod period) {
        boolean allowed = switch (kind) {
            case VALIDATE -> Set.of(CloseState.OPEN, CloseState.REOPENED, CloseState.FAILED)
                    .contains(period.state());
            case CLOSE -> Set.of(CloseState.OPEN, CloseState.REOPENED)
                    .contains(period.state());
            case REOPEN -> period.state() == CloseState.CLOSED
                    && period.latestResultId() != null;
            case HANDOFF -> period.state() == CloseState.CLOSED
                    && period.latestResultId() != null;
        };
        if (!allowed) throw new BaseException(
                ErrorCode.RESOURCE_CONFLICT, "The close period state changed.");
    }

    private <T> T inTenant(long tenantId, Supplier<T> work) {
        T result = transactions.execute(status -> {
            String expected = Long.toString(tenantId);
            String actual = jdbc.queryForObject(SET_TENANT, String.class, expected);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return work.get();
        });
        return Objects.requireNonNull(result, "Time close transaction returned null");
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Time close evidence cannot be encoded", invalid);
        }
    }

    private static String requiredPermission(CommandKind kind) {
        return kind == CommandKind.REOPEN
                ? "ACTION.HRIS_TIME_REOPEN:MANAGE"
                : "ACTION.HRIS_TIME_CLOSE:EXECUTE";
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw new IllegalArgumentException("tenantId must be positive");
    }

    private static Long nullableLong(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static BaseException opaqueNotFound() {
        return new BaseException(ErrorCode.NOT_FOUND, "The close resource was not found.");
    }

    private static TimeCloseBoundaryFailure versionConflict(long currentVersion) {
        return new TimeCloseBoundaryFailure(
                412, "PRECONDITION_FAILED", false, currentVersion,
                "The close period aggregate version changed.");
    }

    private record ResultHeader(
            long internalId,
            UUID publicId,
            UUID closePeriodId,
            long closeRevision,
            UUID payrollEntityId,
            UUID payPeriodId,
            long ledgerFrom,
            long ledgerTo,
            int workerCount,
            int lineCount,
            String sourceDigest,
            String ruleDigest,
            String payloadDigest,
            UUID supersedesId,
            Instant createdAt) {}

    private record ResultSourceRow(
            long lineId,
            int lineNo,
            UUID workerId,
            UUID assignmentId,
            String payCode,
            java.math.BigDecimal quantity,
            String currency,
            int sourceCount,
            String sourceEntryDigest,
            String unit,
            int sourceSequence,
            UUID ledgerEntryId,
            Instant postedAt,
            long ledgerRevision,
            java.math.BigDecimal contributionQuantity,
            String contributionUnit,
            String entryDigest) {}

    private record OutcomeApplication(
            PayrollTimeHandoffStatusV1 projection, boolean conflict) {}
}
