package com.dwp.services.people.hris.workbench.hrm012;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted Auth field-policy boundary for IAQ-012. Browser headers, query selectors
 * and locally inferred roles are never valid implementations of this port.
 */
public interface Hrm012FieldDecisionPort {
    DecisionResult resolve(DecisionBinding binding);

    enum FieldDecision { VIEW, MASK, OMIT }

    record DecisionBinding(
            long tenantId,
            long numericUserId,
            UUID personPublicId,
            String routeContractKey,
            String capability,
            String purpose,
            String scopeKey,
            String targetPopulationRevision,
            String authorizationRevision,
            String fieldPolicyKey,
            String originatingRouteContractKey,
            String originatingAction,
            String originatingCapability,
            Set<String> fieldGroups) {
        public DecisionBinding {
            fieldGroups = fieldGroups == null ? Set.of() : Set.copyOf(fieldGroups);
        }
    }

    record DecisionResult(
            DecisionBinding binding,
            Map<String, FieldDecision> currentDecisions,
            FieldDecision originatingCommandReceiptDecision,
            String fieldDecisionRevision,
            Instant evaluatedAt,
            Instant revalidateAt) {
        public DecisionResult {
            currentDecisions = currentDecisions == null
                    ? Map.of() : Map.copyOf(currentDecisions);
        }
    }
}
