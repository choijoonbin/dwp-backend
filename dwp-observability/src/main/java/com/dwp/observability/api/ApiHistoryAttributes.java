package com.dwp.observability.api;

/** Request attributes shared by authentication, tracing, and API history filters. */
public final class ApiHistoryAttributes {

    public static final String CORRELATION_ID = "dwp.api-history.correlation-id";
    public static final String TRACE_ID = "dwp.api-history.trace-id";
    public static final String SPAN_ID = "dwp.api-history.span-id";
    public static final String PARENT_SPAN_ID = "dwp.api-history.parent-span-id";
    public static final String TENANT_ID = "dwp.api-history.tenant-id";
    public static final String ACTOR_TYPE = "dwp.api-history.actor-type";
    public static final String ACTOR_ID = "dwp.api-history.actor-id";
    public static final String AUTH_TYPE = "dwp.api-history.auth-type";

    private ApiHistoryAttributes() {
    }
}
