package com.dwp.services.provider.resourcegovernance;

import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.governance.DataPolicyRepository;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendArtifactEvidenceRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendLedgerEntryRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactManifest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactReviewDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactRolloutEvidence;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactRolloutPlan;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AssessArtifactCompatibilityRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.Commitment;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CommitmentPage;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactManifestRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactRolloutPlanRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateResourceCommitmentChangeRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateTenantLifecycleRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.LedgerEntry;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.LedgerPage;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChange;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChangePage;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChangeDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleRequestPage;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.UpsertCommitmentRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Transactional facade for provider-owned resource, lifecycle, and artifact governance.
 * Domain collaborators retain the established validation and authorization behavior.
 */
@Service
public class ResourceGovernanceService {

    static final String RESOURCE_READ = "RESOURCE_GOVERNANCE_READ";
    static final String RESOURCE_WRITE = "RESOURCE_GOVERNANCE_WRITE";
    static final String RESOURCE_APPROVE = "RESOURCE_GOVERNANCE_APPROVE";
    static final String ARTIFACT_READ = "ARTIFACT_GOVERNANCE_READ";
    static final String ARTIFACT_WRITE = "ARTIFACT_GOVERNANCE_WRITE";
    static final String ARTIFACT_APPROVE = "ARTIFACT_GOVERNANCE_APPROVE";
    static final String TENANT_LIFECYCLE_APPROVE = "TENANT_LIFECYCLE_GOVERNANCE_APPROVE";

    private final ResourceCommitmentGovernance commitments;
    private final ResourceCommitmentChangeGovernance commitmentChanges;
    private final TenantLifecycleGovernance tenantLifecycle;
    private final ArtifactManifestGovernance artifactManifests;
    private final ArtifactRolloutGovernance artifactRollouts;

    public ResourceGovernanceService(
            ResourceGovernanceRepository repository,
            DataPolicyRepository dataPolicyRepository,
            ProviderAuditService audit) {
        ArtifactGovernanceRules artifactRules = new ArtifactGovernanceRules();
        this.commitments = new ResourceCommitmentGovernance(repository, audit);
        this.commitmentChanges = new ResourceCommitmentChangeGovernance(repository, audit);
        this.tenantLifecycle = new TenantLifecycleGovernance(repository, dataPolicyRepository, audit);
        this.artifactManifests = new ArtifactManifestGovernance(repository, audit, artifactRules);
        this.artifactRollouts = new ArtifactRolloutGovernance(repository, audit, artifactRules);
    }

    @Transactional(readOnly = true)
    public CommitmentPage commitments(UUID tenantId) {
        return commitments.commitments(tenantId);
    }

    @Transactional(readOnly = true)
    public LedgerPage ledger(UUID tenantId, String resourceKey, int requestedLimit) {
        return commitments.ledger(tenantId, resourceKey, requestedLimit);
    }

    @Transactional
    public Commitment upsertCommitment(
            UUID tenantId,
            String resourceKey,
            UpsertCommitmentRequest request,
            String correlationId) {
        return commitments.upsertCommitment(tenantId, resourceKey, request, correlationId);
    }

    @Transactional
    public LedgerEntry appendLedger(
            UUID tenantId,
            String resourceKey,
            AppendLedgerEntryRequest request,
            String correlationId) {
        return commitments.appendLedger(tenantId, resourceKey, request, correlationId);
    }

    @Transactional(readOnly = true)
    public ResourceCommitmentChangePage resourceChanges(UUID tenantId) {
        return commitmentChanges.resourceChanges(tenantId);
    }

    @Transactional
    public ResourceCommitmentChange createResourceChange(
            UUID tenantId,
            String resourceKey,
            CreateResourceCommitmentChangeRequest request,
            String correlationId) {
        return commitmentChanges.createResourceChange(tenantId, resourceKey, request, correlationId);
    }

    @Transactional
    public ResourceCommitmentChange decideResourceChange(
            UUID changeRequestId,
            ResourceCommitmentChangeDecisionRequest request,
            String correlationId) {
        return commitmentChanges.decideResourceChange(changeRequestId, request, correlationId);
    }

    @Transactional
    public ResourceCommitmentChange publishResourceChange(
            UUID changeRequestId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        return commitmentChanges.publishResourceChange(changeRequestId, request, correlationId);
    }

    @Transactional(readOnly = true)
    public TenantLifecycleRequestPage lifecycleRequests(UUID tenantId) {
        return tenantLifecycle.lifecycleRequests(tenantId);
    }

    @Transactional
    public TenantLifecycleRequest createLifecycleRequest(
            UUID tenantId,
            CreateTenantLifecycleRequest request,
            String correlationId) {
        return tenantLifecycle.createLifecycleRequest(tenantId, request, correlationId);
    }

    @Transactional
    public TenantLifecycleRequest refreshLifecycleHold(
            UUID requestId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        return tenantLifecycle.refreshLifecycleHold(requestId, request, correlationId);
    }

    @Transactional
    public TenantLifecycleRequest submitLifecycleRequest(
            UUID requestId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        return tenantLifecycle.submitLifecycleRequest(requestId, request, correlationId);
    }

    @Transactional
    public TenantLifecycleRequest cancelLifecycleRequest(
            UUID requestId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        return tenantLifecycle.cancelLifecycleRequest(requestId, request, correlationId);
    }

    @Transactional
    public TenantLifecycleRequest decideLifecycleRequest(
            UUID requestId,
            TenantLifecycleDecisionRequest request,
            String correlationId) {
        return tenantLifecycle.decideLifecycleRequest(requestId, request, correlationId);
    }

    @Transactional(readOnly = true)
    public ResourceGovernanceDtos.ArtifactManifestPage artifacts() {
        return artifactManifests.artifacts();
    }

    @Transactional
    public ArtifactManifest createArtifact(
            CreateArtifactManifestRequest request,
            String correlationId) {
        return artifactManifests.createArtifact(request, correlationId);
    }

    @Transactional
    public ArtifactManifest assessCompatibility(
            UUID artifactId,
            AssessArtifactCompatibilityRequest request,
            String correlationId) {
        return artifactManifests.assessCompatibility(artifactId, request, correlationId);
    }

    @Transactional
    public ArtifactManifest submitArtifact(
            UUID artifactId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        return artifactManifests.submitArtifact(artifactId, request, correlationId);
    }

    @Transactional
    public ArtifactManifest decideArtifact(
            UUID artifactId,
            ArtifactReviewDecisionRequest request,
            String correlationId) {
        return artifactManifests.decideArtifact(artifactId, request, correlationId);
    }

    @Transactional(readOnly = true)
    public ResourceGovernanceDtos.ArtifactRolloutPlanPage plans() {
        return artifactRollouts.plans();
    }

    @Transactional
    public ArtifactRolloutPlan createPlan(
            CreateArtifactRolloutPlanRequest request,
            String correlationId) {
        return artifactRollouts.createPlan(request, correlationId);
    }

    @Transactional
    public ArtifactRolloutPlan submitPlan(
            UUID planId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        return artifactRollouts.submitPlan(planId, request, correlationId);
    }

    @Transactional
    public ArtifactRolloutPlan decidePlan(
            UUID planId,
            ResourceGovernanceDtos.ArtifactRolloutDecisionRequest request,
            String correlationId) {
        return artifactRollouts.decidePlan(planId, request, correlationId);
    }

    @Transactional
    public ArtifactRolloutPlan markPlanReady(
            UUID planId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        return artifactRollouts.markPlanReady(planId, request, correlationId);
    }

    @Transactional
    public ArtifactRolloutEvidence appendPlanEvidence(
            UUID planId,
            AppendArtifactEvidenceRequest request,
            String correlationId) {
        return artifactRollouts.appendPlanEvidence(planId, request, correlationId);
    }
}
