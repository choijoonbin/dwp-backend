package com.dwp.services.approval.domain;

import com.dwp.services.approval.security.ApprovalRequestContext;

public final class ApprovalWorkflowQuorumBindings {
    private ApprovalWorkflowQuorumBindings() { }

    public static ApprovalWorkflowQuorumFacade.ExpectedVote expected(ApprovalDtos.QuorumVotePrecondition value) {
        if (value == null) return null;
        var actor = ApprovalRequestContext.require();
        var pins = value.pins();
        if (pins == null) throw ApprovalWorkflowQuorum.invalid("Immutable quorum pins are required.");
        return new ApprovalWorkflowQuorumFacade.ExpectedVote(value.generation(), value.expectedStageVersion(),
                new ApprovalWorkflowQuorum.Pins(actor.tenantId(), pins.workflowVersionId(), pins.workflowVersion(),
                        pins.workflowDefinitionSha256(), pins.formSchemaSha256(), pins.policyVersion(), pins.policySha256()),
                value.payloadRevision(), value.payloadSha256(), value.expectedRequestVersion());
    }

    static ApprovalDtos.WorkflowRuntimePins publicPins(ApprovalWorkflowQuorum.Pins pins) {
        return new ApprovalDtos.WorkflowRuntimePins(pins.workflowVersionId(), pins.workflowVersion(),
                pins.workflowDefinitionSha256(), pins.formSchemaSha256(), pins.policyVersion(), pins.policySha256());
    }
}
