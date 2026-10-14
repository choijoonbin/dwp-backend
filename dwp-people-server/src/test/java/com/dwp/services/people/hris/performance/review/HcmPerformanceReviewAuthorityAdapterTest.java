package com.dwp.services.people.hris.performance.review;

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

class HcmPerformanceReviewAuthorityAdapterTest {

    private static final Instant NOW = Instant.parse("2027-05-01T00:00:00Z");
    private static final String REVISION = "psr-" + "c".repeat(64);

    @Test
    void acceptsExactAssignedReadProjectionAndSelfSubmitDecision() {
        HcmPerformanceReviewAuthorityAdapter assigned = adapter(
                actor(PerformanceReviewTestFixtures.TENANT,
                        PerformanceReviewTestFixtures.SUBJECT),
                evidence(
                        HcmPerformanceReviewAuthorityAdapter.GET_ROUTE,
                        "DATA", "GET", true, "assigned-reviewer",
                        HcmPerformanceReviewAuthorityAdapter.ASSIGNED_CAPABILITY,
                        "TARGET_POPULATION",
                        HcmPerformanceReviewAuthorityAdapter.GET_ROUTE
                                + ".assigned-reviewer.projection.v1",
                        "AssignedReviewV1"));

        PerformanceReviewAuthorityPort.AuthorityEvidence read = assigned.authorize(
                request(PerformanceReviewService.GET_OPERATION,
                        "TARGET_POPULATION", null));

        assertThat(read.profileKey()).isEqualTo("assigned-reviewer");
        assertThat(read.populationScopeDigest()).matches("[0-9a-f]{64}");
        assertThat(read.fieldPolicyRevision()).isPositive();

        HcmPerformanceReviewAuthorityAdapter self = adapter(
                actor(PerformanceReviewTestFixtures.TENANT,
                        PerformanceReviewTestFixtures.SUBJECT),
                evidence(
                        HcmPerformanceReviewAuthorityAdapter.SUBMIT_ROUTE,
                        "ACTION", "POST", false, "self-reviewer",
                        HcmPerformanceReviewAuthorityAdapter.SELF_CAPABILITY,
                        "SELF", null, null));
        assertThat(self.authorize(request(
                PerformanceReviewService.SUBMIT_OPERATION, "SELF", REVISION))
                .profileKey()).isEqualTo("self-reviewer");
    }

    @Test
    void rejectsWrongTenantPurposePopulationAndAssignmentProfile() {
        HcmPepContext.Evidence valid = evidence(
                HcmPerformanceReviewAuthorityAdapter.PATCH_ROUTE,
                "ACTION", "PATCH", false, "assigned-reviewer",
                HcmPerformanceReviewAuthorityAdapter.ASSIGNED_CAPABILITY,
                "TARGET_POPULATION", null, null);
        HcmPerformanceReviewAuthorityAdapter crossTenant = adapter(
                actor(PerformanceReviewTestFixtures.TENANT + 1,
                        PerformanceReviewTestFixtures.SUBJECT), valid);
        HcmPerformanceReviewAuthorityAdapter exact = adapter(
                actor(PerformanceReviewTestFixtures.TENANT,
                        PerformanceReviewTestFixtures.SUBJECT), valid);

        assertCode(() -> crossTenant.authorize(request(
                PerformanceReviewService.SAVE_OPERATION,
                "TARGET_POPULATION", REVISION)), ErrorCode.FORBIDDEN);
        PerformanceReviewAuthorityPort.AuthorityRequest wrongPurpose =
                new PerformanceReviewAuthorityPort.AuthorityRequest(
                        PerformanceReviewTestFixtures.TENANT,
                        PerformanceReviewTestFixtures.ACTOR,
                        PerformanceReviewTestFixtures.SUBJECT,
                        PerformanceReviewTestFixtures.REVIEW,
                        PerformanceReviewService.SAVE_OPERATION,
                        "PERFORMANCE_OPERATIONS", "TARGET_POPULATION", REVISION);
        assertCode(() -> exact.authorize(wrongPurpose),
                ErrorCode.FORBIDDEN);
        assertCode(() -> exact.authorize(request(
                PerformanceReviewService.SAVE_OPERATION, "SELF", REVISION)),
                ErrorCode.FORBIDDEN);
    }

    @Test
    void rejectsMissingPrivateProjectionStaleDecisionAndDecisionRevisionDrift() {
        HcmPerformanceReviewAuthorityAdapter missingProjection = adapter(
                actor(PerformanceReviewTestFixtures.TENANT,
                        PerformanceReviewTestFixtures.SUBJECT),
                evidence(
                        HcmPerformanceReviewAuthorityAdapter.GET_ROUTE,
                        "DATA", "GET", true, "assigned-reviewer",
                        HcmPerformanceReviewAuthorityAdapter.ASSIGNED_CAPABILITY,
                        "TARGET_POPULATION", "wrong.projection", "AssignedReviewV1"));
        assertCode(() -> missingProjection.authorize(request(
                PerformanceReviewService.GET_OPERATION, "TARGET_POPULATION", null)),
                ErrorCode.FORBIDDEN);

        HcmPerformanceReviewAuthorityAdapter stale = adapter(
                actor(PerformanceReviewTestFixtures.TENANT,
                        PerformanceReviewTestFixtures.SUBJECT),
                evidence(
                        HcmPerformanceReviewAuthorityAdapter.PATCH_ROUTE,
                        "ACTION", "PATCH", false, "assigned-reviewer",
                        HcmPerformanceReviewAuthorityAdapter.ASSIGNED_CAPABILITY,
                        "TARGET_POPULATION", null, null, NOW));
        assertCode(() -> stale.authorize(request(
                PerformanceReviewService.SAVE_OPERATION,
                "TARGET_POPULATION", REVISION)),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);

        HcmPerformanceReviewAuthorityAdapter exact = adapter(
                actor(PerformanceReviewTestFixtures.TENANT,
                        PerformanceReviewTestFixtures.SUBJECT),
                evidence(
                        HcmPerformanceReviewAuthorityAdapter.PATCH_ROUTE,
                        "ACTION", "PATCH", false, "assigned-reviewer",
                        HcmPerformanceReviewAuthorityAdapter.ASSIGNED_CAPABILITY,
                        "TARGET_POPULATION", null, null));
        assertCode(() -> exact.authorize(request(
                PerformanceReviewService.SAVE_OPERATION,
                "TARGET_POPULATION", "psr-" + "d".repeat(64))),
                ErrorCode.DECISION_REVISION_CONFLICT);
    }

    private HcmPerformanceReviewAuthorityAdapter adapter(
            PeopleRequestContext.Actor actor,
            HcmPepContext.Evidence evidence) {
        return new HcmPerformanceReviewAuthorityAdapter(
                () -> evidence, () -> actor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PeopleRequestContext.Actor actor(long tenant, UUID subject) {
        return new PeopleRequestContext.Actor(
                PerformanceReviewTestFixtures.ACTOR, tenant, subject,
                Set.of("EMPLOYEE"), Set.of(
                HcmPerformanceReviewAuthorityAdapter.PRODUCT_PERMISSION,
                HcmPerformanceReviewAuthorityAdapter.RESOURCE_PERMISSION));
    }

    private HcmPepContext.Evidence evidence(
            String route,
            String routeKind,
            String method,
            boolean readOnly,
            String profile,
            String capability,
            String population,
            String projection,
            String responseSchema) {
        return evidence(route, routeKind, method, readOnly, profile, capability,
                population, projection, responseSchema, NOW.plusSeconds(60));
    }

    private HcmPepContext.Evidence evidence(
            String route,
            String routeKind,
            String method,
            boolean readOnly,
            String profile,
            String capability,
            String population,
            String projection,
            String responseSchema,
            Instant revalidateAt) {
        HcmV3PepRegistry.RouteAuthority authority = new HcmV3PepRegistry.RouteAuthority(
                route, routeKind, profile, readOnly,
                Set.of("predicate.hcm-performance-review-assignment.v1"),
                Set.of("OBJECT", population), route + ".binding.01",
                capability, null, method,
                "/api/people/v1/hris/performance/reviews/{reviewId}", null,
                projection, responseSchema);
        return new HcmPepContext.Evidence(
                authority, REVISION,
                OffsetDateTime.ofInstant(revalidateAt, ZoneOffset.UTC),
                "psc-" + "a".repeat(64), "hcm-scope-" + "b".repeat(40), "111");
    }

    private PerformanceReviewAuthorityPort.AuthorityRequest request(
            String operation,
            String population,
            String expectedRevision) {
        return new PerformanceReviewAuthorityPort.AuthorityRequest(
                PerformanceReviewTestFixtures.TENANT,
                PerformanceReviewTestFixtures.ACTOR,
                PerformanceReviewTestFixtures.SUBJECT,
                PerformanceReviewTestFixtures.REVIEW,
                operation, HcmPerformanceReviewAuthorityAdapter.PURPOSE,
                population, expectedRevision);
    }

    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(code);
    }
}
