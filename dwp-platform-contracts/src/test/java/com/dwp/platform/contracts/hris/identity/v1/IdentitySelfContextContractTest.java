package com.dwp.platform.contracts.hris.identity.v1;

import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;
import static org.assertj.core.api.Assertions.*;

class IdentitySelfContextContractTest {
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static final UUID PRINCIPAL = id(101), PERSON = id(102), WORKER = id(103), RELATIONSHIP = id(104), ASSIGNMENT = id(105);

    @Test
    void ownerCanBuildRawCarrierAndGuardMintsOneSelectedNativeContext() {
        Fixture fixture = new Fixture();
        SelfContextResolutionV1 result = fixture.port().resolve(fixture.query);
        assertThat(result.kind()).isEqualTo(SelfContextResolutionV1.Kind.SELECTED);
        assertThat(result.selected().binding()).isEqualTo(fixture.binding);
        assertThat(result.selected().authority().expectedUserRowVersion()).isEqualTo(7);
        assertThat(result.selected().authority().expectedAccessRevision()).isEqualTo(11);
        assertThat(result.selected().context().selector()).isEqualTo(new SelfContextSelectorV1(WORKER, RELATIONSHIP, ASSIGNMENT));
        assertThat(result.selected().personVersion()).isEqualTo(2);
        assertThat(result.selected().verifiedAt()).isEqualTo(NOW);
        assertThat(result.candidates()).isEmpty();
        fixture.assertCalls(1, 1, 1);
    }

    @Test
    void multipleContextsRequireExplicitNativeTupleAndNeverMintImplicitFirst() {
        Fixture fixture = new Fixture();
        var second = context(id(203), id(204), id(205));
        fixture.nativeSet = fixture.nativeWith(List.of(fixture.context, second));
        var unresolved = fixture.port().resolve(fixture.query);
        assertThat(unresolved.kind()).isEqualTo(SelfContextResolutionV1.Kind.SELECTION_REQUIRED);
        assertThat(unresolved.selected()).isNull();
        assertThat(unresolved.candidates()).containsExactly(fixture.context.selector(), second.selector());
        assertThatThrownBy(() -> unresolved.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
        fixture.query = new SelfContextQueryV1(fixture.query.purpose(), NOW, second.selector());
        assertThat(fixture.port().resolve(fixture.query).selected().context()).isEqualTo(second);
        fixture.assertCalls(2, 2, 2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejections")
    void actualTypedNegativeFixturesRejectWithExactCodeAndProviderCalls(Rejection rejection) {
        Fixture fixture = new Fixture();
        rejection.mutate().accept(fixture);
        assertThatThrownBy(() -> fixture.port().resolve(fixture.query))
                .isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                        error -> assertThat(error.code()).isEqualTo(rejection.code()));
        fixture.assertCalls(rejection.verifierCalls(), rejection.authCalls(), rejection.peopleCalls());
    }

    static Stream<Rejection> rejections() {
        return Stream.of(
                reject("zero native contexts", f -> f.nativeSet = f.nativeWith(List.of()), SELF_SCOPE_UNRESOLVED, 1, 1, 1),
                reject("foreign selected native tuple", f -> f.query = new SelfContextQueryV1(f.query.purpose(), NOW,
                        new SelfContextSelectorV1(WORKER, RELATIONSHIP, id(999))), SELF_CONTEXT_FORBIDDEN, 1, 1, 1),
                reject("foreign native tenant", f -> f.nativeSet = nativeSet(2, PERSON, NOW, true, List.of(f.context)), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("foreign native person", f -> f.nativeSet = nativeSet(1, id(999), NOW, true, List.of(f.context)), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("revoked Auth status", f -> f.binding = binding(1, 3, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.SUSPENDED, 7, 11, NOW, NOW.plusSeconds(10)), AUTH_BINDING_REVOKED, 1, 1, 0),
                reject("inactive Auth status", f -> f.binding = binding(1, 3, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.INACTIVE, 7, 11, NOW, NOW.plusSeconds(10)), AUTH_BINDING_REVOKED, 1, 1, 0),
                reject("expired authority", f -> f.authority = authority(PRINCIPAL, PERSON, 7, 11, true, true, NOW.minusSeconds(10), NOW), AUTHORITY_INVALID, 1, 0, 0),
                reject("future authority", f -> f.authority = authority(PRINCIPAL, PERSON, 7, 11, true, true, NOW.plusSeconds(1), NOW.plusSeconds(10)), AUTHORITY_INVALID, 1, 0, 0),
                reject("oversized authority lease", f -> f.authority = authority(PRINCIPAL, PERSON, 7, 11, true, true, NOW, NOW.plusSeconds(31)), AUTHORITY_INVALID, 1, 0, 0),
                reject("expired Auth", f -> f.binding = binding(1, 3, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.ACTIVE, 7, 11, NOW.minusSeconds(10), NOW), AUTH_BINDING_STALE, 1, 1, 0),
                reject("changed user rowVersion conservatively invalidates person stamp", f -> f.binding = binding(1, 3, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.ACTIVE, 8, 11, NOW, NOW.plusSeconds(10)), AUTH_BINDING_STALE, 1, 1, 0),
                reject("changed accessRevision independently invalidates access", f -> f.binding = binding(1, 3, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.ACTIVE, 7, 12, NOW, NOW.plusSeconds(10)), AUTH_BINDING_STALE, 1, 1, 0),
                reject("principal UUID relabel to user numeric UUID is not identity", f -> f.binding = binding(1, 3, id(3), PERSON, AuthPersonBindingV1.Status.ACTIVE, 7, 11, NOW, NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1, 1, 0),
                reject("Auth person mismatch", f -> f.binding = binding(1, 3, PRINCIPAL, id(999), AuthPersonBindingV1.Status.ACTIVE, 7, 11, NOW, NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1, 1, 0),
                reject("Auth tenant mismatch", f -> f.binding = binding(2, 3, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.ACTIVE, 7, 11, NOW, NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1, 1, 0),
                reject("Auth actor mismatch", f -> f.binding = binding(1, 4, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.ACTIVE, 7, 11, NOW, NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1, 1, 0),
                reject("missing verifier", f -> f.missingVerifier = true, ADAPTER_UNAVAILABLE, 0, 0, 0),
                reject("missing Auth adapter", f -> f.missingAuth = true, ADAPTER_UNAVAILABLE, 0, 0, 0),
                reject("missing People adapter", f -> f.missingPeople = true, ADAPTER_UNAVAILABLE, 0, 0, 0),
                reject("current APP entitlement missing", f -> f.authority = authority(PRINCIPAL, PERSON, 7, 11, false, true, NOW, NOW.plusSeconds(10)), APP_ENTITLEMENT_REQUIRED, 1, 0, 0),
                reject("SoD denied", f -> f.authority = authority(PRINCIPAL, PERSON, 7, 11, true, false, NOW, NOW.plusSeconds(10)), SEPARATION_OF_DUTIES_DENIED, 1, 0, 0),
                reject("configured purpose audience mismatch", f -> f.query = new SelfContextQueryV1(SelfContextPurposeV1.SELF_PAY_READ, NOW, null), QUERY_INVALID, 0, 0, 0),
                reject("native incomplete cannot pretend singleton", f -> f.nativeSet = nativeSet(1, PERSON, NOW, false, List.of(f.context)), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("duplicate native tuple", f -> f.nativeSet = f.nativeWith(List.of(f.context, f.context)), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("stale native asOf", f -> f.nativeSet = nativeSet(1, PERSON, NOW.minusSeconds(1), true, List.of(f.context)), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("future requested asOf", f -> f.query = new SelfContextQueryV1(f.query.purpose(), NOW.plusSeconds(1), null), QUERY_INVALID, 0, 0, 0),
                reject("asOf outside Java civil date range", f -> f.query = new SelfContextQueryV1(f.query.purpose(), Instant.MIN, null), QUERY_INVALID, 0, 0, 0),
                reject("native zone civil date overflow is typed owner rejection", f -> {
                    f.query = new SelfContextQueryV1(f.query.purpose(), LocalDate.MIN.atStartOfDay(ZoneOffset.UTC).toInstant(), null);
                    f.nativeSet = nativeSet(1, PERSON, f.query.asOf(), true, List.of(new NativeSelfContextSetV1.EmploymentContext(
                            f.context.worker(), new NativeSelfContextSetV1.WorkRelationship(RELATIONSHIP, WORKER, id(106), 4, LocalDate.MIN, null),
                            new NativeSelfContextSetV1.Assignment(ASSIGNMENT, RELATIONSHIP, 5, "ACTIVE", LocalDate.MIN, null, ZoneOffset.ofHours(-18)))));
                }, OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("null query", f -> f.query = null, QUERY_INVALID, 0, 0, 0),
                reject("nil selector UUID", f -> f.query = new SelfContextQueryV1(f.query.purpose(), NOW, new SelfContextSelectorV1(new UUID(0, 0), RELATIONSHIP, ASSIGNMENT)), QUERY_INVALID, 0, 0, 0),
                reject("UUID equality cannot substitute native relationship parent", f -> f.nativeSet = f.nativeWith(List.of(new NativeSelfContextSetV1.EmploymentContext(
                        new NativeSelfContextSetV1.Worker(PERSON, PERSON, 3, "ACTIVE"), f.context.relationship(), f.context.assignment()))), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("wrong assignment parent", f -> f.nativeSet = f.nativeWith(List.of(new NativeSelfContextSetV1.EmploymentContext(
                        f.context.worker(), f.context.relationship(), new NativeSelfContextSetV1.Assignment(ASSIGNMENT, id(999), 5, "ACTIVE", LocalDate.of(2020, 1, 1), null, ZoneOffset.UTC)))), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("negative worker version", f -> f.nativeSet = f.nativeWith(List.of(new NativeSelfContextSetV1.EmploymentContext(
                        new NativeSelfContextSetV1.Worker(WORKER, PERSON, -1, "ACTIVE"), f.context.relationship(), f.context.assignment()))), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("assignment no longer effective", f -> f.nativeSet = f.nativeWith(List.of(new NativeSelfContextSetV1.EmploymentContext(
                        f.context.worker(), f.context.relationship(), new NativeSelfContextSetV1.Assignment(ASSIGNMENT, RELATIONSHIP, 5, "ACTIVE", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 9, 13), ZoneOffset.UTC)))), SELF_SCOPE_UNRESOLVED, 1, 1, 1),
                reject("owner verifier unavailable no fallback", f -> f.verifierFailure = new IllegalStateException("private details"), OWNER_UNAVAILABLE, 1, 0, 0),
                reject("owner returns null Auth no fallback", f -> f.binding = null, AUTH_BINDING_INVALID, 1, 1, 0),
                reject("owner returns null native no fallback", f -> f.nativeSet = null, OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("foreign worker person despite outer person match", f -> f.nativeSet = f.nativeWith(List.of(new NativeSelfContextSetV1.EmploymentContext(
                        new NativeSelfContextSetV1.Worker(WORKER, id(999), 3, "ACTIVE"), f.context.relationship(), f.context.assignment()))), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("inactive native person not allowed by current policy", f -> f.nativeSet = new NativeSelfContextSetV1(1, PERSON, 2,
                        NativeSelfContextSetV1.PersonState.INACTIVE, NOW, true, List.of(f.context)), SELF_SCOPE_UNRESOLVED, 1, 1, 1),
                reject("platform principal cannot acquire tenant self context", f -> f.binding = new AuthPersonBindingV1(1, 3, PRINCIPAL, PERSON,
                        AuthPersonBindingV1.IdentityPlane.PLATFORM, AuthPersonBindingV1.Status.ACTIVE, 7, 11, NOW, NOW.plusSeconds(10)), AUTH_BINDING_INVALID, 1, 1, 0),
                reject("negative person version", f -> f.nativeSet = new NativeSelfContextSetV1(1, PERSON, -1,
                        NativeSelfContextSetV1.PersonState.ACTIVE, NOW, true, List.of(f.context)), OWNER_RESPONSE_INVALID, 1, 1, 1),
                reject("inverted relationship date interval", f -> f.nativeSet = f.nativeWith(List.of(new NativeSelfContextSetV1.EmploymentContext(f.context.worker(),
                        new NativeSelfContextSetV1.WorkRelationship(RELATIONSHIP, WORKER, id(106), 4, LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 13)), f.context.assignment()))), OWNER_RESPONSE_INVALID, 1, 1, 1)
        );
    }

    @ParameterizedTest(name = "UUID record consistency: {0}")
    @MethodSource("inconsistentContexts")
    void nativeUuidCannotBeReusedWithDifferentImmutableRecord(NativeSelfContextSetV1.EmploymentContext inconsistent) {
        Fixture fixture = new Fixture();
        fixture.nativeSet = fixture.nativeWith(List.of(fixture.context, inconsistent));
        assertThatThrownBy(() -> fixture.port().resolve(fixture.query))
                .isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                        error -> assertThat(error.code()).isEqualTo(OWNER_RESPONSE_INVALID));
        fixture.assertCalls(1, 1, 1);
    }

    static Stream<NativeSelfContextSetV1.EmploymentContext> inconsistentContexts() {
        var original = context(WORKER, RELATIONSHIP, ASSIGNMENT);
        var differentRelationship = context(WORKER, id(304), id(305));
        var differentWorker = context(id(303), RELATIONSHIP, id(305));
        return Stream.of(
                differentWorker,
                context(WORKER, id(304), ASSIGNMENT),
                new NativeSelfContextSetV1.EmploymentContext(new NativeSelfContextSetV1.Worker(WORKER, PERSON, 4, "ACTIVE"), differentRelationship.relationship(), differentRelationship.assignment()),
                new NativeSelfContextSetV1.EmploymentContext(new NativeSelfContextSetV1.Worker(WORKER, PERSON, 3, "LEAVE"), differentRelationship.relationship(), differentRelationship.assignment()),
                new NativeSelfContextSetV1.EmploymentContext(original.worker(),
                        new NativeSelfContextSetV1.WorkRelationship(RELATIONSHIP, WORKER, id(106), 5, LocalDate.of(2020, 1, 1), null),
                        context(WORKER, RELATIONSHIP, id(305)).assignment()),
                new NativeSelfContextSetV1.EmploymentContext(original.worker(),
                        new NativeSelfContextSetV1.WorkRelationship(RELATIONSHIP, WORKER, id(106), 4, LocalDate.of(2021, 1, 1), null),
                        context(WORKER, RELATIONSHIP, id(305)).assignment())
        );
    }

    @ParameterizedTest(name = "expiry during owner calls: {0}")
    @MethodSource("issuanceFailures")
    void issuanceRechecksCurrentClockAndBothLeases(IssuanceFailure failure) {
        Fixture fixture = new Fixture();
        AtomicReference<Instant> current = new AtomicReference<>(NOW);
        fixture.clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return current.get(); }
        };
        // Advance inside the owner call: this fixture exercises final issuance,
        // not the earlier post-verifier or post-Auth freshness checks.
        fixture.afterPeopleRead = () -> current.set(failure.finalNow());
        if (failure.shortAuthLease()) {
            fixture.binding = binding(1, 3, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.ACTIVE, 7, 11, NOW, NOW.plusSeconds(1));
        }
        assertThatThrownBy(() -> fixture.port().resolve(fixture.query))
                .isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                        error -> assertThat(error.code()).isEqualTo(failure.code()));
        fixture.assertCalls(1, 1, 1);
    }

    static Stream<IssuanceFailure> issuanceFailures() {
        return Stream.of(new IssuanceFailure(NOW.plusSeconds(10), false, AUTHORITY_INVALID),
                new IssuanceFailure(NOW.plusSeconds(2), true, AUTH_BINDING_STALE),
                new IssuanceFailure(NOW.minusSeconds(1), false, AUTHORITY_INVALID));
    }

    record IssuanceFailure(Instant finalNow, boolean shortAuthLease, SelfContextContractExceptionV1.Code code) {
    }

    static Clock advancingClock(Instant finalNow) {
        AtomicInteger calls = new AtomicInteger();
        return new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return calls.getAndIncrement() == 0 ? NOW : finalNow; }
        };
    }

    @Test
    void issuanceTimeIsCurrentButHistoricalAsOfRemainsOwnerBound() {
        Fixture fixture = new Fixture();
        fixture.clock = advancingClock(NOW.plusSeconds(1));
        var result = fixture.port().resolve(fixture.query);
        assertThat(result.selected().verifiedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(result.selected().authority().purpose()).isEqualTo(fixture.query.purpose());
        fixture.assertCalls(1, 1, 1);
    }

    @Test
    void formerPersonHistoricalPayrollPolicyIsExplicitAndNotGlobalActiveOnly() {
        Fixture fixture = new Fixture();
        fixture.audience = SelfContextPurposeV1.Audience.HRIS_PAY;
        fixture.query = new SelfContextQueryV1(SelfContextPurposeV1.SELF_PAY_READ, NOW.minusSeconds(86400), null);
        var a = fixture.authority;
        fixture.authority = new SelfContextAuthorityV1(a.tenantId(), a.userId(), a.principalPublicId(), a.personPublicId(),
                a.expectedUserRowVersion(), a.expectedAccessRevision(), fixture.audience, fixture.query.purpose(), true, true,
                a.permissionDecisionRevision(), a.issuedAt(), a.expiresAt(), new SelfContextAuthorityV1.EffectivePolicy(
                "tenant/former-payroll-policy", 2, Set.of(NativeSelfContextSetV1.PersonState.INACTIVE), Set.of("TERM"), Set.of("ENDED")));
        var c = fixture.context;
        fixture.nativeSet = new NativeSelfContextSetV1(1, PERSON, 2, NativeSelfContextSetV1.PersonState.INACTIVE,
                fixture.query.asOf(), true, List.of(new NativeSelfContextSetV1.EmploymentContext(
                new NativeSelfContextSetV1.Worker(WORKER, PERSON, 3, "TERM"), c.relationship(),
                new NativeSelfContextSetV1.Assignment(ASSIGNMENT, RELATIONSHIP, 5, "ENDED", LocalDate.of(2020, 1, 1), LocalDate.of(2026, 9, 13), ZoneId.of("Asia/Seoul")))));
        assertThat(fixture.port().resolve(fixture.query).selected().context().worker().status()).isEqualTo("TERM");
        fixture.assertCalls(1, 1, 1);
    }

    @Test
    void existingVersionAndAccessRevisionMayAdvanceTogetherWhenCurrentExpectedOwnerStampsMatch() {
        Fixture fixture = new Fixture();
        fixture.authority = authority(PRINCIPAL, PERSON, 8, 12, true, true, NOW, NOW.plusSeconds(10));
        fixture.binding = binding(1, 3, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.ACTIVE, 8, 12, NOW, NOW.plusSeconds(10));
        assertThat(fixture.port().resolve(fixture.query).selected().binding().userRowVersion()).isEqualTo(8);
    }

    @Test
    void rawCarriersDefensivelyCopyAndOnlyGuardCanConstructLookupOrVerifiedSelection() {
        Fixture fixture = new Fixture();
        List<NativeSelfContextSetV1.EmploymentContext> mutable = new ArrayList<>(List.of(fixture.context));
        fixture.nativeSet = fixture.nativeWith(mutable);
        mutable.clear();
        assertThat(fixture.port().resolve(fixture.query).selected()).isNotNull();
        for (Class<?> guarded : List.of(SelfContextOwnerPortsV1.AuthLookup.class, SelfContextOwnerPortsV1.PeopleLookup.class,
                SelfContextResolutionV1.class, SelfContextResolutionV1.VerifiedSelfContext.class, GuardedSelfContextPortV1.class)) {
            assertThat(Arrays.stream(guarded.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers()))).isTrue();
        }
        assertThat(Arrays.stream(SelfContextQueryV1.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("purpose", "asOf", "selector");
    }

    @Test
    void clockRecapturedAfterVerifierAuthAndPeopleWithValidatedBinding() {
        Fixture fixture = new Fixture();
        AtomicInteger captures = new AtomicInteger();
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { captures.incrementAndGet(); return NOW; }
        };
        var port = GuardedSelfContextPortV1.guarded(SelfContextPurposeV1.Audience.HRIS_HRM, clock,
                (q, audience, now) -> fixture.authority,
                request -> { assertThat(request.authority()).isSameAs(fixture.authority); return fixture.binding; },
                request -> {
                    assertThat(request.binding()).isSameAs(fixture.binding);
                    assertThat(request.capturedNow()).isEqualTo(NOW);
                    assertThat(request.query()).isSameAs(fixture.query);
                    return fixture.nativeSet;
                });
        assertThat(port.resolve(fixture.query).selected()).isNotNull();
        assertThat(captures).hasValue(4);
    }

    @Test
    void optionalNativeLeaveStatusIsPurposePolicyNotGlobalAuthSuspensionMapping() {
        Fixture fixture = new Fixture();
        var a = fixture.authority;
        fixture.authority = new SelfContextAuthorityV1(a.tenantId(), a.userId(), a.principalPublicId(), a.personPublicId(),
                a.expectedUserRowVersion(), a.expectedAccessRevision(), a.audience(), a.purpose(), true, true,
                a.permissionDecisionRevision(), a.issuedAt(), a.expiresAt(),
                new SelfContextAuthorityV1.EffectivePolicy("tenant/self-profile-policy", 2,
                        Set.of(NativeSelfContextSetV1.PersonState.ACTIVE), Set.of("ACTIVE", "LEAVE"), Set.of("ACTIVE")));
        fixture.nativeSet = fixture.nativeWith(List.of(new NativeSelfContextSetV1.EmploymentContext(
                new NativeSelfContextSetV1.Worker(WORKER, PERSON, 3, "LEAVE"), fixture.context.relationship(), fixture.context.assignment())));
        assertThat(fixture.port().resolve(fixture.query).selected().binding().status()).isEqualTo(AuthPersonBindingV1.Status.ACTIVE);
    }

    private static Rejection reject(String name, Consumer<Fixture> mutation, SelfContextContractExceptionV1.Code code,
                                    int verifierCalls, int authCalls, int peopleCalls) {
        return new Rejection(name, mutation, code, verifierCalls, authCalls, peopleCalls);
    }

    record Rejection(String name, Consumer<Fixture> mutate, SelfContextContractExceptionV1.Code code,
                     int verifierCalls, int authCalls, int peopleCalls) {
        @Override public String toString() { return name; }
    }

    static class Fixture {
        SelfContextQueryV1 query = new SelfContextQueryV1(SelfContextPurposeV1.SELF_PROFILE_READ, NOW, null);
        SelfContextAuthorityV1 authority = authority(PRINCIPAL, PERSON, 7, 11, true, true, NOW, NOW.plusSeconds(10));
        AuthPersonBindingV1 binding = binding(1, 3, PRINCIPAL, PERSON, AuthPersonBindingV1.Status.ACTIVE, 7, 11, NOW, NOW.plusSeconds(10));
        NativeSelfContextSetV1.EmploymentContext context = context(WORKER, RELATIONSHIP, ASSIGNMENT);
        NativeSelfContextSetV1 nativeSet = nativeWith(List.of(context));
        boolean missingVerifier, missingAuth, missingPeople;
        RuntimeException verifierFailure;
        Runnable afterPeopleRead = () -> { };
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SelfContextPurposeV1.Audience audience = SelfContextPurposeV1.Audience.HRIS_HRM;
        AtomicInteger verifierCalls = new AtomicInteger(), authCalls = new AtomicInteger(), peopleCalls = new AtomicInteger();

        SelfContextPortV1 port() {
            return GuardedSelfContextPortV1.guarded(audience, clock,
                    missingVerifier ? null : (q, audience, now) -> { verifierCalls.incrementAndGet(); if (verifierFailure != null) throw verifierFailure; return authority; },
                    missingAuth ? null : request -> { authCalls.incrementAndGet(); return binding; },
                    missingPeople ? null : request -> { peopleCalls.incrementAndGet(); afterPeopleRead.run(); return nativeSet; });
        }

        NativeSelfContextSetV1 nativeWith(List<NativeSelfContextSetV1.EmploymentContext> contexts) {
            return nativeSet(1, PERSON, NOW, true, contexts);
        }

        void assertCalls(int verifier, int auth, int people) {
            assertThat(verifierCalls).hasValue(verifier);
            assertThat(authCalls).hasValue(auth);
            assertThat(peopleCalls).hasValue(people);
        }
    }

    static SelfContextAuthorityV1 authority(UUID principal, UUID person, long version, long access,
                                           boolean entitled, boolean sod, Instant issued, Instant expires) {
        return new SelfContextAuthorityV1(1, 3, principal, person, version, access, SelfContextPurposeV1.Audience.HRIS_HRM,
                SelfContextPurposeV1.SELF_PROFILE_READ, entitled, sod, 9, issued, expires,
                new SelfContextAuthorityV1.EffectivePolicy("tenant/self-profile-policy", 1,
                        Set.of(NativeSelfContextSetV1.PersonState.ACTIVE), Set.of("ACTIVE"), Set.of("ACTIVE")));
    }

    static AuthPersonBindingV1 binding(long tenant, long user, UUID principal, UUID person,
                                       AuthPersonBindingV1.Status status, long version, long access,
                                       Instant captured, Instant expires) {
        return new AuthPersonBindingV1(tenant, user, principal, person, AuthPersonBindingV1.IdentityPlane.TENANT,
                status, version, access, captured, expires);
    }

    static NativeSelfContextSetV1 nativeSet(long tenant, UUID person, Instant asOf, boolean complete,
                                            List<NativeSelfContextSetV1.EmploymentContext> contexts) {
        return new NativeSelfContextSetV1(tenant, person, 2, NativeSelfContextSetV1.PersonState.ACTIVE, asOf, complete, contexts);
    }

    static NativeSelfContextSetV1.EmploymentContext context(UUID worker, UUID relationship, UUID assignment) {
        return new NativeSelfContextSetV1.EmploymentContext(new NativeSelfContextSetV1.Worker(worker, PERSON, 3, "ACTIVE"),
                new NativeSelfContextSetV1.WorkRelationship(relationship, worker, id(106), 4, LocalDate.of(2020, 1, 1), null),
                new NativeSelfContextSetV1.Assignment(assignment, relationship, 5, "ACTIVE", LocalDate.of(2020, 1, 1), null, ZoneId.of("Asia/Seoul")));
    }

    static UUID id(long value) { return new UUID(0x1000000000004000L, 0x8000000000000000L | value); }
}
