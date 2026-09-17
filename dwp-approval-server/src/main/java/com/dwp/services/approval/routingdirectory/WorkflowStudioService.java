package com.dwp.services.approval.routingdirectory;

import static com.dwp.services.approval.routingdirectory.RoutingDirectoryModels.Context;
import static com.dwp.services.approval.routingdirectory.WorkflowStudioModels.*;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
final class WorkflowStudioService {
    private final RoutingDirectoryRepository commands;
    private final WorkflowStudioRepository workflows;

    WorkflowStudioService(RoutingDirectoryRepository commands, WorkflowStudioRepository workflows) {
        this.commands = commands;
        this.workflows = workflows;
    }

    WorkflowStudio studio(UUID workflowId) {
        return workflows.studio(Context.current("workflow-studio-read-" + UUID.randomUUID()), workflowId, false);
    }

    WorkflowDiff diff(UUID workflowId, int fromVersion, int toVersion) {
        return workflows.diff(Context.current("workflow-studio-diff-" + UUID.randomUUID()),
                workflowId, fromVersion, toVersion);
    }

    WorkflowStudio save(UUID workflowId, CanvasSave input, String idempotencyKey) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "SAVE_WORKFLOW_CANVAS", workflowId, input,
                WorkflowStudio.class, () -> workflows.save(context, workflowId, input));
    }

    DryRunResult dryRun(UUID workflowId, CanvasDryRun input, String idempotencyKey) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "DRY_RUN_WORKFLOW", workflowId, input,
                DryRunResult.class, () -> workflows.dryRun(context, workflowId, input));
    }

    Retirement retire(UUID workflowId, RetireWorkflow input, String idempotencyKey) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "RETIRE_WORKFLOW", workflowId, input,
                Retirement.class, () -> workflows.retire(context, workflowId, input));
    }

    private <T> T idempotent(Context context, String operation, UUID target, Object input,
            Class<T> type, Supplier<T> command) {
        T prior = commands.prior(context, operation, target, input, type);
        if (prior != null) return prior;
        T result = command.get();
        commands.complete(context, operation, input, result);
        return result;
    }
}
