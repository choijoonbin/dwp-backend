package com.dwp.services.platform.hrisconfiguration.automation;

import java.util.Set;

/** Immutable reference to the reviewed SYS-006 transport schemas; it is not a schema copy. */
public final class Sys006ClosedTransportSchemaReference {

    public static final String SOURCE_REPOSITORY = "dwp-frontend";
    public static final String SOURCE_PATH =
            "docs/06-delivery/hris/wave7-2026-10-17/"
                    + "r7-batch-04-module-start-r1/semantic-dispositions/"
                    + "base-tfr-sys-006.semantic-disposition.v1.json";
    public static final String JSON_POINTER =
            "/contractDecision/closedTransportSchemas";
    /** SHA-256 of the jq -S -c canonical fragment, including its trailing LF. */
    public static final String CANONICAL_SHA256 =
            "414aa96f4f9a0bfffe6a506c208e62c94834d96f656e0551f64c67255335c0df";
    public static final Set<String> API_SCHEMAS = Set.of(
            "SYS_API_017_GET_QUERY",
            "SYS_API_017_GET_RESPONSE",
            "SYS_API_017_POST_REQUEST",
            "SYS_API_017_POST_RESPONSE",
            "SYS_API_019_GET_QUERY",
            "SYS_API_019_GET_RESPONSE");

    private Sys006ClosedTransportSchemaReference() {}
}
