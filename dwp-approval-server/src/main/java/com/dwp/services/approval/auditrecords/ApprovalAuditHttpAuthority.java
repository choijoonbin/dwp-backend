package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.EnumSet;

import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.Capability;

@Component
final class ApprovalAuditHttpAuthority {
    static final String VIEW_PERMISSION = "ADMIN.APPROVAL_OPERATIONS:VIEW";
    static final String EXECUTE_PERMISSION = "ADMIN.APPROVAL_OPERATIONS:EXECUTE";

    private final ApprovalWorkAuthority workAuthority;

    ApprovalAuditHttpAuthority(ApprovalWorkAuthority workAuthority) {
        this.workAuthority = workAuthority;
    }

    ApprovalAuditModels.Scope readScope() {
        return scope(workAuthority.requireCurrent(VIEW_PERMISSION), false);
    }

    Current command(String expectedDecisionRevision) {
        ApprovalRequestContext.Actor actor = workAuthority.requireCurrent(EXECUTE_PERMISSION);
        ApprovalManagementScopeContext.Evidence management = management();
        ApprovalDecisionRevisionContext.Evidence decision = ApprovalDecisionRevisionContext
                .current().orElseThrow(() -> unavailable(
                        "Current Approval audit decision evidence is unavailable."));
        if (expectedDecisionRevision == null
                || !expectedDecisionRevision.equals(decision.revision())) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "Current Approval audit authority changed.");
        }
        if (decision.validUntil() == null
                || !decision.validUntil().isAfter(OffsetDateTime.now())
                || decision.contextKey() == null || decision.contextKey().isBlank()
                || !management.opaqueScopeKey().equals(decision.contextScopeKey())
                || !("110".equals(decision.rolloutState())
                    || "111".equals(decision.rolloutState()))) {
            throw unavailable("Current Approval audit command authority is unavailable.");
        }
        return new Current(actor, scope(actor, management, true), management, decision);
    }

    private ApprovalAuditModels.Scope scope(
            ApprovalRequestContext.Actor actor,
            boolean command) {
        return scope(actor, management(), command);
    }

    private ApprovalAuditModels.Scope scope(
            ApprovalRequestContext.Actor actor,
            ApprovalManagementScopeContext.Evidence selected,
            boolean command) {
        EnumSet<Capability> capabilities = EnumSet.of(Capability.VIEW);
        if (command || actor.permissions().contains(EXECUTE_PERMISSION)
                || actor.permissions().contains("ADMIN.APPROVAL_OPERATIONS:MANAGE")) {
            capabilities.add(Capability.VIEW_PRIVILEGED);
            capabilities.add(Capability.MANAGE_PERSONAL_VIEWS);
            capabilities.add(Capability.MANAGE_SHARED_VIEWS);
            capabilities.add(Capability.EXPORT);
            capabilities.add(Capability.VERIFY_EXPORT);
            capabilities.add(Capability.LINK_EXTERNAL_ATTESTATION);
        }
        if (actor.hasAnyRole(
                "APPROVAL_AUDITOR", "COMPLIANCE_AUDITOR",
                "APPROVAL_RECOVERY_AUDITOR")) {
            capabilities.add(Capability.VIEW_PRIVILEGED);
            capabilities.add(Capability.VIEW_AUDITOR);
        }
        try {
            return new ApprovalAuditModels.Scope(
                    actor.tenantId(), selected.resourceSetKey(),
                    actor.userId(), capabilities);
        } catch (IllegalArgumentException exception) {
            throw unavailable("Current Approval management scope is invalid.");
        }
    }

    private ApprovalManagementScopeContext.Evidence management() {
        return ApprovalManagementScopeContext.current().orElseThrow(() -> unavailable(
                "Current Approval management scope is unavailable."));
    }

    record Current(
            ApprovalRequestContext.Actor actor,
            ApprovalAuditModels.Scope scope,
            ApprovalManagementScopeContext.Evidence management,
            ApprovalDecisionRevisionContext.Evidence decision) {
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
