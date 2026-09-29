package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HcmPerformanceCycleAuthorityAdapterTest {

    private static final Instant NOW = Instant.parse("2026-09-29T01:00:00Z");
    private static final long TENANT = 71L;
    private static final long ACTOR = 19L;
    private static final UUID SUBJECT =
            UUID.fromString("10000000-0000-0000-0000-000000000019");
    private static final String CONTEXT = "psc-" + "a".repeat(64);
    private static final String SCOPE = "scope-" + "b".repeat(32);
    private static final String REVISION = "psr-" + "c".repeat(64);

    @Test
    void projectsOnlyTheExactCurrentRoutePopulationAndSubjectEvidence() {
        PerformanceCycleAuthorityPort.AuthorityRequest request = request(
                "VIEW", "performance.cycle.list");
        HcmPerformanceCycleAuthorityAdapter adapter = adapter(
                actor(Set.of("DATA.HR_TALENT:VIEW")),
                evidence(HcmPerformanceCycleAuthorityAdapter.LIST_ROUTE,
                        "DATA", "hcm.operations.talent.read", NOW.plusSeconds(60)));

        PerformanceCycleAuthorityPort.AuthorityEvidence result = adapter.authorize(request);

        assertThat(result.tenantId()).isEqualTo(TENANT);
        assertThat(result.actorId()).isEqualTo(ACTOR);
        assertThat(result.subjectPrincipalPublicId()).isEqualTo(SUBJECT);
        assertThat(result.populationScopeDigest()).matches("[0-9a-f]{64}");
        assertThat(result.fieldPolicyRevision()).isPositive();
        assertThat(result.authorizationRevision()).isPositive();
    }

    @Test
    void deniesAValidGrantPresentedForAnotherExactRoute() {
        HcmPerformanceCycleAuthorityAdapter adapter = adapter(
                actor(Set.of("DATA.HR_TALENT:VIEW")),
                evidence(HcmPerformanceCycleAuthorityAdapter.DETAIL_ROUTE,
                        "DATA", "hcm.operations.talent.read", NOW.plusSeconds(60)));

        assertCode(() -> adapter.authorize(request(
                        "VIEW", "performance.cycle.list")), ErrorCode.FORBIDDEN);
    }

    @Test
    void deniesWhenTheExplicitDataGrantIsMissingEvenIfRoleLabelsArePresent() {
        PeopleRequestContext.Actor roleOnly = new PeopleRequestContext.Actor(
                ACTOR, TENANT, SUBJECT, Set.of("ADMIN", "HR_ADMIN"), Set.of());
        HcmPerformanceCycleAuthorityAdapter adapter = adapter(
                roleOnly,
                evidence(HcmPerformanceCycleAuthorityAdapter.LIST_ROUTE,
                        "DATA", "hcm.operations.talent.read", NOW.plusSeconds(60)));

        assertCode(() -> adapter.authorize(request(
                        "VIEW", "performance.cycle.list")), ErrorCode.FORBIDDEN);
    }

    @Test
    void deniesTenantOrSubjectSwitchBeforeReturningReceiptEvidence() {
        HcmPerformanceCycleAuthorityAdapter tenantSwitch = adapter(
                new PeopleRequestContext.Actor(
                        ACTOR, TENANT + 1, SUBJECT, Set.of(),
                        Set.of("DATA.HR_TALENT:VIEW")),
                evidence(HcmPerformanceCycleAuthorityAdapter.LIST_ROUTE,
                        "DATA", "hcm.operations.talent.read", NOW.plusSeconds(60)));
        HcmPerformanceCycleAuthorityAdapter subjectSwitch = adapter(
                new PeopleRequestContext.Actor(
                        ACTOR, TENANT, UUID.randomUUID(), Set.of(),
                        Set.of("DATA.HR_TALENT:VIEW")),
                evidence(HcmPerformanceCycleAuthorityAdapter.LIST_ROUTE,
                        "DATA", "hcm.operations.talent.read", NOW.plusSeconds(60)));

        assertCode(() -> tenantSwitch.authorize(request(
                        "VIEW", "performance.cycle.list")), ErrorCode.FORBIDDEN);
        assertCode(() -> subjectSwitch.authorize(request(
                        "VIEW", "performance.cycle.list")), ErrorCode.FORBIDDEN);
    }

    @Test
    void failsClosedForMissingExpiredOrMalformedGatewayEvidence() {
        HcmPerformanceCycleAuthorityAdapter missing = new HcmPerformanceCycleAuthorityAdapter(
                () -> null,
                () -> actor(Set.of("DATA.HR_TALENT:VIEW")),
                Clock.fixed(NOW, ZoneOffset.UTC));
        HcmPerformanceCycleAuthorityAdapter expired = adapter(
                actor(Set.of("DATA.HR_TALENT:VIEW")),
                evidence(HcmPerformanceCycleAuthorityAdapter.LIST_ROUTE,
                        "DATA", "hcm.operations.talent.read", NOW));
        HcmPepContext.Evidence malformed = new HcmPepContext.Evidence(
                authority(HcmPerformanceCycleAuthorityAdapter.LIST_ROUTE,
                        "DATA", "hcm.operations.talent.read"),
                REVISION, OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC),
                CONTEXT, "not-a-canonical-scope", "111");

        assertCode(() -> missing.authorize(request(
                        "VIEW", "performance.cycle.list")),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        assertCode(() -> expired.authorize(request(
                        "VIEW", "performance.cycle.list")),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        assertCode(() -> adapter(
                        actor(Set.of("DATA.HR_TALENT:VIEW")), malformed)
                        .authorize(request("VIEW", "performance.cycle.list")),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void mutationRequiresItsActionCapabilityAndPopulationPredicate() {
        HcmPerformanceCycleAuthorityAdapter wrongCapability = adapter(
                actor(Set.of("DATA.HR_TALENT:UPDATE")),
                evidence(HcmPerformanceCycleAuthorityAdapter.UPDATE_ROUTE,
                        "ACTION", "hcm.operations.talent.read", NOW.plusSeconds(60)));
        HcmPepContext.Evidence missingPopulation = evidence(
                new HcmV3PepRegistry.RouteAuthority(
                        HcmPerformanceCycleAuthorityAdapter.UPDATE_ROUTE,
                        "ACTION", "full-operations", false, Set.of(),
                        Set.of("OBJECT"),
                        HcmPerformanceCycleAuthorityAdapter.UPDATE_ROUTE + ".binding.01",
                        "hcm.operations.talent.update", "ACTIVE",
                        "PATCH", "/v1/hris/performance/cycles/{cycleId}", null),
                NOW.plusSeconds(60));

        assertCode(() -> wrongCapability.authorize(request(
                        "UPDATE", "performance.cycle.update")), ErrorCode.FORBIDDEN);
        assertCode(() -> adapter(
                        actor(Set.of("DATA.HR_TALENT:UPDATE")), missingPopulation)
                        .authorize(request("UPDATE", "performance.cycle.update")),
                ErrorCode.FORBIDDEN);
    }

    private HcmPerformanceCycleAuthorityAdapter adapter(
            PeopleRequestContext.Actor actor,
            HcmPepContext.Evidence evidence) {
        return new HcmPerformanceCycleAuthorityAdapter(
                () -> evidence, () -> actor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PeopleRequestContext.Actor actor(Set<String> permissions) {
        return new PeopleRequestContext.Actor(
                ACTOR, TENANT, SUBJECT, Set.of(), permissions);
    }

    private HcmPepContext.Evidence evidence(
            String route,
            String kind,
            String capability,
            Instant revalidateAt) {
        return evidence(authority(route, kind, capability), revalidateAt);
    }

    private HcmPepContext.Evidence evidence(
            HcmV3PepRegistry.RouteAuthority authority,
            Instant revalidateAt) {
        return new HcmPepContext.Evidence(
                authority, REVISION,
                OffsetDateTime.ofInstant(revalidateAt, ZoneOffset.UTC),
                CONTEXT, SCOPE, "111");
    }

    private HcmV3PepRegistry.RouteAuthority authority(
            String route,
            String kind,
            String capability) {
        return new HcmV3PepRegistry.RouteAuthority(
                route, kind, "full-operations", "DATA".equals(kind),
                Set.of("predicate.hcm-domain-target-population.v1"),
                Set.of("TARGET_POPULATION"), route + ".binding.01",
                capability, "ACTIVE", "DATA".equals(kind) ? "GET" : "PATCH",
                "/v1/hris/performance/cycles", null);
    }

    private PerformanceCycleAuthorityPort.AuthorityRequest request(
            String action,
            String operation) {
        return new PerformanceCycleAuthorityPort.AuthorityRequest(
                TENANT, ACTOR, SUBJECT, "APP.HRIS", "DATA.HR_TALENT",
                action, operation, "HRIS_PERFORMANCE_CYCLE", null);
    }

    private void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
