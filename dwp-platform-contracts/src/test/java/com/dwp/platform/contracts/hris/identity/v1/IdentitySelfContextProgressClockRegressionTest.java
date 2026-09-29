package com.dwp.platform.contracts.hris.identity.v1;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent positive regression: normal owner-read elapsed time must not make a fresh native binding stale. */
class IdentitySelfContextProgressClockRegressionTest {
    @Test
    void freshNativeBindingCapturedAfterInitialClockStillResolvesWithProgressingTime() {
        Instant initial = Instant.parse("2026-09-14T00:00:00Z");
        var clock = new ProgressClock(initial);
        UUID principal = UUID.randomUUID(), person = UUID.randomUUID(), worker = UUID.randomUUID(), relation = UUID.randomUUID();
        var authority = new SelfContextAuthorityV1(1, 2, principal, person, 0, 0,
                SelfContextPurposeV1.Audience.HRIS_HRM, SelfContextPurposeV1.SELF_PROFILE_READ, true, true, 0,
                initial, initial.plusSeconds(10), new SelfContextAuthorityV1.EffectivePolicy("independent/live-clock", 0,
                Set.of(NativeSelfContextSetV1.PersonState.ACTIVE), Set.of("ACTIVE"), Set.of("ACTIVE")));
        var port = GuardedSelfContextPortV1.guarded(SelfContextPurposeV1.Audience.HRIS_HRM, clock,
                (query, audience, captured) -> authority,
                request -> {
                    clock.value = initial.plusSeconds(1); // Native Auth SQL/Clock capture occurs after initial guard time.
                    return new AuthPersonBindingV1(1, 2, principal, person, AuthPersonBindingV1.IdentityPlane.TENANT,
                            AuthPersonBindingV1.Status.ACTIVE, 0, 0, clock.instant(), initial.plusSeconds(10));
                },
                request -> {
                    clock.value = initial.plusSeconds(2);
                    var context = new NativeSelfContextSetV1.EmploymentContext(
                            new NativeSelfContextSetV1.Worker(worker, person, 0, "ACTIVE"),
                            new NativeSelfContextSetV1.WorkRelationship(relation, worker, UUID.randomUUID(), 0, LocalDate.of(2020, 1, 1), null),
                            new NativeSelfContextSetV1.Assignment(UUID.randomUUID(), relation, 0, "ACTIVE", LocalDate.of(2020, 1, 1), null, ZoneOffset.UTC));
                    return new NativeSelfContextSetV1(1, person, 0, NativeSelfContextSetV1.PersonState.ACTIVE,
                            request.query().asOf(), true, List.of(context));
                });
        var result = port.resolve(new SelfContextQueryV1(SelfContextPurposeV1.SELF_PROFILE_READ, initial, null));
        assertThat(result.kind()).isEqualTo(SelfContextResolutionV1.Kind.SELECTED);
        assertThat(result.selected().binding().capturedAt()).isEqualTo(initial.plusSeconds(1));
        assertThat(result.selected().verifiedAt()).isEqualTo(initial.plusSeconds(2));
    }

    static final class ProgressClock extends Clock {
        Instant value;
        ProgressClock(Instant initial) { value = initial; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
