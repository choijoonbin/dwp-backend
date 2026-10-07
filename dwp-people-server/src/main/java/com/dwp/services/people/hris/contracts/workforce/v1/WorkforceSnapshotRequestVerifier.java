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
}
