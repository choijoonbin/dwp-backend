package com.dwp.services.people.integration;

/** Exact cross-filter path owned by the local synthetic People boundary. */
public final class LocalSyntheticPeopleWorkforceBootstrapPaths {
    public static final String PATH = "/internal/synthetic/v1/people-workforce/bootstrap";
    public static final String AUTHORIZED_REQUEST_ATTRIBUTE =
            LocalSyntheticPeopleWorkforceBootstrapPaths.class.getName() + ".authorized";

    private LocalSyntheticPeopleWorkforceBootstrapPaths() {
    }
}
