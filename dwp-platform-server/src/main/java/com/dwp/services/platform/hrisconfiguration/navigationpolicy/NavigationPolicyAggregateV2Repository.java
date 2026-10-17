package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import java.util.Optional;
import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.AggregatePolicyDocument;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.AggregateRevisionView;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.PublishedAggregate;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.CommandReceipt;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyRepository.CanonicalReceipt;

interface NavigationPolicyAggregateV2Repository {

    void lockIdempotency(long tenantId, long subjectId, String action, String idempotencyKey);

    Optional<StoredReceipt> findReceipt(
            long tenantId, long subjectId, String action, String idempotencyKey);

    boolean canonicalReceiptExists(
            long tenantId, UUID subjectPrincipalPublicId, UUID receiptId);

    void saveReceipt(
            long tenantId, long subjectId, String action, String idempotencyKey,
            String requestDigest, CommandReceipt receipt);

    void saveCanonicalReceipt(long tenantId, CanonicalReceipt receipt);

    AggregateRevisionView createDraft(
            long tenantId, UUID candidatePolicyId, UUID revisionId, long actorId,
            AggregatePolicyDocument policy, String payloadDigest, OffsetDateTime now);

    Optional<AggregateRevisionView> findByRevisionId(long tenantId, UUID revisionId);

    Optional<AggregateRevisionView> findLatestDraft(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId);

    List<AggregateRevisionView> findPublishedHistory(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId);

    Optional<AggregateRevisionView> findPublishedRevision(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId);

    boolean updateDraft(
            long tenantId, UUID revisionId, long expectedVersion,
            AggregatePolicyDocument policy, String payloadDigest, OffsetDateTime now);

    boolean transition(
            long tenantId, UUID revisionId, long expectedVersion,
            LifecycleState expectedState, LifecycleState targetState,
            long actorId, String evidenceDigest, OffsetDateTime now);

    boolean publish(
            long tenantId, UUID revisionId, long expectedVersion,
            long actorId, String evidenceDigest, OffsetDateTime now);

    AggregateRevisionView restore(
            long tenantId, UUID sourceRevisionId, UUID newRevisionId,
            long expectedCurrentVersion, long actorId, String evidenceDigest,
            OffsetDateTime now);

    void activate(long tenantId, AggregateRevisionView revision);

    void appendAudit(
            long tenantId, AggregateRevisionView revision, String action, long actorId,
            UUID correlationId, String evidenceDigest, OffsetDateTime now);

    void appendOutbox(
            long tenantId, AggregateRevisionView revision, String payload,
            OffsetDateTime now);

    Optional<PublishedAggregate> findPublished(
            long tenantId, ScopeKind scopeKind, UUID legalEntityPublicId);

    record StoredReceipt(String requestDigest, CommandReceipt receipt) {
    }
}
