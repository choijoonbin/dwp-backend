package com.dwp.services.payroll.hris.identity.v1;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.dwp.platform.contracts.hris.identity.v1.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** PAY bounded-context compile witness only; no owner adapter, endpoint or Spring wiring. */
class IdentitySelfContextConsumerCompileTest {
    @Test
    void payrollConsumerProjectsGuardMintedNativeIdsVersionsAndExplicitPurpose() {
        Fixture fixture = new Fixture();
        var resolution = fixture.port(false).resolve(fixture.query);
        assertThat(resolution.kind()).isEqualTo(SelfContextResolutionV1.Kind.SELECTED);
        assertThat(project(resolution.selected())).isEqualTo(new PayrollSelfView(Fixture.PERSON, Fixture.WORKER,
                Fixture.RELATIONSHIP, Fixture.ASSIGNMENT, 2, 3, 4, 5, 7, 11, SelfContextPurposeV1.SELF_PAY_READ));
        assertThat(resolution.selected().authority().audience()).isEqualTo(SelfContextPurposeV1.Audience.HRIS_PAY);
        assertThat(fixture.calls).hasValue(3);
    }

    @Test
    void payrollConsumerMissingAdapterFailsClosedWithoutFallbackBeforeProviderCalls() {
        Fixture fixture = new Fixture();
        assertThatThrownBy(() -> fixture.port(true).resolve(fixture.query))
                .isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                        error -> assertThat(error.code()).isEqualTo(SelfContextContractExceptionV1.Code.ADAPTER_UNAVAILABLE));
        assertThat(fixture.calls).hasValue(0);
    }

    @Test
    void payrollConsumerOwnerUnavailableFailsClosedWithoutFallbackAfterVerifierCall() {
        Fixture fixture = new Fixture();
        assertThatThrownBy(() -> fixture.port(false, true).resolve(fixture.query))
                .isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                        error -> assertThat(error.code()).isEqualTo(SelfContextContractExceptionV1.Code.OWNER_UNAVAILABLE));
        assertThat(fixture.calls).hasValue(1);
    }

    private PayrollSelfView project(SelfContextResolutionV1.VerifiedSelfContext verified) {
        var context = verified.context();
        return new PayrollSelfView(verified.binding().personPublicId(), context.worker().publicId(),
                context.relationship().publicId(), context.assignment().publicId(), verified.personVersion(),
                context.worker().version(), context.relationship().version(), context.assignment().version(),
                verified.binding().userRowVersion(), verified.binding().accessRevision(), verified.authority().purpose());
    }

    record PayrollSelfView(UUID person, UUID worker, UUID relationship, UUID assignment, long personVersion,
                           long workerVersion, long relationshipVersion, long assignmentVersion,
                           long authRowVersion, long accessRevision, SelfContextPurposeV1 purpose) {
    }

    static class Fixture {
        static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
        static final UUID PRINCIPAL = id(101), PERSON = id(102), WORKER = id(103), RELATIONSHIP = id(104), ASSIGNMENT = id(105);
        final AtomicInteger calls = new AtomicInteger();
        final SelfContextQueryV1 query = new SelfContextQueryV1(SelfContextPurposeV1.SELF_PAY_READ, NOW, null);
        final SelfContextAuthorityV1 authority = new SelfContextAuthorityV1(1, 3, PRINCIPAL, PERSON, 7, 11,
                SelfContextPurposeV1.Audience.HRIS_PAY, query.purpose(), true, true, 9, NOW, NOW.plusSeconds(10),
                new SelfContextAuthorityV1.EffectivePolicy("tenant/self-pay", 1,
                        Set.of(NativeSelfContextSetV1.PersonState.ACTIVE), Set.of("ACTIVE"), Set.of("ACTIVE")));
        final AuthPersonBindingV1 binding = new AuthPersonBindingV1(1, 3, PRINCIPAL, PERSON,
                AuthPersonBindingV1.IdentityPlane.TENANT, AuthPersonBindingV1.Status.ACTIVE, 7, 11, NOW, NOW.plusSeconds(10));
        final NativeSelfContextSetV1.EmploymentContext context = new NativeSelfContextSetV1.EmploymentContext(
                new NativeSelfContextSetV1.Worker(WORKER, PERSON, 3, "ACTIVE"),
                new NativeSelfContextSetV1.WorkRelationship(RELATIONSHIP, WORKER, id(106), 4, LocalDate.of(2020, 1, 1), null),
                new NativeSelfContextSetV1.Assignment(ASSIGNMENT, RELATIONSHIP, 5, "ACTIVE", LocalDate.of(2020, 1, 1), null, ZoneOffset.UTC));

        SelfContextPortV1 port(boolean missingPeople) {
            return port(missingPeople, false);
        }

        SelfContextPortV1 port(boolean missingPeople, boolean ownerUnavailable) {
            return GuardedSelfContextPortV1.guarded(SelfContextPurposeV1.Audience.HRIS_PAY, Clock.fixed(NOW, ZoneOffset.UTC),
                    (q, audience, captured) -> {
                        calls.incrementAndGet();
                        if (ownerUnavailable) throw new IllegalStateException("owner unavailable");
                        return authority;
                    },
                    request -> { calls.incrementAndGet(); return binding; },
                    missingPeople ? null : request -> {
                        calls.incrementAndGet();
                        return new NativeSelfContextSetV1(1, PERSON, 2, NativeSelfContextSetV1.PersonState.ACTIVE,
                                request.query().asOf(), true, List.of(context));
                    });
        }

        static UUID id(long value) { return new UUID(0x1000000000004000L, 0x8000000000000000L | value); }
    }
}
