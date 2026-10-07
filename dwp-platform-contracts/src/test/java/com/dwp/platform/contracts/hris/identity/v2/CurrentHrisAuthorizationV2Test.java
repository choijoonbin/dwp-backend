package com.dwp.platform.contracts.hris.identity.v2;

import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.*;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationPortsV2.*;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationExceptionV2.Code.*;
import static org.junit.jupiter.api.Assertions.*;

/** EXPLICIT MOCK ONLY. No native Auth/People provider, Gateway transport or production PEP. */
class CurrentHrisAuthorizationV2Test {
    private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
    private enum Positive { SELF_PERSON, NONSELF_PERSON_WITHOUT_TARGET_AUTH,
        SELF_EMPLOYMENT, NONSELF_EMPLOYMENT_ACTOR_WITHOUT_PERSON, ZERO_NATIVE_VERSIONS, PROGRESSING_CLOCK }
    @ParameterizedTest(name = "{index} positive={0}") @EnumSource(Positive.class)
    void typedCurrentCompositionPreservesNativeStampsAndOpaqueRevisions(Positive kind) {
        Fixture f = new Fixture();
        if (kind == Positive.NONSELF_PERSON_WITHOUT_TARGET_AUTH) f.selection = Selection.TARGET_POPULATION;
        if (kind == Positive.SELF_EMPLOYMENT || kind == Positive.NONSELF_EMPLOYMENT_ACTOR_WITHOUT_PERSON) f.kind = TargetKind.EMPLOYMENT;
        if (kind == Positive.NONSELF_EMPLOYMENT_ACTOR_WITHOUT_PERSON) {
            f.selection = Selection.TARGET_POPULATION; f.actorPerson = null;
        }
        if (kind == Positive.ZERO_NATIVE_VERSIONS) { f.zeroVersions = true; f.kind = TargetKind.EMPLOYMENT; }
        if (kind == Positive.PROGRESSING_CLOCK) f.progress = true;
        var result = f.guard().authorize(f.selector());
        assertEquals(f.principal, result.actor().principalPublicId());
        assertEquals(f.actorPerson, result.actor().personPublicId());
        assertEquals(f.zeroVersions ? 0 : 7, result.actor().userRowVersion());
        assertEquals(f.zeroVersions ? 0 : 3, result.actor().accessRevision());
        assertEquals(f.targetPerson(), result.target().personPublicId());
        assertEquals(f.zeroVersions ? 0 : 11, result.target().personVersion());
        assertEquals("auth-" + "a".repeat(64), result.authRevision().value());
        assertEquals("policy-release-candidate.v3-9-" + "b".repeat(64), result.policyRevision().value());
        assertEquals("psc-" + "c".repeat(64), result.contextKey().value());
        assertEquals("psr-" + "d".repeat(64), result.decisionRevision().value());
        assertEquals("people-population:native.v2:opaque-ref", result.populationRevision().value());
        assertEquals(f.requirements(), result.requirements());
        if (f.kind == TargetKind.PERSON) {
            assertNull(result.target().workerPublicId());
            assertNull(result.target().assignmentVersion());
        } else {
            assertEquals(f.worker, result.target().workerPublicId());
            assertEquals(f.relationship, result.target().workRelationshipPublicId());
            assertEquals(f.assignment, result.target().assignmentPublicId());
            assertEquals(f.zeroVersions ? 0L : 23L, result.target().workerVersion().longValue());
            assertEquals(f.zeroVersions ? 0L : 29L, result.target().workRelationshipVersion().longValue());
            assertEquals(f.zeroVersions ? 0L : 31L, result.target().assignmentVersion().longValue());
        }
        if (f.selection == Selection.TARGET_POPULATION) assertNotEquals(f.actorPerson, result.target().personPublicId());
        assertEquals(f.time.get(), result.issuedAt());
        assertTrue(result.expiresAt().isAfter(result.issuedAt()));
        f.calls(1, 2, 2);
    }

    private enum Negative {
        MISSING_INVOCATION, MISSING_DWP, MISSING_PEOPLE, MISSING_CLOCK,
        NULL_INVOCATION, WRONG_OPERATION, INVALID_USER, NULL_PRINCIPAL,
        UNBOUND_PERSON_UUID_EQUALITY_FALLBACK, WRONG_NATIVE_PERSON, USER_VERSION_CHANGED, ACCESS_REVISION_CHANGED,
        REVOKED_ACTOR, PLATFORM_ACTOR, INVALID_AUTH_REVISION, INVALID_POLICY_REVISION,
        INVALID_CONTEXT_REVISION, INVALID_DECISION_REVISION,
        APP_MISSING, PERMISSION_MISSING, DUTY_MISSING, STATIC_SOD_DENIED,
        POPULATION_DENIED, PURPOSE_DENIED, FIELD_DENIED, DYNAMIC_SOD_DENIED,
        FOREIGN_TARGET_TENANT, WRONG_TARGET_PERSON, WRONG_EMPLOYMENT_PARENT, PERSON_HAS_EMPLOYMENT,
        SCOPE_MISMATCH, WRONG_PEOPLE_REVISION_KIND, AUTH_REVISION_CHANGED,
        POLICY_POINTER_CHANGED, CONTEXT_CHANGED, DECISION_CHANGED,
        TARGET_PERSON_VERSION_CHANGED, TARGET_WORKER_VERSION_CHANGED,
        TARGET_RELATIONSHIP_VERSION_CHANGED, TARGET_ASSIGNMENT_VERSION_CHANGED,
        FIELD_POLICY_CHANGED, PURPOSE_POLICY_CHANGED, DYNAMIC_SOD_POLICY_CHANGED,
        INVOCATION_EXPIRED, DWP_EXPIRED, PEOPLE_EXPIRED, FIRST_PROOF_EXPIRES_DURING_REFETCH,
        STALE_AUTH_CAPTURE, BACKWARDS_CLOCK, NULL_CLOCK, THROWING_CLOCK, OWNER_SECRET, TYPED_OWNER_CAUSE
    }
    @ParameterizedTest(name = "{index} negative={0}") @EnumSource(Negative.class)
    void exactTypedRejectAndProviderCallCounts(Negative negative) {
        Fixture f = new Fixture(); f.negative = negative;
        if (Set.of(Negative.WRONG_EMPLOYMENT_PARENT, Negative.TARGET_WORKER_VERSION_CHANGED,
                Negative.TARGET_RELATIONSHIP_VERSION_CHANGED, Negative.TARGET_ASSIGNMENT_VERSION_CHANGED).contains(negative)) {
            f.kind = TargetKind.EMPLOYMENT;
        }
        if (negative == Negative.UNBOUND_PERSON_UUID_EQUALITY_FALLBACK) f.actorPerson = null;
        var failure = assertThrows(CurrentHrisAuthorizationExceptionV2.class, () -> f.guard().authorize(f.selector()));
        assertEquals(expectedCode(negative), failure.code());
        assertEquals("current HRIS authorization rejected or unavailable", failure.getMessage());
        assertNull(failure.getCause());
        int[] calls = expectedCalls(negative); f.calls(calls[0], calls[1], calls[2]);
    }
    private static CurrentHrisAuthorizationExceptionV2.Code expectedCode(Negative n) {
        return switch (n) {
            case MISSING_INVOCATION, MISSING_DWP, MISSING_PEOPLE, MISSING_CLOCK -> MISSING_ADAPTER;
            case NULL_INVOCATION, WRONG_OPERATION, INVALID_USER, NULL_PRINCIPAL -> INVOCATION_INVALID;
            case UNBOUND_PERSON_UUID_EQUALITY_FALLBACK, FOREIGN_TARGET_TENANT, WRONG_TARGET_PERSON,
                    WRONG_EMPLOYMENT_PARENT, PERSON_HAS_EMPLOYMENT -> TARGET_INVALID;
            case APP_MISSING -> APP_DENIED;
            case PERMISSION_MISSING -> PERMISSION_DENIED;
            case DUTY_MISSING -> DUTY_DENIED;
            case STATIC_SOD_DENIED, DYNAMIC_SOD_DENIED -> SOD_DENIED;
            case POPULATION_DENIED, SCOPE_MISMATCH -> SCOPE_DENIED;
            case PURPOSE_DENIED -> PURPOSE_DENIED;
            case FIELD_DENIED -> FIELD_DENIED;
            case AUTH_REVISION_CHANGED, POLICY_POINTER_CHANGED, CONTEXT_CHANGED, DECISION_CHANGED -> AUTHORITY_CHANGED;
            case TARGET_PERSON_VERSION_CHANGED, TARGET_WORKER_VERSION_CHANGED, TARGET_RELATIONSHIP_VERSION_CHANGED,
                    TARGET_ASSIGNMENT_VERSION_CHANGED, FIELD_POLICY_CHANGED, PURPOSE_POLICY_CHANGED,
                    DYNAMIC_SOD_POLICY_CHANGED -> TARGET_CHANGED;
            case INVOCATION_EXPIRED, DWP_EXPIRED, PEOPLE_EXPIRED,
                    FIRST_PROOF_EXPIRES_DURING_REFETCH, STALE_AUTH_CAPTURE -> AUTHORITY_STALE;
            case BACKWARDS_CLOCK, NULL_CLOCK, THROWING_CLOCK -> CLOCK_INVALID;
            case OWNER_SECRET, TYPED_OWNER_CAUSE -> OWNER_UNAVAILABLE;
            default -> AUTHORITY_MISMATCH;
        };
    }
    private static int[] expectedCalls(Negative n) {
        return switch (n) {
            case MISSING_INVOCATION, MISSING_DWP, MISSING_PEOPLE, MISSING_CLOCK, NULL_CLOCK, THROWING_CLOCK -> new int[]{0,0,0};
            case NULL_INVOCATION, WRONG_OPERATION, INVALID_USER, NULL_PRINCIPAL,
                    UNBOUND_PERSON_UUID_EQUALITY_FALLBACK, INVOCATION_EXPIRED -> new int[]{1,0,0};
            case WRONG_NATIVE_PERSON, USER_VERSION_CHANGED, ACCESS_REVISION_CHANGED, REVOKED_ACTOR, PLATFORM_ACTOR,
                    INVALID_AUTH_REVISION, INVALID_POLICY_REVISION, INVALID_CONTEXT_REVISION, INVALID_DECISION_REVISION,
                    APP_MISSING, PERMISSION_MISSING, DUTY_MISSING, STATIC_SOD_DENIED, DWP_EXPIRED, STALE_AUTH_CAPTURE -> new int[]{1,1,0};
            case AUTH_REVISION_CHANGED, POLICY_POINTER_CHANGED, CONTEXT_CHANGED, DECISION_CHANGED -> new int[]{1,2,1};
            case TARGET_PERSON_VERSION_CHANGED, TARGET_WORKER_VERSION_CHANGED, TARGET_RELATIONSHIP_VERSION_CHANGED,
                    TARGET_ASSIGNMENT_VERSION_CHANGED, FIELD_POLICY_CHANGED, PURPOSE_POLICY_CHANGED,
                    DYNAMIC_SOD_POLICY_CHANGED, FIRST_PROOF_EXPIRES_DURING_REFETCH -> new int[]{1,2,2};
            default -> new int[]{1,1,1};
        };
    }

    @Test void publicApiNeverAcceptsRawAuthorityAndVerifiedConstructionIsPrivate() {
        assertTrue(Arrays.stream(VerifiedCurrentHrisAuthorizationV2.class.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers())));
        assertTrue(Arrays.stream(DwpLookup.class.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers())));
        assertTrue(Arrays.stream(PeopleLookup.class.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers())));
        var authorize = Arrays.stream(GuardedCurrentHrisAuthorizationPortV2.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && m.getName().equals("authorize")).toList();
        assertEquals(1, authorize.size());
        assertArrayEquals(new Class<?>[]{TargetSelector.class}, authorize.getFirst().getParameterTypes());
    }

    private static final class Fixture {
        final UUID principal = UUID.randomUUID(), person = UUID.randomUUID(), foreignPerson = UUID.randomUUID();
        final UUID worker = UUID.randomUUID(), relationship = UUID.randomUUID(), assignment = UUID.randomUUID();
        final AtomicInteger invocationCalls = new AtomicInteger(), dwpCalls = new AtomicInteger(), peopleCalls = new AtomicInteger();
        final AtomicReference<Instant> time = new AtomicReference<>(NOW);
        UUID actorPerson = person;
        TargetKind kind = TargetKind.PERSON;
        Selection selection = Selection.SELF;
        Negative negative;
        boolean zeroVersions, progress;
        final Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() {
                if (negative == Negative.NULL_CLOCK) return null;
                if (negative == Negative.THROWING_CLOCK) throw new IllegalStateException("synthetic-private-clock-sentinel");
                return time.get();
            }
        };
        Requirements requirements() {
            return new Requirements("candidate.hris.current.read", "hcm", "hcm.personal",
                    "candidate.route.hris.current.read", "SELF_PROFILE_READ", "HRIS_HRM", Plane.WORK,
                    AccessMode.NORMAL, selection, kind, Set.of("APP.HRIS:VIEW"),
                    Set.of("CANDIDATE_CURRENT_DUTY"), Set.of("person.publicId", "person.version"), false);
        }
        UUID targetPerson() { return selection == Selection.SELF ? actorPerson : foreignPerson; }
        TargetSelector selector() {
            if (negative == Negative.UNBOUND_PERSON_UUID_EQUALITY_FALLBACK) return new TargetSelector(kind, principal, null, null, null);
            if (kind == TargetKind.PERSON && selection == Selection.SELF) return null;
            return new TargetSelector(kind, targetPerson(), kind == TargetKind.EMPLOYMENT ? worker : null,
                    kind == TargetKind.EMPLOYMENT ? relationship : null, kind == TargetKind.EMPLOYMENT ? assignment : null);
        }
        void advance() { if (progress) time.set(time.get().plusSeconds(1)); }
        GuardedCurrentHrisAuthorizationPortV2 guard() {
            return new GuardedCurrentHrisAuthorizationPortV2(requirements(),
                    negative == Negative.MISSING_INVOCATION ? null : this::invocation,
                    negative == Negative.MISSING_DWP ? null : this::dwp,
                    negative == Negative.MISSING_PEOPLE ? null : this::people,
                    negative == Negative.MISSING_CLOCK ? null : clock);
        }
        Invocation invocation() {
            invocationCalls.incrementAndGet(); advance();
            if (negative == Negative.NULL_INVOCATION) return null;
            return new Invocation(41, negative == Negative.INVALID_USER ? 0 : 9,
                    negative == Negative.NULL_PRINCIPAL ? null : principal, actorPerson,
                    zeroVersions ? 0 : 7, zeroVersions ? 0 : 3,
                    negative == Negative.WRONG_OPERATION ? "foreign.operation" : requirements().operationId(),
                    "verified-session:opaque.native-source", time.get(),
                    negative == Negative.INVOCATION_EXPIRED ? time.get() : time.get().plusSeconds(10));
        }
        DwpAuthoritySnapshot dwp(DwpLookup lookup) {
            int call = dwpCalls.incrementAndGet(); advance();
            var actor = new AuthPersonBindingV1(41,9,principal,
                    negative == Negative.WRONG_NATIVE_PERSON ? foreignPerson : actorPerson,
                    negative == Negative.PLATFORM_ACTOR ? AuthPersonBindingV1.IdentityPlane.PLATFORM : AuthPersonBindingV1.IdentityPlane.TENANT,
                    negative == Negative.REVOKED_ACTOR ? AuthPersonBindingV1.Status.SUSPENDED : AuthPersonBindingV1.Status.ACTIVE,
                    zeroVersions ? 0 : negative == Negative.USER_VERSION_CHANGED ? 8 : 7,
                    zeroVersions ? 0 : negative == Negative.ACCESS_REVISION_CHANGED ? 4 : 3,
                    negative == Negative.STALE_AUTH_CAPTURE ? lookup.capturedNow().minusNanos(1) : time.get(), time.get().plusSeconds(10));
            return new DwpAuthoritySnapshot(requirements(), actor, Decision.ALLOWED,
                    new AuthRevision(negative == Negative.INVALID_AUTH_REVISION ? "42" : "auth-" + (call == 2 && negative == Negative.AUTH_REVISION_CHANGED ? "e" : "a").repeat(64)),
                    new PolicyRevision(negative == Negative.INVALID_POLICY_REVISION ? "42" : "policy-release-candidate.v3-" + (call == 2 && negative == Negative.POLICY_POINTER_CHANGED ? "10" : "9") + "-" + "b".repeat(64)),
                    new ContextKey(negative == Negative.INVALID_CONTEXT_REVISION ? "auth-" + "c".repeat(64) : "psc-" + (call == 2 && negative == Negative.CONTEXT_CHANGED ? "e" : "c").repeat(64)),
                    new DecisionRevision(negative == Negative.INVALID_DECISION_REVISION ? "auth-" + "d".repeat(64) : "psr-" + (call == 2 && negative == Negative.DECISION_CHANGED ? "e" : "d").repeat(64)),
                    "selected.native-owner-scope", negative == Negative.PERMISSION_MISSING ? Set.of() : Set.of("APP.HRIS:VIEW"),
                    negative == Negative.DUTY_MISSING ? Set.of() : Set.of("CANDIDATE_CURRENT_DUTY"),
                    negative != Negative.APP_MISSING, negative != Negative.STATIC_SOD_DENIED,
                    time.get(), negative == Negative.DWP_EXPIRED ? time.get() : time.get().plusSeconds(10));
        }
        PeopleAuthoritySnapshot people(PeopleLookup lookup) {
            int call = peopleCalls.incrementAndGet(); advance();
            if (negative == Negative.OWNER_SECRET) throw new IllegalStateException("synthetic-private-owner-sentinel");
            if (negative == Negative.TYPED_OWNER_CAUSE) {
                var failure = new CurrentHrisAuthorizationExceptionV2(OWNER_UNAVAILABLE);
                failure.initCause(new IllegalStateException("synthetic-private-owner-cause"));
                throw failure;
            }
            if (negative == Negative.BACKWARDS_CLOCK) time.set(NOW.minusSeconds(1));
            if (negative == Negative.FIRST_PROOF_EXPIRES_DURING_REFETCH && call == 2) time.set(NOW.plusSeconds(10));
            boolean employment = kind == TargetKind.EMPLOYMENT;
            boolean extra = negative == Negative.PERSON_HAS_EMPLOYMENT;
            var target = new TargetSnapshot(kind, negative == Negative.FOREIGN_TARGET_TENANT ? 42 : 41,
                    negative == Negative.WRONG_TARGET_PERSON ? foreignPerson : targetPerson(),
                    zeroVersions ? 0 : 11 + (call == 2 && negative == Negative.TARGET_PERSON_VERSION_CHANGED ? 1 : 0),
                    "ACTIVE", employment || extra ? worker : null, employment ? targetPerson() : null,
                    employment ? (zeroVersions ? 0L : 23L + (call == 2 && negative == Negative.TARGET_WORKER_VERSION_CHANGED ? 1 : 0)) : null,
                    employment ? relationship : null,
                    employment ? (negative == Negative.WRONG_EMPLOYMENT_PARENT ? UUID.randomUUID() : worker) : null,
                    employment ? (zeroVersions ? 0L : 29L + (call == 2 && negative == Negative.TARGET_RELATIONSHIP_VERSION_CHANGED ? 1 : 0)) : null,
                    employment ? assignment : null, employment ? relationship : null,
                    employment ? (zeroVersions ? 0L : 31L + (call == 2 && negative == Negative.TARGET_ASSIGNMENT_VERSION_CHANGED ? 1 : 0)) : null);
            return new PeopleAuthoritySnapshot(requirements(),
                    negative == Negative.SCOPE_MISMATCH ? "foreign.owner.scope" : lookup.authority().selectedScopeKey(),
                    target, revision(OwnerRevisionKind.RELATIONSHIP,call),
                    revision(negative == Negative.WRONG_PEOPLE_REVISION_KIND ? OwnerRevisionKind.FIELD_POLICY : OwnerRevisionKind.POPULATION,call),
                    revision(OwnerRevisionKind.FIELD_POLICY,call), revision(OwnerRevisionKind.PURPOSE_POLICY,call),
                    revision(OwnerRevisionKind.DYNAMIC_SOD,call),
                    negative == Negative.FIELD_DENIED ? Set.of("person.publicId") : requirements().fieldPaths(),
                    negative != Negative.POPULATION_DENIED, negative != Negative.PURPOSE_DENIED,
                    negative != Negative.DYNAMIC_SOD_DENIED, time.get(),
                    negative == Negative.PEOPLE_EXPIRED ? time.get() : time.get().plusSeconds(10));
        }
        PeopleRevision revision(OwnerRevisionKind kind, int call) {
            boolean changed = call == 2 && ((kind == OwnerRevisionKind.FIELD_POLICY && negative == Negative.FIELD_POLICY_CHANGED)
                    || (kind == OwnerRevisionKind.PURPOSE_POLICY && negative == Negative.PURPOSE_POLICY_CHANGED)
                    || (kind == OwnerRevisionKind.DYNAMIC_SOD && negative == Negative.DYNAMIC_SOD_POLICY_CHANGED));
            return new PeopleRevision(kind, "people-" + kind.name().toLowerCase() + ":native.v2:opaque-ref" + (changed ? ":changed" : ""));
        }
        void calls(int invocation, int dwp, int people) {
            assertEquals(invocation, invocationCalls.get(), "invocation calls");
            assertEquals(dwp, dwpCalls.get(), "DWP calls");
            assertEquals(people, peopleCalls.get(), "People calls");
        }
    }
}
