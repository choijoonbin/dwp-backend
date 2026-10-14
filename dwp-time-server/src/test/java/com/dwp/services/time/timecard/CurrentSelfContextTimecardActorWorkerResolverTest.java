package com.dwp.services.time.timecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import com.dwp.platform.contracts.hris.identity.v1.GuardedSelfContextPortV1;
import com.dwp.platform.contracts.hris.identity.v1.NativeSelfContextSetV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextAuthorityV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextPortV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextPurposeV1;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CurrentSelfContextTimecardActorWorkerResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final UUID PRINCIPAL = id(1);
    private static final UUID PERSON = id(2);
    private static final UUID WORKER = id(3);
    private static final UUID RELATIONSHIP = id(4);
    private static final UUID ASSIGNMENT = id(5);

    @Test
    void resolvesGatewayPersonIdentityToTheCurrentWorker() {
        CurrentSelfContextTimecardActorWorkerResolver resolver = resolver();

        assertThat(resolver.resolve(41, 72, PERSON, NOW))
                .get()
                .extracting(evidence -> evidence.workerPublicId())
                .isEqualTo(WORKER);
    }

    @Test
    void rejectsPrincipalIdentityInTheGatewayPersonHeader() {
        assertThat(resolver().resolve(41, 72, PRINCIPAL, NOW)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private CurrentSelfContextTimecardActorWorkerResolver resolver() {
        ObjectProvider<SelfContextPortV1> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(port());
        return new CurrentSelfContextTimecardActorWorkerResolver(provider);
    }

    private SelfContextPortV1 port() {
        var purpose = SelfContextPurposeV1.SELF_ATTENDANCE_READ;
        var authority = new SelfContextAuthorityV1(
                41, 72, PRINCIPAL, PERSON, 7, 11,
                SelfContextPurposeV1.Audience.HRIS_TIM, purpose, true, true, 9,
                NOW, NOW.plusSeconds(10),
                new SelfContextAuthorityV1.EffectivePolicy(
                        "tenant/self-attendance", 1,
                        Set.of(NativeSelfContextSetV1.PersonState.ACTIVE),
                        Set.of("ACTIVE"), Set.of("ACTIVE")));
        var binding = new AuthPersonBindingV1(
                41, 72, PRINCIPAL, PERSON,
                AuthPersonBindingV1.IdentityPlane.TENANT,
                AuthPersonBindingV1.Status.ACTIVE, 7, 11,
                NOW, NOW.plusSeconds(10));
        var context = new NativeSelfContextSetV1.EmploymentContext(
                new NativeSelfContextSetV1.Worker(WORKER, PERSON, 3, "ACTIVE"),
                new NativeSelfContextSetV1.WorkRelationship(
                        RELATIONSHIP, WORKER, id(6), 4, LocalDate.of(2020, 1, 1), null),
                new NativeSelfContextSetV1.Assignment(
                        ASSIGNMENT, RELATIONSHIP, 5, "ACTIVE",
                        LocalDate.of(2020, 1, 1), null, ZoneOffset.UTC));
        return GuardedSelfContextPortV1.guarded(
                SelfContextPurposeV1.Audience.HRIS_TIM,
                Clock.fixed(NOW, ZoneOffset.UTC),
                (query, audience, capturedAt) -> authority,
                request -> binding,
                request -> new NativeSelfContextSetV1(
                        41, PERSON, 2,
                        NativeSelfContextSetV1.PersonState.ACTIVE,
                        request.query().asOf(), true, List.of(context)));
    }

    private static UUID id(long value) {
        return new UUID(0x1700000000004000L, 0x8000000000000000L | value);
    }
}
