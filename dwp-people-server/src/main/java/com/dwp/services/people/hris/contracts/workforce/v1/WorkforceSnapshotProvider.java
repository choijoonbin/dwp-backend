package com.dwp.services.people.hris.contracts.workforce.v1;

/** HRM-owned provider SPI; it receives only requests accepted by the owner verifier. */
@FunctionalInterface
public interface WorkforceSnapshotProvider {

    WorkforceSnapshotPage query(VerifiedWorkforceSnapshotRequest request);
}
