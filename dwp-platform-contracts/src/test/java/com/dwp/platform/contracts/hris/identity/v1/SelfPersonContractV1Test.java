package com.dwp.platform.contracts.hris.identity.v1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Modifier;
import java.time.*;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;
import static org.assertj.core.api.Assertions.*;

/** Author structural fixtures. Current authority/Auth adapters are explicitly MOCK ONLY; no native PEP/transport. */
class SelfPersonContractV1Test {
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static final UUID PRINCIPAL = id(301), PERSON = id(302), FOREIGN = id(399);

    @Test
    void profileNeedsNoEmploymentTupleZoneTimOrWorkerPolicy() {
        Fixture f = new Fixture();
        var result = f.port().resolve();
        assertThat(result.person().personPublicId()).isEqualTo(PERSON);
        assertThat(result.person().personVersion()).isZero();
        assertThat(result.binding().userRowVersion()).isZero();
        assertThat(result.authority().effectivePolicy().allowedWorkerStatuses()).isNull();
        assertThat(result.authority().effectivePolicy().allowedAssignmentStatuses()).isEmpty();
        assertThat(result.verifiedAt()).isEqualTo(NOW);
        assertThat(f.seenQuery.purpose()).isEqualTo(SelfContextPurposeV1.SELF_PROFILE_READ);
        assertThat(f.seenQuery.asOf()).isEqualTo(NOW);
        assertThat(f.seenQuery.selector()).isNull();
        f.calls(1, 1, 1);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {0}")
    @MethodSource("rejections")
    void typedBoundaryRejectsWithoutIdentityFallback(Rejection r) {
        Fixture f = new Fixture();
        r.mutate.accept(f);
        assertThatThrownBy(() -> f.port().resolve()).isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                error -> assertThat(error.code()).isEqualTo(r.code));
        f.calls(r.verifierCalls, r.authCalls, r.personCalls);
    }

    static Stream<Rejection> rejections() {
        return Stream.of(
            reject("missing clock", f -> f.missingClock = true, ADAPTER_UNAVAILABLE, 0,0,0),
            reject("missing current verifier", f -> f.missingVerifier = true, ADAPTER_UNAVAILABLE, 0,0,0),
            reject("missing Auth provider", f -> f.missingAuth = true, ADAPTER_UNAVAILABLE, 0,0,0),
            reject("missing People provider", f -> f.missingPeople = true, ADAPTER_UNAVAILABLE, 0,0,0),
            reject("null clock instant", f -> f.clock.value = null, AUTHORITY_INVALID, 0,0,0),
            reject("null authority", f -> f.authority = null, AUTHORITY_INVALID, 1,0,0),
            reject("foreign purpose", f -> f.purpose = SelfContextPurposeV1.SELF_PAY_READ, AUTHORITY_INVALID, 1,0,0),
            reject("foreign audience", f -> f.audience = SelfContextPurposeV1.Audience.HRIS_PAY, AUTHORITY_INVALID, 1,0,0),
            reject("zero authority tenant", f -> f.authorityTenant = 0, AUTHORITY_INVALID, 1,0,0),
            reject("nil authority principal", f -> f.authorityPrincipal = new UUID(0,0), AUTHORITY_INVALID, 1,0,0),
            reject("negative expected row version", f -> f.expectedVersion = -1, AUTHORITY_INVALID, 1,0,0),
            reject("negative expected access revision", f -> f.expectedAccess = -1, AUTHORITY_INVALID, 1,0,0),
            reject("empty person policy", f -> f.allowedPersons = Set.of(), AUTHORITY_INVALID, 1,0,0),
            reject("APP absent", f -> f.entitled = false, APP_ENTITLEMENT_REQUIRED, 1,0,0),
            reject("SoD denied", f -> f.sod = false, SEPARATION_OF_DUTIES_DENIED, 1,0,0),
            reject("authority expired", f -> f.authorityExpiry = NOW, AUTHORITY_INVALID, 1,0,0),
            reject("authority too long", f -> f.authorityExpiry = NOW.plusSeconds(31), AUTHORITY_INVALID, 1,0,0),
            reject("null Auth binding", f -> f.binding = null, AUTH_BINDING_INVALID, 1,1,0),
            reject("foreign Auth tenant", f -> f.binding = binding(2,PRINCIPAL,PERSON,0,0,AuthPersonBindingV1.Status.ACTIVE,NOW,NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1,1,0),
            reject("principal UUID relabel", f -> f.binding = binding(1,FOREIGN,PERSON,0,0,AuthPersonBindingV1.Status.ACTIVE,NOW,NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1,1,0),
            reject("person cannot equal principal as fallback", f -> f.binding = binding(1,PRINCIPAL,PRINCIPAL,0,0,AuthPersonBindingV1.Status.ACTIVE,NOW,NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1,1,0),
            reject("null Auth person", f -> f.binding = binding(1,PRINCIPAL,null,0,0,AuthPersonBindingV1.Status.ACTIVE,NOW,NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1,1,0),
            reject("platform plane", f -> f.binding = new AuthPersonBindingV1(1,3,PRINCIPAL,PERSON,AuthPersonBindingV1.IdentityPlane.PLATFORM,AuthPersonBindingV1.Status.ACTIVE,0,0,NOW,NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1,1,0),
            reject("Auth revoked", f -> f.binding = binding(1,PRINCIPAL,PERSON,0,0,AuthPersonBindingV1.Status.SUSPENDED,NOW,NOW.plusSeconds(10)), AUTH_BINDING_REVOKED, 1,1,0),
            reject("Auth row version changed", f -> f.binding = binding(1,PRINCIPAL,PERSON,1,0,AuthPersonBindingV1.Status.ACTIVE,NOW,NOW.plusSeconds(10)), AUTH_BINDING_STALE, 1,1,0),
            reject("Auth access revision changed", f -> f.binding = binding(1,PRINCIPAL,PERSON,0,1,AuthPersonBindingV1.Status.ACTIVE,NOW,NOW.plusSeconds(10)), AUTH_BINDING_STALE, 1,1,0),
            reject("cached Auth capture", f -> f.binding = binding(1,PRINCIPAL,PERSON,0,0,AuthPersonBindingV1.Status.ACTIVE,NOW.minusNanos(1),NOW.plusSeconds(10)), AUTH_BINDING_STALE, 1,1,0),
            reject("expired Auth", f -> f.binding = binding(1,PRINCIPAL,PERSON,0,0,AuthPersonBindingV1.Status.ACTIVE,NOW.minusSeconds(1),NOW), AUTH_BINDING_STALE, 1,1,0),
            reject("null native person", f -> f.person = null, OWNER_RESPONSE_INVALID, 1,1,1),
            reject("foreign person tenant", f -> f.person = person(2,PERSON,0,NativeSelfContextSetV1.PersonState.ACTIVE,NOW,NOW.plusSeconds(10)), OWNER_RESPONSE_INVALID, 1,1,1),
            reject("native person cannot equal principal fallback", f -> f.person = person(1,PRINCIPAL,0,NativeSelfContextSetV1.PersonState.ACTIVE,NOW,NOW.plusSeconds(10)), OWNER_RESPONSE_INVALID, 1,1,1),
            reject("negative person revision", f -> f.person = person(1,PERSON,-1,NativeSelfContextSetV1.PersonState.ACTIVE,NOW,NOW.plusSeconds(10)), OWNER_RESPONSE_INVALID, 1,1,1),
            reject("null person state", f -> f.person = person(1,PERSON,0,null,NOW,NOW.plusSeconds(10)), OWNER_RESPONSE_INVALID, 1,1,1),
            reject("cached person capture", f -> f.person = person(1,PERSON,0,NativeSelfContextSetV1.PersonState.ACTIVE,NOW.minusNanos(1),NOW.plusSeconds(10)), OWNER_RESPONSE_INVALID, 1,1,1),
            reject("future person capture", f -> f.person = person(1,PERSON,0,NativeSelfContextSetV1.PersonState.ACTIVE,NOW.plusNanos(1),NOW.plusSeconds(10)), OWNER_RESPONSE_INVALID, 1,1,1),
            reject("person proof expired", f -> f.person = person(1,PERSON,0,NativeSelfContextSetV1.PersonState.ACTIVE,NOW.minusSeconds(1),NOW), OWNER_RESPONSE_INVALID, 1,1,1),
            reject("person proof exceeds Auth", f -> f.person = person(1,PERSON,0,NativeSelfContextSetV1.PersonState.ACTIVE,NOW,NOW.plusSeconds(11)), OWNER_RESPONSE_INVALID, 1,1,1),
            reject("inactive excluded by owner policy", f -> f.person = person(1,PERSON,0,NativeSelfContextSetV1.PersonState.INACTIVE,NOW,NOW.plusSeconds(10)), SELF_SCOPE_UNRESOLVED, 1,1,1),
            reject("authority expires in verifier", f -> f.afterVerifier = x -> x.clock.value = NOW.plusSeconds(10), AUTHORITY_INVALID, 1,0,0),
            reject("binding expires in Auth callback", f -> f.afterAuth = x -> x.clock.value = NOW.plusSeconds(10), AUTHORITY_INVALID, 1,1,0),
            reject("short binding expires in People", f -> { f.binding = binding(1,PRINCIPAL,PERSON,0,0,AuthPersonBindingV1.Status.ACTIVE,NOW,NOW.plusSeconds(1)); f.afterPeople = x -> x.clock.value = NOW.plusSeconds(1); }, AUTH_BINDING_STALE, 1,1,1),
            reject("clock regresses after People", f -> f.afterPeople = x -> x.clock.value = NOW.minusNanos(1), AUTHORITY_INVALID, 1,1,1)
        );
    }

    @Test
    void ownerCallsAdvanceClockWhileQueryAsOfStaysCapturedAndFreshProofsPass() {
        Fixture f = new Fixture();
        f.afterVerifier = x -> x.clock.value = NOW.plusSeconds(1);
        f.afterAuth = x -> { x.clock.value = NOW.plusSeconds(2); x.binding = binding(1,PRINCIPAL,PERSON,0,0,AuthPersonBindingV1.Status.ACTIVE,x.clock.value,NOW.plusSeconds(10)); };
        f.afterPeople = x -> { x.clock.value = NOW.plusSeconds(3); x.person = person(1,PERSON,0,NativeSelfContextSetV1.PersonState.ACTIVE,x.clock.value,NOW.plusSeconds(10)); };
        var result = f.port().resolve();
        assertThat(result.verifiedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(f.seenQuery.asOf()).isEqualTo(NOW);
        f.calls(1,1,1);
    }

    @Test
    void explicitInactivePersonPolicySupportsFormerPersonWithoutEmploymentPolicy() {
        Fixture f = new Fixture();
        f.allowedPersons = Set.of(NativeSelfContextSetV1.PersonState.INACTIVE);
        f.person = person(1,PERSON,7,NativeSelfContextSetV1.PersonState.INACTIVE,NOW,NOW.plusSeconds(10));
        assertThat(f.port().resolve().person().personVersion()).isEqualTo(7);
    }

    @Test
    void finalIssuanceClockCannotReturnAnExpiredSnapshot() {
        Fixture f = new Fixture();
        f.person = person(1,PERSON,0,NativeSelfContextSetV1.PersonState.ACTIVE,NOW,NOW.plusSeconds(1));
        f.clock.finalRead = NOW.plusSeconds(1);
        assertThatThrownBy(() -> f.port().resolve()).isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                error -> assertThat(error.code()).isEqualTo(OWNER_RESPONSE_INVALID));
        f.calls(1,1,1);
    }

    @Test
    void privateLookupsAndVerifiedResultHaveNoPublicCallerMintAndRequestHasNoCallerIds() {
        for (Class<?> type : List.of(SelfPersonOwnerPortsV1.PersonLookup.class,SelfPersonResolutionV1.class,GuardedSelfPersonPortV1.class)) {
            assertThat(Arrays.stream(type.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers()))).isTrue();
        }
        assertThat(SelfPersonPortV1.class.getDeclaredMethods()).singleElement().satisfies(m -> assertThat(m.getParameterCount()).isZero());
    }

    @Test
    void adapterExceptionIsRedactedWithoutRawCause() {
        Fixture f = new Fixture();
        f.afterPeople = x -> { throw new IllegalStateException("jdbc secret-password private data"); };
        assertThat(catchThrowable(() -> f.port().resolve())).hasMessage("Self-context contract rejected: OWNER_UNAVAILABLE").hasNoCause();
    }

    record Rejection(String name, Consumer<Fixture> mutate, SelfContextContractExceptionV1.Code code, int verifierCalls,int authCalls,int personCalls) {
        @Override public String toString() { return name; }
    }
    static Rejection reject(String n,Consumer<Fixture> m,SelfContextContractExceptionV1.Code c,int v,int a,int p) { return new Rejection(n,m,c,v,a,p); }

    static class Fixture {
        MutableClock clock = new MutableClock();
        long authorityTenant = 1, expectedVersion = 0, expectedAccess = 0;
        UUID authorityPrincipal = PRINCIPAL;
        SelfContextPurposeV1 purpose = SelfContextPurposeV1.SELF_PROFILE_READ;
        SelfContextPurposeV1.Audience audience = SelfContextPurposeV1.Audience.HRIS_HRM;
        boolean entitled = true,sod = true,missingClock,missingVerifier,missingAuth,missingPeople;
        Instant authorityExpiry = NOW.plusSeconds(10);
        Set<NativeSelfContextSetV1.PersonState> allowedPersons = Set.of(NativeSelfContextSetV1.PersonState.ACTIVE);
        SelfContextAuthorityV1 authority = authority();
        AuthPersonBindingV1 binding = binding(1,PRINCIPAL,PERSON,0,0,AuthPersonBindingV1.Status.ACTIVE,NOW,NOW.plusSeconds(10));
        NativeSelfPersonSnapshotV1 person = person(1,PERSON,0,NativeSelfContextSetV1.PersonState.ACTIVE,NOW,NOW.plusSeconds(10));
        SelfContextQueryV1 seenQuery;
        AtomicInteger verifierCalls = new AtomicInteger(),authCalls = new AtomicInteger(),personCalls = new AtomicInteger();
        Consumer<Fixture> afterVerifier = x -> {},afterAuth = x -> {},afterPeople = x -> {};
        SelfContextAuthorityV1 authority() {
            return new SelfContextAuthorityV1(authorityTenant,3,authorityPrincipal,PERSON,expectedVersion,expectedAccess,audience,purpose,
                    entitled,sod,0,NOW,authorityExpiry,new SelfContextAuthorityV1.EffectivePolicy("mock/current-person",0,allowedPersons,null,Set.of()));
        }
        SelfPersonPortV1 port() {
            return GuardedSelfPersonPortV1.guarded(missingClock ? null : clock,
                missingVerifier ? null : (query,aud,now) -> { verifierCalls.incrementAndGet(); seenQuery=query; assertThat(aud).isEqualTo(SelfContextPurposeV1.Audience.HRIS_HRM); afterVerifier.accept(this); return authority == null ? null : authority(); },
                missingAuth ? null : request -> { authCalls.incrementAndGet(); afterAuth.accept(this); return binding; },
                missingPeople ? null : request -> { personCalls.incrementAndGet(); afterPeople.accept(this); return person; });
        }
        void calls(int v,int a,int p) { assertThat(verifierCalls).hasValue(v); assertThat(authCalls).hasValue(a); assertThat(personCalls).hasValue(p); }
    }
    static class MutableClock extends Clock {
        Instant value = NOW,finalRead;
        int reads;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return ++reads == 5 && finalRead != null ? finalRead : value; }
    }
    static NativeSelfPersonSnapshotV1 person(long tenant,UUID person,long version,NativeSelfContextSetV1.PersonState state,Instant capture,Instant expiry) {
        return new NativeSelfPersonSnapshotV1(tenant,person,version,state,capture,expiry);
    }
    static AuthPersonBindingV1 binding(long tenant,UUID principal,UUID person,long version,long access,AuthPersonBindingV1.Status state,Instant capture,Instant expiry) {
        return new AuthPersonBindingV1(tenant,3,principal,person,AuthPersonBindingV1.IdentityPlane.TENANT,state,version,access,capture,expiry);
    }
    static UUID id(long n) { return new UUID(0x1000000000004000L,0x8000000000000000L|n); }
}
