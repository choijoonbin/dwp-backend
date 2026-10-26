package com.dwp.services.approval.domain;

import java.time.Instant;
import java.util.UUID;

/** Optional owner-local sink invoked inside the accepted terminal vote transaction. */
public interface ApprovalTerminalDecisionSink {
    default void accepted(long tenantId, UUID requestId, UUID voteId,
            UUID actorPersonPublicId, Instant acceptedAt, String requestStatus) {
        accepted(tenantId, requestId, voteId, actorPersonPublicId, acceptedAt,
                requestStatus, ApprovalTerminalDecisionContext.standard());
    }

    void accepted(long tenantId, UUID requestId, UUID voteId,
            UUID actorPersonPublicId, Instant acceptedAt, String requestStatus,
            ApprovalTerminalDecisionContext context);
}
