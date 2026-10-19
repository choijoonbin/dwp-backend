package com.dwp.services.auth.hrisperformancefeature;

import java.time.Instant;

/** Independently reload the exact ACTION authority; no PAGE/DATA lookup is authorized here. */
@FunctionalInterface
public interface PerformanceCommandAuthority {
    Evidence evaluate(PerformanceFeatureContract.Request request);

    record Evidence(boolean allowed, long authorizationRevision, long bundleVersion,
            String policyRevision, String tenantControlRevision, Instant revalidateAt) { }
}
