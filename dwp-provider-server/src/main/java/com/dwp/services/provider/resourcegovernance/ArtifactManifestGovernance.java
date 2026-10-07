package com.dwp.services.provider.resourcegovernance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactManifest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactReview;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactReviewDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AssessArtifactCompatibilityRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactManifestRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactReviewRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactRow;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns artifact-manifest lifecycle orchestration while the facade retains transaction boundaries. */
final class ArtifactManifestGovernance {

    private static final int LIST_LIMIT = 100;
    private static final int REVIEW_LIMIT = 50;

    private final ResourceGovernanceRepository repository;
    private final ProviderAuditService audit;
    private final ArtifactGovernanceRules rules;

    ArtifactManifestGovernance(
            ResourceGovernanceRepository repository,
            ProviderAuditService audit,
            ArtifactGovernanceRules rules) {
        this.repository = repository;
        this.audit = audit;
        this.rules = rules;
    }

    public ResourceGovernanceDtos.ArtifactManifestPage artifacts() {
        ProviderRequestContext.requirePermission(ResourceGovernancePermissions.ARTIFACT_READ);
        List<ArtifactRow> rows = repository.artifacts(LIST_LIMIT + 1);
        return new ResourceGovernanceDtos.ArtifactManifestPage(
                rows.stream().limit(LIST_LIMIT).map(this::artifact).toList(),
                LIST_LIMIT,
                rows.size() > LIST_LIMIT);
    }

    public ArtifactManifest createArtifact(
            CreateArtifactManifestRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernancePermissions.ARTIFACT_WRITE);
        CreateArtifactManifestRequest normalized = normalize(request);
        rules.validateArtifactDefinition(normalized);
        UUID artifactId = UUID.randomUUID();
        ArtifactRow saved;
        try {
            saved = repository.createArtifact(
                    artifactId, normalized, ProviderRequestContext.require().operatorId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An artifact with this product key and version already exists.");
        }
        audit.success(
                "provider.artifact-governance.manifest-created",
                "PRODUCT_ARTIFACT",
                artifactId.toString(),
                correlationId,
                Map.of("productKey", saved.productKey(), "artifactVersion", saved.artifactVersion(),
                        "artifactType", saved.artifactType(), "signatureState", saved.signatureState(),
                        "distributionState", saved.distributionState()));
        return artifact(saved);
    }

    public ArtifactManifest assessCompatibility(
            UUID artifactId,
            AssessArtifactCompatibilityRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernancePermissions.ARTIFACT_WRITE);
        AssessArtifactCompatibilityRequest normalized = normalize(request);
        rules.validateEvidenceObject(normalized.evidence(), "Compatibility evidence");
        rules.rejectInlineSecretMaterial(normalized.evidence());
        ArtifactRow before = requireArtifact(artifactId);
        rules.validateCompatibilityEvidence(before.compatibilityPolicy(), normalized.evidence());
        String derivedState = rules.deriveCompatibilityState(before.compatibilityPolicy(), normalized.evidence());
        if (!derivedState.equals(normalized.compatibilityState())) {
            throw invalid("The compatibility decision must match the typed compatibility evidence: "
                    + derivedState + ".");
        }
        if (!repository.assessCompatibility(
                artifactId, normalized, ProviderRequestContext.require().operatorId())) {
            throw conflict("The artifact changed or is no longer eligible for compatibility assessment.");
        }
        ArtifactRow saved = requireArtifact(artifactId);
        audit.success(
                "provider.artifact-governance.compatibility-assessed",
                "PRODUCT_ARTIFACT",
                artifactId.toString(),
                correlationId,
                Map.of("productKey", saved.productKey(), "beforeState", before.compatibilityState(),
                        "afterState", saved.compatibilityState(), "evidence", normalized.evidence(),
                        "reason", normalized.reason()));
        return artifact(saved);
    }

    public ArtifactManifest submitArtifact(
            UUID artifactId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernancePermissions.ARTIFACT_WRITE);
        ArtifactRow before = requireArtifact(artifactId);
        if (!repository.submitArtifact(
                artifactId, request.version(), ProviderRequestContext.require().operatorId())) {
            throw conflict("Only a compatible draft artifact can be submitted, and it must be current.");
        }
        ArtifactRow saved = requireArtifact(artifactId);
        audit.success(
                "provider.artifact-governance.manifest-submitted",
                "PRODUCT_ARTIFACT",
                artifactId.toString(),
                correlationId,
                Map.of("productKey", saved.productKey(), "beforeLifecycle", before.lifecycleState(),
                        "afterLifecycle", saved.lifecycleState(), "reason", request.reason()));
        return artifact(saved);
    }

    public ArtifactManifest decideArtifact(
            UUID artifactId,
            ArtifactReviewDecisionRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernancePermissions.ARTIFACT_APPROVE);
        ArtifactReviewDecisionRequest normalized = normalize(request);
        rules.validateEvidenceObject(normalized.evidence(), "Artifact review evidence");
        rules.rejectInlineSecretMaterial(normalized.evidence());
        ArtifactRow before = repository.lockArtifact(artifactId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Long actorId = ProviderRequestContext.require().operatorId();
        if (Objects.equals(actorId, before.createdBy())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "An artifact author cannot independently review the same artifact.");
        }
        if (!repository.decideArtifact(artifactId, normalized, actorId)) {
            throw conflict("The artifact review changed, cannot be decided, or is no longer compatible.");
        }
        ArtifactRow saved = requireArtifact(artifactId);
        audit.success(
                "provider.artifact-governance.manifest-"
                        + normalized.decision().toLowerCase(),
                "PRODUCT_ARTIFACT",
                artifactId.toString(),
                correlationId,
                Map.of("productKey", saved.productKey(), "beforeLifecycle", before.lifecycleState(),
                        "afterLifecycle", saved.lifecycleState(), "decision", normalized.decision(),
                        "reason", normalized.reason()));
        return artifact(saved);
    }

    private ArtifactManifest artifact(ArtifactRow row) {
        List<ArtifactReviewRow> reviewRows = repository.reviews(
                row.artifactId(), REVIEW_LIMIT + 1);
        List<ArtifactReview> reviews = reviewRows.stream()
                .limit(REVIEW_LIMIT).map(this::review).toList();
        return new ArtifactManifest(
                row.artifactId(), row.productKey(), row.artifactVersion(), row.artifactType(),
                row.manifestSchemaVersion(), row.manifest(), row.compatibilityPolicy(),
                rules.compatibility(row), row.declaredDigest(), row.lifecycleState(), row.compatibilityState(),
                row.compatibilityEvidence(), row.signatureState(), row.distributionState(),
                row.createdBy(), row.updatedBy(), row.createdAt(), row.updatedAt(), row.version(),
                reviews, REVIEW_LIMIT, reviewRows.size() > REVIEW_LIMIT);
    }

    private ArtifactReview review(ArtifactReviewRow row) {
        return new ArtifactReview(
                row.reviewId(), row.artifactId(), row.decision(), row.reason(), row.evidence(),
                row.reviewedBy(), row.reviewedAt());
    }

    private ArtifactRow requireArtifact(UUID artifactId) {
        return repository.artifact(artifactId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private CreateArtifactManifestRequest normalize(CreateArtifactManifestRequest request) {
        return new CreateArtifactManifestRequest(
                request.productKey().trim(), request.artifactVersion().trim(), request.artifactType().trim(),
                request.manifestSchemaVersion(), request.manifest(), request.compatibilityPolicy(),
                trimToNull(request.declaredDigest()));
    }

    private AssessArtifactCompatibilityRequest normalize(AssessArtifactCompatibilityRequest request) {
        return new AssessArtifactCompatibilityRequest(
                request.version(), request.compatibilityState().trim(), request.evidence(), request.reason().trim());
    }

    private ArtifactReviewDecisionRequest normalize(ArtifactReviewDecisionRequest request) {
        return new ArtifactReviewDecisionRequest(
                request.version(), request.decision().trim(), request.reason().trim(), request.evidence());
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
