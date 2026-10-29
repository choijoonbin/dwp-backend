package com.dwp.services.payroll.calculation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Attempt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculationOutcome;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Checkpoint;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandIdentity;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.EventFact;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticContract.FormulaBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Lease;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PersistedReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SourceInput;

interface PayrollCalculationStore {

    void bindTenant(long tenantId);

    void lockRun(long tenantId, UUID runId);

    Optional<PersistedReceipt> command(CommandIdentity identity);

    Optional<PersistedReceipt> receipt(long tenantId, UUID receiptId);

    Optional<RunRevision> currentRun(long tenantId, UUID runId);

    Optional<AttemptStatus> currentAttemptStatus(long tenantId, UUID runId, int attemptNo);

    Preparation prepare(
            RunRevision run,
            int attemptNo,
            String targetPopulationDigest,
            Instant pinnedAt);

    void freeze(Preparation preparation);

    void appendAttempt(Attempt attempt);

    void appendAttemptStatus(AttemptStatus status);

    void appendRunRevision(RunRevision revision);

    void insertReceipt(PersistedReceipt receipt);

    void appendReceiptStatus(long tenantId, UUID receiptId,
                             PayrollCalculationModels.ReceiptStatus status, Instant occurredAt);

    void appendAudit(
            PersistedReceipt receipt,
            long runRevision,
            String eventType,
            String eventPayload,
            Instant occurredAt);

    void appendEvent(long tenantId, EventFact event);

    Optional<Lease> claim(
            long tenantId,
            UUID runId,
            int attemptNo,
            String workerInstanceId,
            Instant now,
            Duration leaseDuration);

    Optional<Lease> renew(Lease lease, Instant now, Duration leaseDuration);

    void verifyLease(Lease lease);

    FrozenSnapshot snapshot(long tenantId, UUID runId, int attemptNo);

    FrozenSnapshot cloneSnapshot(
            long tenantId,
            UUID runId,
            int sourceAttemptNo,
            int targetAttemptNo);

    Optional<Checkpoint> latestCheckpoint(long tenantId, UUID runId, int attemptNo);

    void verifyCheckpoint(Lease lease, Checkpoint checkpoint, CalculationOutcome outcome);

    void appendProgress(
            Lease lease,
            CalculationOutcome outcome,
            int fromInputOrdinal,
            int toInputOrdinal,
            Checkpoint checkpoint,
            Instant recordedAt);

    void complete(
            Lease lease,
            CalculationOutcome outcome,
            RunRevision completedRun,
            UUID receiptId,
            EventFact event,
            String auditPayload,
            Instant completedAt);

    void terminalFailure(
            Lease lease,
            UUID receiptId,
            PayrollCalculationModels.AttemptState state,
            String failureCode,
            RunRevision failedRun,
            String auditPayload,
            Instant occurredAt);

    boolean completionExists(
            long tenantId,
            UUID runId,
            int attemptNo,
            CalculationOutcome outcome);

    long outboxCount(long tenantId, UUID runId, String eventType);

    List<ClaimableAttempt> claimable(
            long tenantId,
            int limit,
            String workerInstanceId,
            Instant now,
            Duration leaseDuration);

    List<RecoveryCandidate> recoveryCandidates(long tenantId, int limit);

    record Preparation(
            UUID payPeriodId,
            UUID legalEntityId,
            UUID payrollGroupId,
            String currency,
            FrozenSnapshot snapshot) {
    }

    record ClaimableAttempt(Lease lease) {
        public ClaimableAttempt {
            if (lease == null) throw new IllegalArgumentException("lease is required");
        }
    }

    record RecoveryCandidate(long tenantId, UUID runId) {
    }
}
