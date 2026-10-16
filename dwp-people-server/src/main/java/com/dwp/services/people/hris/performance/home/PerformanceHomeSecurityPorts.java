package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;

/** Module-qualified security ports prevent HRM and PER beans from colliding. */
public final class PerformanceHomeSecurityPorts {

    private PerformanceHomeSecurityPorts() {
    }

    @FunctionalInterface
    public interface SigningKeys
            extends HomeMaterializationWorkloadProofV1.SigningKeyResolver {
    }

    @FunctionalInterface
    public interface ReplayReservations
            extends HomeMaterializationWorkloadProofV1.ReplayStore {
    }
}
