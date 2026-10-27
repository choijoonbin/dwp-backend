package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderRequest;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.AttemptRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.CompensationClaim;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.BoundaryResolutionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.OperationsExceptionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEffectPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimeServiceInternals.*;

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

    private final IntegrationRuntimeServiceOperations operations;
    private final IntegrationProviderEvidence providerEvidence;
    private final IntegrationRuntimeBoundary runtimeBoundary;
    private final IntegrationRuntimeFinalizer finalizer;
    private final IntegrationRuntimeEffects providerEffects;

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
        this.operations = new OwnerOperations();
        this.providerEvidence = new IntegrationProviderEvidence();
        this.runtimeBoundary = new IntegrationRuntimeBoundary(boundaries, references, canonical);
        this.finalizer = new IntegrationRuntimeFinalizer(
                operations, repository, executions, canonical, maxRetryGenerations);
        this.providerEffects = new IntegrationRuntimeEffects(operations);
    }

    /** Internal worker entry point; there is deliberately no browser dispatch route. */
    public ExecutionResponse dispatch(Authority authority, UUID executionId) {
        requireExecutor(authority);
        DispatchClaim claim = inTransaction(authority, () -> {
            ExecutionRecord current = operations.lockExecutionAuthorized(
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
                providerEffects.dispatchEffect(authority, claim));
        ProviderEvidence durableEvidence = effect.evidence();
        String durableCategory = effect.ambiguityCategory();
        return inTransaction(authority, () -> finalizer.finalizeDispatch(
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
            ExecutionRecord current = operations.lockExecutionAuthorized(
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
                    runtimeBoundary.resolveProviderReceipt(authority, current, command);
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
        ProviderEffectPort provider = claim.recovery() ? null : operations.providerPort();

        EffectEvidence effect = withoutTransaction(() ->
                providerEffects.reconciliationEffect(authority, command, claim, provider));
        ProviderEvidence durableEvidence = effect.evidence();
        String durableCategory = effect.ambiguityCategory();
        return inTransaction(authority, () -> finalizer.finalizeReconciliation(
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
            ExecutionRecord current = operations.lockExecutionAuthorized(
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
            ExecutionRecord current = operations.lockExecutionAuthorized(
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
                ResolvedCompensation compensation = runtimeBoundary.resolveCompensation(
                        authority, current, command);
                String compensationKey = runtimeBoundary.compensationKey(
                        authority.tenantId(), current,
                        compensation.policyDigest());
                String compensationRequestDigest = runtimeBoundary.compensationRequestDigest(
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
            ResolvedCompensation compensation = runtimeBoundary.resolveCompensation(
                    authority, current, command);
            List<AttemptRecord> attempts = executions.currentAttempts(current);
            String compensationKey = runtimeBoundary.compensationKey(
                    authority.tenantId(), current, compensation.policyDigest());
            String compensationRequestDigest = runtimeBoundary.compensationRequestDigest(
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
        ProviderEffectPort provider = claim.recovery() ? null : operations.providerPort();

        CompensationEffect effect = withoutTransaction(() ->
                providerEffects.compensationEffect(authority, command, claim, provider));
        ProviderEvidence durableResult = effect.evidence();
        boolean durableAmbiguous = effect.ambiguous();
        return inTransaction(authority, () -> finalizer.finalizeCancellation(
                authority, executionId, command, seal, claim,
                durableResult, durableAmbiguous));
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

    /** Keeps collaboration behind the original service bean API. */
    private final class OwnerOperations implements IntegrationRuntimeServiceOperations {

        @Override
        public ExecutionRecord lockExecutionAuthorized(
                Authority authority,
                UUID executionId) {
            return executions.lockExecutionAuthorized(
                    authority.tenantId(), executionId,
                    authority.populationScopeDigest(), authority.populationKind(),
                    authority.legalEntityPublicId());
        }

        @Override
        public ExecutionResponse executionResponseAuthorized(
                Authority authority,
                UUID executionId) {
            return repository.executionResponseAuthorized(
                    authority.tenantId(), executionId,
                    authority.populationScopeDigest(), authority.populationKind(),
                    authority.legalEntityPublicId());
        }

        @Override
        public CommandSeal runtimeSeal(
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

        @Override
        public ProviderEffectPort providerPort() {
            ProviderEffectPort port = providers.getIfAvailable();
            if (port == null) {
                throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                        "SYS008_PRODUCTION_PROVIDER_NOT_BOUND");
            }
            return port;
        }

        @Override
        public String recordOperationsException(
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

        @Override
        public ProviderEvidence normalizeReconciliationEvidence(
                ReconcileClaim claim, ProviderEvidence returned) {
            return providerEvidence.normalizeReconciliationEvidence(claim, returned);
        }

        @Override
        public ProviderEvidence normalizeDispatchEvidence(
                DispatchClaim claim, ProviderEvidence returned) {
            return providerEvidence.normalizeDispatchEvidence(claim, returned);
        }

        @Override
        public ProviderRequest providerRequest(
                Authority authority,
                ExecutionRecord execution,
                List<AttemptRecord> attempts) {
            return runtimeBoundary.providerRequest(authority, execution, attempts);
        }

        @Override
        public ProviderEvidence unknown(
                ExecutionRecord execution,
                List<AttemptRecord> attempts,
                OffsetDateTime now) {
            return providerEvidence.unknown(execution, attempts, now);
        }
    }
}
