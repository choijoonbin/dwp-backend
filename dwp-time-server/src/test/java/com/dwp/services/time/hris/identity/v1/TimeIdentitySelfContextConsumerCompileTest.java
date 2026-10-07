package com.dwp.services.time.hris.identity.v1;

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

/** TIM bounded-context compile witness only; no People database or fallback wiring. */
class TimeIdentitySelfContextConsumerCompileTest {
    @Test
    void timeConsumerProjectsVerifiedAssignmentWithExplicitPurpose() {
        Fixture fixture = new Fixture();
        var selected = fixture.port(false, false).resolve(fixture.query).selected();
        assertThat(new TimeSelfView(selected.context().worker().publicId(),
                selected.context().relationship().publicId(), selected.context().assignment().publicId(),
                selected.authority().purpose())).isEqualTo(new TimeSelfView(Fixture.WORKER,
                Fixture.RELATIONSHIP, Fixture.ASSIGNMENT, SelfContextPurposeV1.SELF_ATTENDANCE_READ));
        assertThat(selected.authority().audience()).isEqualTo(SelfContextPurposeV1.Audience.HRIS_TIM);
        assertThat(fixture.calls).hasValue(3);
    }

    @Test
    void timeConsumerMissingAdapterFailsClosedWithoutPeopleFallback() {
        Fixture fixture = new Fixture();
        assertThatThrownBy(() -> fixture.port(true, false).resolve(fixture.query))
                .isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                        error -> assertThat(error.code()).isEqualTo(SelfContextContractExceptionV1.Code.ADAPTER_UNAVAILABLE));
        assertThat(fixture.calls).hasValue(0);
    }

    @Test
    void timeConsumerOwnerUnavailableFailsClosedWithoutFallback() {
        Fixture fixture = new Fixture();
        assertThatThrownBy(() -> fixture.port(false, true).resolve(fixture.query))
                .isInstanceOfSatisfying(SelfContextContractExceptionV1.class,
                        error -> assertThat(error.code()).isEqualTo(SelfContextContractExceptionV1.Code.OWNER_UNAVAILABLE));
        assertThat(fixture.calls).hasValue(1);
    }

    record TimeSelfView(UUID worker, UUID relationship, UUID assignment, SelfContextPurposeV1 purpose) { }

    static final class Fixture {
        static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
        static final UUID PRINCIPAL = id(301), PERSON = id(302), WORKER = id(303);
        static final UUID RELATIONSHIP = id(304), ASSIGNMENT = id(305);
        final AtomicInteger calls = new AtomicInteger();
        final SelfContextQueryV1 query = new SelfContextQueryV1(
                SelfContextPurposeV1.SELF_ATTENDANCE_READ, NOW, null);
        final SelfContextAuthorityV1 authority = new SelfContextAuthorityV1(1, 3, PRINCIPAL, PERSON, 7, 11,
                SelfContextPurposeV1.Audience.HRIS_TIM, query.purpose(), true, true, 9, NOW, NOW.plusSeconds(10),
                new SelfContextAuthorityV1.EffectivePolicy("tenant/self-attendance", 1,
                        Set.of(NativeSelfContextSetV1.PersonState.ACTIVE), Set.of("ACTIVE"), Set.of("ACTIVE")));
        final AuthPersonBindingV1 binding = new AuthPersonBindingV1(1, 3, PRINCIPAL, PERSON,
                AuthPersonBindingV1.IdentityPlane.TENANT, AuthPersonBindingV1.Status.ACTIVE,
                7, 11, NOW, NOW.plusSeconds(10));
        final NativeSelfContextSetV1.EmploymentContext context = new NativeSelfContextSetV1.EmploymentContext(
                new NativeSelfContextSetV1.Worker(WORKER, PERSON, 3, "ACTIVE"),
                new NativeSelfContextSetV1.WorkRelationship(RELATIONSHIP, WORKER, id(306), 4,
                        LocalDate.of(2020, 1, 1), null),
                new NativeSelfContextSetV1.Assignment(ASSIGNMENT, RELATIONSHIP, 5, "ACTIVE",
                        LocalDate.of(2020, 1, 1), null, ZoneOffset.UTC));

        SelfContextPortV1 port(boolean missingPeople, boolean ownerUnavailable) {
            return GuardedSelfContextPortV1.guarded(SelfContextPurposeV1.Audience.HRIS_TIM,
                    Clock.fixed(NOW, ZoneOffset.UTC), (q, audience, captured) -> {
                        calls.incrementAndGet();
                        if (ownerUnavailable) throw new IllegalStateException("owner unavailable");
                        return authority;
                    }, request -> { calls.incrementAndGet(); return binding; }, missingPeople ? null : request -> {
                        calls.incrementAndGet();
                        return new NativeSelfContextSetV1(1, PERSON, 2,
                                NativeSelfContextSetV1.PersonState.ACTIVE, request.query().asOf(), true,
                                List.of(context));
                    });
        }

        static UUID id(long value) {
            return new UUID(0x3000000000004000L, 0x8000000000000000L | value);
        }
    }
}
