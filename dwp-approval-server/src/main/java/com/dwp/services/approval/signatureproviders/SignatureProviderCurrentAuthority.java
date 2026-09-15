package com.dwp.services.approval.signatureproviders;

import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalPilotPepRegistry;
import com.dwp.services.approval.security.ApprovalRequestContext;

interface SignatureProviderCurrentAuthority {
    Current require(SignatureProviderOperation operation);
    void unchanged(Current original);

    record Current(ApprovalRequestContext.Actor actor,
                   ApprovalDecisionRevisionContext.Evidence decision,
                   ApprovalManagementScopeContext.Evidence scope,
                   ApprovalPilotPepRegistry.RouteAuthority route) { }
}
