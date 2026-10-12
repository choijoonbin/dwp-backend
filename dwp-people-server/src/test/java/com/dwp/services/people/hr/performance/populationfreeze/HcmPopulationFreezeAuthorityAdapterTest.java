package com.dwp.services.people.hr.performance.populationfreeze;

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

class HcmPopulationFreezeAuthorityAdapterTest {

    private static final Instant NOW = Instant.parse("2027-04-01T00:00:00Z");
    private static final long TENANT = 71L;
    private static final long ACTOR = 19L;
    private static final UUID SUBJECT = UUID.randomUUID();

    @Test
    void acceptsOnlyTheExactPepPer009RouteCapabilityAndPopulation() {
        assertThat(HcmPopulationFreezeAuthorityAdapter.FREEZE_CAPABILITY_CONTRACT)
                .isEqualTo("hcm.operations.performance.freeze");
        HcmPopulationFreezeAuthorityAdapter adapter = adapter(
                actor(TENANT, SUBJECT, Set.of("DATA.HR_TALENT:APPROVE")),
                evidence(HcmPopulationFreezeAuthorityAdapter.FREEZE_ROUTE_CONTRACT,
                        HcmPopulationFreezeAuthorityAdapter.FREEZE_CAPABILITY_CONTRACT,
                        Set.of("predicate.hcm-domain-target-population.v1"),
                        Set.of("TARGET_POPULATION"), NOW.plusSeconds(60)));

        PopulationFreezeAuthorityPort.AuthorityEvidence result = adapter.authorize(request());

        assertThat(result.populationScopeDigest()).matches("[0-9a-f]{64}");
        assertThat(result.targetPopulation().organizationRefs()).isNotEmpty();
        assertThat(result.fieldDecisions()).containsEntry(
                "WORK_QUEUE", PopulationFreezeAuthorityPort.FieldDecision.VIEW);
        assertThat(result.fieldPolicyRevision()).isPositive();
        assertThat(result.authorizationRevision()).isPositive();
    }

    @Test
    void acceptsOnlyTheExactIaq021PageRouteForReviewOperations() {
        HcmPopulationFreezeAuthorityAdapter adapter = adapter(
                actor(TENANT, SUBJECT, Set.of("DATA.HR_TALENT:VIEW")),
                reviewEvidence());

        PopulationFreezeAuthorityPort.AuthorityEvidence result = adapter.authorize(
                new PopulationFreezeAuthorityPort.AuthorityRequest(
                        TENANT, ACTOR, SUBJECT,
                        PerformanceReviewOperationsService.APPLICATION_ENTITLEMENT,
                        PerformanceReviewOperationsService.RESOURCE,
                        PerformanceReviewOperationsService.AUTHORITY_ACTION,
                        PerformanceReviewOperationsService.QUERY_OPERATION,
                        PerformanceReviewOperationsService.QUERY_PURPOSE,
                        UUID.randomUUID()));

        assertThat(result.operation())
                .isEqualTo(PerformanceReviewOperationsService.QUERY_OPERATION);
        assertThat(result.populationScopeDigest()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsCrossTenantAndWrongRouteBeforeReturningEvidence() {
        HcmPopulationFreezeAuthorityAdapter crossTenant = adapter(
                actor(TENANT + 1, SUBJECT, Set.of("DATA.HR_TALENT:APPROVE")),
                validEvidence());
        HcmPopulationFreezeAuthorityAdapter wrongRoute = adapter(
                actor(TENANT, SUBJECT, Set.of("DATA.HR_TALENT:APPROVE")),
                evidence("route.hcm.operations.performance-cycle-population-preview.action",
                        HcmPopulationFreezeAuthorityAdapter.FREEZE_CAPABILITY_CONTRACT,
                        Set.of("predicate.hcm-domain-target-population.v1"),
                        Set.of("TARGET_POPULATION"), NOW.plusSeconds(60)));

        assertCode(() -> crossTenant.authorize(request()), ErrorCode.FORBIDDEN);
        assertCode(() -> wrongRoute.authorize(request()), ErrorCode.FORBIDDEN);
    }

    @Test
    void rejectsStaleEvidenceAndMissingPopulationPredicate() {
        HcmPopulationFreezeAuthorityAdapter stale = adapter(
                actor(TENANT, SUBJECT, Set.of("DATA.HR_TALENT:APPROVE")),
                evidence(HcmPopulationFreezeAuthorityAdapter.FREEZE_ROUTE_CONTRACT,
                        HcmPopulationFreezeAuthorityAdapter.FREEZE_CAPABILITY_CONTRACT,
                        Set.of("predicate.hcm-domain-target-population.v1"),
                        Set.of("TARGET_POPULATION"), NOW));
        HcmPopulationFreezeAuthorityAdapter missingPopulation = adapter(
                actor(TENANT, SUBJECT, Set.of("DATA.HR_TALENT:APPROVE")),
                evidence(HcmPopulationFreezeAuthorityAdapter.FREEZE_ROUTE_CONTRACT,
                        HcmPopulationFreezeAuthorityAdapter.FREEZE_CAPABILITY_CONTRACT,
                        Set.of(), Set.of("OBJECT"), NOW.plusSeconds(60)));

        assertCode(() -> stale.authorize(request()),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        assertCode(() -> missingPopulation.authorize(request()), ErrorCode.FORBIDDEN);
    }

    private HcmPopulationFreezeAuthorityAdapter adapter(
            PeopleRequestContext.Actor actor,
            HcmPepContext.Evidence evidence) {
        return new HcmPopulationFreezeAuthorityAdapter(
                () -> evidence, () -> actor, ignored -> targetPopulation(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PopulationFreezeAuthorityPort.TargetPopulation targetPopulation() {
        return new PopulationFreezeAuthorityPort.TargetPopulation(
                false, UUID.randomUUID().toString(), Set.of(UUID.randomUUID()),
                Set.of("DIRECTORY", "EMPLOYMENT"), "population:17");
    }

    private PeopleRequestContext.Actor actor(
            long tenant,
            UUID subject,
            Set<String> permissions) {
        return new PeopleRequestContext.Actor(
                ACTOR, tenant, subject, Set.of("OPERATOR"), permissions);
    }

    private HcmPepContext.Evidence validEvidence() {
        return evidence(HcmPopulationFreezeAuthorityAdapter.FREEZE_ROUTE_CONTRACT,
                HcmPopulationFreezeAuthorityAdapter.FREEZE_CAPABILITY_CONTRACT,
                Set.of("predicate.hcm-domain-target-population.v1"),
                Set.of("TARGET_POPULATION"), NOW.plusSeconds(60));
    }

    private HcmPepContext.Evidence reviewEvidence() {
        String route = HcmPopulationFreezeAuthorityAdapter.REVIEW_OPERATIONS_ROUTE_CONTRACT;
        HcmV3PepRegistry.RouteAuthority authority = new HcmV3PepRegistry.RouteAuthority(
                route, "PAGE", "performance-operations-reader", true,
                Set.of("predicate.hcm-domain-target-population.v1"),
                Set.of("TARGET_POPULATION"), route + ".binding.01",
                HcmPopulationFreezeAuthorityAdapter.REVIEW_OPERATIONS_CAPABILITY_CONTRACT,
                "ACTIVE", "GET",
                "/v1/hris/performance/workbench/review-operations", null,
                "projection.hcm.performance.review-operations.v1",
                "schema.hcm.performance.review-operations.v1");
        return new HcmPepContext.Evidence(
                authority, "psr-" + "c".repeat(64),
                OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC),
                "psc-" + "a".repeat(64), "hcm-scope-" + "b".repeat(40), "111");
    }

    private HcmPepContext.Evidence evidence(
            String route,
            String capability,
            Set<String> predicates,
            Set<String> targetKinds,
            Instant revalidateAt) {
        HcmV3PepRegistry.RouteAuthority authority = new HcmV3PepRegistry.RouteAuthority(
                route, "ACTION", "full-management", false, predicates, targetKinds,
                route + ".binding.01", capability, "ACTIVE", "POST",
                "/v1/hris/performance/cycles/{cycleId}/population-freezes", null);
        return new HcmPepContext.Evidence(
                authority, "psr-" + "c".repeat(64),
                OffsetDateTime.ofInstant(revalidateAt, ZoneOffset.UTC),
                "psc-" + "a".repeat(64), "hcm-scope-" + "b".repeat(40), "111");
    }

    private PopulationFreezeAuthorityPort.AuthorityRequest request() {
        return new PopulationFreezeAuthorityPort.AuthorityRequest(
                TENANT, ACTOR, SUBJECT, PopulationFreezeService.APPLICATION_ENTITLEMENT,
                PopulationFreezeService.RESOURCE, PopulationFreezeService.AUTHORITY_ACTION,
                PopulationFreezeService.ORIGINATING_ACTION,
                PopulationFreezeService.AUTHORITY_PURPOSE, UUID.randomUUID());
    }

    private void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
