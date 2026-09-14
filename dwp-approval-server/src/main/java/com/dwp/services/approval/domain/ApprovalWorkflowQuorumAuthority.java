package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import java.time.Instant;
import java.util.UUID;

/** Internal owner port. There is intentionally no search/count-based fallback or permissive default bean. */
public interface ApprovalWorkflowQuorumAuthority {
    CandidatePool candidates(Pins pins, UUID requestId, ApprovalWorkflowQuorumDefinition.Stage stage, Instant now);
    CurrentAuthority voter(Snapshot snapshot, long actorUserId, long principalUserId, Instant now);
}
