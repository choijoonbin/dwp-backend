package com.dwp.services.platform.workplace.safetyoperations;

import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

/** Provider-owned port. A timeout must be returned as RESULT_UNKNOWN, never inferred as failure. */
public interface SafetyDispatchProvider {
    boolean supports(DeliveryChannel channel);

    /**
     * Readiness is evaluated against the immutable provider binding captured for this attempt.
     * Test and in-process providers may keep the default; network providers must fail closed.
     */
    default boolean ready(ProviderContext context) {
        return context == null || supports(context.channel());
    }

    DispatchResult dispatch(DispatchRequest request);

    /** Read-only provider lookup. Implementations must never create or repeat a dispatch. */
    DispatchResult lookupStatus(LookupRequest request);

    record DispatchRequest(
            UUID attemptId, long tenantId, UUID incidentId, DeliveryChannel channel,
            String subjectKeySha256, Long subjectUserId, Severity severity,
            String message, String safetyAction, ProviderContext providerContext) {
        public DispatchRequest(
                UUID attemptId, long tenantId, UUID incidentId, DeliveryChannel channel,
                String subjectKeySha256, Long subjectUserId, Severity severity,
                String message, String safetyAction) {
            this(attemptId, tenantId, incidentId, channel, subjectKeySha256, subjectUserId,
                    severity, message, safetyAction, null);
        }
    }

    record LookupRequest(
            UUID attemptId, long tenantId, UUID incidentId, DeliveryChannel channel,
            String providerOperationReference, ProviderContext providerContext) {
        public LookupRequest(
                UUID attemptId, long tenantId, UUID incidentId, DeliveryChannel channel,
                String providerOperationReference) {
            this(attemptId, tenantId, incidentId, channel, providerOperationReference, null);
        }
    }

    record ProviderContext(
            DeliveryChannel channel,
            String providerCode,
            long providerConfigurationVersion,
            String credentialReference) { }

    record DispatchResult(
            AttemptState state,
            String providerOperationReference,
            String resultCode,
            String evidenceReference) {
        public DispatchResult {
            if (state != AttemptState.DELIVERED
                    && state != AttemptState.DELIVERY_FAILED
                    && state != AttemptState.RESULT_UNKNOWN) {
                throw new IllegalArgumentException("Provider returned a non-terminal dispatch state.");
            }
        }
    }

    final class OutcomeUnknownException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String providerOperationReference;

        public OutcomeUnknownException(String message, String providerOperationReference) {
            super(message);
            this.providerOperationReference = providerOperationReference;
        }

        public String providerOperationReference() {
            return providerOperationReference;
        }
    }
}
