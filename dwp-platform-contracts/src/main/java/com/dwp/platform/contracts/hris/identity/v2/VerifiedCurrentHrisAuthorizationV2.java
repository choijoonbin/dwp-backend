package com.dwp.platform.contracts.hris.identity.v2;

import java.time.Instant;
import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.*;

/**
 * Guard-minted bounded local composition result. NOT a signed/current production owner proof.
 * Mutations still require native transaction PEP/CAS and current authenticated transport.
 */
public final class VerifiedCurrentHrisAuthorizationV2 {
    private final Requirements requirements;
    private final AuthPersonBindingV1 actor;
    private final TargetSnapshot target;
    private final DwpAuthoritySnapshot dwp;
    private final PeopleAuthoritySnapshot people;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private VerifiedCurrentHrisAuthorizationV2(Requirements requirements, DwpAuthoritySnapshot dwp,
            PeopleAuthoritySnapshot people, Instant issuedAt, Instant expiresAt) {
        this.requirements = requirements; this.actor = dwp.actor(); this.target = people.target();
        this.dwp = dwp; this.people = people; this.issuedAt = issuedAt; this.expiresAt = expiresAt;
    }
    static VerifiedCurrentHrisAuthorizationV2 mint(Requirements requirements,
            DwpAuthoritySnapshot dwp, PeopleAuthoritySnapshot people, Instant now, Instant expiresAt) {
        return new VerifiedCurrentHrisAuthorizationV2(requirements, dwp, people, now, expiresAt);
    }
    public Requirements requirements() { return requirements; }
    public AuthPersonBindingV1 actor() { return actor; }
    public TargetSnapshot target() { return target; }
    public AuthRevision authRevision() { return dwp.authRevision(); }
    public PolicyRevision policyRevision() { return dwp.policyRevision(); }
    public ContextKey contextKey() { return dwp.contextKey(); }
    public DecisionRevision decisionRevision() { return dwp.decisionRevision(); }
    public String selectedScopeKey() { return dwp.selectedScopeKey(); }
    public PeopleRevision relationshipRevision() { return people.relationshipRevision(); }
    public PeopleRevision populationRevision() { return people.populationRevision(); }
    public PeopleRevision fieldPolicyRevision() { return people.fieldPolicyRevision(); }
    public PeopleRevision purposePolicyRevision() { return people.purposePolicyRevision(); }
    public PeopleRevision dynamicSodRevision() { return people.dynamicSodRevision(); }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
}
