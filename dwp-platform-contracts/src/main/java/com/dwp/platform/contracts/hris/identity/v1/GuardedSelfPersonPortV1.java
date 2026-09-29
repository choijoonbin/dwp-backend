package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;

/** Unwired current self-person pilot. Real current PEP, signed transport and lifecycle fencing remain OPEN. */
public final class GuardedSelfPersonPortV1 implements SelfPersonPortV1 {
    private static final Duration MAX_LEASE = Duration.ofSeconds(30);
    private final Clock clock;
    private final SelfContextOwnerPortsV1.AuthorityVerifier verifier;
    private final SelfContextOwnerPortsV1.AuthBindingProvider auth;
    private final SelfPersonOwnerPortsV1.PersonSnapshotProvider people;

    private GuardedSelfPersonPortV1(Clock clock, SelfContextOwnerPortsV1.AuthorityVerifier verifier,
                                     SelfContextOwnerPortsV1.AuthBindingProvider auth,
                                     SelfPersonOwnerPortsV1.PersonSnapshotProvider people) {
        this.clock = clock;
        this.verifier = verifier;
        this.auth = auth;
        this.people = people;
    }

    /** Trusted owner composition only. Public raw carriers cannot themselves certify current authentication. */
    public static SelfPersonPortV1 guarded(Clock clock, SelfContextOwnerPortsV1.AuthorityVerifier verifier,
                                           SelfContextOwnerPortsV1.AuthBindingProvider auth,
                                           SelfPersonOwnerPortsV1.PersonSnapshotProvider people) {
        return new GuardedSelfPersonPortV1(clock, verifier, auth, people);
    }

    @Override
    public SelfPersonResolutionV1 resolve() {
        require(clock != null && verifier != null && auth != null && people != null, ADAPTER_UNAVAILABLE);
        Instant capturedNow = currentInstant(null);
        var query = new SelfContextQueryV1(SelfContextPurposeV1.SELF_PROFILE_READ, capturedNow, null);
        var authority = ownerCall(() -> verifier.verifyCurrent(query, SelfContextPurposeV1.Audience.HRIS_HRM, capturedNow));
        Instant authorityNow = currentInstant(capturedNow);
        checkAuthority(authority, authorityNow);
        var binding = ownerCall(() -> auth.loadCurrent(SelfContextOwnerPortsV1.authLookup(authority, authorityNow)));
        Instant authNow = currentInstant(authorityNow);
        checkAuthority(authority, authNow);
        checkBinding(binding, authority, authorityNow, authNow);
        var person = ownerCall(() -> people.loadCurrent(SelfPersonOwnerPortsV1.lookup(binding, authority, authNow)));
        Instant personNow = currentInstant(authNow);
        checkAuthority(authority, personNow);
        checkBinding(binding, authority, authorityNow, personNow);
        checkPerson(person, binding, authority, authNow, personNow);
        Instant issuanceNow = currentInstant(personNow);
        checkAuthority(authority, issuanceNow);
        checkBinding(binding, authority, authorityNow, issuanceNow);
        checkPerson(person, binding, authority, authNow, issuanceNow);
        return SelfPersonResolutionV1.verified(binding, authority, person, issuanceNow);
    }

    private void checkAuthority(SelfContextAuthorityV1 value, Instant now) {
        require(value != null && value.tenantId() > 0 && value.userId() > 0
                && uuid(value.principalPublicId()) && uuid(value.personPublicId())
                && value.expectedUserRowVersion() >= 0 && value.expectedAccessRevision() >= 0
                && value.permissionDecisionRevision() >= 0
                && value.audience() == SelfContextPurposeV1.Audience.HRIS_HRM
                && value.purpose() == SelfContextPurposeV1.SELF_PROFILE_READ
                && lease(value.issuedAt(), value.expiresAt(), now) && personPolicy(value.effectivePolicy()), AUTHORITY_INVALID);
        require(value.appEntitled(), APP_ENTITLEMENT_REQUIRED);
        require(value.separationOfDutiesAllowed(), SEPARATION_OF_DUTIES_DENIED);
    }

    private void checkBinding(AuthPersonBindingV1 value, SelfContextAuthorityV1 authority, Instant lookupNow, Instant now) {
        require(value != null && value.tenantId() == authority.tenantId() && value.userId() == authority.userId()
                && authority.principalPublicId().equals(value.principalPublicId())
                && authority.personPublicId().equals(value.personPublicId())
                && value.identityPlane() == AuthPersonBindingV1.IdentityPlane.TENANT
                && value.status() != null && value.userRowVersion() >= 0 && value.accessRevision() >= 0,
                AUTH_BINDING_INVALID);
        require(value.status() == AuthPersonBindingV1.Status.ACTIVE, AUTH_BINDING_REVOKED);
        require(value.userRowVersion() == authority.expectedUserRowVersion()
                && value.accessRevision() == authority.expectedAccessRevision()
                && lease(value.capturedAt(), value.expiresAt(), now)
                && !value.capturedAt().isBefore(lookupNow)
                && !value.expiresAt().isAfter(authority.expiresAt()), AUTH_BINDING_STALE);
    }

    private void checkPerson(NativeSelfPersonSnapshotV1 value, AuthPersonBindingV1 binding,
                               SelfContextAuthorityV1 authority, Instant lookupNow, Instant now) {
        require(value != null && value.tenantId() == binding.tenantId()
                && binding.personPublicId().equals(value.personPublicId()) && uuid(value.personPublicId())
                && value.personVersion() >= 0 && value.personState() != null
                && lease(value.capturedAt(), value.expiresAt(), now)
                && !value.capturedAt().isBefore(lookupNow)
                && !value.expiresAt().isAfter(binding.expiresAt())
                && !value.expiresAt().isAfter(authority.expiresAt()), OWNER_RESPONSE_INVALID);
        require(authority.effectivePolicy().allowedPersonStates().contains(value.personState()), SELF_SCOPE_UNRESOLVED);
    }

    private boolean personPolicy(SelfContextAuthorityV1.EffectivePolicy policy) {
        return policy != null && policy.reference() != null
                && policy.reference().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}") && policy.version() >= 0
                && policy.allowedPersonStates() != null && !policy.allowedPersonStates().isEmpty();
    }

    private boolean lease(Instant captured, Instant expires, Instant now) {
        return captured != null && expires != null && !captured.isAfter(now) && expires.isAfter(now)
                && expires.isAfter(captured) && !captured.isBefore(now.minus(MAX_LEASE))
                && !expires.isAfter(captured.plus(MAX_LEASE));
    }

    private boolean uuid(UUID value) {
        return value != null && (value.getMostSignificantBits() != 0 || value.getLeastSignificantBits() != 0);
    }

    private Instant currentInstant(Instant previous) {
        Instant value;
        try {
            value = clock.instant();
        } catch (RuntimeException invalidClock) {
            throw new SelfContextContractExceptionV1(AUTHORITY_INVALID);
        }
        require(value != null && (previous == null || !value.isBefore(previous)), AUTHORITY_INVALID);
        return value;
    }

    private <T> T ownerCall(Supplier<T> call) {
        try {
            return call.get();
        } catch (SelfContextContractExceptionV1 rejected) {
            throw rejected;
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new SelfContextContractExceptionV1(OWNER_RESPONSE_INVALID);
        } catch (RuntimeException unavailable) {
            throw new SelfContextContractExceptionV1(OWNER_UNAVAILABLE);
        }
    }

    private void require(boolean condition, SelfContextContractExceptionV1.Code code) {
        if (!condition) throw new SelfContextContractExceptionV1(code);
    }
}
