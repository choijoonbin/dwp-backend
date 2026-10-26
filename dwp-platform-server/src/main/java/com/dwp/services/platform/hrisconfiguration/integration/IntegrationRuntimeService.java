package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.AttemptRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.CompensationClaim;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.BoundaryResolutionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.OperationsExceptionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEffectPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderItemAttempt;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderItemEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderRequest;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.CompensationPolicyConstraint;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolutionRequest;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.UnpinnedReferenceConstraint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;

/**
 * Durable provider-effect coordinator. External calls are surrounded by a
 * committed fenced claim and an independent finalization transaction.
 */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class IntegrationRuntimeService {

    private static final UUID DISPATCH_WORKER_PRINCIPAL = UUID.fromString(
            "00000000-0000-4000-8000-000000000008");
    private static final Duration EFFECT_CLAIM_RECOVERY_AFTER =
            Duration.ofMinutes(5);

    private final IntegrationRepository repository;
    private final IntegrationExecutionRepository executions;
    private final ObjectProvider<ProviderEffectPort> providers;
    private final ObjectProvider<BoundaryResolutionPort> boundaries;
    private final ObjectProvider<Sys008OpaqueReferenceOwnerPort> references;
    private final OperationsExceptionPort operationsExceptions;
    private final TransactionTemplate transactions;
    private final TransactionTemplate effects;
    private final IntegrationCanonicalJson canonical;
    private final int maxRetryGenerations;

    public IntegrationRuntimeService(
            IntegrationRepository repository,
            IntegrationExecutionRepository executions,
            ObjectProvider<ProviderEffectPort> providers,
            ObjectProvider<BoundaryResolutionPort> boundaries,
            ObjectProvider<Sys008OpaqueReferenceOwnerPort> references,
            OperationsExceptionPort operationsExceptions,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            @Value("${dwp.hris.integration-control-plane.max-retry-generations:3}")
            int maxRetryGenerations) {
        this.repository = repository;
        this.executions = executions;
        this.providers = providers;
        this.boundaries = boundaries;
        this.references = references;
        this.operationsExceptions = Objects.requireNonNull(
                operationsExceptions, "operationsExceptions must not be null");
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.effects = new TransactionTemplate(transactionManager);
        this.effects.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.canonical = new IntegrationCanonicalJson(json);
        if (maxRetryGenerations < 1 || maxRetryGenerations > 100) {
            throw new IllegalArgumentException(
                    "SYS008 retry generation budget must be between 1 and 100");
        }
        this.maxRetryGenerations = maxRetryGenerations;
    }

    /** Internal worker entry point; there is deliberately no browser dispatch route. */
    public ExecutionResponse dispatch(Authority authority, UUID executionId) {
        requireExecutor(authority);
        DispatchClaim claim = inTransaction(authority, () -> {
            ExecutionRecord current = lockExecutionAuthorized(
                    authority, executionId);
            requireRevision(current, authority);
            OffsetDateTime now = repository.databaseNow();
            if (current.state() == ExecutionState.DISPATCHED) {
                return new DispatchClaim(current,
                        executions.currentAttempts(current), now, true);
            }
            if (current.state() != ExecutionState.ACCEPTED) {
                throw conflict("SYS008_EXECUTION_NOT_ACCEPTED");
            }
            ExecutionRecord dispatched = executions.beginDispatch(
                    current, current.attemptFence(), now);
            return new DispatchClaim(dispatched,
                    executions.currentAttempts(dispatched), now, false);
        });

        EffectEvidence effect = withoutTransaction(() ->
                dispatchEffect(authority, claim));
        ProviderEvidence durableEvidence = effect.evidence();
        String durableCategory = effect.ambiguityCategory();
        return inTransaction(authority, () -> finalizeDispatch(
                authority, executionId, claim, durableEvidence, durableCategory));
    }

    /**
     * Reconstructs only the frozen execution authority needed by the internal
     * event worker. Terminal redelivery is a no-op; DISPATCHED redelivery uses
     * the normal crash-recovery path and never sends a second provider effect.
     */
    ExecutionResponse dispatchAccepted(
            long tenantId,
            UUID executionId,
            UUID originalSubjectPrincipal) {
        if (tenantId < 1 || executionId == null
                || originalSubjectPrincipal == null
                || originalSubjectPrincipal.equals(new UUID(0L, 0L))) {
            throw new IllegalArgumentException(
                    "SYS008_DISPATCH_WORK_ITEM_INVALID");
        }
        ExecutionRecord snapshot = transactions.execute(status -> {
            repository.bindTenantPrincipal(tenantId, originalSubjectPrincipal);
            return repository.lockExecution(tenantId, executionId);
        });
        Objects.requireNonNull(snapshot, "SYS008 dispatch snapshot is required");
        if (snapshot.state() != ExecutionState.ACCEPTED
                && snapshot.state() != ExecutionState.DISPATCHED) {
            return inTransaction(
                    Authority.dispatchWorker(snapshot, originalSubjectPrincipal,
                            DISPATCH_WORKER_PRINCIPAL),
                    () -> repository.executionResponse(tenantId, executionId));
        }
        return dispatch(
                Authority.dispatchWorker(snapshot, originalSubjectPrincipal,
                        DISPATCH_WORKER_PRINCIPAL),
                executionId);
    }

    public CommandReceipt reconcile(
            Authority authority,
            UUID executionId,
            ReconcileCommand command) {
        requireExecutor(authority);
        requireStepUp(authority);
        CommandSeal seal = seal(authority, "RECONCILE_CONNECTOR_EXECUTION",
                IntegrationService.RECONCILE_EXECUTION, executionId, command);
        ReconcileClaim claim = inTransaction(authority, () -> {
            OffsetDateTime now = repository.databaseNow();
            var reservation = repository.beginCommand(seal, now);
            if (!reservation.owner()
                    && terminal(reservation.receipt().status())) {
                return ReconcileClaim.replay(reservation.receipt());
            }
            ExecutionRecord current = lockExecutionAuthorized(
                    authority, executionId);
            if (!reservation.owner()
                    && reservation.receipt().status() == ReceiptStatus.RUNNING) {
                if (now.isBefore(reservation.receipt().createdAt()
                        .plus(EFFECT_CLAIM_RECOVERY_AFTER))) {
                    return ReconcileClaim.replay(reservation.receipt());
                }
                return new ReconcileClaim(
                        reservation.receipt(), current,
                        executions.currentAttempts(current), now,
                        null, true, false);
            }
            requireRevision(current, authority);
            if (current.state() != ExecutionState.RESULT_UNKNOWN
                    && current.state() != ExecutionState.ACKNOWLEDGED) {
                throw conflict("SYS008_RECONCILIATION_STATE_INVALID");
            }
            if (command.expectedSourceCount() != current.sourceCount()) {
                throw conflict("SYS008_RECONCILIATION_SOURCE_COUNT_CHANGED");
            }
            List<AttemptRecord> attempts = executions.currentAttempts(current);
            NonExportableProviderHandle receiptHandle =
                    resolveProviderReceipt(authority, current, command);
            ExecutionRecord reconciling = executions.beginReconciliation(
                    current, command.providerReceiptRef(),
                    command.providerReceiptDigest(), now);
            CommandReceipt running = repository.progressCommand(
                    seal, reservation.receipt().receiptId(), executionId,
                    reconciling.aggregateRevision(), executionRef(executionId), now);
            return new ReconcileClaim(running, reconciling, attempts, now,
                    receiptHandle, false, false);
        });
        if (claim.replay()) return claim.receipt();
        ProviderEffectPort provider = claim.recovery() ? null : providerPort();

        EffectEvidence effect = withoutTransaction(() ->
                reconciliationEffect(authority, command, claim, provider));
        ProviderEvidence durableEvidence = effect.evidence();
        String durableCategory = effect.ambiguityCategory();
        return inTransaction(authority, () -> finalizeReconciliation(
                authority, executionId, seal, claim, durableEvidence,
                durableCategory));
    }

    public CommandReceipt retry(
            Authority authority,
            UUID executionId,
            RetryCommand command) {
        requireExecutor(authority);
        requireStepUp(authority);
        CommandSeal seal = seal(authority, "RETRY_CONNECTOR_EXECUTION",
                IntegrationService.RETRY_EXECUTION, executionId, command);
        return inTransaction(authority, () -> {
            OffsetDateTime now = repository.databaseNow();
            var reservation = repository.beginCommand(seal, now);
            if (!reservation.owner()) return reservation.receipt();
            ExecutionRecord current = lockExecutionAuthorized(
                    authority, executionId);
            requireRevision(current, authority);
            if (current.state() != ExecutionState.FAILED
                    && current.state() != ExecutionState.PARTIAL) {
                throw conflict("SYS008_RETRY_STATE_INVALID");
            }
            List<UUID> retryable = executions.retryableItemIds(current);
            List<UUID> exact;
            try {
                exact = IntegrationDomainPolicy.requireExactRetryableSet(
                        command.failedItemIds(), retryable,
                        command.failedItemSetDigest());
            } catch (IllegalStateException invalid) {
                throw conflict("SYS008_RETRY_ITEM_SET_CHANGED");
            }
            if (command.expectedRejectedCount() != exact.size()) {
                throw conflict("SYS008_RETRY_COUNT_CHANGED");
            }
            if (current.retryGeneration() >= maxRetryGenerations) {
                ExecutionRecord terminal = executions.deadLetter(
                        current, authority.actorId(), now);
                repository.appendExecutionEventAndAudit(
                        seal, terminal, "EXECUTION_DEAD_LETTER", now);
                return repository.finishCommand(
                        seal, reservation.receipt().receiptId(),
                        ReceiptStatus.SUCCEEDED, executionId,
                        terminal.aggregateRevision(), executionRef(executionId), now);
            }
            ExecutionRecord changed = executions.createRetryAttempts(
                    current, exact, command.failedItemSetDigest(),
                    authority.actorId(), now);
            repository.appendExecutionEventAndAudit(
                    seal, changed, "EXECUTION_RETRY_ACCEPTED", now);
            return repository.finishCommand(
                    seal, reservation.receipt().receiptId(),
                    ReceiptStatus.SUCCEEDED, executionId,
                    changed.aggregateRevision(), executionRef(executionId), now);
        });
    }

    public CommandReceipt cancel(
            Authority authority,
            UUID executionId,
            CancelCommand command) {
        requireExecutor(authority);
        requireStepUp(authority);
        CommandSeal seal = seal(authority, "CANCEL_CONNECTOR_EXECUTION",
                IntegrationService.CANCEL_EXECUTION, executionId, command);
        CancelClaim claim = inTransaction(authority, () -> {
            OffsetDateTime now = repository.databaseNow();
            var reservation = repository.beginCommand(seal, now);
            if (!reservation.owner()
                    && terminal(reservation.receipt().status())) {
                return CancelClaim.replay(reservation.receipt());
            }
            ExecutionRecord current = lockExecutionAuthorized(
                    authority, executionId);
            if (!reservation.owner()
                    && reservation.receipt().status() == ReceiptStatus.RUNNING) {
                if (current.state() == ExecutionState.CANCELLED) {
                    CommandReceipt completed = repository.finishCommand(
                            seal, reservation.receipt().receiptId(),
                            ReceiptStatus.SUCCEEDED, executionId,
                            current.aggregateRevision(), executionRef(executionId), now);
                    return CancelClaim.completed(completed);
                }
                if (current.state() == ExecutionState.RESULT_UNKNOWN) {
                    CommandReceipt completed = repository.finishCommand(
                            seal, reservation.receipt().receiptId(),
                            ReceiptStatus.RESULT_UNKNOWN, executionId,
                            current.aggregateRevision(), executionRef(executionId), now);
                    return CancelClaim.completed(completed);
                }
                if (now.isBefore(reservation.receipt().createdAt()
                        .plus(EFFECT_CLAIM_RECOVERY_AFTER))) {
                    return CancelClaim.replay(reservation.receipt());
                }
                List<AttemptRecord> attempts = executions.currentAttempts(current);
                ResolvedCompensation compensation = resolveCompensation(
                        authority, current, command);
                String compensationKey = compensationKey(
                        authority.tenantId(), current,
                        compensation.policyDigest());
                String compensationRequestDigest = compensationRequestDigest(
                        current, compensation.policyDigest(),
                        command.reasonCode());
                return CancelClaim.recovery(
                        reservation.receipt(), current, attempts, now,
                        compensation.policyHandle(), compensation.policyDigest(),
                        compensationKey, compensationRequestDigest);
            }
            requireExecutionTransition(current.state(), ExecutionState.CANCELLED);
            if (!current.providerDispatchOccurred()) {
                requireRevision(current, authority);
                ExecutionRecord changed = executions.cancel(
                        current, command.compensationPolicyRef(), null,
                        null, null, authority.actorId(), now);
                repository.markOriginalReceiptOutcome(
                        changed, ReceiptStatus.FAILED, now);
                repository.appendExecutionEventAndAudit(
                        seal, changed, "EXECUTION_CANCELLED", now);
                CommandReceipt completed = repository.finishCommand(
                        seal, reservation.receipt().receiptId(),
                        ReceiptStatus.SUCCEEDED, executionId,
                        changed.aggregateRevision(), executionRef(executionId), now);
                return CancelClaim.completed(completed);
            }
            ResolvedCompensation compensation = resolveCompensation(
                    authority, current, command);
            List<AttemptRecord> attempts = executions.currentAttempts(current);
            String compensationKey = compensationKey(
                    authority.tenantId(), current, compensation.policyDigest());
            String compensationRequestDigest = compensationRequestDigest(
                    current, compensation.policyDigest(), command.reasonCode());
            String claimDigest = canonical.digest(
                    compensationKey, compensationRequestDigest);
            CompensationClaim globalClaim = executions.claimCompensation(
                    current, claimDigest,
                    authority.expectedObjectRevision(), now);
            ExecutionRecord claimed = globalClaim.execution();
            CommandReceipt running = repository.progressCommand(
                    seal, reservation.receipt().receiptId(), executionId,
                    claimed.aggregateRevision(), executionRef(executionId), now);
            if (!globalClaim.owner()) {
                return CancelClaim.completed(running);
            }
            repository.appendExecutionEventAndAudit(
                    seal, claimed, "COMPENSATION_CLAIMED", now);
            return new CancelClaim(running, claimed, attempts, now,
                    compensation.policyHandle(), compensation.policyDigest(),
                    compensationKey, compensationRequestDigest,
                    false, false, false);
        });
        if (claim.replay() || claim.completed()) return claim.receipt();
        ProviderEffectPort provider = claim.recovery() ? null : providerPort();

        CompensationEffect effect = withoutTransaction(() ->
                compensationEffect(authority, command, claim, provider));
        ProviderEvidence durableResult = effect.evidence();
        boolean durableAmbiguous = effect.ambiguous();
        return inTransaction(authority, () -> finalizeCancellation(
                authority, executionId, command, seal, claim,
                durableResult, durableAmbiguous));
    }

    private ExecutionResponse finalizeDispatch(
            Authority authority,
            UUID executionId,
            DispatchClaim claim,
            ProviderEvidence evidence,
            String ambiguityCategory) {
        OffsetDateTime now = repository.databaseNow();
        ExecutionRecord current = lockExecutionAuthorized(
                authority, executionId);
        if (current.state() != ExecutionState.DISPATCHED) {
            return executionResponseAuthorized(authority, executionId);
        }
        ExecutionState target = switch (evidence.outcome()) {
            case ACKNOWLEDGED -> ExecutionState.ACKNOWLEDGED;
            case FAILED -> ExecutionState.FAILED;
            case RESULT_UNKNOWN -> ExecutionState.RESULT_UNKNOWN;
            default -> ExecutionState.RESULT_UNKNOWN;
        };
        ProviderEvidence normalized = target == ExecutionState.RESULT_UNKNOWN
                && evidence.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN
                ? unknown(current, claim.attempts(), now) : evidence;
        if (target == ExecutionState.RESULT_UNKNOWN) {
            recordOperationsException(authority.tenantId(), executionId,
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
            default -> throw conflict("SYS008_DISPATCH_RECEIPT_OUTCOME_INVALID");
        }, now);
        repository.appendExecutionEventAndAudit(runtimeSeal(
                authority, IntegrationService.CREATE_EXECUTION, executionId,
                canonical.digest(normalized)), changed,
                "PROVIDER_" + changed.state(), now);
        return executionResponseAuthorized(authority, executionId);
    }

    private EffectEvidence dispatchEffect(
            Authority authority, DispatchClaim claim) {
        if (claim.recovery()) {
            return new EffectEvidence(
                    unknown(claim.execution(), claim.attempts(), claim.claimedAt()),
                    "STALE_DISPATCH_CLAIM");
        }
        try {
            ProviderRequest request = providerRequest(
                    authority, claim.execution(), claim.attempts());
            ProviderEvidence returned = providerPort().dispatch(request);
            ProviderEvidence evidence = normalizeDispatchEvidence(claim, returned);
            String category = evidence.outcome()
                    == ProviderEvidence.Outcome.RESULT_UNKNOWN
                    && (returned == null
                    || returned.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN)
                    ? "PROVIDER_EVIDENCE_UNBOUND" : null;
            return new EffectEvidence(evidence, category);
        } catch (RuntimeException ambiguous) {
            return new EffectEvidence(
                    unknown(claim.execution(), claim.attempts(), claim.claimedAt()),
                    "PROVIDER_RESULT_UNKNOWN");
        }
    }

    private EffectEvidence reconciliationEffect(
            Authority authority,
            ReconcileCommand command,
            ReconcileClaim claim,
            ProviderEffectPort provider) {
        if (claim.recovery()) {
            return new EffectEvidence(
                    unknown(claim.execution(), claim.attempts(), claim.claimedAt()),
                    "STALE_RECONCILIATION_CLAIM");
        }
        try {
            ProviderRequest request = providerRequest(
                    authority, claim.execution(), claim.attempts());
            ProviderEvidence returned = provider.reconcile(
                    new IntegrationRuntimePorts.ReconcileRequest(
                            request, claim.providerReceiptHandle(),
                            command.providerReceiptDigest(),
                            command.expectedSourceCount()));
            ProviderEvidence evidence = normalizeReconciliationEvidence(
                    claim, returned);
            String category = evidence.outcome()
                    == ProviderEvidence.Outcome.RESULT_UNKNOWN
                    && (returned == null
                    || returned.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN)
                    ? "RECONCILIATION_EVIDENCE_UNBOUND" : null;
            return new EffectEvidence(evidence, category);
        } catch (RuntimeException ambiguous) {
            return new EffectEvidence(
                    unknown(claim.execution(), claim.attempts(), claim.claimedAt()),
                    "RECONCILIATION_RESULT_UNKNOWN");
        }
    }

    private CompensationEffect compensationEffect(
            Authority authority,
            CancelCommand command,
            CancelClaim claim,
            ProviderEffectPort provider) {
        if (claim.recovery()) {
            return new CompensationEffect(null, true);
        }
        try {
            ProviderRequest originalRequest = providerRequest(
                    authority, claim.execution(), claim.attempts());
            ProviderEvidence result = provider.compensate(
                    new IntegrationRuntimePorts.CompensationRequest(
                            originalRequest, claim.compensationPolicyHandle(),
                            claim.compensationPolicyDigest(), command.reasonCode(),
                            claim.compensationKey(),
                            claim.compensationRequestDigest()));
            return new CompensationEffect(result,
                    !validCompensationEvidence(
                            result, claim.compensationKey(),
                            claim.compensationRequestDigest()));
        } catch (RuntimeException unavailable) {
            return new CompensationEffect(null, true);
        }
    }

    private CommandReceipt finalizeReconciliation(
            Authority authority,
            UUID executionId,
            CommandSeal seal,
            ReconcileClaim claim,
            ProviderEvidence evidence,
            String ambiguityCategory) {
        OffsetDateTime now = repository.databaseNow();
        ExecutionRecord current = lockExecutionAuthorized(
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
                    executionRef(executionId), now);
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
                ? unknown(current, claim.attempts(), now) : evidence;
        if (target == ExecutionState.RESULT_UNKNOWN) {
            recordOperationsException(authority.tenantId(), executionId,
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
                executionRef(executionId), now);
    }

    private ExecutionRecord terminalizeWhenRetryUnavailable(
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

    private CommandReceipt finalizeCancellation(
            Authority authority,
            UUID executionId,
            CancelCommand command,
            CommandSeal seal,
            CancelClaim claim,
            ProviderEvidence result,
            boolean ambiguous) {
        OffsetDateTime now = repository.databaseNow();
        ExecutionRecord current = lockExecutionAuthorized(
                authority, executionId);
        if (current.state() == ExecutionState.CANCELLED) {
            return repository.finishCommand(
                    seal, claim.receipt().receiptId(), ReceiptStatus.SUCCEEDED,
                    executionId, current.aggregateRevision(),
                    executionRef(executionId), now);
        }
        if (current.state() == ExecutionState.RESULT_UNKNOWN) {
            return repository.finishCommand(
                    seal, claim.receipt().receiptId(),
                    ReceiptStatus.RESULT_UNKNOWN, executionId,
                    current.aggregateRevision(), executionRef(executionId), now);
        }
        if (current.aggregateRevision()
                != claim.execution().aggregateRevision()) {
            throw conflict("SYS008_COMPENSATION_FINALIZER_FENCE_CONFLICT");
        }
        if (ambiguous) {
            String operationsExceptionRef = recordOperationsException(
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
                    durable.aggregateRevision(), executionRef(executionId), now);
        }
        requireCancellationEvidence(true, command.compensationPolicyRef(),
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
                executionRef(executionId), now);
    }

    private ExecutionRecord compensationResultUnknown(
            ExecutionRecord current,
            List<AttemptRecord> attempts,
            String operationsExceptionRef,
            OffsetDateTime now) {
        ProviderEvidence evidence = unknown(current, attempts, now);
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

    private ProviderEvidence normalizeDispatchEvidence(
            DispatchClaim claim, ProviderEvidence returned) {
        if (!validProviderEvidence(
                claim.execution(), claim.attempts(), returned,
                Set.of(ProviderEvidence.Outcome.ACKNOWLEDGED,
                        ProviderEvidence.Outcome.FAILED,
                        ProviderEvidence.Outcome.RESULT_UNKNOWN), false)) {
            return unknown(claim.execution(), claim.attempts(), claim.claimedAt());
        }
        return returned;
    }

    private ProviderEvidence normalizeReconciliationEvidence(
            ReconcileClaim claim, ProviderEvidence returned) {
        if (!validProviderEvidence(
                claim.execution(), claim.attempts(), returned,
                Set.of(ProviderEvidence.Outcome.RECONCILED,
                        ProviderEvidence.Outcome.PARTIAL,
                        ProviderEvidence.Outcome.FAILED,
                        ProviderEvidence.Outcome.RESULT_UNKNOWN), true)) {
            return unknown(claim.execution(), claim.attempts(), claim.claimedAt());
        }
        return returned;
    }

    private boolean validProviderEvidence(
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            ProviderEvidence evidence,
            Set<ProviderEvidence.Outcome> allowedOutcomes,
            boolean reconciliation) {
        if (evidence == null || !allowedOutcomes.contains(evidence.outcome())) {
            return false;
        }
        try {
            IntegrationDomainPolicy.requireOriginalProviderEvidence(
                    execution.providerIdempotencyKey(),
                    evidence.providerIdempotencyKey(),
                    execution.providerRequestDigest(), evidence.requestDigest());
        } catch (RuntimeException unbound) {
            return false;
        }
        if (evidence.sourceCount() != execution.sourceCount()
                || evidence.acceptedCount() < 0
                || evidence.rejectedCount() < 0
                || evidence.quarantinedCount() < 0
                || evidence.acceptedCount() + evidence.rejectedCount()
                    > execution.sourceCount()
                || evidence.quarantinedCount() > evidence.rejectedCount()) {
            return false;
        }
        if (evidence.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN
                && !paired(evidence.receiptRef(), evidence.receiptDigest())) {
            return false;
        }
        if (reconciliation
                && evidence.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN
                && (evidence.reconciliationDigest() == null
                || !evidence.reconciliationDigest().matches("^[a-f0-9]{64}$"))) {
            return false;
        }
        return exactItemEvidence(
                attempts, evidence.items(), evidence.outcome(), reconciliation);
    }

    private static boolean exactItemEvidence(
            List<AttemptRecord> attempts,
            List<ProviderItemEvidence> evidenceItems,
            ProviderEvidence.Outcome aggregateOutcome,
            boolean reconciliation) {
        if (evidenceItems == null || evidenceItems.size() != attempts.size()) {
            return false;
        }
        Map<UUID, AttemptRecord> expected = new HashMap<>();
        attempts.forEach(attempt -> expected.put(attempt.itemPublicId(), attempt));
        Set<UUID> observed = new HashSet<>();
        for (ProviderItemEvidence item : evidenceItems) {
            AttemptRecord attempt = expected.get(item.itemPublicId());
            if (attempt == null || !observed.add(item.itemPublicId())
                    || !constant(attempt.providerIdempotencyKey(),
                            item.providerIdempotencyKey())
                    || !constant(attempt.requestDigest(), item.requestDigest())
                    || !itemOutcomeAllowed(
                            aggregateOutcome, item.outcome(), reconciliation)
                    || (item.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN
                        && !paired(item.receiptRef(), item.receiptDigest()))
                    || (reconciliation
                        && item.outcome()
                            == ProviderEvidence.Outcome.RECONCILED
                        && (item.reconciliationDigest() == null
                        || !item.reconciliationDigest()
                            .matches("^[a-f0-9]{64}$")))
                    || (item.outcome() == ProviderEvidence.Outcome.FAILED
                        && (item.failureClass() == null
                        || item.failureClass().isBlank()))
                    || (item.quarantined()
                        && item.outcome()
                            != ProviderEvidence.Outcome.FAILED)) {
                return false;
            }
        }
        return observed.size() == expected.size();
    }

    private static boolean itemOutcomeAllowed(
            ProviderEvidence.Outcome aggregate,
            ProviderEvidence.Outcome item,
            boolean reconciliation) {
        if (aggregate == ProviderEvidence.Outcome.RESULT_UNKNOWN) {
            return item == ProviderEvidence.Outcome.RESULT_UNKNOWN;
        }
        if (!reconciliation) {
            return aggregate == item
                    && Set.of(ProviderEvidence.Outcome.ACKNOWLEDGED,
                    ProviderEvidence.Outcome.FAILED).contains(item);
        }
        return switch (aggregate) {
            case RECONCILED -> item == ProviderEvidence.Outcome.RECONCILED;
            case FAILED -> item == ProviderEvidence.Outcome.FAILED;
            case PARTIAL -> item == ProviderEvidence.Outcome.RECONCILED
                    || item == ProviderEvidence.Outcome.FAILED;
            default -> false;
        };
    }

    private static boolean validCompensationEvidence(
            ProviderEvidence result,
            String compensationKey,
            String commandDigest) {
        return result != null
                && result.outcome() == ProviderEvidence.Outcome.CANCELLED
                && constant(compensationKey, result.providerIdempotencyKey())
                && constant(commandDigest, result.requestDigest())
                && paired(result.receiptRef(), result.receiptDigest());
    }

    private ProviderEvidence unknown(
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            OffsetDateTime now) {
        List<ProviderItemEvidence> items = attempts.stream()
                .map(attempt -> new ProviderItemEvidence(
                        attempt.itemPublicId(),
                        ProviderEvidence.Outcome.RESULT_UNKNOWN,
                        attempt.providerIdempotencyKey(), attempt.requestDigest(),
                        null, null, null, false, null, false))
                .toList();
        return new ProviderEvidence(
                ProviderEvidence.Outcome.RESULT_UNKNOWN,
                execution.providerIdempotencyKey(),
                execution.providerRequestDigest(), null, null,
                execution.sourceCount(), execution.acceptedCount(),
                execution.rejectedCount(), execution.quarantinedCount(),
                execution.reconciliationDigest(), now, items);
    }

    private NonExportableProviderHandle resolveProviderReceipt(
            Authority authority,
            ExecutionRecord execution,
            ReconcileCommand command) {
        ResolutionRequest request = new ResolutionRequest(
                command.providerReceiptRef(), authority.tenantId(),
                execution.connectorId(), execution.executionId().toString(),
                authority.purposeCode(), ReferenceType.PROVIDER_RECEIPT_REF,
                "sys008.provider-receipt", command.providerReceiptDigest(),
                execution.authorizationRevision(),
                new Sys008OpaqueReferenceOwnerPort.ProviderReceiptConstraint(
                        execution.executionId(),
                        execution.connectorId().toString(),
                        execution.providerIdempotencyKey()), true);
        return referencePort().resolveAuthorized(request)
                .map(Sys008OpaqueReferenceOwnerPort.ResolvedReference::providerHandle)
                .orElseThrow(() -> notFound(
                        "SYS008_PROVIDER_RECEIPT_NOT_AVAILABLE"));
    }

    private ResolvedCompensation resolveCompensation(
            Authority authority,
            ExecutionRecord execution,
            CancelCommand command) {
        if (command.compensationPolicyRef() == null) {
            throw conflict("SYS008_COMPENSATION_POLICY_REQUIRED");
        }
        String digest = canonical.digest(command.compensationPolicyRef(),
                execution.connectorId().toString(),
                Long.toString(execution.definitionVersion()),
                execution.operation().name());
        ResolutionRequest request = new ResolutionRequest(
                command.compensationPolicyRef(), authority.tenantId(),
                execution.connectorId(), execution.executionId().toString(),
                authority.purposeCode(), ReferenceType.COMPENSATION_POLICY_REF,
                "sys008.compensation-policy", digest,
                execution.authorizationRevision(), new CompensationPolicyConstraint(
                        execution.connectorId(), execution.definitionVersion(),
                        execution.operation().name(), true), true);
        NonExportableProviderHandle handle = referencePort()
                .resolveAuthorized(request)
                .map(Sys008OpaqueReferenceOwnerPort.ResolvedReference::providerHandle)
                .orElseThrow(() -> notFound(
                        "SYS008_COMPENSATION_POLICY_NOT_AVAILABLE"));
        return new ResolvedCompensation(handle, digest);
    }

    private ProviderRequest providerRequest(
            Authority authority,
            ExecutionRecord execution,
            List<AttemptRecord> attempts) {
        if (attempts.isEmpty()) {
            throw conflict("SYS008_CURRENT_ATTEMPT_REQUIRED");
        }
        Set<UUID> legalEntities = execution.legalEntityPublicId() == null
                ? Set.of() : Set.of(execution.legalEntityPublicId());
        var population = Sys008TypedArtifactContract.issueCurrentPopulationContext(
                execution.connectorId(), legalEntities,
                execution.authorizationRevision());
        var frozenSource = Sys008TypedArtifactContract.freezeOwnerSource(
                execution.payloadRef(), execution.payloadDigest());
        var source = "LEGAL_ENTITY".equals(execution.populationKind())
                ? Sys008TypedArtifactContract.issueLegalEntitySourceEnvelope(
                        population, execution.legalEntityPublicId(), frozenSource)
                : Sys008TypedArtifactContract.issueTenantSourceEnvelope(
                        population, frozenSource);
        var context = Sys008TypedArtifactContract.issueOwnerExecutionContext(
                execution.executionId(), execution.connectorId(),
                execution.mappingId(), execution.mappingVersion(),
                execution.requestedAt().toInstant(),
                "actor:" + execution.requestedByActorId(),
                execution.ownerIdempotencyKey(), execution.correlationId());
        ConnectorExecutionRequestV1 artifact = IntegrationExecutionArtifacts.project(
                context, population, source, execution.operation(),
                execution.mappingDirection());

        var payloadReference = referenceHandle(
                authority, execution, execution.executionId().toString(),
                ReferenceType.PAYLOAD_REF, "sys008.execution-payload",
                execution.payloadRef(), execution.payloadDigest());
        var mappingReference = referenceHandle(
                authority, execution, execution.mappingId().toString(),
                ReferenceType.MAPPING_REF, "sys008.mapping",
                execution.mappingRef(), execution.mappingDigest());
        String secretDigest = canonical.digest(
                execution.connectorId().toString(), execution.endpoint().toString(),
                execution.egressPolicyKey(), execution.secretRef());
        var secretReference = referenceHandle(
                authority, execution, execution.connectorId().toString(),
                ReferenceType.SECRET_REF, "sys008.connector-secret",
                execution.secretRef(), secretDigest);
        BoundaryResolutionPort resolver = boundaryPort();
        var definitionBoundary = resolver.resolveDefinition(
                execution.tenantId(), execution.connectorId(),
                execution.definitionVersion(), execution.authorizationRevision(),
                authority.purposeCode());
        if (!Objects.equals(definitionBoundary.endpoint(), execution.endpoint())
                || !Objects.equals(definitionBoundary.secretRef(),
                    execution.secretRef())
                || !Objects.equals(definitionBoundary.egressPolicyKey(),
                    execution.egressPolicyKey())
                || definitionBoundary.endpointPolicyRevision()
                    != execution.endpointPolicyRevision()
                || definitionBoundary.secretRevision() != execution.secretRevision()) {
            throw notFound("SYS008_FROZEN_DEFINITION_BOUNDARY_NOT_AVAILABLE");
        }
        if (!secretReference.equals(definitionBoundary.secretHandle())) {
            throw notFound("SYS008_FROZEN_DEFINITION_BOUNDARY_NOT_AVAILABLE");
        }
        var mappingBoundary = resolver.resolveMapping(
                execution.tenantId(), execution.connectorId(),
                execution.mappingVersion(), execution.authorizationRevision(),
                authority.purposeCode());
        if (!Objects.equals(mappingBoundary.direction(),
                    execution.mappingDirection())
                || !Objects.equals(mappingBoundary.mappingRef(),
                    execution.mappingRef())
                || !constant(mappingBoundary.mappingDigest(),
                    execution.mappingDigest())
                || !constant(mappingBoundary.sourceSchemaDigest(),
                    execution.sourceSchemaDigest())
                || !constant(mappingBoundary.targetSchemaDigest(),
                    execution.targetSchemaDigest())
                || !mappingReference.equals(mappingBoundary.mappingHandle())) {
            throw notFound("SYS008_FROZEN_MAPPING_BOUNDARY_NOT_AVAILABLE");
        }
        var payloadBoundary = resolver.resolvePayload(
                execution.tenantId(), execution.connectorId(),
                execution.executionId(), execution.payloadRef(),
                execution.payloadDigest(), execution.authorizationRevision(),
                authority.purposeCode());
        if (!payloadReference.equals(payloadBoundary.payloadHandle())) {
            throw notFound("SYS008_FROZEN_PAYLOAD_BOUNDARY_NOT_AVAILABLE");
        }
        var egressPin = Sys008EgressBoundary.requireAllowed(
                execution.endpoint(), definitionBoundary.allowlist(),
                definitionBoundary.resolvedDestinations());
        List<ProviderItemAttempt> selected = attempts.stream()
                .map(attempt -> new ProviderItemAttempt(
                        attempt.itemPublicId(), attempt.providerIdempotencyKey(),
                        attempt.requestDigest(), attempt.attemptFence()))
                .toList();
        long fence = attempts.stream().mapToLong(AttemptRecord::attemptFence)
                .max().orElseThrow();
        return new ProviderRequest(
                artifact, execution.providerIdempotencyKey(),
                execution.providerRequestDigest(), fence,
                execution.endpoint(), egressPin,
                definitionBoundary.secretHandle(),
                payloadBoundary.payloadHandle(), mappingBoundary.mappingHandle(),
                selected);
    }

    private Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle referenceHandle(
            Authority authority,
            ExecutionRecord execution,
            String subjectRef,
            ReferenceType type,
            String namespace,
            String reference,
            String digest) {
        ResolutionRequest request = new ResolutionRequest(
                reference, authority.tenantId(), execution.connectorId(), subjectRef,
                authority.purposeCode(), type, namespace, digest,
                execution.authorizationRevision(),
                UnpinnedReferenceConstraint.INSTANCE, true);
        return referencePort().resolveAuthorized(request)
                .map(Sys008OpaqueReferenceOwnerPort.ResolvedReference::providerHandle)
                .orElseThrow(() -> notFound(
                        "SYS008_OPAQUE_REFERENCE_NOT_AVAILABLE"));
    }

    private CommandSeal seal(
            Authority authority,
            String type,
            String action,
            UUID executionId,
            Object command) {
        return new CommandSeal(authority.tenantId(),
                authority.subjectPrincipalPublicId(), type, action,
                authority.routeContractKey(), authority.contextKey(),
                authority.contextScopeKey(), authority.decisionRevision(),
                authority.populationScopeDigest(), authority.fieldPolicyRevision(),
                authority.authorizationRevision(), authority.purposeCode(),
                authority.idempotencyKey(), canonical.digest(
                        action, executionId.toString(),
                        String.valueOf(authority.expectedObjectRevision()),
                        canonical.digest(command)),
                authority.correlationId());
    }

    private String compensationKey(
            long tenantId,
            ExecutionRecord execution,
            String policyDigest) {
        return "sys008-comp-" + canonical.digest(
                Long.toString(tenantId), execution.executionId().toString(),
                execution.providerIdempotencyKey(), policyDigest);
    }

    private String compensationRequestDigest(
            ExecutionRecord execution,
            String policyDigest,
            String reasonCode) {
        return canonical.digest(
                "SYS008_COMPENSATE", execution.executionId().toString(),
                execution.connectorId().toString(),
                Long.toString(execution.definitionVersion()),
                execution.operation().name(), execution.providerRequestDigest(),
                policyDigest, reasonCode);
    }

    private ExecutionRecord lockExecutionAuthorized(
            Authority authority,
            UUID executionId) {
        return executions.lockExecutionAuthorized(
                authority.tenantId(), executionId,
                authority.populationScopeDigest(), authority.populationKind(),
                authority.legalEntityPublicId());
    }

    private ExecutionResponse executionResponseAuthorized(
            Authority authority,
            UUID executionId) {
        return repository.executionResponseAuthorized(
                authority.tenantId(), executionId,
                authority.populationScopeDigest(), authority.populationKind(),
                authority.legalEntityPublicId());
    }

    private static boolean terminal(ReceiptStatus status) {
        return status != ReceiptStatus.ACCEPTED
                && status != ReceiptStatus.RUNNING;
    }

    private CommandSeal runtimeSeal(
            Authority authority,
            String action,
            UUID executionId,
            String evidenceDigest) {
        return new CommandSeal(authority.tenantId(),
                authority.subjectPrincipalPublicId(), "RUNTIME_PROVIDER_EFFECT", action,
                authority.routeContractKey(), authority.contextKey(),
                authority.contextScopeKey(), authority.decisionRevision(),
                authority.populationScopeDigest(), authority.fieldPolicyRevision(),
                authority.authorizationRevision(), authority.purposeCode(),
                authority.idempotencyKey(), canonical.digest(
                        action, executionId.toString(), evidenceDigest),
                authority.correlationId());
    }

    private <T> T inTransaction(Authority authority, Supplier<T> work) {
        T result = transactions.execute(status -> {
            repository.bindTenantPrincipal(
                    authority.tenantId(), authority.subjectPrincipalPublicId());
            return work.get();
        });
        return Objects.requireNonNull(result,
                "SYS008 transaction returned no result");
    }

    private <T> T withoutTransaction(Supplier<T> effect) {
        T result = effects.execute(status -> effect.get());
        return Objects.requireNonNull(result,
                "SYS008 provider-effect phase returned no result");
    }

    private static void requireExecutor(Authority authority) {
        if (authority == null || !authority.executor()
                || !IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE
                .equals(authority.purposeCode())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_EXECUTOR_AUTHORITY_REQUIRED");
        }
    }

    private static void requireRevision(
            ExecutionRecord execution, Authority authority) {
        if (authority.expectedObjectRevision() == null
                || authority.expectedObjectRevision()
                != execution.aggregateRevision()) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "SYS008_EXECUTION_REVISION_CONFLICT");
        }
    }

    private static void requireStepUp(Authority authority) {
        if (authority.stepUpEvidenceDigest() == null) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "SYS008_TARGET_BOUND_STEP_UP_REQUIRED");
        }
    }

    private static void requireExecutionTransition(
            ExecutionState from, ExecutionState to) {
        try {
            IntegrationDomainPolicy.requireTransition(from, to);
        } catch (IllegalStateException invalid) {
            throw conflict("SYS008_EXECUTION_STATE_INVALID");
        }
    }

    private static void requireCancellationEvidence(
            boolean dispatched, String policyRef, String receiptDigest) {
        try {
            IntegrationDomainPolicy.requireCancellationEvidence(
                    dispatched, policyRef, receiptDigest);
        } catch (IllegalStateException invalid) {
            throw conflict("SYS008_COMPENSATION_EVIDENCE_INVALID");
        }
    }

    private ProviderEffectPort providerPort() {
        ProviderEffectPort port = providers.getIfAvailable();
        if (port == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "SYS008_PRODUCTION_PROVIDER_NOT_BOUND");
        }
        return port;
    }

    private BoundaryResolutionPort boundaryPort() {
        BoundaryResolutionPort port = boundaries.getIfAvailable();
        if (port == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "SYS008_BOUNDARY_RESOLUTION_UNAVAILABLE");
        }
        return port;
    }

    private Sys008OpaqueReferenceOwnerPort referencePort() {
        Sys008OpaqueReferenceOwnerPort port = references.getIfAvailable();
        if (port == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "SYS008_REFERENCE_OWNER_UNAVAILABLE");
        }
        return port;
    }

    private String recordOperationsException(
            long tenantId,
            UUID executionId,
            String category,
            String evidenceDigest,
            OffsetDateTime observedAt) {
        var exception = new IntegrationRuntimePorts.OperationsException(
                tenantId, executionId, category, evidenceDigest, observedAt);
        operationsExceptions.record(exception);
        return exception.evidenceReference();
    }

    private static boolean paired(String reference, String digest) {
        return reference != null && !reference.isBlank()
                && digest != null && digest.matches("^[a-f0-9]{64}$");
    }

    private static boolean constant(String left, String right) {
        return left != null && right != null
                && java.security.MessageDigest.isEqual(
                        left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String executionRef(UUID id) {
        return "/v1/admin/hris/connector-executions/" + id;
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, message);
    }

    private record DispatchClaim(
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            OffsetDateTime claimedAt,
            boolean recovery) {
        private DispatchClaim {
            attempts = List.copyOf(attempts);
        }
    }

    private record EffectEvidence(
            ProviderEvidence evidence,
            String ambiguityCategory) {
        private EffectEvidence {
            Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }

    private record CompensationEffect(
            ProviderEvidence evidence,
            boolean ambiguous) {}

    private record ReconcileClaim(
            CommandReceipt receipt,
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            OffsetDateTime claimedAt,
            NonExportableProviderHandle providerReceiptHandle,
            boolean recovery,
            boolean replay) {
        private ReconcileClaim {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        static ReconcileClaim replay(CommandReceipt receipt) {
            return new ReconcileClaim(
                    receipt, null, List.of(), null, null, false, true);
        }
    }

    private record CancelClaim(
            CommandReceipt receipt,
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            OffsetDateTime claimedAt,
            NonExportableProviderHandle compensationPolicyHandle,
            String compensationPolicyDigest,
            String compensationKey,
            String compensationRequestDigest,
            boolean recovery,
            boolean replay,
            boolean completed) {
        private CancelClaim {
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        static CancelClaim replay(CommandReceipt receipt) {
            return new CancelClaim(receipt, null, List.of(), null,
                    null, null, null, null, false, true, false);
        }

        static CancelClaim completed(CommandReceipt receipt) {
            return new CancelClaim(receipt, null, List.of(), null,
                    null, null, null, null, false, false, true);
        }

        static CancelClaim recovery(
                CommandReceipt receipt,
                ExecutionRecord execution,
                List<AttemptRecord> attempts,
                OffsetDateTime claimedAt,
                NonExportableProviderHandle policyHandle,
                String policyDigest,
                String compensationKey,
                String compensationRequestDigest) {
            return new CancelClaim(receipt, execution, attempts, claimedAt,
                    policyHandle, policyDigest, compensationKey,
                    compensationRequestDigest, true, false, false);
        }
    }

    private record ResolvedCompensation(
            NonExportableProviderHandle policyHandle,
            String policyDigest) {}
}
