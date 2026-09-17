package com.dwp.services.platform.workplace.workplacevisits;

import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.ProviderKind;

/**
 * Adapter boundary for visitor and access-control providers. Implementations resolve credentials
 * from their own secure runtime; operation payloads intentionally contain no secret or credential.
 */
public interface WorkplaceVisitProviderPort {
    boolean supports(ProviderKind kind, String providerCode);

    ProviderOutcome dispatch(ProviderOperation operation);

    ProviderOutcome lookup(ProviderOperation originalOperation);

    enum OutcomeState { SUCCEEDED, FAILED, RESULT_UNKNOWN }

    record ProviderOperation(
            UUID operationId,
            long tenantId,
            UUID visitId,
            ProviderKind providerKind,
            String providerCode,
            long providerConfigurationVersion,
            String operationType) { }

    record ProviderOutcome(
            OutcomeState state,
            String evidenceReference,
            String detailCode) {
        public ProviderOutcome {
            if (state == null) throw new IllegalArgumentException("Provider outcome state is required.");
            if (evidenceReference != null
                    && !evidenceReference.matches("[A-Za-z0-9._~:/-]{8,320}")) {
                throw new IllegalArgumentException("Provider evidence reference is invalid.");
            }
            if (detailCode != null && !detailCode.matches("[A-Z0-9_]{2,120}")) {
                throw new IllegalArgumentException("Provider detail code is invalid.");
            }
        }
    }
}
