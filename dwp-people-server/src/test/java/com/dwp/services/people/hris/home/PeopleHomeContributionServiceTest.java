package com.dwp.services.people.hris.home;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeopleHomeContributionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T09:10:00Z");
    private static final UUID SUBJECT = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");

    @Test
    void publishesXcon013AndRecorderRetryUsesTheSameEventIdentityAndPayload() {
        AtomicReference<PeopleHomeSourcePort.Snapshot> snapshot =
                new AtomicReference<>(completeSnapshot());
        RecorderFixture recorder = recorder();
        PeopleHomeContributionService service = service(
                snapshot::get, recorder.recorder(), NOW);

        PeopleHomeContributionService.Published first = service.publish(authority(viewPolicy()));
        PeopleHomeContributionService.Published retry = service.publish(authority(viewPolicy()));

        assertThat(first.event().type())
                .isEqualTo(PeopleHomeContributionService.EVENT_TYPE);
        assertThat(first.event().data().fieldNames()).toIterable().containsExactlyInAnyOrder(
                "ownerPopulationScope", "freshUntil", "generatedAt", "module",
                "payloadDigest", "payloadRef", "scopeRevision", "staleAfter",
                "state", "widgetKey");
        assertThat(retry.event()).isEqualTo(first.event());
        assertThat(HrisHomeMaterializationDigestV1.matches(
                first.materialization(), first.materialization().payloadDigest())).isTrue();
        assertThat(recorder.appendCount()).hasValue(2);
        verify(recorder.repository(), times(2)).append(any(), any(), any());
    }

    @Test
    void materializesClosedPeopleDataWithViewMaskAndOmit() {
        RecorderFixture recorder = recorder();
        PeopleHomeSourcePort.Snapshot snapshot = completeSnapshot();
        Map<String, HrisHomeContributionMaterializationV1.Decision> policy = Map.of(
                "employmentStatus", HrisHomeContributionMaterializationV1.Decision.VIEW,
                "organizationLabel", HrisHomeContributionMaterializationV1.Decision.MASK,
                "pendingActionCount", HrisHomeContributionMaterializationV1.Decision.OMIT);
        PeopleHomeContributionService service = service(() -> snapshot, recorder.recorder(), NOW);
        var grant = authority(policy);
        String reference = service.publish(grant).materialization().payloadRef();

        HrisHomeContributionMaterializationV1 result =
                service.materialize(reference, grant, CORRELATION);
        HrisHomeContributionMaterializationV1.PeopleHomeData data =
                (HrisHomeContributionMaterializationV1.PeopleHomeData) result.data();

        assertThat(result.state()).isEqualTo(HrisHomeContributionMaterializationV1.State.FRESH);
        assertThat(data.employmentStatus())
                .isEqualTo(new HrisHomeContributionMaterializationV1.ViewedText(
                        HrisHomeContributionMaterializationV1.Decision.VIEW, "ACTIVE"));
        assertThat(data.organizationLabel().decision())
                .isEqualTo(HrisHomeContributionMaterializationV1.Decision.MASK);
        assertThat(data.pendingActionCount().decision())
                .isEqualTo(HrisHomeContributionMaterializationV1.Decision.OMIT);
        assertThat(result.partialFailures()).isEmpty();
    }

    @Test
    void missingViewedOwnerFieldProducesTypedDegradedProjection() {
        RecorderFixture recorder = recorder();
        PeopleHomeSourcePort.Snapshot missing = new PeopleHomeSourcePort.Snapshot(
                Optional.of("ACTIVE"), Optional.empty(), Optional.of(2L), 7, NOW.minusSeconds(4));
        PeopleHomeContributionService service = service(() -> missing, recorder.recorder(), NOW);
        var grant = authority(viewPolicy());
        var published = service.publish(grant).materialization();

        assertThat(published.state())
                .isEqualTo(HrisHomeContributionMaterializationV1.State.DEGRADED);
        assertThat(published.partialFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.code())
                    .isEqualTo(HrisHomeContributionMaterializationV1.FailureCode.OWNER_UNAVAILABLE);
            assertThat(failure.retryable()).isTrue();
        });
        assertThat(((HrisHomeContributionMaterializationV1.PeopleHomeData) published.data())
                .organizationLabel().decision())
                .isEqualTo(HrisHomeContributionMaterializationV1.Decision.OMIT);
        assertThat(HrisHomeMaterializationDigestV1.matches(
                published, published.payloadDigest())).isTrue();
        assertThat(service.materialize(published.payloadRef(), grant, CORRELATION))
                .isEqualTo(published);
    }

    @Test
    void tamperedCrossTenantAndChangedSourceReferencesFailClosed() {
        AtomicReference<PeopleHomeSourcePort.Snapshot> source =
                new AtomicReference<>(completeSnapshot());
        RecorderFixture recorder = recorder();
        PeopleHomeContributionService service = service(source::get, recorder.recorder(), NOW);
        var grant = authority(viewPolicy());
        String reference = service.publish(grant).materialization().payloadRef();

        String replacement = reference.endsWith("A") ? "B" : "A";
        assertThatThrownBy(() -> service.materialize(
                reference.substring(0, reference.length() - 1) + replacement,
                grant, CORRELATION))
                .isInstanceOf(PeopleHomePayloadCodec.UnknownReference.class);
        var otherTenant = new PeopleHomeAuthorityPort.CurrentAuthority(
                8, SUBJECT, grant.capability(), grant.browserAudience(),
                grant.ownerPopulationScope(), grant.scopeRevision(),
                grant.populationScopeDigest(), grant.effectivePermissionRevision(),
                grant.fieldPolicyRevision(), grant.widgetKey(),
                grant.populationPersonPublicIds(), grant.fieldDecisions());
        assertThatThrownBy(() -> service.materialize(reference, otherTenant, CORRELATION))
                .isInstanceOf(PeopleHomePayloadCodec.UnknownReference.class);

        source.set(new PeopleHomeSourcePort.Snapshot(
                Optional.of("LEAVE"), Optional.of("People Operations"),
                Optional.of(2L), 8, NOW.minusSeconds(1)));
        assertThatThrownBy(() -> service.materialize(reference, grant, CORRELATION))
                .isInstanceOf(PeopleHomePayloadCodec.UnknownReference.class);
    }

    @Test
    void referenceBeyondStaleWindowFailsClosed() {
        RecorderFixture recorder = recorder();
        PeopleHomeSourcePort.Snapshot snapshot = completeSnapshot();
        var grant = authority(viewPolicy());
        PeopleHomeContributionService publisher = service(
                () -> snapshot, recorder.recorder(), NOW);
        String reference = publisher.publish(grant).materialization().payloadRef();
        PeopleHomeContributionService expired = service(
                () -> snapshot, recorder.recorder(), NOW.plusSeconds(901));

        assertThatThrownBy(() -> expired.materialize(reference, grant, CORRELATION))
                .isInstanceOf(PeopleHomePayloadCodec.UnknownReference.class);
    }

    @Test
    void reMaterializationPreservesTheSealedWireAcrossTheFreshnessBoundary() {
        RecorderFixture recorder = recorder();
        PeopleHomeSourcePort.Snapshot snapshot = completeSnapshot();
        var grant = authority(viewPolicy());
        var published = service(() -> snapshot, recorder.recorder(), NOW)
                .publish(grant).materialization();
        PeopleHomeContributionService afterFreshUntil = service(
                () -> snapshot, recorder.recorder(), NOW.plusSeconds(301));

        var rematerialized = afterFreshUntil.materialize(
                published.payloadRef(), grant, CORRELATION);

        assertThat(rematerialized).isEqualTo(published);
        assertThat(HrisHomeMaterializationDigestV1.matches(
                rematerialized, rematerialized.payloadDigest())).isTrue();
    }

    private static PeopleHomeContributionService service(
            java.util.function.Supplier<PeopleHomeSourcePort.Snapshot> source,
            DomainEventRecorder recorder,
            Instant now) {
        PeopleHomeAuthorityPort.CurrentAuthority grant = authority(viewPolicy());
        PeopleHomeAuthorityPort authorities = new PeopleHomeAuthorityPort() {
            @Override
            public Optional<CurrentAuthority> current(
                    com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1.ProofClaims claims) {
                return grant.exactlyMatches(claims) ? Optional.of(grant) : Optional.empty();
            }

            @Override
            public java.util.List<CurrentAuthority> currentGrants() {
                return java.util.List.of(grant);
            }
        };
        return new PeopleHomeContributionService(
                ignored -> source.get(), authorities,
                new PeopleHomePayloadCodec("0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8)),
                recorder, new DomainEventContractRegistry(), mapper(),
                Clock.fixed(now, ZoneOffset.UTC), false);
    }

    private static RecorderFixture recorder() {
        DomainEventOutboxRepository repository = mock(DomainEventOutboxRepository.class);
        AtomicReference<String> hash = new AtomicReference<>();
        AtomicInteger appends = new AtomicInteger();
        when(repository.append(any(DomainEventEnvelope.class), any(), any()))
                .thenAnswer(invocation -> {
                    hash.compareAndSet(null, invocation.getArgument(2));
                    return appends.getAndIncrement() == 0;
                });
        when(repository.payloadHash(any())).thenAnswer(ignored -> Optional.of(hash.get()));
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        contracts.register(PeopleHomeContributionService.EVENT_TYPE, 1, 1);
        return new RecorderFixture(repository,
                new DomainEventRecorder(repository, contracts, mapper()), appends);
    }

    private static PeopleHomeSourcePort.Snapshot completeSnapshot() {
        return new PeopleHomeSourcePort.Snapshot(
                Optional.of("ACTIVE"), Optional.of("People Operations"),
                Optional.of(2L), 7, NOW.minusSeconds(4));
    }

    private static PeopleHomeAuthorityPort.CurrentAuthority authority(
            Map<String, HrisHomeContributionMaterializationV1.Decision> policy) {
        return new PeopleHomeAuthorityPort.CurrentAuthority(
                7, SUBJECT, "hcm.personal.profile.view",
                HrisHomeContributionMaterializationV1.BrowserAudience.SELF,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF,
                11, "a".repeat(64), 12, 13, "people-profile-summary",
                Set.of(SUBJECT), policy);
    }

    private static Map<String, HrisHomeContributionMaterializationV1.Decision> viewPolicy() {
        return Map.of(
                "employmentStatus", HrisHomeContributionMaterializationV1.Decision.VIEW,
                "organizationLabel", HrisHomeContributionMaterializationV1.Decision.VIEW,
                "pendingActionCount", HrisHomeContributionMaterializationV1.Decision.VIEW);
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private record RecorderFixture(
            DomainEventOutboxRepository repository,
            DomainEventRecorder recorder,
            AtomicInteger appendCount) {
    }
}
