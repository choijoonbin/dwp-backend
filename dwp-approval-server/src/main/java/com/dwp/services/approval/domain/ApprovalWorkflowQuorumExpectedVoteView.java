package com.dwp.services.approval.domain;

/** Shared immutable pin view; runtime engines do not depend on the application facade. */
public interface ApprovalWorkflowQuorumExpectedVoteView {
    long generation();
    long stageVersion();
    ApprovalWorkflowQuorum.Pins pins();
    int payloadRevision();
    String payloadSha256();
    Long expectedRequestVersion();
}
