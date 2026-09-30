package com.dwp.services.provider.resourcegovernance;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendArtifactEvidenceRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendLedgerEntryRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactManifest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactReviewDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactRolloutEvidence;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactRolloutPlan;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AssessArtifactCompatibilityRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.Commitment;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactManifestRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactRolloutPlanRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateTenantLifecycleRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateResourceCommitmentChangeRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.LedgerEntry;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.LedgerPage;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChange;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChangeDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.VersionedReasonRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Provider-facing API for V58's internal-only resource and artifact evidence. */
@RestController
public class ResourceGovernanceController {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String RESOURCE_KEY = "^[a-z][a-z0-9._-]{2,119}$";

    private final ResourceGovernanceService service;

    public ResourceGovernanceController(ResourceGovernanceService service) {
        this.service = service;
    }

    @GetMapping("/v1/admin/resource-governance/commitments")
    public ApiResponse<List<Commitment>> commitments(
            @RequestParam(required = false) UUID tenantId) {
        return ApiResponse.success(service.commitments(tenantId));
    }

    @GetMapping("/v1/admin/resource-governance/tenants/{tenantId}/commitments/{resourceKey}/ledger")
    public ApiResponse<LedgerPage> ledger(
            @PathVariable UUID tenantId,
            @PathVariable @Pattern(regexp = RESOURCE_KEY) String resourceKey,
            @RequestParam(defaultValue = "100") @Min(1) @Max(250) int limit) {
        return ApiResponse.success(service.ledger(tenantId, resourceKey, limit));
    }

    @PostMapping("/v1/admin/resource-governance/tenants/{tenantId}/commitments/{resourceKey}/ledger")
    public ApiResponse<LedgerEntry> appendLedger(
            @PathVariable UUID tenantId,
            @PathVariable @Pattern(regexp = RESOURCE_KEY) String resourceKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AppendLedgerEntryRequest request) {
        return ApiResponse.success(service.appendLedger(tenantId, resourceKey, request, correlationId));
    }

    @GetMapping("/v1/admin/resource-governance/commitment-changes")
    public ApiResponse<List<ResourceCommitmentChange>> resourceChanges(
            @RequestParam(required = false) UUID tenantId) {
        return ApiResponse.success(service.resourceChanges(tenantId));
    }

    @PostMapping("/v1/admin/resource-governance/tenants/{tenantId}/commitments/{resourceKey}/changes")
    public ApiResponse<ResourceCommitmentChange> createResourceChange(
            @PathVariable UUID tenantId,
            @PathVariable @Pattern(regexp = RESOURCE_KEY) String resourceKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody CreateResourceCommitmentChangeRequest request) {
        return ApiResponse.success(
                service.createResourceChange(tenantId, resourceKey, request, correlationId));
    }

    @PostMapping("/v1/admin/resource-governance/commitment-changes/{changeRequestId}/decision")
    public ApiResponse<ResourceCommitmentChange> decideResourceChange(
            @PathVariable UUID changeRequestId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ResourceCommitmentChangeDecisionRequest request) {
        return ApiResponse.success(
                service.decideResourceChange(changeRequestId, request, correlationId));
    }

    @PostMapping("/v1/admin/resource-governance/commitment-changes/{changeRequestId}/publish")
    public ApiResponse<ResourceCommitmentChange> publishResourceChange(
            @PathVariable UUID changeRequestId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody VersionedReasonRequest request) {
        return ApiResponse.success(
                service.publishResourceChange(changeRequestId, request, correlationId));
    }

    @GetMapping("/v1/admin/resource-governance/lifecycle-requests")
    public ApiResponse<List<TenantLifecycleRequest>> lifecycleRequests(
            @RequestParam(required = false) UUID tenantId) {
        return ApiResponse.success(service.lifecycleRequests(tenantId));
    }

    @PostMapping("/v1/admin/resource-governance/tenants/{tenantId}/lifecycle-requests")
    public ApiResponse<TenantLifecycleRequest> createLifecycleRequest(
            @PathVariable UUID tenantId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody CreateTenantLifecycleRequest request) {
        return ApiResponse.success(service.createLifecycleRequest(tenantId, request, correlationId));
    }

    @PostMapping("/v1/admin/resource-governance/lifecycle-requests/{requestId}/refresh-hold")
    public ApiResponse<TenantLifecycleRequest> refreshLifecycleHold(
            @PathVariable UUID requestId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody VersionedReasonRequest request) {
        return ApiResponse.success(service.refreshLifecycleHold(requestId, request, correlationId));
    }

    @PostMapping("/v1/admin/resource-governance/lifecycle-requests/{requestId}/submit")
    public ApiResponse<TenantLifecycleRequest> submitLifecycleRequest(
            @PathVariable UUID requestId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody VersionedReasonRequest request) {
        return ApiResponse.success(service.submitLifecycleRequest(requestId, request, correlationId));
    }

    @PostMapping("/v1/admin/resource-governance/lifecycle-requests/{requestId}/decision")
    public ApiResponse<TenantLifecycleRequest> decideLifecycleRequest(
            @PathVariable UUID requestId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody TenantLifecycleDecisionRequest request) {
        return ApiResponse.success(service.decideLifecycleRequest(requestId, request, correlationId));
    }

    @GetMapping("/v1/admin/artifact-governance/manifests")
    public ApiResponse<List<ArtifactManifest>> artifacts() {
        return ApiResponse.success(service.artifacts());
    }

    @PostMapping("/v1/admin/artifact-governance/manifests")
    public ApiResponse<ArtifactManifest> createArtifact(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody CreateArtifactManifestRequest request) {
        return ApiResponse.success(service.createArtifact(request, correlationId));
    }

    @PostMapping("/v1/admin/artifact-governance/manifests/{artifactId}/compatibility")
    public ApiResponse<ArtifactManifest> assessArtifactCompatibility(
            @PathVariable UUID artifactId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AssessArtifactCompatibilityRequest request) {
        return ApiResponse.success(service.assessCompatibility(artifactId, request, correlationId));
    }

    @PostMapping("/v1/admin/artifact-governance/manifests/{artifactId}/submit")
    public ApiResponse<ArtifactManifest> submitArtifact(
            @PathVariable UUID artifactId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody VersionedReasonRequest request) {
        return ApiResponse.success(service.submitArtifact(artifactId, request, correlationId));
    }

    @PostMapping("/v1/admin/artifact-governance/manifests/{artifactId}/review")
    public ApiResponse<ArtifactManifest> decideArtifact(
            @PathVariable UUID artifactId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ArtifactReviewDecisionRequest request) {
        return ApiResponse.success(service.decideArtifact(artifactId, request, correlationId));
    }

    @GetMapping("/v1/admin/artifact-governance/rollout-plans")
    public ApiResponse<List<ArtifactRolloutPlan>> plans() {
        return ApiResponse.success(service.plans());
    }

    @PostMapping("/v1/admin/artifact-governance/rollout-plans")
    public ApiResponse<ArtifactRolloutPlan> createPlan(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody CreateArtifactRolloutPlanRequest request) {
        return ApiResponse.success(service.createPlan(request, correlationId));
    }

    @PostMapping("/v1/admin/artifact-governance/rollout-plans/{planId}/submit")
    public ApiResponse<ArtifactRolloutPlan> submitPlan(
            @PathVariable UUID planId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody VersionedReasonRequest request) {
        return ApiResponse.success(service.submitPlan(planId, request, correlationId));
    }

    @PostMapping("/v1/admin/artifact-governance/rollout-plans/{planId}/approval")
    public ApiResponse<ArtifactRolloutPlan> decidePlan(
            @PathVariable UUID planId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ResourceGovernanceDtos.ArtifactRolloutDecisionRequest request) {
        return ApiResponse.success(service.decidePlan(planId, request, correlationId));
    }

    @PostMapping("/v1/admin/artifact-governance/rollout-plans/{planId}/ready")
    public ApiResponse<ArtifactRolloutPlan> markPlanReady(
            @PathVariable UUID planId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody VersionedReasonRequest request) {
        return ApiResponse.success(service.markPlanReady(planId, request, correlationId));
    }

    @PostMapping("/v1/admin/artifact-governance/rollout-plans/{planId}/evidence")
    public ApiResponse<ArtifactRolloutEvidence> appendPlanEvidence(
            @PathVariable UUID planId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody AppendArtifactEvidenceRequest request) {
        return ApiResponse.success(service.appendPlanEvidence(planId, request, correlationId));
    }
}
