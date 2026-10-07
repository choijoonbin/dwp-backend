package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Instant;

/** Explicit owner composition. Native Auth binding is reused, never an independent person link ledger. */
public final class SelfPersonOwnerPortsV1 {
    private SelfPersonOwnerPortsV1() {
    }

    @FunctionalInterface
    public interface PersonSnapshotProvider {
        NativeSelfPersonSnapshotV1 loadCurrent(PersonLookup request);
    }

    public static final class PersonLookup {
        private final AuthPersonBindingV1 binding;
        private final SelfContextAuthorityV1 authority;
        private final Instant capturedNow;

        private PersonLookup(AuthPersonBindingV1 binding, SelfContextAuthorityV1 authority, Instant capturedNow) {
            this.binding = binding;
            this.authority = authority;
            this.capturedNow = capturedNow;
        }

        public AuthPersonBindingV1 binding() { return binding; }
        public SelfContextAuthorityV1 authority() { return authority; }
        public Instant capturedNow() { return capturedNow; }
    }

    // The guard is the sole production caller. External apps cannot mint a lookup.
    static PersonLookup lookup(AuthPersonBindingV1 binding, SelfContextAuthorityV1 authority, Instant now) {
        return new PersonLookup(binding, authority, now);
    }
}
