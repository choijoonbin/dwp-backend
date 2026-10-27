package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.AttemptRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderItemEvidence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimeServiceInternals.*;

/** Internal integration provider evidence operations shared by the owner facade. */
final class IntegrationProviderEvidence {


    ProviderEvidence normalizeDispatchEvidence(
            DispatchClaim claim, ProviderEvidence returned) {
        if (!validProviderEvidence(
                claim.execution(), claim.attempts(), returned,
                Set.of(ProviderEvidence.Outcome.ACKNOWLEDGED,
                        ProviderEvidence.Outcome.FAILED,
                        ProviderEvidence.Outcome.RESULT_UNKNOWN), false)) {
            return unknown(claim.execution(), claim.attempts(), claim.claimedAt());
        }
        return returned;
    }

    ProviderEvidence normalizeReconciliationEvidence(
            ReconcileClaim claim, ProviderEvidence returned) {
        if (!validProviderEvidence(
                claim.execution(), claim.attempts(), returned,
                Set.of(ProviderEvidence.Outcome.RECONCILED,
                        ProviderEvidence.Outcome.PARTIAL,
                        ProviderEvidence.Outcome.FAILED,
                        ProviderEvidence.Outcome.RESULT_UNKNOWN), true)) {
            return unknown(claim.execution(), claim.attempts(), claim.claimedAt());
        }
        return returned;
    }

    boolean validProviderEvidence(
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            ProviderEvidence evidence,
            Set<ProviderEvidence.Outcome> allowedOutcomes,
            boolean reconciliation) {
        if (evidence == null || !allowedOutcomes.contains(evidence.outcome())) {
            return false;
        }
        try {
            IntegrationDomainPolicy.requireOriginalProviderEvidence(
                    execution.providerIdempotencyKey(),
                    evidence.providerIdempotencyKey(),
                    execution.providerRequestDigest(), evidence.requestDigest());
        } catch (RuntimeException unbound) {
            return false;
        }
        if (evidence.sourceCount() != execution.sourceCount()
                || evidence.acceptedCount() < 0
                || evidence.rejectedCount() < 0
                || evidence.quarantinedCount() < 0
                || evidence.acceptedCount() + evidence.rejectedCount()
                    > execution.sourceCount()
                || evidence.quarantinedCount() > evidence.rejectedCount()) {
            return false;
        }
        if (evidence.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN
                && !paired(evidence.receiptRef(), evidence.receiptDigest())) {
            return false;
        }
        if (reconciliation
                && evidence.outcome() != ProviderEvidence.Outcome.RESULT_UNKNOWN
                && (evidence.reconciliationDigest() == null
                || !evidence.reconciliationDigest().matches("^[a-f0-9]{64}$"))) {
            return false;
        }
        return exactItemEvidence(
                attempts, evidence.items(), evidence.outcome(), reconciliation);
    }

    ProviderEvidence unknown(
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            OffsetDateTime now) {
        List<ProviderItemEvidence> items = attempts.stream()
                .map(attempt -> new ProviderItemEvidence(
                        attempt.itemPublicId(),
                        ProviderEvidence.Outcome.RESULT_UNKNOWN,
                        attempt.providerIdempotencyKey(), attempt.requestDigest(),
                        null, null, null, false, null, false))
                .toList();
        return new ProviderEvidence(
                ProviderEvidence.Outcome.RESULT_UNKNOWN,
                execution.providerIdempotencyKey(),
                execution.providerRequestDigest(), null, null,
                execution.sourceCount(), execution.acceptedCount(),
                execution.rejectedCount(), execution.quarantinedCount(),
                execution.reconciliationDigest(), now, items);
    }
}
