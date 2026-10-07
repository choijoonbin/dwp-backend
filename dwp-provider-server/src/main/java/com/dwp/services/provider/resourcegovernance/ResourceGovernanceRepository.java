package com.dwp.services.provider.resourcegovernance;

import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendArtifactEvidenceRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendLedgerEntryRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactReviewDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AssessArtifactCompatibilityRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactManifestRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactRolloutPlanRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateResourceCommitmentChangeRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateTenantLifecycleRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChangeDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.UpsertCommitmentRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Composes the resource, artifact, and tenant-lifecycle persistence boundaries. */
@Repository
public class ResourceGovernanceRepository {

    private final ResourceCommitmentPersistence<
            CommitmentRow, ResourceCommitmentChangeRow, InternalEvidenceFreshnessRow,
            LedgerTotalsRow, LedgerRow> resources;
    private final ArtifactGovernancePersistence<
            ArtifactRow, ArtifactReviewRow, PlanRow, ArtifactEvidenceRow> artifacts;
    private final TenantLifecycleGovernancePersistence<TenantLifecycleRequestRow> lifecycle;

    @Autowired
    ResourceGovernanceRepository(
            ResourceCommitmentPersistence<
                    CommitmentRow, ResourceCommitmentChangeRow,
                    InternalEvidenceFreshnessRow, LedgerTotalsRow, LedgerRow> resources,
            ArtifactGovernancePersistence<
                    ArtifactRow, ArtifactReviewRow, PlanRow, ArtifactEvidenceRow> artifacts,
            TenantLifecycleGovernancePersistence<TenantLifecycleRequestRow> lifecycle) {
        this.resources = resources;
        this.artifacts = artifacts;
        this.lifecycle = lifecycle;
    }

    public ResourceGovernanceRepository(
            ResourceCommitmentJdbcRepository resources,
            ArtifactGovernanceJdbcRepository artifacts,
            TenantLifecycleGovernanceJdbcRepository lifecycle) {
        this(
                (ResourceCommitmentPersistence<
                        CommitmentRow, ResourceCommitmentChangeRow,
                        InternalEvidenceFreshnessRow, LedgerTotalsRow, LedgerRow>) resources,
                (ArtifactGovernancePersistence<
                        ArtifactRow, ArtifactReviewRow, PlanRow, ArtifactEvidenceRow>) artifacts,
                (TenantLifecycleGovernancePersistence<TenantLifecycleRequestRow>) lifecycle);
    }

    public boolean tenantExists(UUID tenantId) {
        return resources.tenantExists(tenantId);
    }

    public List<CommitmentRow> commitments(UUID tenantId, int fetchLimit) {
        return resources.commitments(tenantId, fetchLimit);
    }

    public Optional<CommitmentRow> commitment(UUID tenantId, String resourceKey) {
        return resources.commitment(tenantId, resourceKey);
    }

    public Optional<CommitmentRow> lockCommitment(UUID tenantId, String resourceKey) {
        return resources.lockCommitment(tenantId, resourceKey);
    }

    public CommitmentRow createCommitment(
            UUID tenantId,
            String resourceKey,
            UpsertCommitmentRequest request,
            Long actorId) {
        return resources.createCommitment(tenantId, resourceKey, request, actorId);
    }

    public boolean updateCommitment(
            UUID tenantId,
            String resourceKey,
            long version,
            UpsertCommitmentRequest request,
            Long actorId) {
        return resources.updateCommitment(tenantId, resourceKey, version, request, actorId);
    }

    public boolean publishedCommercialEvidenceMatchesTenant(UUID tenantId, UUID revisionId) {
        return resources.publishedCommercialEvidenceMatchesTenant(tenantId, revisionId);
    }

    public boolean hasActiveTemporaryOverride(UUID tenantId, String resourceKey) {
        return resources.hasActiveTemporaryOverride(tenantId, resourceKey);
    }

    public List<ResourceCommitmentChangeRow> resourceChanges(UUID tenantId, int fetchLimit) {
        return resources.resourceChanges(tenantId, fetchLimit);
    }

    public Optional<ResourceCommitmentChangeRow> resourceChange(UUID changeRequestId) {
        return resources.resourceChange(changeRequestId);
    }

    public Optional<ResourceCommitmentChangeRow> lockResourceChange(UUID changeRequestId) {
        return resources.lockResourceChange(changeRequestId);
    }

    public Optional<ResourceCommitmentChangeRow> resourceChangeByRequestKey(
            Long requesterId,
            String requestKey) {
        return resources.resourceChangeByRequestKey(requesterId, requestKey);
    }

    public ResourceCommitmentChangeRow createResourceChange(
            UUID changeRequestId,
            UUID tenantId,
            String resourceKey,
            CreateResourceCommitmentChangeRequest request,
            JsonNode baselineDefinition,
            JsonNode proposedDefinition,
            Instant decisionDueAt,
            Long actorId) {
        return resources.createResourceChange(
                changeRequestId, tenantId, resourceKey, request,
                baselineDefinition, proposedDefinition, decisionDueAt, actorId);
    }

    public boolean decideResourceChange(
            UUID changeRequestId,
            ResourceCommitmentChangeDecisionRequest request,
            Long actorId) {
        return resources.decideResourceChange(changeRequestId, request, actorId);
    }

    public boolean markResourceChangePublished(
            UUID changeRequestId,
            long version,
            Long actorId) {
        return resources.markResourceChangePublished(changeRequestId, version, actorId);
    }

    public boolean hasLedgerEntries(UUID tenantId, String resourceKey) {
        return resources.hasLedgerEntries(tenantId, resourceKey);
    }

    public LedgerTotalsRow ledgerTotals(
            UUID tenantId,
            String resourceKey,
            Instant controlPeriodStart,
            Instant controlPeriodEnd) {
        return resources.ledgerTotals(
                tenantId, resourceKey, controlPeriodStart, controlPeriodEnd);
    }

    public InternalEvidenceFreshnessRow internalEvidenceFreshness(
            UUID tenantId,
            String resourceKey,
            Instant controlPeriodStart,
            Instant controlPeriodEnd) {
        return resources.internalEvidenceFreshness(
                tenantId, resourceKey, controlPeriodStart, controlPeriodEnd);
    }

    public List<LedgerRow> ledger(UUID tenantId, String resourceKey, int limit) {
        return resources.ledger(tenantId, resourceKey, limit);
    }

    public Optional<LedgerRow> ledgerByIdempotency(UUID tenantId, String idempotencyKey) {
        return resources.ledgerByIdempotency(tenantId, idempotencyKey);
    }

    public boolean appendLedger(
            UUID entryId,
            UUID tenantId,
            String resourceKey,
            CommitmentRow commitment,
            AppendLedgerEntryRequest request,
            Long actorId) {
        return resources.appendLedger(entryId, tenantId, resourceKey, commitment, request, actorId);
    }

    public List<ArtifactRow> artifacts(int fetchLimit) {
        return artifacts.artifacts(fetchLimit);
    }

    public Optional<ArtifactRow> artifact(UUID artifactId) {
        return artifacts.artifact(artifactId);
    }

    public Optional<ArtifactRow> lockArtifact(UUID artifactId) {
        return artifacts.lockArtifact(artifactId);
    }

    public ArtifactRow createArtifact(
            UUID artifactId,
            CreateArtifactManifestRequest request,
            Long actorId) {
        return artifacts.createArtifact(artifactId, request, actorId);
    }

    public boolean assessCompatibility(
            UUID artifactId,
            AssessArtifactCompatibilityRequest request,
            Long actorId) {
        return artifacts.assessCompatibility(artifactId, request, actorId);
    }

    public boolean submitArtifact(UUID artifactId, long version, Long actorId) {
        return artifacts.submitArtifact(artifactId, version, actorId);
    }

    public boolean decideArtifact(
            UUID artifactId,
            ArtifactReviewDecisionRequest request,
            Long actorId) {
        return artifacts.decideArtifact(artifactId, request, actorId);
    }

    public List<ArtifactReviewRow> reviews(UUID artifactId, int fetchLimit) {
        return artifacts.reviews(artifactId, fetchLimit);
    }

    public List<PlanRow> plans(int fetchLimit) {
        return artifacts.plans(fetchLimit);
    }

    public Optional<PlanRow> plan(UUID planId) {
        return artifacts.plan(planId);
    }

    public Optional<PlanRow> lockPlan(UUID planId) {
        return artifacts.lockPlan(planId);
    }

    public PlanRow createPlan(
            UUID planId,
            CreateArtifactRolloutPlanRequest request,
            Long actorId) {
        return artifacts.createPlan(planId, request, actorId);
    }

    public boolean submitPlan(UUID planId, long version) {
        return artifacts.submitPlan(planId, version);
    }

    public boolean decidePlan(
            UUID planId,
            long version,
            String decision,
            String reason,
            Long actorId) {
        return artifacts.decidePlan(planId, version, decision, reason, actorId);
    }

    public boolean markPlanReady(UUID planId, long version) {
        return artifacts.markPlanReady(planId, version);
    }

    public ArtifactEvidenceRow appendEvidence(
            UUID evidenceId,
            UUID planId,
            AppendArtifactEvidenceRequest request,
            Long actorId) {
        return artifacts.appendEvidence(evidenceId, planId, request, actorId);
    }

    public List<ArtifactEvidenceRow> evidence(UUID planId, int fetchLimit) {
        return artifacts.evidence(planId, fetchLimit);
    }

    public List<ArtifactEvidenceRow> readinessEvidence(UUID planId) {
        return artifacts.readinessEvidence(planId);
    }

    public List<TenantLifecycleRequestRow> lifecycleRequests(UUID tenantId, int fetchLimit) {
        return lifecycle.lifecycleRequests(tenantId, fetchLimit);
    }

    public Optional<TenantLifecycleRequestRow> lifecycleRequest(UUID requestId) {
        return lifecycle.lifecycleRequest(requestId);
    }

    public Optional<TenantLifecycleRequestRow> lockLifecycleRequest(UUID requestId) {
        return lifecycle.lockLifecycleRequest(requestId);
    }

    public TenantLifecycleRequestRow createLifecycleRequest(
            UUID requestId,
            UUID tenantId,
            CreateTenantLifecycleRequest request,
            String lifecycleState,
            String holdEvaluationState,
            List<String> holdEvidenceRefs,
            Long actorId) {
        return lifecycle.createLifecycleRequest(
                requestId, tenantId, request, lifecycleState,
                holdEvaluationState, holdEvidenceRefs, actorId);
    }

    public boolean refreshLifecycleHold(
            UUID requestId,
            long version,
            String lifecycleState,
            String holdEvaluationState,
            List<String> holdEvidenceRefs) {
        return lifecycle.refreshLifecycleHold(
                requestId, version, lifecycleState, holdEvaluationState, holdEvidenceRefs);
    }

    public boolean submitLifecycleRequest(UUID requestId, long version, Long actorId) {
        return lifecycle.submitLifecycleRequest(requestId, version, actorId);
    }

    public boolean cancelLifecycleRequest(
            UUID requestId,
            long version,
            Long actorId,
            String reason) {
        return lifecycle.cancelLifecycleRequest(requestId, version, actorId, reason);
    }

    public boolean decideLifecycleRequest(
            UUID requestId,
            TenantLifecycleDecisionRequest request,
            Long actorId) {
        return lifecycle.decideLifecycleRequest(requestId, request, actorId);
    }

    public record CommitmentRow(
            UUID tenantId,
            String tenantKey,
            String tenantDisplayName,
            String resourceKey,
            String unit,
            BigDecimal quotaLimit,
            BigDecimal budgetLimit,
            String currencyCode,
            String lifecycleState,
            String sourceSystem,
            String externalFeedState,
            String controlMode,
            String controlScope,
            Instant controlPeriodStart,
            Instant controlPeriodEnd,
            UUID activeOverrideId,
            Instant activeOverrideExpiresAt,
            long version,
            Instant updatedAt) {
    }

    public record ResourceCommitmentChangeRow(
            UUID changeRequestId,
            UUID tenantId,
            String tenantKey,
            String tenantDisplayName,
            String resourceKey,
            String changeKind,
            Long baselineCommitmentVersion,
            JsonNode baselineDefinition,
            JsonNode proposedDefinition,
            UUID commercialRenewalRevisionId,
            Instant overrideExpiresAt,
            String lifecycleState,
            String reservationState,
            String justification,
            Instant decisionDueAt,
            long requestedBy,
            Instant requestedAt,
            Long decidedBy,
            Instant decidedAt,
            String decisionReason,
            Long publishedBy,
            Instant publishedAt,
            long version,
            Instant updatedAt) {
    }

    public record InternalEvidenceFreshnessRow(
            long entryCount,
            Instant latestOccurredAt,
            Instant latestRecordedAt) {
    }

    public record LedgerTotalsRow(
            BigDecimal allocated,
            BigDecimal released,
            BigDecimal adjusted,
            BigDecimal metered,
            BigDecimal budgetReserved,
            BigDecimal budgetReleased,
            BigDecimal budgetSpent) {
    }

    public record LedgerRow(
            UUID ledgerEntryId,
            UUID tenantId,
            String resourceKey,
            String entryType,
            BigDecimal amount,
            String unit,
            String currencyCode,
            String evidenceRef,
            String idempotencyKey,
            String reason,
            Instant occurredAt,
            Instant controlPeriodStart,
            Instant controlPeriodEnd,
            Long recordedBy,
            Instant recordedAt) {
    }

    public record TenantLifecycleRequestRow(
            UUID requestId,
            UUID tenantId,
            String tenantKey,
            String tenantDisplayName,
            String requestedAction,
            String lifecycleState,
            String holdEvaluationState,
            List<String> holdEvidenceRefs,
            String executionState,
            String justification,
            long requestedBy,
            Long submittedBy,
            Long approvedBy,
            Instant submittedAt,
            Instant approvedAt,
            String decisionReason,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ArtifactRow(
            UUID artifactId,
            String productKey,
            String artifactVersion,
            String artifactType,
            int manifestSchemaVersion,
            JsonNode manifest,
            JsonNode compatibilityPolicy,
            String declaredDigest,
            String lifecycleState,
            String compatibilityState,
            JsonNode compatibilityEvidence,
            String signatureState,
            String distributionState,
            long createdBy,
            long updatedBy,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    public record ArtifactReviewRow(
            UUID reviewId,
            UUID artifactId,
            String decision,
            String reason,
            JsonNode evidence,
            long reviewedBy,
            Instant reviewedAt) {
    }

    public record PlanRow(
            UUID planId,
            UUID artifactId,
            String productKey,
            String artifactVersion,
            String name,
            JsonNode targetScope,
            JsonNode stages,
            JsonNode rollbackPlan,
            String rollbackFeasibility,
            String lifecycleState,
            String executorState,
            String reason,
            long requestedBy,
            Long approvedBy,
            Instant submittedAt,
            Instant approvedAt,
            String decisionReason,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ArtifactEvidenceRow(
            UUID evidenceId,
            UUID planId,
            String evidenceType,
            String evidenceState,
            JsonNode evidence,
            String source,
            long recordedBy,
            Instant recordedAt) {
    }
}
