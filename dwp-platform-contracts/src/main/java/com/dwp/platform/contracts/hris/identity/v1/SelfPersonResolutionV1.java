package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Instant;

/** Guard-only structural result, not an HTTP authorization token, signature or public caller mint. */
public final class SelfPersonResolutionV1 {
    private final AuthPersonBindingV1 binding;
    private final SelfContextAuthorityV1 authority;
    private final NativeSelfPersonSnapshotV1 person;
    private final Instant verifiedAt;

    private SelfPersonResolutionV1(AuthPersonBindingV1 binding, SelfContextAuthorityV1 authority,
                                   NativeSelfPersonSnapshotV1 person, Instant verifiedAt) {
        this.binding = binding;
        this.authority = authority;
        this.person = person;
        this.verifiedAt = verifiedAt;
    }

    // Package-private and sole guard callsite; raw owner DTOs stay constructible by future adapters.
    static SelfPersonResolutionV1 verified(AuthPersonBindingV1 binding, SelfContextAuthorityV1 authority,
                                           NativeSelfPersonSnapshotV1 person, Instant now) {
        return new SelfPersonResolutionV1(binding, authority, person, now);
    }

    public AuthPersonBindingV1 binding() { return binding; }
    public SelfContextAuthorityV1 authority() { return authority; }
    public NativeSelfPersonSnapshotV1 person() { return person; }
    public Instant verifiedAt() { return verifiedAt; }
}
