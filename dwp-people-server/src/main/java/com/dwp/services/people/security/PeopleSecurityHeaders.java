package com.dwp.services.people.security;

/** Canonical trusted-boundary header names shared by People security filters. */
final class PeopleSecurityHeaders {

    static final String ROLES = "X-DWP-Roles";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String SUPPORT_SESSION = "X-DWP-Support-Session-ID";
    static final String SUPPORT_SCOPES = "X-DWP-Support-Scopes";
    static final String ROUTE_CONTRACT = "X-DWP-Route-Contract-Key";
    static final String ROLLOUT_STATE = "X-DWP-Rollout-State";

    private PeopleSecurityHeaders() {
    }
}
