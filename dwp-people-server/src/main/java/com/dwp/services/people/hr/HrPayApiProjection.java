package com.dwp.services.people.hr;

/** Keeps local fixture provenance internal to People API responses. */
final class HrPayApiProjection {

    private static final String LOCAL_SEED = "LOCAL_SEED";
    private static final String REFERENCE = "REFERENCE";

    private HrPayApiProjection() {
    }

    static HrDtos.PayCycle canonicalCycle(HrDtos.PayCycle cycle) {
        if (cycle == null || !LOCAL_SEED.equalsIgnoreCase(cycle.dataOrigin())) {
            return cycle;
        }
        return new HrDtos.PayCycle(
                cycle.payCycleId(), cycle.name(), cycle.periodStart(), cycle.periodEnd(),
                cycle.payDate(), cycle.status(), cycle.timeValidated(),
                cycle.absenceValidated(), cycle.sourceConfirmed(), REFERENCE);
    }
}
