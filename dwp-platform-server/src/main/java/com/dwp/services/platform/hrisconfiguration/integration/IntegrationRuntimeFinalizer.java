package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.AttemptRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEvidence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimeServiceInternals.*;

/** Internal integration runtime finalizer operations shared by the owner facade. */
final class IntegrationRuntimeFinalizer {

    private final IntegrationRuntimeServiceOperations owner;
    private final IntegrationRepository repository;
    private final IntegrationExecutionRepository executions;
    private final IntegrationCanonicalJson canonical;
    private final int maxRetryGenerations;

    IntegrationRuntimeFinalizer(
            IntegrationRuntimeServiceOperations owner,
            IntegrationRepository repository,
            IntegrationExecutionRepository executions,
            IntegrationCanonicalJson canonical,
            int maxRetryGenerations) {
        this.owner = owner;
        this.repository = repository;
        this.executions = executions;
        this.canonical = canonical;
        this.maxRetryGenerations = maxRetryGenerations;
    }


    ExecutionResponse finalizeDispatch(
            Authority authority,
            UUID executionId,
            DispatchClaim claim,
            ProviderEvidence evidence,
            String ambiguityCategory) {
        OffsetDateTime now = repository.databaseNow();
        ExecutionRecord current = owner.lockExecutionAuthorized(
                authority, executionId);
        if (current.state() != ExecutionState.DISPATCHED) {
            return owner.executionResponseAuthorized(authority, executionId);
        }
        ExecutionState target = switch (evidence.outcome()) {
            case ACKNOWLEDGED -> ExecutionState.ACKNOWLEDGED;
            case FAILED -> ExecutionState.FAILED;
            case RESULT_UNKNOWN -> ExecutionState.RESULT_UNKNOWN;
            default -> ExecutionState.RESULT_UNKNOWN;
        };
        ProviderEvidence normalized = target == ExecutionState.RESULT_UNKNOWN
                && evidence.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN
                ? owner.unknown(current, claim.attempts(), now) : evidence;
        if (target == ExecutionState.RESULT_UNKNOWN) {
            owner.recordOperationsException(authority.tenantId(), executionId,
                    ambiguityCategory == null
                            ? "PROVIDER_RESULT_UNKNOWN" : ambiguityCategory,
                    canonical.digest(normalized), now);
        }
        ExecutionRecord changed = executions.applyProviderEvidence(
                current, normalized, target, now);
        changed = terminalizeWhenRetryUnavailable(
                authority, changed, now);
        repository.markOriginalReceiptOutcome(changed, switch (target) {
            case ACKNOWLEDGED -> ReceiptStatus.SUCCEEDED;
            case FAILED -> ReceiptStatus.FAILED;
            case RESULT_UNKNOWN -> ReceiptStatus.RESULT_UNKNOWN;
            default -> throw IntegrationRuntimeServiceInternals.conflict("SYS008_DISPATCH_RECEIPT_OUTCOME_INVALID");
        }, now);
        repository.appendExecutionEventAndAudit(owner.runtimeSeal(
                authority, IntegrationService.CREATE_EXECUTION, executionId,
                canonical.digest(normalized)), changed,
                "PROVIDER_" + changed.state(), now);
        return owner.executionResponseAuthorized(authority, executionId);
    }

    CommandReceipt finalizeReconciliation(
            Authority authority,
            UUID executionId,
            CommandSeal seal,
            ReconcileClaim claim,
            ProviderEvidence evidence,
            String ambiguityCategory) {
        OffsetDateTime now = repository.databaseNow();
        ExecutionRecord current = owner.lockExecutionAuthorized(
                authority, executionId);
        if (current.state() != ExecutionState.RECONCILING) {
            ReceiptStatus recoveredStatus = switch (current.state()) {
                case RECONCILED, PARTIAL, FAILED, DEAD_LETTER ->
                        ReceiptStatus.SUCCEEDED;
                default -> ReceiptStatus.RESULT_UNKNOWN;
            };
            return repository.finishCommand(
                    seal, claim.receipt().receiptId(), recoveredStatus,
                    executionId, current.aggregateRevision(),
                    IntegrationRuntimeServiceInternals.executionRef(executionId), now);
        }
        ExecutionState target = switch (evidence.outcome()) {
            case RECONCILED -> ExecutionState.RECONCILED;
            case PARTIAL -> ExecutionState.PARTIAL;
            case FAILED -> ExecutionState.FAILED;
            case RESULT_UNKNOWN -> ExecutionState.RESULT_UNKNOWN;
            default -> ExecutionState.RESULT_UNKNOWN;
        };
        ProviderEvidence normalized = target == ExecutionState.RESULT_UNKNOWN
                && evidence.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN
                ? owner.unknown(current, claim.attempts(), now) : evidence;
        if (target == ExecutionState.RESULT_UNKNOWN) {
            owner.recordOperationsException(authority.tenantId(), executionId,
                    ambiguityCategory == null
                            ? "RECONCILIATION_RESULT_UNKNOWN" : ambiguityCategory,
                    canonical.digest(normalized), now);
        }
        ExecutionRecord changed = executions.applyProviderEvidence(
                current, normalized, target, now);
        changed = terminalizeWhenRetryUnavailable(
                authority, changed, now);
        repository.appendExecutionEventAndAudit(
                seal, changed, "EXECUTION_" + changed.state(), now);
        ReceiptStatus receiptStatus = target == ExecutionState.RESULT_UNKNOWN
                ? ReceiptStatus.RESULT_UNKNOWN : ReceiptStatus.SUCCEEDED;
        return repository.finishCommand(
                seal, claim.receipt().receiptId(), receiptStatus,
                executionId, changed.aggregateRevision(),
                IntegrationRuntimeServiceInternals.executionRef(executionId), now);
    }

    ExecutionRecord terminalizeWhenRetryUnavailable(
            Authority authority,
            ExecutionRecord current,
            OffsetDateTime now) {
        if (current.state() != ExecutionState.FAILED
                && current.state() != ExecutionState.PARTIAL) {
            return current;
        }
        boolean exhausted = current.retryGeneration() >= maxRetryGenerations;
        boolean retryable = !executions.retryableItemIds(current).isEmpty();
        if (!exhausted && retryable) return current;
        return executions.deadLetter(
                current, authority.actorId(), now);
    }

    CommandReceipt finalizeCancellation(
            Authority authority,
            UUID executionId,
            CancelCommand command,
            CommandSeal seal,
            CancelClaim claim,
            ProviderEvidence result,
            boolean ambiguous) {
        OffsetDateTime now = repository.databaseNow();
        ExecutionRecord current = owner.lockExecutionAuthorized(
                authority, executionId);
        if (current.state() == ExecutionState.CANCELLED) {
            return repository.finishCommand(
                    seal, claim.receipt().receiptId(), ReceiptStatus.SUCCEEDED,
                    executionId, current.aggregateRevision(),
                    IntegrationRuntimeServiceInternals.executionRef(executionId), now);
        }
        if (current.state() == ExecutionState.RESULT_UNKNOWN) {
            return repository.finishCommand(
                    seal, claim.receipt().receiptId(),
                    ReceiptStatus.RESULT_UNKNOWN, executionId,
                    current.aggregateRevision(), IntegrationRuntimeServiceInternals.executionRef(executionId), now);
        }
        if (current.aggregateRevision()
                != claim.execution().aggregateRevision()) {
            throw IntegrationRuntimeServiceInternals.conflict("SYS008_COMPENSATION_FINALIZER_FENCE_CONFLICT");
        }
        if (ambiguous) {
            String operationsExceptionRef = owner.recordOperationsException(
                    authority.tenantId(), executionId,
                    "COMPENSATION_RESULT_UNKNOWN",
                    canonical.digest("COMPENSATION_RESULT_UNKNOWN",
                            claim.compensationRequestDigest(),
                            result == null ? "NO_RESULT" : canonical.digest(result)),
                    now);
            ExecutionRecord durable = compensationResultUnknown(
                    current, claim.attempts(), operationsExceptionRef, now);
            repository.markOriginalReceiptOutcome(
                    durable, ReceiptStatus.RESULT_UNKNOWN, now);
            repository.appendExecutionEventAndAudit(
                    seal, durable, "COMPENSATION_RESULT_UNKNOWN", now);
            return repository.finishCommand(
                    seal, claim.receipt().receiptId(),
                    ReceiptStatus.RESULT_UNKNOWN, executionId,
                    durable.aggregateRevision(), IntegrationRuntimeServiceInternals.executionRef(executionId), now);
        }
        IntegrationRuntimeServiceInternals.requireCancellationEvidence(true, command.compensationPolicyRef(),
                result.receiptDigest());
        ExecutionRecord changed = executions.cancel(
                current, command.compensationPolicyRef(),
                claim.compensationPolicyDigest(),
                result.receiptRef(), result.receiptDigest(),
                authority.actorId(), now);
        repository.markOriginalReceiptOutcome(
                changed, ReceiptStatus.FAILED, now);
        repository.appendExecutionEventAndAudit(
                seal, changed, "EXECUTION_CANCELLED", now);
        return repository.finishCommand(
                seal, claim.receipt().receiptId(), ReceiptStatus.SUCCEEDED,
                executionId, changed.aggregateRevision(),
                IntegrationRuntimeServiceInternals.executionRef(executionId), now);
    }

    ExecutionRecord compensationResultUnknown(
            ExecutionRecord current,
            List<AttemptRecord> attempts,
            String operationsExceptionRef,
            OffsetDateTime now) {
        ProviderEvidence evidence = owner.unknown(current, attempts, now);
        return switch (current.state()) {
            case DISPATCHED, RECONCILING -> executions.applyProviderEvidence(
                    current, evidence, ExecutionState.RESULT_UNKNOWN, now);
            case ACKNOWLEDGED -> {
                ExecutionRecord reconciling = executions.beginReconciliation(
                        current, current.providerReceiptRef(),
                        current.providerReceiptDigest(), now);
                yield executions.applyProviderEvidence(
                        reconciling, evidence, ExecutionState.RESULT_UNKNOWN, now);
            }
            default -> executions.markCompensationResultUnknown(
                    current, operationsExceptionRef, now);
        };
    }
}
