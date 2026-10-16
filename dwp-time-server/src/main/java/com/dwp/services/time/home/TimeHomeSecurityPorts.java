package com.dwp.services.time.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;

/** Explicit owner integration ports; no browser or caller-supplied authority is accepted. */
public final class TimeHomeSecurityPorts {

    private TimeHomeSecurityPorts() {
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
