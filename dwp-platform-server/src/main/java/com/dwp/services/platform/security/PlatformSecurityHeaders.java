package com.dwp.services.platform.security;

/** Canonical trusted-boundary header names shared by Platform security collaborators. */
final class PlatformSecurityHeaders {

    static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    static final String USER = "X-DWP-User-ID";
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String ROLES = "X-DWP-Roles";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String RESOURCE_ROLES = "X-DWP-Resource-Roles";
    static final String SUPPORT_SESSION = "X-DWP-Support-Session-ID";
    static final String SUPPORT_SCOPES = "X-DWP-Support-Scopes";
    static final String ACTOR_TENANT = "X-DWP-Actor-Tenant-ID";
    static final String ROUTE_CONTRACT = "X-DWP-Route-Contract-Key";
    static final String CURRENT_DECISION_REVISION = "X-DWP-Current-Decision-Revision";
    static final String CURRENT_REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    static final String EXPECTED_DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    static final String CONTEXT = "X-DWP-Context-Key";
    static final String SCOPE = "X-DWP-Context-Scope-Key";
    static final String RESPONSE_DECISION_REVISION = "X-DWP-Decision-Revision";
    static final String ROLLOUT_COHORT = "X-DWP-Rollout-Cohort";
    static final String ROLLOUT_REVISION = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_STATE = "X-DWP-Rollout-State";

    private PlatformSecurityHeaders() {
    }
}
