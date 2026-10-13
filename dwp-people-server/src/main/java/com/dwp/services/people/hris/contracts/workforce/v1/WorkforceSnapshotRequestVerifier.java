package com.dwp.services.people.hris.contracts.workforce.v1;

/**
 * HRM-owned security boundary. Implementations resolve trusted tenant/caller policy and
 * authenticate opaque cursor tokens before any provider or repository access.
 */
public interface WorkforceSnapshotRequestVerifier {

    VerifiedWorkforceSnapshotRequest verify(WorkforceSnapshotQuery query);

    VerifiedWorkforceSnapshotCursor verifyIssuedCursor(
            VerifiedWorkforceSnapshotRequest request,
            WorkforceSnapshotPage page);

    /**
     * Additive compensation-population authority boundary. Existing owner implementations remain
     * fail closed until they explicitly implement the sealed projection.
     */
    default VerifiedCompensationPopulationRequest verifyCompensationPopulation(
            CompensationPopulationSnapshotQuery query) {
        throw new IllegalStateException(
                "compensation population authority verification is unavailable");
    }

    /** Authenticates every owner-issued compensation continuation cursor. */
    default VerifiedCompensationPopulationCursor verifyIssuedCompensationPopulationCursor(
            VerifiedCompensationPopulationRequest request,
            CompensationPopulationSnapshotPage page) {
        throw new IllegalStateException(
                "compensation population cursor verification is unavailable");
    }
}
