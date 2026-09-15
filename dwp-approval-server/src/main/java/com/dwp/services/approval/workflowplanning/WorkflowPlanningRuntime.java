package com.dwp.services.approval.workflowplanning;

public record WorkflowPlanningRuntime(WorkflowPlanningProofIssuer issuer,WorkflowPlanningAuthorityClient client) {
    public WorkflowPlanningRuntime {if(issuer==null || client==null) throw com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable();}
    public void requireReady() {client.requireReady();}
}
