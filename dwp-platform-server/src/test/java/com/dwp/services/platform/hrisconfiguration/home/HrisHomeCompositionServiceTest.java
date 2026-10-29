package com.dwp.services.platform.hrisconfiguration.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Module;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.*;
import static com.dwp.services.platform.hrisconfiguration.home.HrisHomeCompositionDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisHomeCompositionServiceTest {

    private static final long TENANT_ID = 71L;
    private static final UUID SUBJECT =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant MATERIALIZED_AS_OF =
            Instant.parse("2026-10-17T00:00:00Z");
    private static final Instant GENERATED =
            Instant.parse("2026-10-17T00:01:00Z");
    private static final Instant REQUEST_AS_OF =
            Instant.parse("2026-10-17T00:03:00Z");
    private static final Instant FRESH_UNTIL =
            Instant.parse("2026-10-17T00:05:00Z");
    private static final Instant STALE_AFTER =
            Instant.parse("2026-10-17T00:10:00Z");

    @Test
    void composesAllFourOwnersInDeterministicOrderAndEmitsOnlyTheBrowserProfile()
            throws Exception {
        Map<Module, HrisHomeContributionMaterializationV1> materializations = defaults();
        Map<Module, OwnerAuthority> reversed = new LinkedHashMap<>();
        reversed.put(Module.PAY, owner(materializations.get(Module.PAY)));
        reversed.put(Module.TIM, owner(materializations.get(Module.TIM)));
        reversed.put(Module.PER, owner(materializations.get(Module.PER)));
        reversed.put(Module.HRM, owner(materializations.get(Module.HRM)));

        try (Harness harness = harness(
                candidates(materializations), request -> materializations.get(
                        request.candidate().module()),
                (authority, owner, deepLink) -> deepLink,
                Duration.ofMillis(500), reversed, REQUEST_AS_OF)) {
            HomeResponse response = harness.service().compose(
                    request(Freshness.FRESH_ONLY), authority(reversed));

            assertThat(response.widgets()).extracting(HomeWidget::module)
                    .containsExactly(Module.HRM, Module.PER, Module.TIM, Module.PAY);
            assertThat(response.widgets()).allSatisfy(widget -> {
                assertThat(widget.purposeCode()).isEqualTo(BROWSER_PURPOSE);
                assertThat(widget.state()).isEqualTo(BrowserState.FRESH);
            });
            assertThat(response.partialFailures()).isEmpty();
            assertThat(response.purposeCode()).isEqualTo(BROWSER_PURPOSE);

            String json = new ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(response);
            assertThat(json)
                    .doesNotContain("payloadRef")
                    .doesNotContain("tenantId")
                    .doesNotContain("subjectPrincipalPublicId")
                    .doesNotContain(SUBJECT.toString())
                    .contains("\"purposeCode\":\"HRIS_HOME_VIEW\"");
        }
    }

    @Test
    void anAbsentGrantSkipsCandidateAndOwnerFetchesWithoutLeakingAWidget() {
        AtomicInteger candidateFetches = new AtomicInteger();
        AtomicInteger ownerFetches = new AtomicInteger();
        Map<Module, OwnerAuthority> noOwners = Map.of();
        try (Harness harness = harness(
                query -> {
                    candidateFetches.incrementAndGet();
                    return List.of();
                },
                request -> {
                    ownerFetches.incrementAndGet();
                    return null;
                },
                (authority, owner, deepLink) -> deepLink,
                Duration.ofMillis(500), noOwners, REQUEST_AS_OF)) {
            HomeResponse response = harness.service().compose(
                    request(Freshness.FRESH_ONLY), authority(noOwners));

            assertThat(response.widgets()).isEmpty();
            assertThat(response.partialFailures()).isEmpty();
            assertThat(candidateFetches).hasValue(0);
            assertThat(ownerFetches).hasValue(0);
        }
    }

    @Test
    void missingDuplicateAndCrossTenantCandidatesFailClosedBeforeOwnerFetch() {
        HrisHomeContributionMaterializationV1 people = defaults().get(Module.HRM);
        OwnerAuthority owner = owner(people);
        Map<Module, OwnerAuthority> owners = Map.of(Module.HRM, owner);
        HrisHomeContributionCandidate valid = candidate(people, TENANT_ID);
        HrisHomeContributionCandidate crossTenant = new HrisHomeContributionCandidate(
                valid.eventId(), TENANT_ID + 1, valid.module(), valid.widgetKey(),
                valid.ownerPopulationScope(), valid.scopeRevision(), valid.state(),
                valid.generatedAt(), valid.freshUntil(), valid.staleAfter(),
                valid.payloadRef(), valid.payloadDigest());

        assertCandidateFailure(List.of(), owners, FailureCode.OWNER_UNAVAILABLE);
        assertCandidateFailure(List.of(valid, valid), owners, FailureCode.CONTRACT_INVALID);
        assertCandidateFailure(List.of(crossTenant), owners, FailureCode.AUTHORIZATION_CHANGED);
    }

    @Test
    void verifiesTheSealedDigestBeforeProjectingTamperedOwnerData() {
        HrisHomeContributionMaterializationV1 original = defaults().get(Module.HRM);
        HrisHomeContributionMaterializationV1 tampered = copy(
                original, original.scopeRevision(), original.populationScopeDigest(),
                original.fieldPolicyRevision(), "/hr/tampered", original.data(),
                original.payloadDigest());
        Map<Module, OwnerAuthority> owners = Map.of(Module.HRM, owner(original));

        HomeResponse response = composeOne(
                List.of(candidate(original, TENANT_ID)), request -> tampered,
                owners, Freshness.FRESH_ONLY, REQUEST_AS_OF,
                (authority, owner, deepLink) -> deepLink);

        assertOnlyFailure(response, FailureCode.CONTRACT_INVALID);
    }

    @Test
    void rejectsCandidateScopeAndOwnerFieldPolicyDriftWithTheirTypedFailures() {
        HrisHomeContributionMaterializationV1 people = defaults().get(Module.HRM);
        OwnerAuthority current = owner(people);
        Map<Module, OwnerAuthority> owners = Map.of(Module.HRM, current);
        HrisHomeContributionMaterializationV1 scopeDrift = sealed(
                Module.HRM, people.data(), people.scopeRevision(), "e".repeat(64),
                people.fieldPolicyRevision(), GENERATED, FRESH_UNTIL, STALE_AFTER,
                people.deepLink());
        HrisHomeContributionMaterializationV1 fieldDrift = sealed(
                Module.HRM, people.data(), people.scopeRevision(),
                people.populationScopeDigest(), people.fieldPolicyRevision() + 1,
                GENERATED, FRESH_UNTIL, STALE_AFTER, people.deepLink());
        HrisHomeContributionCandidate wrongScopeRevision = new HrisHomeContributionCandidate(
                UUID.randomUUID(), TENANT_ID, Module.HRM, people.widgetKey(),
                people.ownerPopulationScope(), people.scopeRevision() + 1, people.state(),
                people.generatedAt(), people.freshUntil(), people.staleAfter(),
                people.payloadRef(), people.payloadDigest());

        assertOnlyFailure(composeOne(
                List.of(wrongScopeRevision), request -> people, owners,
                Freshness.FRESH_ONLY, REQUEST_AS_OF,
                (authority, owner, deepLink) -> deepLink), FailureCode.SCOPE_CHANGED);
        assertOnlyFailure(composeOne(
                List.of(candidate(scopeDrift, TENANT_ID)), request -> scopeDrift, owners,
                Freshness.FRESH_ONLY, REQUEST_AS_OF,
                (authority, owner, deepLink) -> deepLink), FailureCode.SCOPE_CHANGED);
        assertOnlyFailure(composeOne(
                List.of(candidate(fieldDrift, TENANT_ID)), request -> fieldDrift, owners,
                Freshness.FRESH_ONLY, REQUEST_AS_OF,
                (authority, owner, deepLink) -> deepLink), FailureCode.FIELD_POLICY_CHANGED);
    }

    @Test
    void outerFieldCeilingsCanOnlyMaskOrOmitAndNeverReviveOwnerRestrictions() {
        PeopleHomeData restrictedData = new PeopleHomeData(
                WidgetType.PEOPLE_SUMMARY,
                new MaskedField(Decision.MASK, MASKED_DISPLAY),
                new ViewedText(Decision.VIEW, "People Operations"),
                new OmittedField(Decision.OMIT));
        HrisHomeContributionMaterializationV1 people = sealed(
                Module.HRM, restrictedData, 11, populationDigest(Module.HRM), 12,
                GENERATED, FRESH_UNTIL, STALE_AFTER, "/hr/me");
        Map<String, Decision> ceilings = Map.of(
                "employmentStatus", Decision.VIEW,
                "organizationLabel", Decision.MASK,
                "pendingActionCount", Decision.VIEW);
        OwnerAuthority owner = owner(people, ceilings);
        Map<Module, OwnerAuthority> owners = Map.of(Module.HRM, owner);

        HomeResponse response = composeOne(
                List.of(candidate(people, TENANT_ID)), request -> people, owners,
                Freshness.FRESH_ONLY, REQUEST_AS_OF,
                (authority, ignored, deepLink) -> deepLink);

        assertThat(response.partialFailures()).isEmpty();
        assertThat(response.widgets()).hasSize(1);
        assertThat(response.widgets().getFirst().data().decisions()).containsExactlyEntriesOf(
                Map.of(
                        "employmentStatus", Decision.MASK,
                        "organizationLabel", Decision.MASK,
                        "pendingActionCount", Decision.OMIT));
    }

    @Test
    void freshnessPolicyAllowsBoundedStaleButRejectsFreshOnlyAndExpiredData() {
        HrisHomeContributionMaterializationV1 people = defaults().get(Module.HRM);
        Map<Module, OwnerAuthority> owners = Map.of(Module.HRM, owner(people));
        List<HrisHomeContributionCandidate> candidates =
                List.of(candidate(people, TENANT_ID));
        Instant boundedStaleNow = FRESH_UNTIL.plusSeconds(30);

        HomeResponse allowed = composeOne(
                candidates, request -> people, owners, Freshness.ALLOW_STALE,
                boundedStaleNow, (authority, owner, deepLink) -> deepLink);
        assertThat(allowed.widgets()).singleElement()
                .extracting(HomeWidget::state).isEqualTo(BrowserState.STALE);

        assertOnlyFailure(composeOne(
                candidates, request -> people, owners, Freshness.FRESH_ONLY,
                boundedStaleNow, (authority, owner, deepLink) -> deepLink),
                FailureCode.STALE_BEYOND_LIMIT);
        assertOnlyFailure(composeOne(
                candidates, request -> people, owners, Freshness.ALLOW_STALE,
                STALE_AFTER.plusSeconds(1), (authority, owner, deepLink) -> deepLink),
                FailureCode.STALE_BEYOND_LIMIT);

        HrisHomeContributionMaterializationV1 ownerMarkedStale =
                withState(people, State.STALE);
        Map<Module, OwnerAuthority> staleOwners =
                Map.of(Module.HRM, owner(ownerMarkedStale));
        assertOnlyFailure(composeOne(
                List.of(candidate(ownerMarkedStale, TENANT_ID)),
                request -> ownerMarkedStale, staleOwners, Freshness.FRESH_ONLY,
                REQUEST_AS_OF, (authority, owner, deepLink) -> deepLink),
                FailureCode.STALE_BEYOND_LIMIT);
    }

    @Test
    void deniedOrEncodedDeepLinksFailClosed() {
        HrisHomeContributionMaterializationV1 people = defaults().get(Module.HRM);
        Map<Module, OwnerAuthority> owners = Map.of(Module.HRM, owner(people));
        List<HrisHomeContributionCandidate> candidates =
                List.of(candidate(people, TENANT_ID));

        assertOnlyFailure(composeOne(
                candidates, request -> people, owners, Freshness.FRESH_ONLY,
                REQUEST_AS_OF, (authority, owner, deepLink) -> null),
                FailureCode.AUTHORIZATION_CHANGED);
        assertOnlyFailure(composeOne(
                candidates, request -> people, owners, Freshness.FRESH_ONLY,
                REQUEST_AS_OF, (authority, owner, deepLink) -> "/hr/%2e%2e/admin"),
                FailureCode.AUTHORIZATION_CHANGED);
        assertOnlyFailure(composeOne(
                candidates, request -> people, owners, Freshness.FRESH_ONLY,
                REQUEST_AS_OF, (authority, owner, deepLink) -> {
                    throw new IllegalStateException("private policy outage");
                }), FailureCode.AUTHORIZATION_CHANGED);
    }

    @Test
    void completionTimeFreshnessPreventsAWidgetCrossingItsExpiryBoundary() {
        HrisHomeContributionMaterializationV1 people = defaults().get(Module.HRM);
        Map<Module, OwnerAuthority> owners = Map.of(Module.HRM, owner(people));
        SequenceClock clock = new SequenceClock(List.of(
                REQUEST_AS_OF,
                FRESH_UNTIL.minusMillis(1),
                FRESH_UNTIL.plusMillis(1)));
        HrisHomeCompositionCoordinator coordinator = new HrisHomeCompositionCoordinator(
                request -> people,
                new HrisHomeCompositionRuntimeControls(Duration.ofMillis(500), 4));
        try (coordinator) {
            HrisHomeCompositionService service = new HrisHomeCompositionService(
                    query -> List.of(candidate(people, TENANT_ID)), coordinator,
                    (authority, owner, deepLink) -> deepLink, clock);

            HomeResponse response = service.compose(
                    request(Freshness.FRESH_ONLY), authority(owners));

            assertOnlyFailure(response, FailureCode.STALE_BEYOND_LIMIT);
            assertThat(response.generatedAt()).isEqualTo(FRESH_UNTIL.plusMillis(1));
        }
    }

    @Test
    void authoritySnapshotIsExplicitAndRevocationDiscardsTheWholeResponse() {
        HrisHomeContributionMaterializationV1 people = defaults().get(Module.HRM);
        Map<Module, OwnerAuthority> owners = Map.of(Module.HRM, owner(people));
        AtomicInteger checks = new AtomicInteger();
        HrisHomeAuthorityContract.CurrentAuthorityGuard guard = expected -> {
            assertThat(expected.tenantId()).isEqualTo(TENANT_ID);
            assertThat(expected.subjectPrincipalPublicId()).isEqualTo(SUBJECT);
            assertThat(expected.browserAudience()).isEqualTo(BrowserAudience.SELF);
            assertThat(expected.owners()).containsOnlyKeys(Module.HRM);
            if (checks.incrementAndGet() == 4) throw new SecurityException("revoked");
        };
        HrisHomeCompositionCoordinator coordinator = new HrisHomeCompositionCoordinator(
                request -> people,
                new HrisHomeCompositionRuntimeControls(Duration.ofMillis(500), 4));
        try (coordinator) {
            HrisHomeCompositionService service = new HrisHomeCompositionService(
                    query -> List.of(candidate(people, TENANT_ID)), coordinator,
                    (authority, owner, deepLink) -> deepLink,
                    Clock.fixed(REQUEST_AS_OF, ZoneOffset.UTC));

            assertThatThrownBy(() -> service.compose(
                    request(Freshness.FRESH_ONLY), authority(owners, guard)))
                    .isInstanceOf(SecurityException.class)
                    .hasMessage("revoked");
            assertThat(checks).hasValue(4);
        }
    }

    @Test
    void aDifferentRequestedAudienceFailsBeforeCandidateOrOwnerMetadataCanLeak() {
        HrisHomeContributionMaterializationV1 people = defaults().get(Module.HRM);
        Map<Module, OwnerAuthority> owners = Map.of(Module.HRM, owner(people));
        AtomicInteger candidateFetches = new AtomicInteger();
        try (Harness harness = harness(
                query -> {
                    candidateFetches.incrementAndGet();
                    return List.of(candidate(people, TENANT_ID));
                }, request -> people, (authority, owner, deepLink) -> deepLink,
                Duration.ofMillis(500), owners, REQUEST_AS_OF)) {
            ViewRequest teamRequest = new ViewRequest(
                    REQUEST_AS_OF, BrowserAudience.TEAM,
                    Freshness.FRESH_ONLY, CORRELATION);

            assertThatThrownBy(() -> harness.service().compose(
                    teamRequest, authority(owners)))
                    .isInstanceOf(SecurityException.class);
            assertThat(candidateFetches).hasValue(0);
        }
    }

    @Test
    void oneOwnerFailureIsIsolatedFromTheOtherThreeOwners() {
        Map<Module, HrisHomeContributionMaterializationV1> materializations = defaults();
        Map<Module, OwnerAuthority> owners = authorities(materializations);

        try (Harness harness = harness(
                candidates(materializations), request -> {
                    if (request.candidate().module() == Module.PER) {
                        throw new HrisHomeCompositionPorts.OwnerMaterializationException(
                                FailureCode.OWNER_UNAVAILABLE, true);
                    }
                    return materializations.get(request.candidate().module());
                },
                (authority, owner, deepLink) -> deepLink,
                Duration.ofMillis(500), owners, REQUEST_AS_OF)) {
            HomeResponse response = harness.service().compose(
                    request(Freshness.FRESH_ONLY), authority(owners));

            assertThat(response.widgets()).extracting(HomeWidget::module)
                    .containsExactly(Module.HRM, Module.TIM, Module.PAY);
            assertThat(response.partialFailures()).singleElement().satisfies(failure -> {
                assertThat(failure.module()).isEqualTo(Module.PER);
                assertThat(failure.code()).isEqualTo(FailureCode.OWNER_UNAVAILABLE);
                assertThat(failure.retryable()).isTrue();
            });
        }
    }

    @Test
    void oneSlowOwnerAndAllSlowOwnersUseOneSharedBoundedDeadline() {
        Map<Module, HrisHomeContributionMaterializationV1> materializations = defaults();
        Map<Module, OwnerAuthority> owners = authorities(materializations);

        try (Harness harness = harness(
                candidates(materializations), request -> {
                    if (request.candidate().module() == Module.HRM) sleepPastDeadline();
                    return materializations.get(request.candidate().module());
                },
                (authority, owner, deepLink) -> deepLink,
                Duration.ofMillis(100), owners, REQUEST_AS_OF)) {
            HomeResponse response = harness.service().compose(
                    request(Freshness.FRESH_ONLY), authority(owners));

            assertThat(response.widgets()).extracting(HomeWidget::module)
                    .containsExactly(Module.PER, Module.TIM, Module.PAY);
            assertThat(response.partialFailures()).singleElement()
                    .satisfies(failure -> {
                        assertThat(failure.module()).isEqualTo(Module.HRM);
                        assertThat(failure.code()).isEqualTo(FailureCode.OWNER_TIMEOUT);
                    });
        }

        try (Harness harness = harness(
                candidates(materializations), request -> {
                    sleepPastDeadline();
                    return materializations.get(request.candidate().module());
                },
                (authority, owner, deepLink) -> deepLink,
                Duration.ofMillis(100), owners, REQUEST_AS_OF)) {
            HomeResponse response = harness.service().compose(
                    request(Freshness.FRESH_ONLY), authority(owners));

            assertThat(response.widgets()).isEmpty();
            assertThat(response.partialFailures()).hasSize(4)
                    .allSatisfy(failure ->
                            assertThat(failure.code()).isEqualTo(FailureCode.OWNER_TIMEOUT));
        }
    }

    private HomeResponse composeOne(
            List<HrisHomeContributionCandidate> candidates,
            HrisHomeCompositionPorts.OwnerMaterializationClient client,
            Map<Module, OwnerAuthority> owners,
            Freshness freshness,
            Instant now,
            HrisHomeCompositionPorts.DeepLinkPolicy deepLinks) {
        try (Harness harness = harness(
                query -> candidates, client, deepLinks,
                Duration.ofMillis(500), owners, now)) {
            return harness.service().compose(
                    new ViewRequest(now, BrowserAudience.SELF, freshness, CORRELATION),
                    authority(owners));
        }
    }

    private void assertCandidateFailure(
            List<HrisHomeContributionCandidate> candidates,
            Map<Module, OwnerAuthority> owners,
            FailureCode expected) {
        AtomicInteger ownerFetches = new AtomicInteger();
        HomeResponse response = composeOne(
                candidates,
                request -> {
                    ownerFetches.incrementAndGet();
                    return defaults().get(Module.HRM);
                },
                owners, Freshness.FRESH_ONLY, REQUEST_AS_OF,
                (authority, owner, deepLink) -> deepLink);
        assertOnlyFailure(response, expected);
        assertThat(ownerFetches).hasValue(0);
    }

    private void assertOnlyFailure(HomeResponse response, FailureCode expected) {
        assertThat(response.widgets()).isEmpty();
        assertThat(response.partialFailures()).singleElement()
                .extracting(HomeWidgetFailure::code).isEqualTo(expected);
    }

    private Harness harness(
            Map<Module, List<HrisHomeContributionCandidate>> candidates,
            HrisHomeCompositionPorts.OwnerMaterializationClient client,
            HrisHomeCompositionPorts.DeepLinkPolicy deepLinks,
            Duration timeout,
            Map<Module, OwnerAuthority> owners,
            Instant now) {
        return harness(
                query -> candidates.getOrDefault(
                        query.ownerAuthority().module(), List.of()),
                client, deepLinks, timeout, owners, now);
    }

    private Harness harness(
            HrisHomeCompositionPorts.CandidateSource candidates,
            HrisHomeCompositionPorts.OwnerMaterializationClient client,
            HrisHomeCompositionPorts.DeepLinkPolicy deepLinks,
            Duration timeout,
            Map<Module, OwnerAuthority> owners,
            Instant now) {
        HrisHomeCompositionCoordinator coordinator = new HrisHomeCompositionCoordinator(
                client, new HrisHomeCompositionRuntimeControls(timeout, 4));
        return new Harness(
                new HrisHomeCompositionService(
                        candidates, coordinator, deepLinks,
                        Clock.fixed(now, ZoneOffset.UTC)),
                coordinator);
    }

    private ViewRequest request(Freshness freshness) {
        return new ViewRequest(
                REQUEST_AS_OF, BrowserAudience.SELF, freshness, CORRELATION);
    }

    private CurrentViewAuthority authority(Map<Module, OwnerAuthority> owners) {
        return authority(owners, ignored -> { });
    }

    private CurrentViewAuthority authority(
            Map<Module, OwnerAuthority> owners,
            HrisHomeAuthorityContract.CurrentAuthorityGuard guard) {
        return new CurrentViewAuthority(
                TENANT_ID, SUBJECT, BrowserAudience.SELF,
                101, "f".repeat(64), 102, 103, 104, owners, guard);
    }

    private Map<Module, OwnerAuthority> authorities(
            Map<Module, HrisHomeContributionMaterializationV1> materializations) {
        EnumMap<Module, OwnerAuthority> result = new EnumMap<>(Module.class);
        materializations.forEach((module, value) -> result.put(module, owner(value)));
        return result;
    }

    private OwnerAuthority owner(HrisHomeContributionMaterializationV1 value) {
        return owner(value, value.data().decisions());
    }

    private OwnerAuthority owner(
            HrisHomeContributionMaterializationV1 value,
            Map<String, Decision> fieldCeilings) {
        return new OwnerAuthority(
                value.module(), value.widgetKey(),
                "hris.home." + value.module().name().toLowerCase() + ".view",
                value.browserAudience(), value.ownerPopulationScope(), value.scopeRevision(),
                value.populationScopeDigest(), 13, value.fieldPolicyRevision(), fieldCeilings);
    }

    private Map<Module, List<HrisHomeContributionCandidate>> candidates(
            Map<Module, HrisHomeContributionMaterializationV1> materializations) {
        EnumMap<Module, List<HrisHomeContributionCandidate>> result =
                new EnumMap<>(Module.class);
        materializations.forEach((module, value) -> result.put(
                module, List.of(candidate(value, TENANT_ID))));
        return result;
    }

    private HrisHomeContributionCandidate candidate(
            HrisHomeContributionMaterializationV1 value, long tenantId) {
        return new HrisHomeContributionCandidate(
                UUID.nameUUIDFromBytes(value.module().name().getBytes()), tenantId,
                value.module(), value.widgetKey(), value.ownerPopulationScope(),
                value.scopeRevision(), value.state(), value.generatedAt(), value.freshUntil(),
                value.staleAfter(), value.payloadRef(), value.payloadDigest());
    }

    private Map<Module, HrisHomeContributionMaterializationV1> defaults() {
        EnumMap<Module, HrisHomeContributionMaterializationV1> values =
                new EnumMap<>(Module.class);
        values.put(Module.HRM, sealed(
                Module.HRM,
                new PeopleHomeData(
                        WidgetType.PEOPLE_SUMMARY,
                        new ViewedText(Decision.VIEW, "ACTIVE"),
                        new ViewedText(Decision.VIEW, "People Operations"),
                        new ViewedNumber(Decision.VIEW, 1)),
                11, populationDigest(Module.HRM), 12,
                GENERATED, FRESH_UNTIL, STALE_AFTER, "/hr/me"));
        values.put(Module.PER, sealed(
                Module.PER,
                new PerformanceHomeData(
                        WidgetType.PERFORMANCE_SUMMARY,
                        new ViewedText(Decision.VIEW, "ON_TRACK"),
                        new ViewedText(Decision.VIEW, "SELF_REVIEW"),
                        new ViewedNumber(Decision.VIEW, 2)),
                11, populationDigest(Module.PER), 12,
                GENERATED, FRESH_UNTIL, STALE_AFTER, "/hr/performance"));
        values.put(Module.TIM, sealed(
                Module.TIM,
                new TimeLeaveHomeData(
                        WidgetType.TIME_LEAVE_SUMMARY,
                        new ViewedText(Decision.VIEW, "CLOCKED_IN"),
                        new ViewedNumber(Decision.VIEW, 480),
                        new ViewedNumber(Decision.VIEW, 1),
                        new ViewedNumber(Decision.VIEW, 2)),
                11, populationDigest(Module.TIM), 12,
                GENERATED, FRESH_UNTIL, STALE_AFTER, "/hr/time"));
        values.put(Module.PAY, sealed(
                Module.PAY,
                new PayrollHomeData(
                        WidgetType.PAYROLL_SUMMARY,
                        new ViewedText(Decision.VIEW, "2026-10"),
                        new ViewedMoney(Decision.VIEW, "1200.00", "KRW"),
                        new ViewedMoney(Decision.VIEW, "1000.00", "KRW"),
                        new ViewedBoolean(Decision.VIEW, true)),
                11, populationDigest(Module.PAY), 12,
                GENERATED, FRESH_UNTIL, STALE_AFTER, "/hr/payroll"));
        return values;
    }

    private HrisHomeContributionMaterializationV1 sealed(
            Module module,
            HomeData data,
            long scopeRevision,
            String populationDigest,
            long fieldPolicyRevision,
            Instant generatedAt,
            Instant freshUntil,
            Instant staleAfter,
            String deepLink) {
        String payloadRef = "href_0123456789abcdef";
        HrisHomeContributionMaterializationV1 draft = raw(
                module, data, scopeRevision, populationDigest, fieldPolicyRevision,
                generatedAt, freshUntil, staleAfter, deepLink,
                HrisHomeMaterializationDigestV1.PLACEHOLDER_REFERENCE,
                HrisHomeMaterializationDigestV1.PLACEHOLDER_DIGEST);
        return raw(
                module, data, scopeRevision, populationDigest, fieldPolicyRevision,
                generatedAt, freshUntil, staleAfter, deepLink, payloadRef,
                HrisHomeMaterializationDigestV1.digest(draft));
    }

    private HrisHomeContributionMaterializationV1 raw(
            Module module,
            HomeData data,
            long scopeRevision,
            String populationDigest,
            long fieldPolicyRevision,
            Instant generatedAt,
            Instant freshUntil,
            Instant staleAfter,
            String deepLink,
            String payloadRef,
            String payloadDigest) {
        List<FieldDecision> decisions = new ArrayList<>();
        data.decisions().forEach((field, decision) ->
                decisions.add(new FieldDecision(field, decision)));
        return new HrisHomeContributionMaterializationV1(
                HrisHomeContributionMaterializationV1.PROFILE,
                module, widgetKey(module), widgetType(module), BrowserAudience.SELF,
                OwnerPopulationScope.SELF, scopeRevision, populationDigest,
                fieldPolicyRevision, HrisHomeContributionMaterializationV1.PURPOSE,
                State.FRESH, generatedAt, freshUntil, staleAfter, deepLink, 7,
                payloadRef, payloadDigest, MATERIALIZED_AS_OF, data, decisions, List.of());
    }

    private HrisHomeContributionMaterializationV1 copy(
            HrisHomeContributionMaterializationV1 value,
            long scopeRevision,
            String populationDigest,
            long fieldPolicyRevision,
            String deepLink,
            HomeData data,
            String payloadDigest) {
        List<FieldDecision> decisions = new ArrayList<>();
        data.decisions().forEach((field, decision) ->
                decisions.add(new FieldDecision(field, decision)));
        return new HrisHomeContributionMaterializationV1(
                value.profile(), value.module(), value.widgetKey(), value.widgetType(),
                value.browserAudience(), value.ownerPopulationScope(), scopeRevision,
                populationDigest, fieldPolicyRevision, value.purposeCode(), value.state(),
                value.generatedAt(), value.freshUntil(), value.staleAfter(), deepLink,
                value.sourceVersion(), value.payloadRef(), payloadDigest, value.asOf(),
                data, decisions, value.partialFailures());
    }

    private HrisHomeContributionMaterializationV1 withState(
            HrisHomeContributionMaterializationV1 value,
            State state) {
        HrisHomeContributionMaterializationV1 draft = new HrisHomeContributionMaterializationV1(
                value.profile(), value.module(), value.widgetKey(), value.widgetType(),
                value.browserAudience(), value.ownerPopulationScope(), value.scopeRevision(),
                value.populationScopeDigest(), value.fieldPolicyRevision(), value.purposeCode(),
                state, value.generatedAt(), value.freshUntil(), value.staleAfter(),
                value.deepLink(), value.sourceVersion(),
                HrisHomeMaterializationDigestV1.PLACEHOLDER_REFERENCE,
                HrisHomeMaterializationDigestV1.PLACEHOLDER_DIGEST, value.asOf(), value.data(),
                value.fieldDecisions(), List.of());
        return new HrisHomeContributionMaterializationV1(
                draft.profile(), draft.module(), draft.widgetKey(), draft.widgetType(),
                draft.browserAudience(), draft.ownerPopulationScope(), draft.scopeRevision(),
                draft.populationScopeDigest(), draft.fieldPolicyRevision(), draft.purposeCode(),
                draft.state(), draft.generatedAt(), draft.freshUntil(), draft.staleAfter(),
                draft.deepLink(), draft.sourceVersion(), value.payloadRef(),
                HrisHomeMaterializationDigestV1.digest(draft), draft.asOf(), draft.data(),
                draft.fieldDecisions(), draft.partialFailures());
    }

    private String widgetKey(Module module) {
        return switch (module) {
            case HRM -> "people-summary";
            case PER -> "performance-summary";
            case TIM -> "time-summary";
            case PAY -> "payroll-summary";
        };
    }

    private WidgetType widgetType(Module module) {
        return switch (module) {
            case HRM -> WidgetType.PEOPLE_SUMMARY;
            case PER -> WidgetType.PERFORMANCE_SUMMARY;
            case TIM -> WidgetType.TIME_LEAVE_SUMMARY;
            case PAY -> WidgetType.PAYROLL_SUMMARY;
        };
    }

    private String populationDigest(Module module) {
        return switch (module) {
            case HRM -> "a".repeat(64);
            case PER -> "b".repeat(64);
            case TIM -> "c".repeat(64);
            case PAY -> "d".repeat(64);
        };
    }

    private void sleepPastDeadline() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("owner call interrupted", exception);
        }
    }

    private record Harness(
            HrisHomeCompositionService service,
            HrisHomeCompositionCoordinator coordinator) implements AutoCloseable {

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static final class SequenceClock extends Clock {
        private final List<Instant> values;
        private final AtomicInteger index = new AtomicInteger();

        private SequenceClock(List<Instant> values) {
            this.values = List.copyOf(values);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported by this test clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            int position = index.getAndIncrement();
            return values.get(Math.min(position, values.size() - 1));
        }
    }
}
