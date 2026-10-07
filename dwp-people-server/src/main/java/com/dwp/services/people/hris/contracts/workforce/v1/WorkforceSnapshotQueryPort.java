package com.dwp.services.people.hris.contracts.workforce.v1;

/**
 * Final Control-owned facade for the in-process Performance consumer contract.
 * It is not a browser or persistence boundary and cannot be implemented by consumers.
 */
public final class WorkforceSnapshotQueryPort {

    public static final String CONTRACT_VERSION = WorkforceSnapshotContract.VERSION;
    private final GuardedWorkforceSnapshotQueryPort enforcement;

    WorkforceSnapshotQueryPort(GuardedWorkforceSnapshotQueryPort enforcement) {
        this.enforcement = java.util.Objects.requireNonNull(
                enforcement, "enforcement must not be null");
    }

    public WorkforceSnapshotPage query(WorkforceSnapshotQuery query) {
        return enforcement.query(query);
    }

    Class<?> enforcementType() {
        return enforcement.getClass();
    }
}
