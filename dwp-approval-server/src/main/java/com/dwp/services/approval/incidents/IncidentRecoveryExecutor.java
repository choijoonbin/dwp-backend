package com.dwp.services.approval.incidents;

import static com.dwp.services.approval.incidents.IncidentModels.ExecutionRequest;
import static com.dwp.services.approval.incidents.IncidentModels.SignedExecutionReceipt;

@FunctionalInterface
public interface IncidentRecoveryExecutor {
    SignedExecutionReceipt execute(ExecutionRequest request);
}
