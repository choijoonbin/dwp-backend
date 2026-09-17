package com.dwp.services.approval.analytics;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

import static com.dwp.services.approval.analytics.ApprovalAnalyticsModels.Capability;

@Component
final class ApprovalAnalyticsHttpAuthority {
    private static final String VIEW_PERMISSION = "ADMIN.APPROVAL_OPERATIONS:VIEW";
    private static final String EXECUTE_PERMISSION = "ADMIN.APPROVAL_OPERATIONS:EXECUTE";

    private final ApprovalWorkAuthority workAuthority;

    ApprovalAnalyticsHttpAuthority(ApprovalWorkAuthority workAuthority) {
        this.workAuthority = workAuthority;
    }

    ApprovalAnalyticsModels.Scope scope() {
        ApprovalRequestContext.Actor actor = workAuthority.requireCurrent(VIEW_PERMISSION);
        ApprovalManagementScopeContext.Evidence selected = ApprovalManagementScopeContext
                .current().orElseThrow(() -> unavailable(
                        "Current Approval analytics scope is unavailable."));
        EnumSet<Capability> capabilities = EnumSet.of(Capability.VIEW);
        if (actor.permissions().contains(EXECUTE_PERMISSION)
                || actor.permissions().contains("ADMIN.APPROVAL_OPERATIONS:MANAGE")
                || actor.hasAnyRole(
                        "APPROVAL_AUDITOR", "COMPLIANCE_AUDITOR",
                        "APPROVAL_RECOVERY_AUDITOR")) {
            capabilities.add(Capability.DRILL_DOWN);
        }
        try {
            return new ApprovalAnalyticsModels.Scope(
                    actor.tenantId(), selected.resourceSetKey(),
                    actor.userId(), capabilities);
        } catch (IllegalArgumentException exception) {
            throw unavailable("Current Approval analytics scope is invalid.");
        }
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
