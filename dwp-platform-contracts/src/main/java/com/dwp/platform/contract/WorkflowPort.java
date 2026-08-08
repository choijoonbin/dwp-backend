package com.dwp.platform.contract;

import java.time.Instant;
import java.util.Map;

public interface WorkflowPort {

    PlanPreview preview(PlanCommand command);

    StartReceipt start(ApprovedCommand command);

    record PlanCommand(
            ExecutionContext context,
            String requestId,
            String action,
            String target,
            RiskTier riskTier,
            Map<String, Object> input) {

        public PlanCommand {
            if (context == null || riskTier == null) {
                throw new IllegalArgumentException("context and riskTier are required");
            }
            requestId = ContractChecks.required(requestId, "requestId");
            action = ContractChecks.required(action, "action");
            target = ContractChecks.required(target, "target");
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }

    record PlanPreview(
            String planId,
            String planHash,
            RiskTier riskTier,
            boolean approvalRequired,
            Instant expiresAt) {

        public PlanPreview {
            planId = ContractChecks.required(planId, "planId");
            planHash = ContractChecks.required(planHash, "planHash");
            if (riskTier == null || expiresAt == null) {
                throw new IllegalArgumentException("riskTier and expiresAt are required");
            }
            if (riskTier.ordinal() >= RiskTier.L2.ordinal() && !approvalRequired) {
                throw new IllegalArgumentException("L2 and L3 plans require approval");
            }
        }
    }

    record ApprovedCommand(
            ExecutionContext context,
            String planId,
            String planHash,
            String approvalId,
            String idempotencyKey) {

        public ApprovedCommand {
            if (context == null) {
                throw new IllegalArgumentException("context is required");
            }
            planId = ContractChecks.required(planId, "planId");
            planHash = ContractChecks.required(planHash, "planHash");
            approvalId = ContractChecks.required(approvalId, "approvalId");
            idempotencyKey = ContractChecks.required(idempotencyKey, "idempotencyKey");
        }
    }

    record StartReceipt(String workflowId, String auditId, String state) {

        public StartReceipt {
            workflowId = ContractChecks.required(workflowId, "workflowId");
            auditId = ContractChecks.required(auditId, "auditId");
            state = ContractChecks.required(state, "state");
        }
    }
}
