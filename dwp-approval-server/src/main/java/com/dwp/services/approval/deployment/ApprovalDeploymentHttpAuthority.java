package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.EnumSet;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.Capability;

@Component
final class ApprovalDeploymentHttpAuthority {
    static final String VIEW_PERMISSION = "ADMIN.APPROVAL_OPERATIONS:VIEW";
    static final String EXECUTE_PERMISSION = "ADMIN.APPROVAL_OPERATIONS:EXECUTE";

    private final ApprovalWorkAuthority workAuthority;

    ApprovalDeploymentHttpAuthority(ApprovalWorkAuthority workAuthority) {
        this.workAuthority = workAuthority;
    }

    ApprovalDeploymentModels.Scope readScope() {
        ApprovalRequestContext.Actor actor = workAuthority.requireCurrent(VIEW_PERMISSION);
        ApprovalManagementScopeContext.Evidence management = management();
        return scope(actor, management, false);
    }

    Current command(String expectedDecisionRevision) {
        ApprovalRequestContext.Actor actor = workAuthority.requireCurrent(EXECUTE_PERMISSION);
        ApprovalManagementScopeContext.Evidence management = management();
        ApprovalDecisionRevisionContext.Evidence decision = ApprovalDecisionRevisionContext
                .current().orElseThrow(() -> unavailable(
                        "Current Approval deployment decision evidence is unavailable."));
        if (expectedDecisionRevision == null
                || !expectedDecisionRevision.equals(decision.revision())
                || decision.validUntil() == null
                || !decision.validUntil().isAfter(OffsetDateTime.now())
                || decision.contextKey() == null || decision.contextKey().isBlank()
                || !management.opaqueScopeKey().equals(decision.contextScopeKey())
                || !("110".equals(decision.rolloutState())
                    || "111".equals(decision.rolloutState()))) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "Current Approval deployment authority changed.");
        }
        return new Current(actor, scope(actor, management, true), management, decision);
    }

    private ApprovalManagementScopeContext.Evidence management() {
        return ApprovalManagementScopeContext.current().orElseThrow(() -> unavailable(
                "Current Approval deployment scope is unavailable."));
    }

    private ApprovalDeploymentModels.Scope scope(
            ApprovalRequestContext.Actor actor,
            ApprovalManagementScopeContext.Evidence management,
            boolean command) {
        EnumSet<Capability> capabilities = EnumSet.of(Capability.VIEW);
        if (command) {
            capabilities.addAll(EnumSet.of(
                    Capability.CREATE_PACKAGE,
                    Capability.REQUEST_PROMOTION,
                    Capability.REVIEW_PROMOTION,
                    Capability.ACTIVATE,
                    Capability.RECORD_EXTERNAL_EVIDENCE,
                    Capability.REQUEST_ROLLBACK));
        }
        try {
            return new ApprovalDeploymentModels.Scope(
                    actor.tenantId(), management.resourceSetKey(),
                    actor.userId(), capabilities);
        } catch (IllegalArgumentException exception) {
            throw unavailable("Current Approval deployment scope is invalid.");
        }
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    record Current(
            ApprovalRequestContext.Actor actor,
            ApprovalDeploymentModels.Scope scope,
            ApprovalManagementScopeContext.Evidence management,
            ApprovalDecisionRevisionContext.Evidence decision) {
    }
}
