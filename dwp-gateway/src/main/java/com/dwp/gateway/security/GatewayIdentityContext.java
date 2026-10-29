package com.dwp.gateway.security;

/**
 * Stable gateway identity contract shared by filters and trusted downstream clients.
 *
 * <p>The constants live outside every filter so consumers do not acquire a compile-time
 * dependency on a filter implementation merely to exchange verified identity evidence.</p>
 */
public final class GatewayIdentityContext {
    public static final String USER_HEADER = "X-DWP-User-ID";
    public static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    public static final String ROLES_HEADER = "X-DWP-Roles";
    public static final String PERMISSIONS_HEADER = "X-DWP-Permissions";
    public static final String GROUP_REFS_HEADER = "X-DWP-Group-Refs";
    public static final String RESOURCE_ROLES_HEADER = "X-DWP-Resource-Roles";
    public static final String PERSON_PUBLIC_ID_HEADER = "X-DWP-Person-Public-ID";
    public static final String DISPLAY_NAME_HEADER = "X-DWP-Display-Name-B64";
    public static final String LEGACY_DISPLAY_NAME_HEADER = "X-DWP-User-Display-Name";
    public static final String AUTH_SESSION_ID_HEADER = "X-DWP-Auth-Session-ID";
    public static final String IDENTITY_PLANE_HEADER = "X-DWP-Identity-Plane";
    public static final String LEGACY_ROLE_FALLBACK_HEADER =
            "X-DWP-Legacy-Role-Fallback-Allowed";
    public static final String CONTROL_PLANE_HEADER = "X-DWP-Control-Plane";
    public static final String WIDGET_OWNER_SCOPE_HEADER =
            "X-DWP-Widget-Owner-Product-Keys";
    public static final String RESPONSIBILITIES_HEADER = "X-DWP-Responsibilities";
    public static final String LEGAL_ENTITY_PUBLIC_ID_HEADER =
            "X-DWP-Legal-Entity-Public-ID";
    public static final String TIME_GROUP_PUBLIC_ID_HEADER =
            "X-DWP-Time-Group-Public-ID";

    /** Retains the established exchange attribute key while decoupling it from its producer. */
    public static final String VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE =
            "com.dwp.gateway.filter.VerifiedIdentityFilter.verifiedActiveAccessMode";

    private GatewayIdentityContext() {
    }
}
