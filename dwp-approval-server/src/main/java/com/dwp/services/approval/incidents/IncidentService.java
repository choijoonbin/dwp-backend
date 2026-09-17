package com.dwp.services.approval.incidents;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.approval.incidents.IncidentModels.*;

@Service
public class IncidentService {
    private final IncidentRepository repository;
    private final IncidentProjectionRepository projections;
    private final IncidentRecoveryAttestationVerifier attestationVerifier;
    private final IncidentStageCommandFacade stageCommands;
    private final IncidentRecoveryExecutor executor;
    private final TransactionTemplate withoutTransaction;
    private final Clock clock;

    @Autowired
    public IncidentService(
            IncidentRepository repository,
            IncidentProjectionRepository projections,
            IncidentRecoveryAttestationVerifier attestationVerifier,
            IncidentStageCommandFacade stageCommands,
            ObjectProvider<IncidentRecoveryExecutor> executor,
            PlatformTransactionManager transactions,
            Clock clock) {
        this(repository, projections, attestationVerifier, stageCommands,
                executor.getIfAvailable(), transactions, clock);
    }

    IncidentService(
            IncidentRepository repository,
            IncidentProjectionRepository projections,
            IncidentRecoveryAttestationVerifier attestationVerifier,
            IncidentRecoveryExecutor executor,
            PlatformTransactionManager transactions,
            Clock clock) {
        this(repository, projections, attestationVerifier,
                new IncidentStageCommandFacade(repository, transactions),
                executor, transactions, clock);
    }

    private IncidentService(
            IncidentRepository repository,
            IncidentProjectionRepository projections,
            IncidentRecoveryAttestationVerifier attestationVerifier,
            IncidentStageCommandFacade stageCommands,
            IncidentRecoveryExecutor executor,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.repository = repository;
        this.projections = projections;
        this.attestationVerifier = attestationVerifier;
        this.stageCommands = stageCommands;
        this.executor = executor;
        this.withoutTransaction = new TransactionTemplate(transactions);
        this.withoutTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.clock = clock;
    }

    @Transactional
    public IncidentView open(String idempotencyKey, OpenIncident input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "OPEN_INCIDENT", input.incidentId(), input,
                IncidentView.class, () -> repository.open(context, input, clock.instant()));
    }

    @Transactional
    public IncidentView changeStatus(
            String idempotencyKey, UUID incidentId, StatusCommand input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "CHANGE_STATUS", incidentId, input,
                IncidentView.class, () -> repository.changeStatus(
                        context, incidentId, input, clock.instant()));
    }

    @Transactional
    public DiagnosticView addDiagnostic(
            String idempotencyKey, UUID incidentId, DiagnosticCommand input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "ADD_DIAGNOSTIC", input.diagnosticId(), input,
                DiagnosticView.class, () -> repository.addDiagnostic(
                        context, incidentId, input, clock.instant()));
    }

    @Transactional
    public PlanView createPlan(
            String idempotencyKey, UUID incidentId, CreatePlan input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "CREATE_PLAN", input.planId(), input,
                PlanView.class, () -> repository.createPlan(
                        context, incidentId, input, clock.instant()));
    }

    @Transactional
    public PlanView recordDryRun(
            String idempotencyKey,
            UUID incidentId,
            UUID planId,
            DryRunObservation input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "RECORD_DRY_RUN", planId, input,
                PlanView.class, () -> repository.recordDryRun(
                        context, incidentId, planId, input, clock.instant()));
    }

    public PlanView startStage(
            String idempotencyKey,
            UUID incidentId,
            UUID planId,
            StageStart input) {
        Context context = Context.current(idempotencyKey);
        Map<String, Object> command = Map.of("planId", planId, "stage", input);
        Instant startedAt = clock.instant();
        IncidentStageCommandFacade.BeginResult begin = stageCommands.begin(
                context, incidentId, planId, input, command, startedAt);
        if (begin.completed() != null) return begin.completed();
        return executeStage(context, incidentId, planId, command, begin.request());
    }

    @Transactional
    public PlanView completeStage(
            String idempotencyKey,
            UUID incidentId,
            UUID planId,
            StageCompletion input) {
        Context context = Context.current(idempotencyKey);
        Map<String, Object> command = Map.of("planId", planId, "stage", input);
        return idempotent(context, "COMPLETE_STAGE", planId, command,
                PlanView.class, () -> {
                    PlanView running = repository.requirePlan(
                            context, incidentId, planId, true);
                    ExecutionRequest request = stageCommands.executionRequest(
                            context, incidentId, planId, running, input.stageNumber());
                    if (input.expectedPlanVersion() != request.expectedPlanVersion()
                            || input.expectedStageVersion() != request.expectedStageVersion()) {
                        throw IncidentRejected.conflict(
                                "Recovery stage receipt versions do not match the running stage.");
                    }
                    VerifiedStageCompletion verified = attestationVerifier.verify(
                            request, input.receipt());
                    return repository.completeStage(
                            context, incidentId, planId, verified, clock.instant());
                });
    }

    @Transactional
    public PlanView reconcilePlan(
            String idempotencyKey,
            UUID incidentId,
            UUID planId,
            long expectedVersion) {
        Context context = Context.current(idempotencyKey);
        Map<String, Object> command = Map.of(
                "planId", planId, "expectedVersion", expectedVersion);
        return idempotent(context, "RECONCILE_PLAN", planId, command,
                PlanView.class, () -> repository.reconcilePlan(
                        context, incidentId, planId, expectedVersion, clock.instant()));
    }

    @Transactional
    public PostmortemView recordPostmortem(
            String idempotencyKey, UUID incidentId, PostmortemCommand input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "RECORD_POSTMORTEM", input.postmortemId(), input,
                PostmortemView.class, () -> repository.recordPostmortem(
                        context, incidentId, input, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<IncidentView> incidents() {
        return projections.incidents(readContext());
    }

    @Transactional(readOnly = true)
    public IncidentDetail incident(UUID incidentId) {
        return projections.detail(readContext(), incidentId);
    }

    @Transactional(readOnly = true)
    public PlanView plan(UUID incidentId, UUID planId) {
        return repository.requirePlan(readContext(), incidentId, planId, false);
    }

    private PlanView executeStage(
            Context context,
            UUID incidentId,
            UUID planId,
            Map<String, Object> command,
            ExecutionRequest request) {
        Instant completedAt = clock.instant();
        VerifiedStageCompletion completion;
        if (executor == null) {
            completion = attestationVerifier.unknown(
                    request, completedAt, "EXECUTOR_NOT_CONFIGURED");
        } else {
            try {
                SignedExecutionReceipt receipt = withoutTransaction.execute(
                        ignored -> executor.execute(request));
                completion = attestationVerifier.verify(request, receipt);
            } catch (RuntimeException ambiguous) {
                completion = attestationVerifier.unknown(
                        request, completedAt, "EXECUTOR_OUTCOME_NOT_VERIFIED");
            }
        }
        try {
            return stageCommands.complete(
                    context, incidentId, planId, command, completion, completedAt);
        } catch (RuntimeException persistenceFailure) {
            if (completion.state() == StageState.UNKNOWN_REMOTE_OUTCOME) {
                throw persistenceFailure;
            }
            VerifiedStageCompletion unknown = attestationVerifier.unknown(
                    request, clock.instant(), "COMPLETION_PERSISTENCE_NOT_CONFIRMED");
            try {
                return stageCommands.complete(
                        context, incidentId, planId, command, unknown, clock.instant());
            } catch (RuntimeException unknownFailure) {
                persistenceFailure.addSuppressed(unknownFailure);
                throw persistenceFailure;
            }
        }
    }

    private Context readContext() {
        return Context.current("read-" + UUID.randomUUID());
    }

    private <T> T idempotent(
            Context context,
            String operation,
            UUID target,
            Object input,
            Class<T> type,
            Supplier<T> command) {
        T prior = repository.prior(context, operation, target, input, type);
        if (prior != null) return prior;
        T result = command.get();
        repository.complete(context, operation, input, result);
        return result;
    }
}
