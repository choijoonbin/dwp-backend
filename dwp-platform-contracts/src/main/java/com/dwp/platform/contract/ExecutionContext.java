package com.dwp.platform.contract;

import java.util.Set;

public record ExecutionContext(
        String tenantId,
        String userId,
        Set<String> roles,
        String correlationId) {

    public ExecutionContext {
        tenantId = ContractChecks.required(tenantId, "tenantId");
        userId = ContractChecks.required(userId, "userId");
        correlationId = ContractChecks.required(correlationId, "correlationId");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
