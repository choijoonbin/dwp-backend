package com.dwp.services.time.interpretation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretCommand;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAction;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAppend;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationCommandReceipt;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationComputation;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationRun;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationTrace;
import com.dwp.services.time.interpretation.InterpretationModels.PopulationKind;
import com.dwp.services.time.interpretation.InterpretationModels.RunStatus;
import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** PEP-TIM-009/010 owner use cases with deterministic replay and append-only reversals. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.time.interpretation.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class InterpretationService {

    public static final String PURPOSE = "TIME_INTERPRETATION";
    public static final String RUN_ACTION = "hris.action.tim.interpretation.run";
    public static final String RECEIPT_COMMAND_TYPE = "INTERPRETATION_RUN";
    public static final String RECEIPT_ACTION = "TIME_INTERPRET_RUN";
    public static final String RUN_PERMISSION = "hcm.operations.time.update";
    public static final String SELF_RUN_PERMISSION = "hcm.personal.time.submit";
    public static final String VIEW_PERMISSION = "hcm.operations.time.view";
    public static final String TRACE_PERMISSION = "hcm.personal.time.view";

    private final InterpretationRepository repository;
    private final DeterministicTimeInterpreter interpreter;
    private final Clock clock;

    @Autowired
    public InterpretationService(InterpretationRepository repository) {
        this(repository, new DeterministicTimeInterpreter(), Clock.systemUTC());
    }

    InterpretationService(
            InterpretationRepository repository,
            DeterministicTimeInterpreter interpreter,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public InterpretationCommandReceipt run(
            VerifiedInterpretationRequest authority,
            UUID idempotencyKey,
            InterpretCommand command) {
        requireRunAuthority(authority, command);
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (command.asOf().isAfter(clock.instant())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Interpretation asOf cannot be in the future.");
        }
        String requestDigest = requestDigest(authority, command);
        Optional<InterpretationCommandReceipt> existing = repository.findReceipt(
                authority.tenantId(), authority.subjectPrincipalPublicId(), idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().requestDigest().equals(requestDigest)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The idempotency key was reused for a different interpretation request.");
            }
            return replayed(existing.get());
        }

        var inputs = repository.loadInputs(authority.tenantId(), command);
        InterpretationComputation computation;
        try {
            computation = interpreter.interpret(
                    authority.tenantId(),
                    command.workerIds(),
                    command.periodStart(),
                    command.periodEnd(),
                    command.asOf(),
                    inputs.clockFacts(),
                    inputs.ruleVersion());
        } catch (IllegalArgumentException rejected) {
            throw new BaseException(
                    ErrorCode.VALIDATION_ERROR,
                    "The frozen interpretation input or rule was rejected.",
                    rejected);
        }

        Optional<InterpretationRun> previous = repository.latestSucceeded(
                authority.tenantId(), command.workerIds(), command);
        int attemptNo = previous.map(run -> run.attemptNo() + 1).orElse(1);
        UUID runId = InterpretationDigests.stableUuid(
                "InterpretationRun.v1",
                authority.tenantId(),
                authority.subjectPrincipalPublicId(),
                idempotencyKey);
        InterpretationLedgerPlanner.Plan ledger = InterpretationLedgerPlanner.plan(
                runId, computation, previous.orElse(null));
        InterpretationRun run = new InterpretationRun(
                runId,
                authority.tenantId(),
                authority.subjectPrincipalPublicId(),
                command.periodStart(),
                command.periodEnd(),
                command.asOf(),
                inputs.ruleVersion().ruleVersionPublicId(),
                inputs.ruleVersion().revision(),
                computation.inputDigest(),
                computation.ruleDigest(),
                computation.outputDigest(),
                attemptNo,
                RunStatus.SUCCEEDED,
                ledger.ledgerDeltaApplied(),
                previous.map(InterpretationRun::runPublicId).orElse(null),
                authority.correlationId(),
                clock.instant(),
                computation.results(),
                ledger.entries(),
                computation.traceSteps());
        InterpretationCommandReceipt persisted = repository.append(new InterpretationAppend(
                authority, idempotencyKey, requestDigest, run));
        if (!persisted.requestDigest().equals(requestDigest)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A concurrent interpretation used the idempotency key differently.");
        }
        return persisted;
    }

    public InterpretationTrace trace(
            VerifiedInterpretationRequest authority, UUID runPublicId) {
        requireAuthority(authority, InterpretationAction.TRACE);
        if (!authority.permissions().contains(RUN_PERMISSION)
                && !authority.permissions().contains(TRACE_PERMISSION)) {
            throw forbidden();
        }
        InterpretationRun run = repository.findRun(authority.tenantId(), runPublicId)
                .filter(candidate -> candidate.results().stream()
                        .map(InterpretationModels.WorkerMinuteResult::workerPublicId)
                        .allMatch(authority.authorizedWorkerPublicIds()::contains))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return new InterpretationTrace(
                run.runPublicId(), run.inputDigest(), run.ruleDigest(), run.traceSteps());
    }

    public void requireViewAuthority(VerifiedInterpretationRequest authority) {
        requireAuthority(authority, InterpretationAction.VIEW);
        if (!authority.permissions().contains(VIEW_PERMISSION)) throw forbidden();
    }

    private void requireRunAuthority(
            VerifiedInterpretationRequest authority, InterpretCommand command) {
        requireAuthority(authority, InterpretationAction.RUN);
        if (!PURPOSE.equals(authority.purposeCode())) {
            throw forbidden();
        }
        if (!authority.authorizedWorkerPublicIds().containsAll(command.workerIds())) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        boolean operator = authority.permissions().contains(RUN_PERMISSION);
        boolean self = authority.permissions().contains(SELF_RUN_PERMISSION)
                && authority.populationKind() == PopulationKind.SELF;
        if (!operator && !self) throw forbidden();
    }

    private void requireAuthority(
            VerifiedInterpretationRequest authority, InterpretationAction action) {
        if (authority == null
                || !authority.actions().contains(action)
                || authority.revalidateAt() == null
                || !authority.revalidateAt().isAfter(clock.instant())) {
            throw forbidden();
        }
    }

    private static String requestDigest(
            VerifiedInterpretationRequest authority, InterpretCommand command) {
        return InterpretationDigests.sha256(
                "InterpretCommand.v1",
                authority.tenantId(),
                authority.actorId(),
                authority.subjectPrincipalPublicId(),
                authority.populationKind(),
                authority.populationPublicId(),
                authority.populationRevision(),
                authority.grantRevision(),
                authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                authority.purposeCode(),
                authority.authorizationRevision(),
                command.workerIds().stream().sorted().toList(),
                command.periodStart(),
                command.periodEnd(),
                command.asOf());
    }

    private static InterpretationCommandReceipt replayed(
            InterpretationCommandReceipt receipt) {
        return new InterpretationCommandReceipt(
                receipt.receiptId(),
                receipt.commandType(),
                receipt.originatingAction(),
                receipt.subjectPrincipalPublicId(),
                receipt.populationScopeDigest(),
                receipt.fieldPolicyRevision(),
                receipt.purposeCode(),
                receipt.authorizationRevision(),
                receipt.status(),
                receipt.correlationId(),
                receipt.requestDigest(),
                receipt.createdAt(),
                receipt.aggregateVersion(),
                receipt.resultRef(),
                receipt.runPublicId(),
                receipt.outputDigest(),
                true);
    }

    private static BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN);
    }
}
