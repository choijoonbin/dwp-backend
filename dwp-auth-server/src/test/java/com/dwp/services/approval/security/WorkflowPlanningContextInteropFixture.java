package com.dwp.services.approval.security;

import java.time.OffsetDateTime;
import java.util.List;

/** Cross-service test access to the same package-private request lifecycle used by Approval filters. */
public final class WorkflowPlanningContextInteropFixture {
    private WorkflowPlanningContextInteropFixture() { }
    public static void install(String revision,OffsetDateTime expiresAt,String contextKey,String contextScopeKey,
            String routeContractKey,String rolloutState,String resourceSetKey,List<ApprovalPilotPepRegistry.RouteAuthority> authorities) {
        ApprovalDecisionRevisionContext.set(revision,expiresAt,contextKey,contextScopeKey,routeContractKey,rolloutState);
        ApprovalManagementScopeContext.set(contextScopeKey,resourceSetKey);
        ApprovalPilotAuthorizationContext.set(authorities);
    }
    public static void clear() {
        ApprovalPilotAuthorizationContext.clear();ApprovalManagementScopeContext.clear();ApprovalDecisionRevisionContext.clear();
    }
}
