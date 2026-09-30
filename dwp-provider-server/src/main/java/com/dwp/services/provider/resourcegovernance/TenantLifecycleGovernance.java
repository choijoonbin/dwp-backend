package com.dwp.services.provider.resourcegovernance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.governance.DataPolicyRepository;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateTenantLifecycleRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.TenantLifecycleRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.TenantLifecycleRequestRow;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class TenantLifecycleGovernance {

    private final ResourceGovernanceRepository repository;
    private final DataPolicyRepository dataPolicyRepository;
    private final ProviderAuditService audit;

    TenantLifecycleGovernance(
            ResourceGovernanceRepository repository,
            DataPolicyRepository dataPolicyRepository,
            ProviderAuditService audit) {
        this.repository = repository;
        this.dataPolicyRepository = dataPolicyRepository;
        this.audit = audit;
    }

    public List<TenantLifecycleRequest> lifecycleRequests(UUID tenantId) {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.RESOURCE_READ);
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return repository.lifecycleRequests(tenantId).stream().map(this::lifecycleRequest).toList();
    }

    public TenantLifecycleRequest createLifecycleRequest(
            UUID tenantId,
            CreateTenantLifecycleRequest request,
            String correlationId) {
        requireTenantSelectionWrite();
        if (!repository.tenantExists(tenantId)) throw new BaseException(ErrorCode.NOT_FOUND);
        CreateTenantLifecycleRequest normalized = new CreateTenantLifecycleRequest(
                request.requestedAction().trim(), request.justification().trim());
        HoldEvaluation hold = evaluateGlobalLegalHold();
        String lifecycleState = hold.blocked() ? "BLOCKED_BY_HOLD" : "DRAFT";
        TenantLifecycleRequestRow saved;
        try {
            saved = repository.createLifecycleRequest(
                    UUID.randomUUID(), tenantId, normalized, lifecycleState, hold.state(),
                    hold.evidenceRefs(), ProviderRequestContext.require().operatorId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An open request for this tenant and lifecycle action already exists.");
        }
        audit.success(
                "provider.tenant-lifecycle.request-created",
                "TENANT_LIFECYCLE_REQUEST",
                saved.requestId().toString(),
                tenantId,
                null,
                correlationId,
                Map.of("requestedAction", saved.requestedAction(),
                        "lifecycleState", saved.lifecycleState(),
                        "holdEvaluationState", saved.holdEvaluationState(),
                        "executionState", saved.executionState()));
        return lifecycleRequest(saved);
    }

    public TenantLifecycleRequest refreshLifecycleHold(
            UUID requestId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        requireTenantSelectionWrite();
        TenantLifecycleRequestRow before = repository.lockLifecycleRequest(requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        HoldEvaluation hold = evaluateGlobalLegalHold();
        String lifecycleState = hold.blocked() ? "BLOCKED_BY_HOLD" : "DRAFT";
        if (!repository.refreshLifecycleHold(
                requestId, request.version(), lifecycleState, hold.state(), hold.evidenceRefs())) {
            throw conflict("The lifecycle request changed or its hold state can no longer be refreshed.");
        }
        TenantLifecycleRequestRow saved = requireLifecycleRequest(requestId);
        audit.success(
                "provider.tenant-lifecycle.hold-refreshed",
                "TENANT_LIFECYCLE_REQUEST",
                requestId.toString(),
                saved.tenantId(),
                null,
                correlationId,
                Map.of("beforeState", before.lifecycleState(), "afterState", saved.lifecycleState(),
                        "holdEvaluationState", saved.holdEvaluationState(), "reason", request.reason()));
        return lifecycleRequest(saved);
    }

    public TenantLifecycleRequest submitLifecycleRequest(
            UUID requestId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        requireTenantSelectionWrite();
        TenantLifecycleRequestRow before = repository.lockLifecycleRequest(requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        HoldEvaluation hold = evaluateGlobalLegalHold();
        if (hold.blocked()) {
            repository.refreshLifecycleHold(
                    requestId, request.version(), "BLOCKED_BY_HOLD", hold.state(), hold.evidenceRefs());
            throw conflict("An active global legal hold blocks this lifecycle request.");
        }
        if (!repository.submitLifecycleRequest(
                requestId, request.version(), ProviderRequestContext.require().operatorId())) {
            throw conflict("Only a current draft without a global legal hold can be submitted.");
        }
        TenantLifecycleRequestRow saved = requireLifecycleRequest(requestId);
        audit.success(
                "provider.tenant-lifecycle.request-submitted",
                "TENANT_LIFECYCLE_REQUEST",
                requestId.toString(),
                saved.tenantId(),
                null,
                correlationId,
                Map.of("beforeState", before.lifecycleState(), "afterState", saved.lifecycleState(),
                        "reason", request.reason(), "executionState", saved.executionState()));
        return lifecycleRequest(saved);
    }

    public TenantLifecycleRequest decideLifecycleRequest(
            UUID requestId,
            TenantLifecycleDecisionRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.TENANT_LIFECYCLE_APPROVE);
        ProviderRequestContext.requirePermission("ESTATE_READ");
        TenantLifecycleRequestRow before = repository.lockLifecycleRequest(requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Long actorId = ProviderRequestContext.require().operatorId();
        if (Objects.equals(actorId, before.requestedBy())
                || Objects.equals(actorId, before.submittedBy())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "A lifecycle requester or submitter cannot independently decide the same request.");
        }
        HoldEvaluation hold = evaluateGlobalLegalHold();
        if ("APPROVED".equals(request.decision()) && hold.blocked()) {
            throw conflict("An active global legal hold blocks lifecycle handoff approval.");
        }
        TenantLifecycleDecisionRequest normalized = new TenantLifecycleDecisionRequest(
                request.version(), request.decision().trim(), request.reason().trim());
        if (!repository.decideLifecycleRequest(requestId, normalized, actorId)) {
            throw conflict("The lifecycle approval changed or can no longer be decided.");
        }
        TenantLifecycleRequestRow saved = requireLifecycleRequest(requestId);
        audit.success(
                "provider.tenant-lifecycle.request-" + request.decision().toLowerCase(Locale.ROOT),
                "TENANT_LIFECYCLE_REQUEST",
                requestId.toString(),
                saved.tenantId(),
                null,
                correlationId,
                Map.of("beforeState", before.lifecycleState(), "afterState", saved.lifecycleState(),
                        "decision", request.decision(), "reason", request.reason(),
                        "executionState", saved.executionState()));
        return lifecycleRequest(saved);
    }

    private TenantLifecycleRequest lifecycleRequest(TenantLifecycleRequestRow row) {
        return new TenantLifecycleRequest(
                row.requestId(), row.tenantId(), row.tenantKey(), row.tenantDisplayName(),
                row.requestedAction(), row.lifecycleState(), row.holdEvaluationState(),
                row.holdEvidenceRefs(), row.executionState(), row.justification(), row.requestedBy(),
                row.submittedBy(), row.approvedBy(), row.submittedAt(), row.approvedAt(),
                row.decisionReason(), row.version(), row.createdAt(), row.updatedAt());
    }
    private TenantLifecycleRequestRow requireLifecycleRequest(UUID requestId) {
        return repository.lifecycleRequest(requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }
    private HoldEvaluation evaluateGlobalLegalHold() {
        List<String> evidence = dataPolicyRepository.activePolicies("LEGAL_HOLD").stream()
                .filter(policy -> "GLOBAL".equals(policy.scopeType()))
                .filter(policy -> policy.rule().path("active").asBoolean(false))
                .map(policy -> "data-policy://" + policy.policyId() + "/revisions/" + policy.revisionId())
                .sorted()
                .toList();
        return evidence.isEmpty()
                ? new HoldEvaluation(false, "OWNER_VERIFICATION_REQUIRED", List.of())
                : new HoldEvaluation(true, "ACTIVE_GLOBAL_LEGAL_HOLD", evidence);
    }
    private record HoldEvaluation(boolean blocked, String state, List<String> evidenceRefs) {
    }
    /** A write grant alone must not become a way to select an otherwise undisclosed tenant. */
    private void requireTenantSelectionWrite() {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.RESOURCE_WRITE);
        ProviderRequestContext.requirePermission("ESTATE_READ");
    }
    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
