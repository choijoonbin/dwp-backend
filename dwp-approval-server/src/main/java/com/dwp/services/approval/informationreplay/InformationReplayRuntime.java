package com.dwp.services.approval.informationreplay;

/** Constructed lazily so disabled or unconfigured replay never opens a database transaction. */
public record InformationReplayRuntime(InformationReplayProofIssuer issuer,InformationReplayAuthorityClient client) {
    public InformationReplayRuntime {
        if(issuer==null || client==null) throw com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable();
    }
}
