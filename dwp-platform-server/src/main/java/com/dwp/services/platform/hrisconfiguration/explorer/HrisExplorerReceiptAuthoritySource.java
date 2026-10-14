package com.dwp.services.platform.hrisconfiguration.explorer;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Trusted current-authority port for an Explorer command receipt's immutable
 * mutation origin. The receipt DATA route is authorized separately; this port
 * re-evaluates the stored ACTION route and its route-bound decision revision.
 */
public interface HrisExplorerReceiptAuthoritySource {

    Optional<CurrentExplorerReceiptAuthority> resolveExplorerOrigin(
            HrisExplorerAuthoritySource.AuthorityRequest receiptRequest,
            UUID receiptId,
            HrisExplorerRepository.ReceiptAuthority stored);

    record CurrentExplorerReceiptAuthority(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String originatingAction,
            String authorityPopulationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String originRouteContractKey,
            String originContextKey,
            String originContextScopeKey,
            String originDecisionRevision,
            OffsetDateTime freshUntil) {
    }
}
