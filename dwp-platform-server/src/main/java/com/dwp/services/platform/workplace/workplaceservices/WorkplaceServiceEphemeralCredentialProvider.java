package com.dwp.services.platform.workplace.workplaceservices;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface WorkplaceServiceEphemeralCredentialProvider {
    boolean supports(String adapterType);

    IssuedCredential issue(IssueRequest request);

    /** Read-only recovery for a previously accepted issue operation. */
    default IssuedCredential lookupIssue(IssueRequest request) {
        throw new IllegalStateException("Credential issue status lookup is unavailable.");
    }

    RevokeResult revoke(RevokeRequest request);

    /** Read-only recovery for a previously accepted revoke operation. */
    default RevokeResult lookupRevoke(RevokeRequest request) {
        throw new IllegalStateException("Credential revoke status lookup is unavailable.");
    }

    record IssueRequest(
            long tenantId,
            UUID operationId,
            UUID serviceOrderId,
            UUID serviceOrderLineId,
            String providerCode,
            String adapterType,
            long providerConfigurationVersion,
            String credentialBindingReference,
            long requesterUserId,
            OffsetDateTime notAfter) { }

    record IssuedCredential(
            String providerGrantReference,
            String oneTimeCredential,
            OffsetDateTime expiresAt) { }

    record RevokeRequest(
            long tenantId,
            UUID operationId,
            String providerCode,
            String adapterType,
            long providerConfigurationVersion,
            String credentialBindingReference,
            String providerGrantReference) { }

    record RevokeResult(boolean revoked, boolean resultUnknown, String detail) { }
}
