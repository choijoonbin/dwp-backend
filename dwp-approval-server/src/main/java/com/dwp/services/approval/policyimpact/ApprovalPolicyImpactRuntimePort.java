package com.dwp.services.approval.policyimpact;

import static com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos.*;
import java.util.UUID;

/** Read-only bridge to the owner runtime, not a permission or candidate simulation service. */
public interface ApprovalPolicyImpactRuntimePort {
    void validate(String policyKey, Rules rules);
    int legacyRejectMinimum(Rules rules);
    String digest(Object value);
    boolean typed(String definition);
    RuntimeDifference quorum(long tenantId, UUID requestId, Head selected);
}
