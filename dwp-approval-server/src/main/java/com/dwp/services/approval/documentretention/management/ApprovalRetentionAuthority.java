package com.dwp.services.approval.documentretention.management;

import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.*;
import org.springframework.stereotype.Component;

@Component
public final class ApprovalRetentionAuthority {
    private final ApprovalWorkAuthority current;
    public ApprovalRetentionAuthority(ApprovalWorkAuthority current) { this.current=current; }
    public ApprovalRequestContext.Actor require(String permission) {
        var actor=current.requireCurrent(permission);
        if (ApprovalDecisionRevisionContext.current().isPresent()
                && ApprovalPilotAuthorizationContext.current().isEmpty()) throw ApprovalRetentionErrors.forbidden();
        scope();
        return actor;
    }
    public String scope() {
        return ApprovalManagementScopeContext.current().orElseThrow(ApprovalRetentionErrors::unavailable).resourceSetKey();
    }
}
