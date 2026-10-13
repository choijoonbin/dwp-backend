package com.dwp.services.payroll.configuration;

import java.util.Set;
import java.util.UUID;

interface PayrollConfigurationScopeResolver {

    Scope resolve(PayrollConfigurationRequestContext.VerifiedSubject subject);

    record Scope(
            String scopeDecisionRevision,
            Set<UUID> legalEntityIds,
            Set<UUID> payrollGroupIds,
            String scopeDigest) {
        public Scope {
            if (scopeDecisionRevision == null || scopeDecisionRevision.isBlank()
                    || legalEntityIds == null || legalEntityIds.isEmpty()
                    || payrollGroupIds == null || payrollGroupIds.isEmpty()) {
                throw new IllegalArgumentException("payroll population scope is incomplete");
            }
            legalEntityIds = Set.copyOf(legalEntityIds);
            payrollGroupIds = Set.copyOf(payrollGroupIds);
            scopeDigest = PayrollConfigurationModels.requireDigest(
                    scopeDigest, "scopeDigest");
        }
    }
}
