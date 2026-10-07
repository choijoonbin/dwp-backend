package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Instant;
import java.util.List;

/** Only the guard mints selected results. Candidates never grant a selected self authority. */
public final class SelfContextResolutionV1 {
    public enum Kind { SELECTED, SELECTION_REQUIRED }

    private final Kind kind;
    private final VerifiedSelfContext selected;
    private final List<SelfContextSelectorV1> candidates;

    private SelfContextResolutionV1(Kind kind, VerifiedSelfContext selected, List<SelfContextSelectorV1> candidates) {
        this.kind = kind;
        this.selected = selected;
        this.candidates = List.copyOf(candidates);
    }

    static SelfContextResolutionV1 selected(AuthPersonBindingV1 binding, SelfContextAuthorityV1 authority,
                                            NativeSelfContextSetV1 nativeSet,
                                            NativeSelfContextSetV1.EmploymentContext context, Instant now) {
        return new SelfContextResolutionV1(Kind.SELECTED,
                new VerifiedSelfContext(binding, authority, nativeSet.personVersion(), context, now), List.of());
    }

    static SelfContextResolutionV1 selectionRequired(List<SelfContextSelectorV1> candidates) {
        return new SelfContextResolutionV1(Kind.SELECTION_REQUIRED, null, candidates);
    }

    public Kind kind() { return kind; }
    public VerifiedSelfContext selected() { return selected; }
    public List<SelfContextSelectorV1> candidates() { return candidates; }

    public static final class VerifiedSelfContext {
        private final AuthPersonBindingV1 binding;
        private final SelfContextAuthorityV1 authority;
        private final long personVersion;
        private final NativeSelfContextSetV1.EmploymentContext context;
        private final Instant verifiedAt;

        private VerifiedSelfContext(AuthPersonBindingV1 binding, SelfContextAuthorityV1 authority,
                                    long personVersion, NativeSelfContextSetV1.EmploymentContext context,
                                    Instant verifiedAt) {
            this.binding = binding;
            this.authority = authority;
            this.personVersion = personVersion;
            this.context = context;
            this.verifiedAt = verifiedAt;
        }

        public AuthPersonBindingV1 binding() { return binding; }
        public SelfContextAuthorityV1 authority() { return authority; }
        public long personVersion() { return personVersion; }
        public NativeSelfContextSetV1.EmploymentContext context() { return context; }
        public Instant verifiedAt() { return verifiedAt; }
    }
}
