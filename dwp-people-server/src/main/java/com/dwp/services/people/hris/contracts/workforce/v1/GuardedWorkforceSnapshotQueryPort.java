package com.dwp.services.people.hris.contracts.workforce.v1;

import java.util.Objects;

/** Owner-internal enforcement pipeline; consumers receive only the public query port. */
final class GuardedWorkforceSnapshotQueryPort {

    private final WorkforceSnapshotRequestVerifier verifier;
    private final WorkforceSnapshotProvider provider;

    GuardedWorkforceSnapshotQueryPort(
            WorkforceSnapshotRequestVerifier verifier,
            WorkforceSnapshotProvider provider) {
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
    }

    WorkforceSnapshotPage query(WorkforceSnapshotQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        VerifiedWorkforceSnapshotRequest verified = Objects.requireNonNull(
                verifier.verify(query), "verifier must return a verified request");
        verified.verifyDerivedFrom(query);
        WorkforceSnapshotPage page = Objects.requireNonNull(
                provider.query(verified), "provider must return a page");
        page.verifyFor(verified);
        if (page.nextCursor() != null) {
            VerifiedWorkforceSnapshotCursor issued = Objects.requireNonNull(
                    verifier.verifyIssuedCursor(verified, page),
                    "verifier must return authenticated issued cursor claims");
            issued.verifyFor(verified, page);
        }
        return page;
    }
}
