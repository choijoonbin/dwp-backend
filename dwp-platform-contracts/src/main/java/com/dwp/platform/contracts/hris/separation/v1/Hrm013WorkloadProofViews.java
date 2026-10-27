package com.dwp.platform.contracts.hris.separation.v1;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only verification views; the public contract retains its nested DTO identities. */
final class Hrm013WorkloadProofViews {
    private Hrm013WorkloadProofViews() {
    }

    interface ProfileView {
        Enum<?> kind();
        String bindingId();
        String callerWorkloadId();
        String ownerWorkloadId();
        String purpose();
        String method();
        String exactPath();
        int maximumBodyBytes();
        Map<String, ? extends Enum<?>> profileClaimTypes();
        Map<String, Object> expectedProfileClaims();
        Set<? extends Enum<?>> allowedDrainKinds();
        String authentication();
    }

    interface HeaderView {
        String name();
        String value();
    }

    interface TransportView {
        String workloadId();
        String leafCertificateSha256();
    }

    interface SigningKeyView {
        PublicKey publicKey();
        String keyId();
        String workloadId();
    }

    interface AuthorityView {
        boolean drainExisting();
    }

    interface DrainView {
        UUID drainRootWorkItemId();
        Instant drainRootCreatedAt();
        String drainRootPayloadSha256();
        Enum<?> drainContinuationKind();
        UUID drainContinuationEventId();
        long drainContinuationRevision();
        String drainContinuationPayloadSha256();
    }

    interface SelectorView<A extends AuthorityView, D extends DrainView> {
        long tenantId();
        A authorityMode();
        D drainClaims();
    }

    interface OwnerView {
        String name();
        String workloadId();
        String receiptPurpose();
        String dispatchBindingId();
        String dispatchPurpose();
        String dispatchPath();
        String taskType();
    }

    interface ResolverView {
        String bindingId();
        String workloadId();
    }
}
