package com.dwp.services.provider.resourcegovernance;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.*;

/** Dependency-inverted persistence contract for resource commitment governance. */
interface ResourceCommitmentPersistence<C, R, F, T, L> {
    boolean tenantExists(UUID tenantId);
    List<C> commitments(UUID tenantId, int fetchLimit);
    Optional<C> commitment(UUID tenantId, String resourceKey);
    Optional<C> lockCommitment(UUID tenantId, String resourceKey);
    C createCommitment(UUID tenantId, String resourceKey,
                       UpsertCommitmentRequest request, Long actorId);
    boolean updateCommitment(UUID tenantId, String resourceKey, long version,
                             UpsertCommitmentRequest request, Long actorId);
    boolean publishedCommercialEvidenceMatchesTenant(UUID tenantId, UUID revisionId);
    boolean hasActiveTemporaryOverride(UUID tenantId, String resourceKey);
    List<R> resourceChanges(UUID tenantId, int fetchLimit);
    Optional<R> resourceChange(UUID changeRequestId);
    Optional<R> lockResourceChange(UUID changeRequestId);
    Optional<R> resourceChangeByRequestKey(Long requesterId, String requestKey);
    R createResourceChange(UUID changeRequestId, UUID tenantId, String resourceKey,
                           CreateResourceCommitmentChangeRequest request,
                           JsonNode baselineDefinition, JsonNode proposedDefinition,
                           Instant decisionDueAt, Long actorId);
    boolean decideResourceChange(UUID changeRequestId,
                                 ResourceCommitmentChangeDecisionRequest request,
                                 Long actorId);
    boolean markResourceChangePublished(UUID changeRequestId, long version, Long actorId);
    boolean hasLedgerEntries(UUID tenantId, String resourceKey);
    T ledgerTotals(UUID tenantId, String resourceKey,
                   Instant controlPeriodStart, Instant controlPeriodEnd);
    F internalEvidenceFreshness(UUID tenantId, String resourceKey,
                                Instant controlPeriodStart, Instant controlPeriodEnd);
    List<L> ledger(UUID tenantId, String resourceKey, int limit);
    Optional<L> ledgerByIdempotency(UUID tenantId, String idempotencyKey);
    boolean appendLedger(UUID entryId, UUID tenantId, String resourceKey,
                         C commitment, AppendLedgerEntryRequest request, Long actorId);
}
