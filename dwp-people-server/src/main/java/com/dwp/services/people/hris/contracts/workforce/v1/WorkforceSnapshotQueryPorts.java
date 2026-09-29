package com.dwp.services.people.hris.contracts.workforce.v1;

import java.util.Objects;

/** Control-owned composition factory used by the HRM owner configuration only. */
public final class WorkforceSnapshotQueryPorts {

    private WorkforceSnapshotQueryPorts() {
    }

    /**
     * Wraps the owner verifier and provider in the mandatory enforcement pipeline.
     * Consumer modules must inject only the returned {@link WorkforceSnapshotQueryPort}.
     */
    public static WorkforceSnapshotQueryPort guarded(
            WorkforceSnapshotRequestVerifier verifier,
            WorkforceSnapshotProvider provider) {
        return new WorkforceSnapshotQueryPort(new GuardedWorkforceSnapshotQueryPort(
                Objects.requireNonNull(verifier, "verifier must not be null"),
                Objects.requireNonNull(provider, "provider must not be null")));
    }
}
