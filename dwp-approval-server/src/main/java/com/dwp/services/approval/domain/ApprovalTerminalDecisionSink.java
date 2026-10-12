package com.dwp.services.approval.domain;

import java.time.Instant;
import java.util.UUID;

/** Optional owner-local sink invoked inside the accepted terminal vote transaction. */
public interface ApprovalTerminalDecisionSink {
    void accepted(long tenantId, UUID requestId, UUID voteId,
            UUID actorPersonPublicId, Instant acceptedAt, String requestStatus);
}
