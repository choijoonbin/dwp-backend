package com.dwp.services.people.hris.contracts.hrm011;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Independent workload vocabulary shared by HRM-011 ingress boundaries. */
public final class Hrm011WorkloadContract {

    public static final String PAYROLL_CALLER = "dwp-payroll-server";
    public static final String AUDIENCE = "dwp-people";
    public static final String SVC003_PURPOSE =
            "HRIS_COMPENSATION_BASIS_SNAPSHOT_BOOTSTRAP";
    public static final String SVC005_PURPOSE =
            "HRIS_PAYROLL_WORKER_FACT_SNAPSHOT_BOOTSTRAP";
    public static final String COMPENSATION = "COMPENSATION_BASIS_V1";
    public static final Set<String> PAYROLL_PROJECTIONS = Set.of(
            "DEPENDENT_ELIGIBILITY_V1", "TAX_IDENTITY_V1",
            "BANK_ACCOUNT_TOKEN_V1");

    private Hrm011WorkloadContract() {
    }

    interface VerifiedRequestView {
        String method();

        String path();

        String queryFingerprint();

        String bindingId();

        UUID correlationId();

        SnapshotRequest snapshotRequest();
    }

    public record TrustedWorkload(
            long tenantId,
            String caller,
            String audience,
            String purposeCode,
            boolean tls13,
            boolean mtlsAuthenticated,
            boolean oauthClientJwtAuthenticated,
            boolean tenantGrantValid,
            long policyRevision) {
    }

    public record SnapshotRequest(
            String purposeCode,
            Instant asOf,
            String projection,
            int limit,
            String cursor) {
    }
}
