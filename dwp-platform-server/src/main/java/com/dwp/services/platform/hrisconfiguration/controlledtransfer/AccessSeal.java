package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** Verified application-layer authority carried from the HTTP adapter to the use case. */
record AccessSeal(
        long tenantId,
        UUID subjectPrincipalPublicId,
        String populationScopeDigest,
        String populationKind,
        Set<String> projectionFieldGroups,
        long fieldPolicyRevision,
        long authorizationRevision,
        String routeContractKey,
        String contextKey,
        String contextScopeKey,
        String decisionRevision,
        String purposeCode,
        String authorityDigest,
        OffsetDateTime authorityFreshUntil,
        UUID correlationId,
        String idempotencyKey) {

    AccessSeal {
        projectionFieldGroups = projectionFieldGroups == null
                ? Set.of() : Set.copyOf(projectionFieldGroups);
    }
}
