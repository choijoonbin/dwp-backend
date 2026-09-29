package com.dwp.platform.contracts.hris.capability.v1;

import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.DecisionKind;

import java.util.Set;

/** Admission outcome. Denials never carry an authorized provider binding. */
public record OptionalCapabilityAdmissionDecisionV1(
        DecisionKind kind,
        String capabilityId,
        String operation,
        Set<OptionalCapabilityAuthorizedBindingV1> authorizedBindings,
        OptionalCapabilityAdmissionErrorV1 error) {
    public OptionalCapabilityAdmissionDecisionV1 {
        if (kind == null || capabilityId == null || operation == null || authorizedBindings == null) {
            throw new IllegalArgumentException("closed decision fields are required");
        }
        authorizedBindings = Set.copyOf(authorizedBindings);
        if (kind.allowed() == (error != null) || (!kind.allowed() && !authorizedBindings.isEmpty())) {
            throw new IllegalArgumentException("decision, error and binding state disagree");
        }
    }

    public boolean allowed() {
        return kind.allowed();
    }

    static OptionalCapabilityAdmissionDecisionV1 allow(
            DecisionKind kind,
            String capabilityId,
            String operation,
            Set<OptionalCapabilityAuthorizedBindingV1> bindings) {
        return new OptionalCapabilityAdmissionDecisionV1(
                kind, capabilityId, operation, bindings, null);
    }

    static OptionalCapabilityAdmissionDecisionV1 deny(
            DecisionKind kind,
            String capabilityId,
            String operation,
            OptionalCapabilityAdmissionErrorV1 error) {
        return new OptionalCapabilityAdmissionDecisionV1(
                kind, capabilityId, operation, Set.of(), error);
    }
}
