package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Instant;

/** Explicit owner composition only, never a Spring/HTTP authentication shortcut. */
public final class SelfContextOwnerPortsV1 {
    private SelfContextOwnerPortsV1() {
    }

    @FunctionalInterface
    public interface AuthorityVerifier {
        SelfContextAuthorityV1 verifyCurrent(SelfContextQueryV1 query, SelfContextPurposeV1.Audience audience,
                                            Instant capturedNow);
    }

    @FunctionalInterface
    public interface AuthBindingProvider {
        AuthPersonBindingV1 loadCurrent(AuthLookup request);
    }

    @FunctionalInterface
    public interface NativeContextProvider {
        NativeSelfContextSetV1 loadComplete(PeopleLookup request);
    }

    public static final class AuthLookup {
        private final SelfContextAuthorityV1 authority;
        private final Instant capturedNow;

        private AuthLookup(SelfContextAuthorityV1 authority, Instant capturedNow) {
            this.authority = authority;
            this.capturedNow = capturedNow;
        }

        public SelfContextAuthorityV1 authority() { return authority; }
        public Instant capturedNow() { return capturedNow; }
    }

    public static final class PeopleLookup {
        private final AuthPersonBindingV1 binding;
        private final SelfContextAuthorityV1 authority;
        private final SelfContextQueryV1 query;
        private final Instant capturedNow;

        private PeopleLookup(AuthPersonBindingV1 binding, SelfContextAuthorityV1 authority,
                             SelfContextQueryV1 query, Instant capturedNow) {
            this.binding = binding;
            this.authority = authority;
            this.query = query;
            this.capturedNow = capturedNow;
        }

        public AuthPersonBindingV1 binding() { return binding; }
        public SelfContextAuthorityV1 authority() { return authority; }
        public SelfContextQueryV1 query() { return query; }
        public Instant capturedNow() { return capturedNow; }
    }

    static AuthLookup authLookup(SelfContextAuthorityV1 authority, Instant now) {
        return new AuthLookup(authority, now);
    }

    static PeopleLookup peopleLookup(AuthPersonBindingV1 binding, SelfContextAuthorityV1 authority,
                                     SelfContextQueryV1 query, Instant now) {
        return new PeopleLookup(binding, authority, query, now);
    }
}
