package com.dwp.services.approval.incidents;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.incidents.IncidentModels.*;

@Service
final class IncidentStageCommandFacade {
    private static final String OPERATION = "START_STAGE";

    private final IncidentRepository repository;
    private final TransactionTemplate requiresNew;

    IncidentStageCommandFacade(
            IncidentRepository repository,
            PlatformTransactionManager transactions) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    BeginResult begin(
            Context context,
            UUID incidentId,
            UUID planId,
            StageStart input,
            Map<String, Object> command,
            Instant now) {
        return requiresNew.execute(status -> {
            PlanView prior = repository.prior(
                    context, OPERATION, planId, command, PlanView.class);
            if (prior != null) return BeginResult.completed(prior);
            PlanView running = repository.startStage(
                    context, incidentId, planId, input, now);
            return BeginResult.started(executionRequest(
                    context, incidentId, planId, running, input.stageNumber()));
        });
    }

    PlanView complete(
            Context context,
            UUID incidentId,
            UUID planId,
            Map<String, Object> command,
            VerifiedStageCompletion completion,
            Instant now) {
        return requiresNew.execute(status -> {
            PlanView result = repository.completeStage(
                    context, incidentId, planId, completion, now);
            repository.complete(context, OPERATION, command, result);
            return result;
        });
    }

    ExecutionRequest executionRequest(
            Context context,
            UUID incidentId,
            UUID planId,
            PlanView plan,
            int stageNumber) {
        StageView stage = plan.stages().stream()
                .filter(item -> item.stageNumber() == stageNumber)
                .findFirst()
                .orElseThrow(() -> IncidentRejected.invalid(
                        "Recovery stage does not exist in this plan."));
        if (stage.state() != StageState.RUNNING || stage.executionKey() == null) {
            throw IncidentRejected.conflict("Recovery stage is not awaiting a receipt.");
        }
        return new ExecutionRequest(
                context.tenantId(), context.resourceSetKey(), incidentId, planId,
                stage.stageNumber(), plan.version(), stage.version(), stage.actionKind(),
                stage.targetType(), stage.targetId(), stage.expectedTargetVersion(),
                stage.executionKey());
    }

    record BeginResult(ExecutionRequest request, PlanView completed) {
        static BeginResult started(ExecutionRequest request) {
            return new BeginResult(request, null);
        }

        static BeginResult completed(PlanView completed) {
            return new BeginResult(null, completed);
        }
    }
}
