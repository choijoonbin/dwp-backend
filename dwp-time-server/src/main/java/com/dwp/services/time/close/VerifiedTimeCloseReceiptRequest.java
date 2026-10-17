package com.dwp.services.time.close;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Sealed close-domain authority for the provider-neutral receipt route. */
public record VerifiedTimeCloseReceiptRequest(
        long tenantId,
        long actorId,
        UUID subjectPrincipalPublicId,
        Set<String> permissions,
        Set<String> responsibilities,
        UUID populationPublicId,
        long populationRevision,
        long grantRevision,
        String populationScopeDigest,
        long fieldPolicyRevision,
        long authorizationRevision,
        String decisionRevision,
        Instant revalidateAt,
        UUID correlationId) {

    public VerifiedTimeCloseReceiptRequest {
        if (tenantId <= 0 || actorId <= 0 || populationRevision <= 0
                || grantRevision <= 0 || fieldPolicyRevision <= 0
                || authorizationRevision <= 0) {
            throw new IllegalArgumentException("receipt authority revisions are invalid");
        }
        TimeCloseModels.requireUuid(subjectPrincipalPublicId, "subjectPrincipalPublicId");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        responsibilities = Set.copyOf(Objects.requireNonNull(
                responsibilities, "responsibilities"));
        TimeCloseModels.requireUuid(populationPublicId, "populationPublicId");
        TimeCloseModels.requireDigest(populationScopeDigest, "populationScopeDigest");
        if (decisionRevision == null
                || !decisionRevision.matches("^psr-[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("decisionRevision is invalid");
        }
        Objects.requireNonNull(revalidateAt, "revalidateAt");
        TimeCloseModels.requireUuid(correlationId, "correlationId");
    }
}
