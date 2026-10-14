package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;

interface NavigationPolicyRepository {

    void lockIdempotency(long tenantId, long subjectId, String action, String idempotencyKey);

    Optional<StoredReceipt> findReceipt(
            long tenantId, long subjectId, String action, String idempotencyKey);

    boolean canonicalReceiptExists(
            long tenantId, UUID subjectPrincipalPublicId, UUID receiptId);

    void saveReceipt(
            long tenantId, long subjectId, String action, String idempotencyKey,
            String requestDigest, CommandReceipt receipt);

    void saveCanonicalReceipt(long tenantId, CanonicalReceipt receipt);

    RevisionView createDraft(
            long tenantId, UUID candidatePolicyId, UUID revisionId, long actorId,
            PolicyDocument policy, String payloadDigest, OffsetDateTime now);

    Optional<RevisionView> findByRevisionId(long tenantId, UUID revisionId);

    Optional<RevisionView> findLatestDraft(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId);

    Optional<RevisionView> findPublished(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId);

    List<RevisionView> findPublishedHistory(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId);

    boolean updateDraft(
            long tenantId, UUID revisionId, long expectedVersion,
            PolicyDocument policy, String payloadDigest, OffsetDateTime now);

    boolean transition(
            long tenantId, UUID revisionId, long expectedVersion,
            LifecycleState expectedState, LifecycleState targetState,
            long actorId, String evidenceDigest, OffsetDateTime now);

    boolean publish(
            long tenantId, UUID revisionId, long expectedVersion,
            long actorId, String evidenceDigest, OffsetDateTime now);

    RevisionView restore(
            long tenantId, UUID sourceRevisionId, UUID newRevisionId,
            long expectedCurrentVersion, long actorId, String evidenceDigest,
            OffsetDateTime now);

    void activate(long tenantId, RevisionView revision, OffsetDateTime now);

    void appendAudit(
            long tenantId, RevisionView revision, String action, long actorId,
            UUID correlationId, String evidenceDigest, OffsetDateTime now);

    void appendOutbox(
            long tenantId, RevisionView revision, String payload, OffsetDateTime now);

    record StoredReceipt(String requestDigest, CommandReceipt receipt) {
    }

    record CanonicalReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            String authorityPopulationScopeDigest,
            long fieldPolicyRevision,
            long authorityFieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            long authorityAuthorizationRevision,
            String originRouteContractKey,
            String originContextKey,
            String originContextScopeKey,
            String originDecisionRevision,
            String idempotencyKey,
            String requestDigest,
            UUID aggregatePublicId,
            long aggregateRevision,
            String status,
            String resultRef,
            UUID correlationId,
            OffsetDateTime createdAt) {
    }
}
