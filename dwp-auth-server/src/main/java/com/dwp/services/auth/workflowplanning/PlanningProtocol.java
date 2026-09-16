package com.dwp.services.auth.workflowplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** A separate read-only planning protocol, never runtime voter or USER authority. */
public final class PlanningProtocol {
    private static final Set<Long> SUPPORTED_POLICY_VERSIONS = Set.of(10L, 11L, 12L, 13L, 14L, 15L);
    private static final Pattern POLICY_REVISION = Pattern.compile(
            "policy-([0-9]+)-[1-9][0-9]*-[a-f0-9]{64}");
    private PlanningProtocol() { }
    public static final String PATH = "/internal/approval-workflow/admin-planning";
    public static final String HEADER = "X-DWP-Approval-Workflow-Planning-Token";
    public static final String OPERATION = "ADMIN_PLANNING";
    public static final String ROUTE = "route.approvals.admin.workflow-planning-simulation.data";
    public static final String OWNER_ISSUER = "dwp-approval-server:workflow-admin-planning:v1";
    public static final String OWNER_AUDIENCE = "dwp-auth-server:workflow-admin-planning:v1";
    public static final String OWNER_PURPOSE = "APPROVAL_WORKFLOW_ADMIN_PLANNING_V1";
    public static final String TRANSPORT_ISSUER = "dwp-approval-server:workflow-planning-transport:v1";
    public static final String TRANSPORT_AUDIENCE = "dwp-auth-server:workflow-planning-transport:v1";
    public static final String TRANSPORT_PURPOSE = "APPROVAL_WORKFLOW_PLANNING_TRANSPORT_V1";
    public static final String ATTESTATION_PURPOSE = "APPROVAL_WORKFLOW_ADMIN_PLANNING_ATTESTATION_V1";
    public static final int BODY_LIMIT = 524288, OWNER_LIMIT = 262144, TRANSPORT_LIMIT = 2048, ATTESTATION_LIMIT = 524270;
    public static final Set<String> STANDARD = Set.of("iss", "aud", "sub", "iat", "nbf", "exp", "jti", "purpose");
    public static final Set<String> OWNER_FIELDS = Set.of("tenantId", "actorId", "personPublicId", "contextKey",
            "contextScopeKey", "decisionRevision", "routeContractKey", "method", "path", "accessMode", "rolloutState",
            "authorityValidUntil", "managementResourceSetKey", "workflowId", "workflowVersionId", "formVersionId", "sourceSnapshotSha256");
    public static final Map<String, String> REQUIRED = Map.of("approvals.admin.workflow-planning-form.read", "ACTION.APPROVAL_FORM:VIEW",
            "approvals.admin.workflow-planning-simulation.read", "ADMIN.APPROVAL_WORKFLOW:UPDATE");
    public static boolean supportedPolicyRevision(String value) {
        if (value == null) return false;
        var match = POLICY_REVISION.matcher(value);
        if (!match.matches()) return false;
        try { return SUPPORTED_POLICY_VERSIONS.contains(Long.parseLong(match.group(1))); }
        catch (NumberFormatException invalid) { return false; }
    }
    public static BaseException denied() { return new BaseException(ErrorCode.FORBIDDEN, "Invalid dedicated workflow planning proof."); }
    public static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Current workflow planning authority is unavailable."); }
    public static BaseException changed() { return new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Current workflow planning authority changed."); }
}
