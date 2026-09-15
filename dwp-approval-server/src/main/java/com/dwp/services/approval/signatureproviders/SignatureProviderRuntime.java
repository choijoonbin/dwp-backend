package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Runtime observations are accepted only from a server-installed implementation. */
public interface SignatureProviderRuntime {
    List<RuntimeProvider> current(long tenantId, String resourceSetKey);
    List<ProbeResult> probe(long tenantId, String resourceSetKey, ProbeInput input);
    Kms probeKms(long tenantId, String resourceSetKey, KmsProbeInput input);
    Worm inspectWorm(long tenantId, String resourceSetKey, WormInspectionInput input);
    ExternalTransition handover(long tenantId, String resourceSetKey, ExternalRequest current,
                                String idempotencyKey);
    ExternalTransition refresh(long tenantId, String resourceSetKey, ExternalRequest current,
                               String idempotencyKey);
    ExternalTransition cancel(long tenantId, String resourceSetKey, ExternalRequest current,
                              String idempotencyKey);

    record RuntimeProvider(ProviderTarget target, Environment environment, boolean adapterInstalled,
                           boolean configurationRegistered, boolean credentialRegistered,
                           boolean credentialVerified, String endpointOriginSha256,
                           String accountBindingSha256, String callbackAuthenticationMode,
                           String configurationOwner, Readiness readiness, Instant lastProbeAt,
                           List<Check> checks) {
        public RuntimeProvider {
            required(target); required(environment); required(readiness);
            text(callbackAuthenticationMode, 80); text(configurationOwner, 160);
            if (endpointOriginSha256 != null) sha(endpointOriginSha256);
            if (accountBindingSha256 != null) sha(accountBindingSha256);
            checks = bounded(checks, 32);
            if (configurationRegistered != (target.expectedConfiguration() != null))
                throw invalid("Runtime configuration pin is inconsistent");
            if (credentialVerified && (!adapterInstalled || !configurationRegistered
                    || !credentialRegistered || lastProbeAt == null
                    || checks.stream().noneMatch(check -> check.state() == ObservationState.PASS)))
                throw invalid("Verified runtime configuration requires observed provider evidence");
            if (Set.of(Readiness.VERIFIED_SANDBOX, Readiness.VERIFIED_PRODUCTION).contains(readiness)
                    && (!credentialVerified || checks.stream().anyMatch(check -> check.state() == ObservationState.FAIL)))
                throw invalid("Verified readiness cannot include failed or unverified evidence");
        }
    }

    record ExternalTransition(ExternalState state, String remoteReferenceSha256,
                              List<String> reasonCodes, UUID evidenceId, String evidenceSha256,
                              List<ExternalArtifact> artifacts) {
        public ExternalTransition {
            required(state); reasonCodes = reasons(reasonCodes); evidence(evidenceId, evidenceSha256);
            artifacts = bounded(artifacts, 20);
            if (state == ExternalState.COMPLETED_VERIFIED
                    && (remoteReferenceSha256 == null || evidenceId == null))
                throw invalid("Verified completion requires provider and evidence bindings");
            if (Set.of(ExternalState.FAILED, ExternalState.UNKNOWN_REMOTE_OUTCOME).contains(state)
                    && reasonCodes.isEmpty())
                throw invalid("Failed or unknown outcomes require exact reasons");
            if ((state == ExternalState.COMPLETED_VERIFIED && artifacts.isEmpty())
                    || (state != ExternalState.COMPLETED_VERIFIED && !artifacts.isEmpty()))
                throw invalid("Only verified completion can attach retained artifacts");
            if (remoteReferenceSha256 != null) sha(remoteReferenceSha256);
        }
    }
}
