package com.dwp.platform.contract;

import java.util.List;

public interface AgentRuntimePort {

    PlanPreview preview(PlanRequest request);

    record PlanRequest(
            ExecutionContext context,
            String requestId,
            String intent,
            String action,
            String target,
            List<String> sourceReferences) {

        public PlanRequest {
            if (context == null) {
                throw new IllegalArgumentException("context is required");
            }
            requestId = ContractChecks.required(requestId, "requestId");
            intent = ContractChecks.required(intent, "intent");
            action = ContractChecks.required(action, "action");
            target = ContractChecks.required(target, "target");
            sourceReferences = sourceReferences == null
                    ? List.of()
                    : List.copyOf(sourceReferences);
        }
    }

    record PlanStep(String id, String title, String tool, String description) {

        public PlanStep {
            id = ContractChecks.required(id, "id");
            title = ContractChecks.required(title, "title");
            tool = ContractChecks.required(tool, "tool");
        }
    }

    record PlanPreview(
            String runId,
            RiskTier riskTier,
            boolean approvalRequired,
            boolean mutationAllowed,
            String summary,
            List<PlanStep> steps,
            List<String> sourceReferences,
            String auditId) {

        public PlanPreview {
            runId = ContractChecks.required(runId, "runId");
            summary = ContractChecks.required(summary, "summary");
            auditId = ContractChecks.required(auditId, "auditId");
            if (riskTier == null) {
                throw new IllegalArgumentException("riskTier is required");
            }
            if (mutationAllowed) {
                throw new IllegalArgumentException("a plan preview must never allow mutation");
            }
            if (riskTier.ordinal() >= RiskTier.L2.ordinal() && !approvalRequired) {
                throw new IllegalArgumentException("L2 and L3 plans require approval");
            }
            steps = steps == null ? List.of() : List.copyOf(steps);
            sourceReferences = sourceReferences == null
                    ? List.of()
                    : List.copyOf(sourceReferences);
        }
    }
}
