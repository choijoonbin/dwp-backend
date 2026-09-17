package com.dwp.services.platform.workplace.workplaceservices;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface WorkplaceServiceProviderVerifier {
    boolean supports(String adapterType);

    VerificationResult verify(VerificationRequest request);

    record VerificationRequest(
            long tenantId,
            UUID operationId,
            UUID providerProfileId,
            String providerCode,
            String adapterType,
            String credentialBindingReference,
            long configurationVersion,
            List<String> capabilities) { }

    record VerificationResult(
            String reportedState,
            String evidenceReference,
            List<String> capabilityEvidence,
            OffsetDateTime sourceObservedAt,
            String errorCode) { }
}
