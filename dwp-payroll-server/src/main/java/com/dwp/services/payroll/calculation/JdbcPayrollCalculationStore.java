package com.dwp.services.payroll.calculation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Attempt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculationOutcome;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Checkpoint;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandIdentity;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.EventFact;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Lease;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PersistedReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ReceiptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcPayrollCalculationStore extends PayrollCalculationJdbcSupport implements PayrollCalculationStore {

    private final PayrollCalculationReceiptJdbc receipts;
    private final PayrollCalculationSnapshotJdbc snapshots;
    private final PayrollCalculationAttemptJdbc attempts;
    private final PayrollCalculationProgressJdbc progress;
    private final PayrollCalculationResultJdbc results;

    JdbcPayrollCalculationStore(
            NamedParameterJdbcTemplate jdbc,
            PayrollCalculationCanonical canonical) {
        super(jdbc, canonical);
        this.receipts = new PayrollCalculationReceiptJdbc(jdbc, canonical);
        this.snapshots = new PayrollCalculationSnapshotJdbc(jdbc, canonical);
        this.attempts = new PayrollCalculationAttemptJdbc(jdbc, canonical);
        this.results = new PayrollCalculationResultJdbc(jdbc, canonical);
        this.progress = new PayrollCalculationProgressJdbc(jdbc, canonical, results, attempts, receipts);
    }

    @Override
    public void lockRun(long tenantId, UUID runId) {
        super.lockRun(tenantId, runId);
    }

    @Override
    public Optional<PersistedReceipt> command(CommandIdentity identity) {
        return receipts.command(identity);
    }

    @Override
    public Optional<PersistedReceipt> receipt(long tenantId, UUID receiptId) {
        return receipts.receipt(tenantId, receiptId);
    }

    @Override
    public Optional<RunRevision> currentRun(long tenantId, UUID runId) {
        return attempts.currentRun(tenantId, runId);
    }

    @Override
    public Optional<AttemptStatus> currentAttemptStatus(
            long tenantId, UUID runId, int attemptNo) {
        return attempts.currentAttemptStatus(tenantId, runId, attemptNo);
    }

    @Override
    public Preparation prepare(
            RunRevision run,
            int attemptNo,
            String targetPopulationDigest,
            Instant pinnedAt) {
        return snapshots.prepare(run, attemptNo, targetPopulationDigest, pinnedAt);
    }

    @Override
    public void freeze(Preparation preparation) {
        snapshots.freeze(preparation);
    }

    @Override
    public void appendAttempt(Attempt attempt) {
        attempts.appendAttempt(attempt);
    }

    @Override
    public void appendAttemptStatus(AttemptStatus status) {
        attempts.appendAttemptStatus(status);
    }

    @Override
    public void appendRunRevision(RunRevision revision) {
        attempts.appendRunRevision(revision);
    }

    @Override
    public void insertReceipt(PersistedReceipt persisted) {
        receipts.insertReceipt(persisted);
    }

    @Override
    public void appendReceiptStatus(
            long tenantId, UUID receiptId, ReceiptStatus status, Instant occurredAt) {
        receipts.appendReceiptStatus(tenantId, receiptId, status, occurredAt);
    }

    @Override
    public void appendAudit(
            PersistedReceipt receipt,
            long runRevision,
            String eventType,
            String eventPayload,
            Instant occurredAt) {
        receipts.appendAudit(receipt, runRevision, eventType, eventPayload, occurredAt);
    }

    @Override
    public void appendEvent(long tenantId, EventFact event) {
        receipts.appendEvent(tenantId, event);
    }

    @Override
    public Optional<Lease> claim(
            long tenantId,
            UUID runId,
            int attemptNo,
            String workerInstanceId,
            Instant now,
            Duration leaseDuration) {
        return attempts.claim(tenantId, runId, attemptNo, workerInstanceId, now, leaseDuration);
    }

    @Override
    public Optional<Lease> renew(Lease lease, Instant now, Duration leaseDuration) {
        return attempts.renew(lease, now, leaseDuration);
    }

    @Override
    public void verifyLease(Lease lease) {
        attempts.verifyLease(lease);
    }

    @Override
    public FrozenSnapshot snapshot(long tenantId, UUID runId, int attemptNo) {
        return snapshots.snapshot(tenantId, runId, attemptNo);
    }

    @Override
    public FrozenSnapshot cloneSnapshot(
            long tenantId,
            UUID runId,
            int sourceAttemptNo,
            int targetAttemptNo) {
        return snapshots.cloneSnapshot(tenantId, runId, sourceAttemptNo, targetAttemptNo);
    }

    @Override
    public Optional<Checkpoint> latestCheckpoint(long tenantId, UUID runId, int attemptNo) {
        return progress.latestCheckpoint(tenantId, runId, attemptNo);
    }

    @Override
    public void verifyCheckpoint(
            Lease lease, Checkpoint checkpoint, CalculationOutcome outcome) {
        progress.verifyCheckpoint(lease, checkpoint, outcome);
    }

    @Override
    public void appendProgress(
            Lease lease,
            CalculationOutcome outcome,
            int fromInputOrdinal,
            int toInputOrdinal,
            Checkpoint checkpoint,
            Instant recordedAt) {
        progress.appendProgress(lease, outcome, fromInputOrdinal, toInputOrdinal, checkpoint, recordedAt);
    }

    @Override
    public void complete(
            Lease lease,
            CalculationOutcome outcome,
            RunRevision completedRun,
            UUID receiptId,
            EventFact event,
            String auditPayload,
            Instant completedAt) {
        progress.complete(lease, outcome, completedRun, receiptId, event, auditPayload, completedAt);
    }

    @Override
    public void terminalFailure(
            Lease lease,
            UUID receiptId,
            AttemptState state,
            String failureCode,
            RunRevision failedRun,
            String auditPayload,
            Instant occurredAt) {
        progress.terminalFailure(lease, receiptId, state, failureCode, failedRun, auditPayload, occurredAt);
    }

    @Override
    public boolean completionExists(
            long tenantId, UUID runId, int attemptNo, CalculationOutcome outcome) {
        return progress.completionExists(tenantId, runId, attemptNo, outcome);
    }

    @Override
    public long outboxCount(long tenantId, UUID runId, String eventType) {
        return attempts.outboxCount(tenantId, runId, eventType);
    }

    @Override
    @Transactional
    public List<PayrollCalculationStore.ClaimableAttempt> claimable(
            long tenantId,
            int limit,
            String workerInstanceId,
            Instant now,
            Duration leaseDuration) {
        return attempts.claimable(tenantId, limit, workerInstanceId, now, leaseDuration);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayrollCalculationStore.RecoveryCandidate> recoveryCandidates(
            long tenantId, int limit) {
        return attempts.recoveryCandidates(tenantId, limit);
    }
}
