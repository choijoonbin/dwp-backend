package com.dwp.core.constant;

/**
 * Shared HTTP header names used at the API boundary.
 */
public final class HeaderConstants {

    public static final String X_TENANT_ID = "X-Tenant-ID";
    public static final String X_CORRELATION_ID = "X-Correlation-ID";
    public static final String X_DWP_USER_ID = "X-DWP-User-ID";
    public static final String X_DWP_TENANT_ID = "X-DWP-Tenant-ID";
    public static final String X_DWP_ROLES = "X-DWP-Roles";
    public static final String X_DWP_GROUP_REFS = "X-DWP-Group-Refs";
    public static final String X_DWP_PERMISSIONS = "X-DWP-Permissions";
    public static final String X_DWP_SERVICE_TOKEN = "X-DWP-Service-Token";
    public static final String TRACE_PARENT = "traceparent";

    private HeaderConstants() {
    }
}
