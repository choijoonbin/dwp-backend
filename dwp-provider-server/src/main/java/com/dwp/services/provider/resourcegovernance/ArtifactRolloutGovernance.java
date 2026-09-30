package com.dwp.services.provider.resourcegovernance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendArtifactEvidenceRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactRolloutEvidence;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactRolloutPlan;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactRolloutPlanRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactEvidenceRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.PlanRow;
import com.dwp.services.provider.security.ProviderRequestContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns rollout-plan lifecycle orchestration while the facade retains transaction boundaries. */
final class ArtifactRolloutGovernance {

    private final ResourceGovernanceRepository repository;
    private final ProviderAuditService audit;
    private final ArtifactGovernanceRules rules;

    ArtifactRolloutGovernance(
            ResourceGovernanceRepository repository,
            ProviderAuditService audit,
            ArtifactGovernanceRules rules) {
        this.repository = repository;
        this.audit = audit;
        this.rules = rules;
    }

    public List<ArtifactRolloutPlan> plans() {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.ARTIFACT_READ);
        return repository.plans().stream().map(this::plan).toList();
    }

    public ArtifactRolloutPlan createPlan(
            CreateArtifactRolloutPlanRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.ARTIFACT_WRITE);
        CreateArtifactRolloutPlanRequest normalized = normalize(request);
        rules.validatePlanDefinition(normalized);
        ArtifactRow artifact = repository.lockArtifact(normalized.artifactId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        rules.requireApprovedCompatibleArtifact(artifact);
        PlanRow saved = repository.createPlan(
                UUID.randomUUID(), normalized, ProviderRequestContext.require().operatorId());
        audit.success(
                "provider.artifact-governance.rollout-plan-created",
                "ARTIFACT_ROLLOUT_PLAN",
                saved.planId().toString(),
                correlationId,
                Map.of("artifactId", artifact.artifactId().toString(), "productKey", artifact.productKey(),
                        "lifecycleState", saved.lifecycleState(), "executorState", saved.executorState(),
                        "rollbackFeasibility", saved.rollbackFeasibility()));
        return plan(saved);
    }

    public ArtifactRolloutPlan submitPlan(
            UUID planId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.ARTIFACT_WRITE);
        PlanRow before = repository.lockPlan(planId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        rules.requireApprovedCompatibleArtifact(repository.lockArtifact(before.artifactId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND)));
        if (!repository.submitPlan(planId, request.version())) {
            throw conflict("The rollout plan changed or is no longer a draft.");
        }
        PlanRow saved = requirePlan(planId);
        audit.success(
                "provider.artifact-governance.rollout-plan-submitted",
                "ARTIFACT_ROLLOUT_PLAN",
                planId.toString(),
                correlationId,
                Map.of("beforeLifecycle", before.lifecycleState(),
                        "afterLifecycle", saved.lifecycleState(), "reason", request.reason(),
                        "executorState", saved.executorState()));
        return plan(saved);
    }

    public ArtifactRolloutPlan decidePlan(
            UUID planId,
            ResourceGovernanceDtos.ArtifactRolloutDecisionRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.ARTIFACT_APPROVE);
        PlanRow before = repository.lockPlan(planId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Long actorId = ProviderRequestContext.require().operatorId();
        if (Objects.equals(actorId, before.requestedBy())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "A rollout-plan requester cannot independently decide the same plan.");
        }
        if (!repository.decidePlan(planId, request.version(), request.decision(), request.reason(), actorId)) {
            throw conflict("The rollout-plan approval changed or cannot be decided.");
        }
        PlanRow saved = requirePlan(planId);
        audit.success(
                "provider.artifact-governance.rollout-plan-"
                        + request.decision().toLowerCase(),
                "ARTIFACT_ROLLOUT_PLAN",
                planId.toString(),
                correlationId,
                Map.of("beforeLifecycle", before.lifecycleState(),
                        "afterLifecycle", saved.lifecycleState(), "reason", request.reason(),
                        "executorState", saved.executorState()));
        return plan(saved);
    }

    public ArtifactRolloutPlan markPlanReady(
            UUID planId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.ARTIFACT_WRITE);
        PlanRow before = repository.lockPlan(planId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        rules.requireApprovedCompatibleArtifact(repository.lockArtifact(before.artifactId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND)));
        rules.requireInternalRolloutReadiness(before, repository.evidence(planId));
        if (!repository.markPlanReady(planId, request.version())) {
            throw conflict("Only a current, independently approved rollout plan can be marked ready.");
        }
        PlanRow saved = requirePlan(planId);
        audit.success(
                "provider.artifact-governance.rollout-plan-ready",
                "ARTIFACT_ROLLOUT_PLAN",
                planId.toString(),
                correlationId,
                Map.of("beforeLifecycle", before.lifecycleState(),
                        "afterLifecycle", saved.lifecycleState(), "reason", request.reason(),
                        "executorState", saved.executorState(),
                        "externalExecution", "UNAVAILABLE"));
        return plan(saved);
    }

    public ArtifactRolloutEvidence appendPlanEvidence(
            UUID planId,
            AppendArtifactEvidenceRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.ARTIFACT_WRITE);
        AppendArtifactEvidenceRequest normalized = normalize(request);
        rules.validateEvidenceObject(normalized.evidence(), "Rollout evidence");
        rules.rejectInlineSecretMaterial(normalized.evidence());
        PlanRow plan = repository.lockPlan(planId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if ("REJECTED".equals(plan.lifecycleState()) || "CANCELLED".equals(plan.lifecycleState())) {
            throw invalid("Rejected or cancelled rollout plans cannot receive new evidence.");
        }
        if ("OBSERVATION".equals(normalized.evidenceType())
                && !"NOT_DISPATCHED".equals(normalized.evidenceState())) {
            throw invalid("Deployment observation cannot be asserted while execution is unavailable.");
        }
        if ("MANUAL_RECEIPT".equals(normalized.evidenceType())
                && "PASSED".equals(normalized.evidenceState())) {
            throw invalid("A manual receipt cannot assert external rollout completion.");
        }
        ArtifactEvidenceRow saved = repository.appendEvidence(
                UUID.randomUUID(), planId, normalized, ProviderRequestContext.require().operatorId());
        audit.success(
                "provider.artifact-governance.rollout-evidence-appended",
                "ARTIFACT_ROLLOUT_EVIDENCE",
                saved.evidenceId().toString(),
                correlationId,
                Map.of("planId", planId.toString(), "evidenceType", saved.evidenceType(),
                        "evidenceState", saved.evidenceState(), "executorState", plan.executorState()));
        return evidence(saved);
    }

    private ArtifactRolloutPlan plan(PlanRow row) {
        List<ArtifactEvidenceRow> evidenceRows = repository.evidence(row.planId());
        List<ArtifactRolloutEvidence> evidence = evidenceRows.stream()
                .map(this::evidence).toList();
        return new ArtifactRolloutPlan(
                row.planId(), row.artifactId(), row.productKey(), row.artifactVersion(), row.name(),
                row.targetScope(), row.stages(), row.rollbackPlan(), row.rollbackFeasibility(),
                row.lifecycleState(), row.executorState(), row.reason(), row.requestedBy(),
                row.approvedBy(), row.submittedAt(), row.approvedAt(), row.decisionReason(),
                row.version(), row.createdAt(), row.updatedAt(),
                rules.planRollbackReadiness(row, evidenceRows), evidence);
    }

    private ArtifactRolloutEvidence evidence(ArtifactEvidenceRow row) {
        return new ArtifactRolloutEvidence(
                row.evidenceId(), row.planId(), row.evidenceType(), row.evidenceState(), row.evidence(),
                row.source(), row.recordedBy(), row.recordedAt());
    }

    private PlanRow requirePlan(UUID planId) {
        return repository.plan(planId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private CreateArtifactRolloutPlanRequest normalize(CreateArtifactRolloutPlanRequest request) {
        return new CreateArtifactRolloutPlanRequest(
                request.artifactId(), request.name().trim(), request.targetScope(), request.stages(),
                request.rollbackPlan(), request.rollbackFeasibility().trim(), request.reason().trim());
    }

    private AppendArtifactEvidenceRequest normalize(AppendArtifactEvidenceRequest request) {
        return new AppendArtifactEvidenceRequest(
                request.evidenceType().trim(), request.evidenceState().trim(), request.evidence());
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
