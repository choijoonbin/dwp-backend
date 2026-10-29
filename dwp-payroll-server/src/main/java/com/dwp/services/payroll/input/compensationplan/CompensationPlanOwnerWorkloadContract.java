package com.dwp.services.payroll.input.compensationplan;

/** Immutable workload-identity contract shared by the PAY owner client and token issuer. */
final class CompensationPlanOwnerWorkloadContract {

    static final String AUDIENCE =
            "dwp-people:approved-compensation-snapshot-refetch";
    static final String SUBJECT = "dwp-payroll-server";
    static final String SCOPE = "hris.internal";
    static final String CONSUMER_SESSION = "HRIS-PAY";
    static final String POPULATION_POLICY =
            "PAYROLL_INPUT_AUTHORIZED_WORKER_POPULATION_ONLY";
    static final String FIELD_POLICY = "MINIMUM_PAYROLL_INPUT_FIELDS_ONLY";

    private CompensationPlanOwnerWorkloadContract() {
    }

    record OwnerGrant(long tenantGrantRevision, long policyRevision) {
        OwnerGrant {
            if (tenantGrantRevision <= 0 || policyRevision <= 0) {
                throw new IllegalArgumentException("Owner grant revisions must be positive.");
            }
        }
    }
}
