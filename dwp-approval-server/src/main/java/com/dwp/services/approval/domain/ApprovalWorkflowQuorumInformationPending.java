package com.dwp.services.approval.domain;

/** Only this exception may cross the service transaction without rolling back a durable UNKNOWN intent. */
public final class ApprovalWorkflowQuorumInformationPending extends RuntimeException {
    private final ApprovalWorkflowQuorumInformationRuntime.Receipt receipt;
    public ApprovalWorkflowQuorumInformationPending(ApprovalWorkflowQuorumInformationRuntime.Receipt receipt) {
        super("The original information command has a durable UNKNOWN intent.");
        if (receipt == null || !"UNKNOWN".equals(receipt.status()) || receipt.roundId() != null) throw new IllegalArgumentException();
        this.receipt = receipt;
    }
    public ApprovalWorkflowQuorumInformationRuntime.Receipt receipt() { return receipt; }
}
