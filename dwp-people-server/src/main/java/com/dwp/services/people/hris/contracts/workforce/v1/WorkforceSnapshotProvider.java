package com.dwp.services.people.hris.contracts.workforce.v1;

/** HRM-owned provider SPI; it receives only requests accepted by the owner verifier. */
@FunctionalInterface
public interface WorkforceSnapshotProvider {

    WorkforceSnapshotPage query(VerifiedWorkforceSnapshotRequest request);

    /**
     * Additive owner projection. The default deliberately fails closed so the existing
     * PERFORMANCE_V1 provider ABI and behavior remain unchanged.
     */
    default CompensationPopulationSnapshotPage queryCompensationPopulation(
            VerifiedCompensationPopulationRequest request) {
        throw new IllegalStateException("compensation population provider is unavailable");
    }
}
