package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;
import java.time.Instant;
import java.util.UUID;

/** Production authority requires actual database identities, not an inferred actor/principal tuple. */
public interface ApprovalWorkflowBoundAuthority extends ApprovalWorkflowQuorumAuthority {
    CandidatePool activation(long tenant,UUID request,UUID step,long generation,Instant now);
    CurrentAuthority voter(Snapshot snapshot,ApprovalWorkflowRuntimeTarget.Use use,UUID task,UUID evidence,Instant now);
}
