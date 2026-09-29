package com.dwp.platform.contracts.hris.identity.v2;

import java.time.Instant;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.*;

/**
 * Explicit server composition only: no autowiring, HTTP/body authority, default adapter or SQL.
 * Production providers/verified transport/native transaction PEP are NOT implemented here.
 */
public final class CurrentHrisAuthorizationPortsV2 {
    private CurrentHrisAuthorizationPortsV2() { }
    public interface CurrentInvocationProvider {
        Invocation current();
    }
    public interface CurrentDwpAuthorityProvider {
        DwpAuthoritySnapshot loadCurrent(DwpLookup lookup);
    }
    public interface CurrentPeopleAuthorityProvider {
        PeopleAuthoritySnapshot loadCurrent(PeopleLookup lookup);
    }
    public static final class DwpLookup {
        private final Invocation invocation;
        private final Requirements requirements;
        private final Instant capturedNow;
        private DwpLookup(Invocation invocation, Requirements requirements, Instant capturedNow) {
            this.invocation = invocation; this.requirements = requirements; this.capturedNow = capturedNow;
        }
        public Invocation invocation() { return invocation; }
        public Requirements requirements() { return requirements; }
        public Instant capturedNow() { return capturedNow; }
    }
    public static final class PeopleLookup {
        private final DwpAuthoritySnapshot authority;
        private final TargetSelector selector;
        private final Instant capturedNow;
        private PeopleLookup(DwpAuthoritySnapshot authority, TargetSelector selector, Instant capturedNow) {
            this.authority = authority; this.selector = selector; this.capturedNow = capturedNow;
        }
        public DwpAuthoritySnapshot authority() { return authority; }
        public TargetSelector selector() { return selector; }
        public Instant capturedNow() { return capturedNow; }
    }
    static DwpLookup dwp(Invocation invocation, Requirements requirements, Instant now) {
        return new DwpLookup(invocation, requirements, now);
    }
    static PeopleLookup people(DwpAuthoritySnapshot authority, TargetSelector selector, Instant now) {
        return new PeopleLookup(authority, selector, now);
    }
}
