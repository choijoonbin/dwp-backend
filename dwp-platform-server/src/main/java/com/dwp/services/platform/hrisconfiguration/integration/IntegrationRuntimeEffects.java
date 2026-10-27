package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEffectPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderRequest;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimeServiceInternals.*;

/** Internal integration runtime effects operations shared by the owner facade. */
final class IntegrationRuntimeEffects {

    private final IntegrationRuntimeServiceOperations owner;

    IntegrationRuntimeEffects(
            IntegrationRuntimeServiceOperations owner) {
        this.owner = owner;
    }


    EffectEvidence dispatchEffect(
            Authority authority, DispatchClaim claim) {
        if (claim.recovery()) {
            return new EffectEvidence(
                    owner.unknown(claim.execution(), claim.attempts(), claim.claimedAt()),
                    "STALE_DISPATCH_CLAIM");
        }
        try {
            ProviderRequest request = owner.providerRequest(
                    authority, claim.execution(), claim.attempts());
            ProviderEvidence returned = owner.providerPort().dispatch(request);
            ProviderEvidence evidence = owner.normalizeDispatchEvidence(claim, returned);
            String category = evidence.outcome()
                    == ProviderEvidence.Outcome.RESULT_UNKNOWN
                    && (returned == null
                    || returned.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN)
                    ? "PROVIDER_EVIDENCE_UNBOUND" : null;
            return new EffectEvidence(evidence, category);
        } catch (RuntimeException ambiguous) {
            return new EffectEvidence(
                    owner.unknown(claim.execution(), claim.attempts(), claim.claimedAt()),
                    "PROVIDER_RESULT_UNKNOWN");
        }
    }

    EffectEvidence reconciliationEffect(
            Authority authority,
            ReconcileCommand command,
            ReconcileClaim claim,
            ProviderEffectPort provider) {
        if (claim.recovery()) {
            return new EffectEvidence(
                    owner.unknown(claim.execution(), claim.attempts(), claim.claimedAt()),
                    "STALE_RECONCILIATION_CLAIM");
        }
        try {
            ProviderRequest request = owner.providerRequest(
                    authority, claim.execution(), claim.attempts());
            ProviderEvidence returned = provider.reconcile(
                    new IntegrationRuntimePorts.ReconcileRequest(
                            request, claim.providerReceiptHandle(),
                            command.providerReceiptDigest(),
                            command.expectedSourceCount()));
            ProviderEvidence evidence = owner.normalizeReconciliationEvidence(
                    claim, returned);
            String category = evidence.outcome()
                    == ProviderEvidence.Outcome.RESULT_UNKNOWN
                    && (returned == null
                    || returned.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN)
                    ? "RECONCILIATION_EVIDENCE_UNBOUND" : null;
            return new EffectEvidence(evidence, category);
        } catch (RuntimeException ambiguous) {
            return new EffectEvidence(
                    owner.unknown(claim.execution(), claim.attempts(), claim.claimedAt()),
                    "RECONCILIATION_RESULT_UNKNOWN");
        }
    }

    CompensationEffect compensationEffect(
            Authority authority,
            CancelCommand command,
            CancelClaim claim,
            ProviderEffectPort provider) {
        if (claim.recovery()) {
            return new CompensationEffect(null, true);
        }
        try {
            ProviderRequest originalRequest = owner.providerRequest(
                    authority, claim.execution(), claim.attempts());
            ProviderEvidence result = provider.compensate(
                    new IntegrationRuntimePorts.CompensationRequest(
                            originalRequest, claim.compensationPolicyHandle(),
                            claim.compensationPolicyDigest(), command.reasonCode(),
                            claim.compensationKey(),
                            claim.compensationRequestDigest()));
            return new CompensationEffect(result,
                    !IntegrationRuntimeServiceInternals.validCompensationEvidence(
                            result, claim.compensationKey(),
                            claim.compensationRequestDigest()));
        } catch (RuntimeException unavailable) {
            return new CompensationEffect(null, true);
        }
    }
}
