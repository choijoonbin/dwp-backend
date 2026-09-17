package com.dwp.services.platform.home.runtime;

final class DwaionHomeWorkloadProtocol {

    static final String PATH = "/internal/home/v1/widget-data:batch";
    static final String ASSERTION_HEADER = "X-DWP-Home-Assertion";
    static final String ISSUER = "dwp-platform-server";
    static final String AUDIENCE = "dwp-agent-home";
    static final String DEFINITION_VERSION = "1.1.0";
    static final String DEFINITION_MANIFEST_HASH =
            "eb2152b7f1cf21611bb4a2c1781f7f267c5cd0c6d489d4edc8f680bb4fa6af54";

    private DwaionHomeWorkloadProtocol() {
    }
}
