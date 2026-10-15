package com.dwp.services.platform.hrisconfiguration.explorer;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Trusted current-authority port for the preserved platform command-receipt profile.
 * Implementations are server-owned integrations; request headers and role lists are
 * never authority evidence.
 */
public interface HrisPlatformReceiptAuthoritySource {

    /** Validates the forwarded command-only receipt route before any row lookup. */
    default Optional<ForwardedReceiptAuthority> resolveForwardedReceipt(
            HrisExplorerAuthoritySource.AuthorityRequest request) {
        return Optional.empty();
    }

    Optional<CurrentReceiptAuthority> resolve(
            HrisExplorerAuthoritySource.AuthorityRequest request,
            UUID receiptId,
            HrisExplorerRepository.ReceiptAuthority stored);

    /**
     * Re-selects the Platform profile after a current Explorer receipt-route proof
     * permitted the sealed receipt family to be classified internally.
     */
    default Optional<CurrentReceiptAuthority> resolveAfterExplorerForwarded(
            HrisExplorerAuthoritySource.AuthorityRequest request,
            HrisExplorerAuthoritySource.AuthoritySnapshot explorerAuthority,
            UUID receiptId,
            HrisExplorerRepository.ReceiptAuthority stored) {
        return Optional.empty();
    }

    record ForwardedReceiptAuthority(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String contextKey,
            String contextScopeKey,
            String decisionRevision,
            OffsetDateTime freshUntil) {
    }

    record CurrentReceiptAuthority(
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
            String platformReceiptContextKey,
            String platformReceiptContextScopeKey,
            String platformReceiptDecisionRevision,
            OffsetDateTime freshUntil) {
    }
}
