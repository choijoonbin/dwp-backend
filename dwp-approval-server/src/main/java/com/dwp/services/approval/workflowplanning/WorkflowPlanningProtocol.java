package com.dwp.services.approval.workflowplanning;

import java.util.Set;

public final class WorkflowPlanningProtocol {
    public static final String PATH="/internal/approval-workflow/admin-planning";
    public static final String HEADER="X-DWP-Approval-Workflow-Planning-Token";
    public static final String OPERATION="ADMIN_PLANNING";
    public static final String OWNER_ISSUER="dwp-approval-server:workflow-admin-planning:v1";
    public static final String OWNER_AUDIENCE="dwp-auth-server:workflow-admin-planning:v1";
    public static final String OWNER_PURPOSE="APPROVAL_WORKFLOW_ADMIN_PLANNING_V1";
    public static final String TRANSPORT_ISSUER="dwp-approval-server:workflow-planning-transport:v1";
    public static final String TRANSPORT_AUDIENCE="dwp-auth-server:workflow-planning-transport:v1";
    public static final String TRANSPORT_PURPOSE="APPROVAL_WORKFLOW_PLANNING_TRANSPORT_V1";
    public static final String ATTESTATION_PURPOSE="APPROVAL_WORKFLOW_ADMIN_PLANNING_ATTESTATION_V1";
    public static final String ROUTE="route.approvals.admin.workflow-planning-simulation.data";
    public static final String CAPABILITY="approvals.admin.workflow-planning-simulation.read";
    public static final String PERMISSION="ADMIN.APPROVAL_WORKFLOW:UPDATE";
    public static final String PREDICATE="predicate.approval.workflow-planning-simulation.v1";
    public static final int BODY_MAX=524288,OWNER_MAX=262144,TOKEN_MAX=2048,ATTESTATION_MAX=524270,LOOKUP_MAX=131072;
    static final Set<String> ATTESTATION_FIELDS=Set.of("iss","aud","sub","iat","nbf","exp","jti","purpose","operation",
            "sourceProofJti","transportProofJti","bodySha256","bindingsSha256","authority","result");
    static final Set<String> AUTHORITY_FIELDS=Set.of("ownerAuthRevision","ownerPolicyRevision","sourceRevision","sourceVectorSha256","evaluatedAt","expiresAt");
    private WorkflowPlanningProtocol() { }
}
