package com.dwp.services.platform.workplace.workplaceservices;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Port for an authoritative provider cancellation and refund operation.
 * {@link #cancel(ProviderRequest)} is the only mutating operation. Implementations must make it
 * idempotent for the supplied operation id. {@link #lookup(ProviderRequest, String)} and
 * {@link #reconcile(ProviderRequest, String)} must only read provider truth; recovery must never
 * issue another cancellation or refund mutation.
 */
public interface WorkplaceServiceLineAdjustmentProvider {

    ProviderOutcome cancel(ProviderRequest request);

    ProviderOutcome reconcile(ProviderRequest request, String providerOperationReference);

    /**
     * Reads the authoritative result for a previously assigned operation id. This method must not
     * create, cancel, refund, or otherwise mutate a provider resource.
     */
    default ProviderOutcome lookup(
            ProviderRequest request, String providerOperationReference) {
        return reconcile(request, providerOperationReference);
    }

    enum OutcomeState { SUCCEEDED, FAILED, RESULT_UNKNOWN, NOT_CONFIGURED }

    record ProviderRequest(
            UUID operationId,
            long tenantId,
            UUID serviceOrderId,
            UUID serviceOrderLineId,
            String providerCode,
            long providerConfigurationVersion,
            String credentialBindingReference,
            int cancelQuantity,
            BigDecimal refundableAmount,
            String currency,
            String reason) { }

    record ProviderOutcome(
            OutcomeState state,
            String providerOperationReference,
            BigDecimal refundedAmount,
            String refundReceiptReference,
            String detail) { }

    /** Raised only when a provider accepted an operation but its terminal result is unknown. */
    final class OutcomeUncertainException extends RuntimeException {
        private final String providerOperationReference;

        public OutcomeUncertainException(
                String providerOperationReference, String detail, Throwable cause) {
            super(detail, cause);
            this.providerOperationReference = providerOperationReference;
        }

        public String providerOperationReference() {
            return providerOperationReference;
        }
    }

    @Component
    final class UnconfiguredProvider implements WorkplaceServiceLineAdjustmentProvider {
        @Override
        public ProviderOutcome cancel(ProviderRequest request) {
            return unavailable();
        }

        @Override
        public ProviderOutcome reconcile(
                ProviderRequest request, String providerOperationReference) {
            return unavailable();
        }

        private static ProviderOutcome unavailable() {
            return new ProviderOutcome(OutcomeState.NOT_CONFIGURED, null,
                    BigDecimal.ZERO, null,
                    "Refund provider is not configured; manual settlement review is required.");
        }
    }
}
