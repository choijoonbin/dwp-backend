package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.HeuristicCompletionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Attempt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculationOutcome;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Checkpoint;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.EventFact;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Lease;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PersistedReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ReceiptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunState;

/**
 * Bounded, explicitly invoked worker. Every lease claim, checkpoint and terminal
 * decision is committed independently so process loss cannot roll back earlier facts.
 */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollCalculationWorker {

    enum FailurePoint {
        NONE,
        AFTER_FIRST_CHECKPOINT_COMMIT,
        BEFORE_COMPLETION_UNCERTAIN,
        AFTER_COMPLETION_COMMIT_UNCERTAIN
    }

    static final class SimulatedWorkerCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SimulatedWorkerCrash() {
            super("simulated Payroll worker crash");
        }
    }

    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private final PayrollCalculationStore store;
    private final PayrollCalculationEngine engine;
    private final PayrollCalculationCanonical canonical;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration leaseDuration;
    private final int checkpointSize;
    private final int maxInputsPerClaim;

    @Autowired
    PayrollCalculationWorker(
            PayrollCalculationStore store,
            PayrollCalculationEngine engine,
            PayrollCalculationCanonical canonical,
            PlatformTransactionManager transactionManager,
            @Value("${dwp.hris.payroll.calculation.worker.lease-duration:PT30S}")
            Duration leaseDuration,
            @Value("${dwp.hris.payroll.calculation.worker.checkpoint-size:100}")
            int checkpointSize,
            @Value("${dwp.hris.payroll.calculation.worker.max-inputs-per-claim:1000}")
            int maxInputsPerClaim) {
        this(store, engine, canonical, transactionManager, Clock.systemUTC(),
                leaseDuration, checkpointSize, maxInputsPerClaim);
    }

    PayrollCalculationWorker(
            PayrollCalculationStore store,
            PayrollCalculationEngine engine,
            PayrollCalculationCanonical canonical,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration leaseDuration,
            int checkpointSize,
            int maxInputsPerClaim) {
        this.store = Objects.requireNonNull(store);
        this.engine = Objects.requireNonNull(engine);
        this.canonical = Objects.requireNonNull(canonical);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || checkpointSize < 1 || checkpointSize > 10_000
                || maxInputsPerClaim < checkpointSize || maxInputsPerClaim > 100_000) {
            throw new IllegalArgumentException("Payroll worker bounds are invalid");
        }
        this.leaseDuration = leaseDuration;
        this.checkpointSize = checkpointSize;
        this.maxInputsPerClaim = maxInputsPerClaim;
    }

    boolean execute(long tenantId, UUID runId, int attemptNo, String workerInstanceId) {
        return execute(tenantId, runId, attemptNo, workerInstanceId, FailurePoint.NONE);
    }

    boolean execute(Lease preclaimedLease) {
        Objects.requireNonNull(preclaimedLease, "preclaimedLease");
        return execute(
                preclaimedLease.tenantId(), preclaimedLease.runId(),
                preclaimedLease.attemptNo(), preclaimedLease.workerInstanceId(),
                FailurePoint.NONE, preclaimedLease);
    }

    boolean execute(
            long tenantId,
            UUID runId,
            int attemptNo,
            String workerInstanceId,
            FailurePoint failurePoint) {
        return execute(tenantId, runId, attemptNo, workerInstanceId,
                failurePoint, null);
    }

    private boolean execute(
            long tenantId,
            UUID runId,
            int attemptNo,
            String workerInstanceId,
            FailurePoint failurePoint,
            Lease preclaimedLease) {
        validateWorkerInstance(workerInstanceId);
        Start start = transaction.execute(status -> start(
                tenantId, runId, attemptNo, workerInstanceId,
                preclaimedLease, clock.instant()));
        if (start == null) return false;
        Lease activeLease = start.lease();

        FrozenSnapshot snapshot = transaction.execute(status ->
                store.snapshot(tenantId, runId, attemptNo));
        if (snapshot == null) throw new IllegalStateException("snapshot transaction returned null");
        if (snapshot.inputs().size() > maxInputsPerClaim) {
            terminal(start, activeLease, AttemptState.FAILED,
                    "WORK_CLAIM_BOUND_EXCEEDED", null);
            return false;
        }
        CalculationOutcome outcome;
        try {
            outcome = engine.calculate(snapshot);
        } catch (RuntimeException exception) {
            if (terminal(start, activeLease, AttemptState.FAILED,
                    failureCode(exception), null)) throw exception;
            return false;
        }

        try {
            activeLease = ensureLease(activeLease);
            Optional<Checkpoint> latest = transaction.execute(status ->
                    store.latestCheckpoint(tenantId, runId, attemptNo));
            Checkpoint prior = latest == null ? null : latest.orElse(null);
            if (prior != null) {
                Lease checkpointOwner = activeLease;
                try {
                    transaction.executeWithoutResult(status ->
                            store.verifyCheckpoint(checkpointOwner, prior, outcome));
                } catch (BaseException exception) {
                    if (exception.getErrorCode() == ErrorCode.RESOURCE_CONFLICT) {
                        throw new ResultUncertain("CHECKPOINT_INTEGRITY_MISMATCH");
                    }
                    throw exception;
                }
            }
            int nextOrdinal = prior == null ? 1 : prior.nextInputOrdinal();
            int checkpointNo = prior == null ? 0 : prior.checkpointNo();
            boolean firstCommittedThisClaim = false;
            while (nextOrdinal <= outcome.lines().size()) {
                int from = nextOrdinal;
                int to = Math.min(outcome.lines().size(), from + checkpointSize - 1);
                int sequence = ++checkpointNo;
                int completedWorkers = (int) outcome.lines().stream().limit(to)
                        .map(PayrollCalculationModels.CalculatedLine::workerId)
                        .distinct().count();
                Checkpoint checkpoint = new Checkpoint(
                        sequence, to + 1, completedWorkers,
                        canonical.checkpointDigest(
                                runId, attemptNo, sequence, to + 1,
                                outcome.lines().subList(0, to).stream()
                                .map(PayrollCalculationModels.CalculatedLine
                                        ::resultLineDigest)
                                .toList()));
                Instant recordedAt = clock.instant();
                activeLease = ensureLease(activeLease);
                Lease checkpointLease = activeLease;
                transaction.executeWithoutResult(status -> store.appendProgress(
                        checkpointLease, outcome, from, to, checkpoint, recordedAt));
                nextOrdinal = to + 1;
                if (!firstCommittedThisClaim) {
                    firstCommittedThisClaim = true;
                    if (failurePoint == FailurePoint.AFTER_FIRST_CHECKPOINT_COMMIT) {
                        throw new SimulatedWorkerCrash();
                    }
                }
            }

            if (failurePoint == FailurePoint.BEFORE_COMPLETION_UNCERTAIN) {
                throw new ResultUncertain("DATABASE_COMMIT_UNCERTAIN");
            }
            Instant completedAt = clock.instant();
            RunRevision completed = validated(start.run(), outcome, completedAt);
            PersistedReceipt receipt = transaction.execute(status ->
                    store.receipt(tenantId, start.run().commandReceiptId())
                            .orElseThrow());
            if (receipt == null) throw new IllegalStateException("receipt transaction returned null");
            EventFact event = calculatedEvent(
                    tenantId, runId, attemptNo, completed.revision(),
                    receipt.receipt().receiptId(), receipt.receipt().correlationId(),
                    outcome, completedAt);
            String audit = canonical.json(Map.of(
                    "attemptNo", attemptNo,
                    "workerCount", outcome.workers().size(),
                    "resultDigest", outcome.resultDigest(),
                    "controlTotals", controlTotals(outcome)));
            activeLease = ensureLease(activeLease);
            Lease completionLease = activeLease;
            transaction.executeWithoutResult(status -> store.complete(
                    completionLease, outcome, completed, receipt.receipt().receiptId(),
                    event, audit, completedAt));
            if (failurePoint == FailurePoint.AFTER_COMPLETION_COMMIT_UNCERTAIN) {
                throw new ResultUncertain("COMPLETION_ACK_UNKNOWN");
            }
            return true;
        } catch (SimulatedWorkerCrash exception) {
            throw exception;
        } catch (ResultUncertain exception) {
            return resolveUncertain(
                    start, activeLease, outcome, exception.failureCode());
        } catch (TransactionSystemException | HeuristicCompletionException exception) {
            return resolveUncertain(
                    start, activeLease, outcome, "TRANSACTION_OUTCOME_UNKNOWN");
        } catch (RuntimeException exception) {
            if (terminal(start, activeLease, AttemptState.FAILED,
                    failureCode(exception), outcome)) throw exception;
            return false;
        }
    }

    private boolean resolveUncertain(
            Start start,
            Lease lease,
            CalculationOutcome outcome,
            String failureCode) {
        Boolean committed = transaction.execute(status -> {
            store.lockRun(start.run().tenantId(), start.run().runId());
            return store.completionExists(
                    start.run().tenantId(), start.run().runId(),
                    lease.attemptNo(), outcome);
        });
        if (Boolean.TRUE.equals(committed)) return true;
        terminal(start, lease, AttemptState.RESULT_UNKNOWN, failureCode, outcome);
        return false;
    }

    boolean reconcile(long tenantId, UUID runId) {
        Boolean result = transaction.execute(status -> {
            store.lockRun(tenantId, runId);
            RunRevision current = store.currentRun(tenantId, runId).orElseThrow(
                    () -> new BaseException(ErrorCode.NOT_FOUND,
                            "The payroll resource was not found."));
            if (current.state() != RunState.RESULT_UNKNOWN) return false;
            int predecessor = current.latestAttemptNo();
            AttemptStatus predecessorStatus = store.currentAttemptStatus(
                    tenantId, runId, predecessor).orElseThrow();
            if (predecessorStatus.state() != AttemptState.RESULT_UNKNOWN) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "The Payroll recovery predecessor is not RESULT_UNKNOWN.");
            }
            FrozenSnapshot old = store.snapshot(tenantId, runId, predecessor);
            CalculationOutcome recomputed = engine.calculate(old);
            if (store.completionExists(
                    tenantId, runId, predecessor, recomputed)) {
                Instant resolvedAt = clock.instant();
                RunRevision resolved = copyRun(
                        current, current.revision() + 1, RunState.VALIDATED,
                        recomputed.workers().size(), recomputed.gross(),
                        recomputed.net(), recomputed.resultDigest(), resolvedAt);
                store.appendAttemptStatus(new AttemptStatus(
                        tenantId, runId, predecessor,
                        predecessorStatus.statusSequence() + 1,
                        AttemptState.VALIDATED, null, resolvedAt));
                store.appendReceiptStatus(
                        tenantId, current.commandReceiptId(),
                        ReceiptStatus.SUCCEEDED, resolvedAt);
                store.appendRunRevision(resolved);
                PersistedReceipt receipt = store.receipt(
                        tenantId, current.commandReceiptId()).orElseThrow();
                store.appendAudit(receipt, resolved.revision(),
                        "PAYROLL_RESULT_UNKNOWN_RESOLVED_COMMITTED",
                        canonical.json(Map.of(
                                "attemptNo", predecessor,
                                "resultDigest", recomputed.resultDigest())),
                        resolvedAt);
                return true;
            }
            int recoveryAttemptNo = predecessor + 1;
            Instant now = clock.instant();
            FrozenSnapshot recovery = new FrozenSnapshot(
                    old.tenantId(), old.runId(), recoveryAttemptNo,
                    old.sourceSnapshotId(), old.sourceSnapshotRevision(),
                    old.sourcePayloadDigest(), old.formulaSetDigest(),
                    old.targetPopulationDigest(), old.compensationInputDigest(),
                    old.semanticBindingDigest(), old.inputs(), old.formulas(),
                    old.semanticBindings(), now);
            store.freeze(new PayrollCalculationStore.Preparation(
                    current.payPeriodId(), current.legalEntityId(),
                    current.payrollGroupId(), current.currency(), recovery));
            store.appendAttemptStatus(new AttemptStatus(
                    tenantId, runId, predecessor,
                    predecessorStatus.statusSequence() + 1,
                    AttemptState.SUPERSEDED, null, now));
            store.appendAttempt(new Attempt(
                    tenantId, runId, recoveryAttemptNo, UUID.randomUUID(), predecessor,
                    PayrollCalculationModels.ENGINE_VERSION,
                    current.commandReceiptId(), now));
            store.appendReceiptStatus(
                    tenantId, current.commandReceiptId(), ReceiptStatus.ACCEPTED, now);
            store.appendRunRevision(new RunRevision(
                    tenantId, runId, current.revision() + 1, current.runType(),
                    current.payPeriodId(), current.sourceSnapshotId(),
                    current.sourceSnapshotRevision(), current.sourcePayloadDigest(),
                    current.legalEntityId(),
                    current.payrollGroupId(), current.populationKind(),
                    current.populationScopeDigest(), RunState.READY,
                    current.makerActorId(), current.makerSubjectPublicId(),
                    recoveryAttemptNo, 0, ZERO, ZERO, current.currency(), null,
                    current.approvalCaseId(), current.decisionDigest(),
                    current.approvedByActorId(), current.approvedAt(),
                    current.auditBundleDigest(), current.commandReceiptId(), now));
            PersistedReceipt receipt = store.receipt(
                    tenantId, current.commandReceiptId()).orElseThrow();
            store.appendAudit(receipt, current.revision() + 1,
                    "PAYROLL_FORWARD_RECOVERY_ATTEMPT_CREATED",
                    canonical.json(Map.of(
                            "predecessorAttemptNo", predecessor,
                            "recoveryAttemptNo", recoveryAttemptNo,
                            "sourcePayloadDigest", old.sourcePayloadDigest(),
                            "formulaSetDigest", old.formulaSetDigest())), now);
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    private Start start(
            long tenantId,
            UUID runId,
            int attemptNo,
            String workerInstanceId,
            Lease preclaimedLease,
            Instant now) {
        store.lockRun(tenantId, runId);
        RunRevision run = store.currentRun(tenantId, runId).orElseThrow(
                () -> new BaseException(ErrorCode.NOT_FOUND,
                        "The payroll resource was not found."));
        if (run.latestAttemptNo() != attemptNo
                || !List.of(RunState.READY, RunState.CALCULATING).contains(run.state())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The Payroll attempt is not claimable.");
        }
        AttemptStatus attempt = store.currentAttemptStatus(tenantId, runId, attemptNo)
                .orElseThrow();
        if (!List.of(AttemptState.READY, AttemptState.CALCULATING)
                .contains(attempt.state())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The Payroll attempt is terminal.");
        }
        Optional<Lease> lease;
        if (preclaimedLease == null) {
            lease = store.claim(
                    tenantId, runId, attemptNo, workerInstanceId, now, leaseDuration);
        } else {
            if (preclaimedLease.tenantId() != tenantId
                    || !preclaimedLease.runId().equals(runId)
                    || preclaimedLease.attemptNo() != attemptNo
                    || !preclaimedLease.workerInstanceId().equals(workerInstanceId)
                    || !preclaimedLease.expiresAt().isAfter(now)) {
                throw new StaleLease();
            }
            store.verifyLease(preclaimedLease);
            lease = Optional.of(preclaimedLease);
        }
        if (lease.isEmpty()) return null;
        if (attempt.state() == AttemptState.READY) {
            store.appendAttemptStatus(new AttemptStatus(
                    tenantId, runId, attemptNo, attempt.statusSequence() + 1,
                    AttemptState.CALCULATING, null, now));
            store.appendReceiptStatus(
                    tenantId, run.commandReceiptId(), ReceiptStatus.RUNNING, now);
            RunRevision calculating = copyRun(
                    run, run.revision() + 1, RunState.CALCULATING,
                    run.workerCount(), run.gross(), run.net(), run.resultDigest(), now);
            store.appendRunRevision(calculating);
            run = calculating;
        }
        return new Start(lease.orElseThrow(), run);
    }

    private Lease ensureLease(Lease lease) {
        Instant now = clock.instant();
        if (now.isBefore(lease.expiresAt().minus(leaseDuration.dividedBy(2)))) {
            return lease;
        }
        Optional<Lease> renewed = transaction.execute(
                status -> store.renew(lease, now, leaseDuration));
        if (renewed == null || renewed.isEmpty()) throw new StaleLease();
        return renewed.orElseThrow();
    }

    private boolean terminal(
            Start start,
            Lease lease,
            AttemptState state,
            String failureCode,
            CalculationOutcome outcome) {
        try {
            transaction.executeWithoutResult(status -> {
                RunRevision current = store.currentRun(
                        start.run().tenantId(), start.run().runId()).orElseThrow();
                if (current.latestAttemptNo() != lease.attemptNo()
                        || !List.of(RunState.READY, RunState.CALCULATING)
                        .contains(current.state())) {
                    throw new StaleLease();
                }
                Instant now = clock.instant();
                RunState runState = state == AttemptState.RESULT_UNKNOWN
                        ? RunState.RESULT_UNKNOWN : RunState.FAILED;
                RunRevision terminal = copyRun(
                        current, current.revision() + 1, runState,
                        current.workerCount(), current.gross(), current.net(),
                        current.resultDigest(), now);
                store.terminalFailure(
                        lease, current.commandReceiptId(), state, failureCode, terminal,
                        canonical.json(Map.of(
                                "attemptNo", current.latestAttemptNo(),
                                "failureCode", failureCode)), now);
            });
            return true;
        } catch (BaseException | StaleLease exception) {
            return false;
        }
    }

    private RunRevision validated(
            RunRevision current, CalculationOutcome outcome, Instant now) {
        return copyRun(
                current, current.revision() + 1, RunState.VALIDATED,
                outcome.workers().size(), outcome.gross(), outcome.net(),
                outcome.resultDigest(), now);
    }

    private RunRevision copyRun(
            RunRevision source,
            long revision,
            RunState state,
            int workerCount,
            BigDecimal gross,
            BigDecimal net,
            String resultDigest,
            Instant now) {
        return new RunRevision(
                source.tenantId(), source.runId(), revision, source.runType(),
                source.payPeriodId(), source.sourceSnapshotId(),
                source.sourceSnapshotRevision(), source.sourcePayloadDigest(),
                source.legalEntityId(), source.payrollGroupId(),
                source.populationKind(), source.populationScopeDigest(), state,
                source.makerActorId(), source.makerSubjectPublicId(),
                source.latestAttemptNo(), workerCount, gross, net, source.currency(),
                resultDigest, source.approvalCaseId(), source.decisionDigest(),
                source.approvedByActorId(), source.approvedAt(),
                source.auditBundleDigest(), source.commandReceiptId(), now);
    }

    private EventFact calculatedEvent(
            long tenantId,
            UUID runId,
            int attemptNo,
            long runRevision,
            UUID receiptId,
            UUID correlationId,
            CalculationOutcome outcome,
            Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId);
        data.put("attemptNo", attemptNo);
        data.put("workerCount", outcome.workers().size());
        data.put("resultDigest", outcome.resultDigest());
        data.put("controlTotals", controlTotals(outcome));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specVersion", "1.0");
        envelope.put("id", eventId);
        envelope.put("source", "urn:dwp:payroll:calculation");
        envelope.put("type", "PayrollCalculated.v1");
        envelope.put("schemaVersion", 1);
        envelope.put("time", now.toString());
        envelope.put("tenantId", tenantId);
        envelope.put("aggregateType", "PAYROLL_RUN");
        envelope.put("aggregateId", runId);
        envelope.put("aggregateSequence", runRevision);
        envelope.put("correlationId", correlationId);
        envelope.put("data", data);
        return new EventFact(
                eventId, "PayrollCalculated", 1, runId, attemptNo, runRevision,
                receiptId, runRevision, correlationId, canonical.json(envelope), now);
    }

    private List<Map<String, String>> controlTotals(CalculationOutcome outcome) {
        return outcome.controlTotals().stream().map(total -> Map.of(
                "code", total.code(),
                "amount", total.amount().toPlainString(),
                "currency", total.currency())).toList();
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof BaseException base) {
            return base.getErrorCode().getCode();
        }
        return "CALCULATION_RUNTIME_FAILURE";
    }

    private void validateWorkerInstance(String value) {
        if (value == null || value.isBlank() || value.length() > 160
                || !value.equals(value.strip()) || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("workerInstanceId is invalid");
        }
    }

    private record Start(Lease lease, RunRevision run) {
    }

    private static final class ResultUncertain extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String failureCode;

        ResultUncertain(String failureCode) {
            super(failureCode);
            this.failureCode = failureCode;
        }

        String failureCode() {
            return failureCode;
        }
    }

    private static final class StaleLease extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
