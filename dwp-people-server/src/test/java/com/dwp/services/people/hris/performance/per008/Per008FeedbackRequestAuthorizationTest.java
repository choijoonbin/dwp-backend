package com.dwp.services.people.hris.performance.per008;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.people.hris.performance.per008.Per008Contracts.FieldDecision;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class Per008FeedbackRequestAuthorizationTest {
    private static final long TENANT = 7;
    private static final UUID PRINCIPAL = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WORKER = PRINCIPAL;
    private static final UUID ASSIGNMENT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @AfterEach
    void clearTrustedContext() {
        PeopleRequestContext.clear();
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
    }

    @Test
    void acceptsOnlyTheExactManagerOptionsRouteManifestAndBuildsSevenLayers() {
        install(evidence(Set.of("OBJECT", "TARGET_POPULATION"),
                Set.of("predicate.team-target-population.v1",
                        "predicate.hcm-performance-feedback-request.v1")));

        AuthorityEvidence result = adapter().authorize(
                new AuthorityRequest(Operation.REQUEST_OPTIONS, null));

        assertThat(result.profileKey()).isEqualTo("manager-feedback-requester");
        assertThat(result.capabilityKey()).isEqualTo("hcm.team.performance.review");
        assertThat(result.satisfiedLayers()).containsExactlyInAnyOrder(PepLayer.values());
    }

    @Test
    void rejectsNearMatchTargetAndPredicateManifests() {
        install(evidence(Set.of("OBJECT"), Set.of(
                "predicate.hcm-performance-feedback-request.v1")));

        assertThatThrownBy(() -> adapter().authorize(
                new AuthorityRequest(Operation.REQUEST_OPTIONS, null)))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_EXACT_ROUTE_AUTHORITY_REQUIRED");
    }

    private HcmPer008AuthorityAdapter adapter() {
        HcmPer008AuthorityAdapter.OwnerAuthorityDataPort data =
                new HcmPer008AuthorityAdapter.OwnerAuthorityDataPort() {
                    @Override public SubjectBinding actorBinding(long tenantId, UUID principal) {
                        return new SubjectBinding(WORKER, ASSIGNMENT);
                    }
                    @Override public Set<SubjectBinding> permittedSubjects(long tenantId,
                            String profileKey, String scopeRevision) {
                        return Set.of(new SubjectBinding(WORKER, ASSIGNMENT));
                    }
                    @Override public Map<String, FieldDecision> fieldDecisions(long tenantId,
                            String policy, String profile, Operation operation) {
                        return Map.of(
                                "PERFORMANCE_FEEDBACK_TEMPLATE_OPTION", FieldDecision.VIEW,
                                "PERFORMANCE_FEEDBACK_POLICY_LIMIT", FieldDecision.VIEW);
                    }
                    @Override public boolean tenantPredicateBound(long tenantId) { return true; }
                };
        return new HcmPer008AuthorityAdapter(data, mock(AuditOutboxRecorder.class),
                Clock.fixed(NOW, ZoneOffset.UTC), Set.of(TENANT));
    }

    private void install(HcmPepContext.Evidence evidence) {
        PeopleRequestContext.set(9L, TENANT, PRINCIPAL, Set.of("EMPLOYEE"), Set.of());
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set", evidence);
    }

    private HcmPepContext.Evidence evidence(Set<String> targets, Set<String> predicates) {
        HcmV3PepRegistry.RouteAuthority authority = new HcmV3PepRegistry.RouteAuthority(
                "route.hcm.performance.feedback-request-options.data", "DATA",
                "manager-feedback-requester", true, predicates, targets,
                "route.hcm.performance.feedback-request-options.data.binding.01",
                "hcm.team.performance.review", null, "GET",
                "/api/people/v1/hris/performance/feedback-request-options", null,
                "per008.feedback-request-options.fields.v1",
                "Per008FeedbackRequestOptionsV1");
        return new HcmPepContext.Evidence(authority, "psr-" + "c".repeat(64),
                OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC),
                "psc-" + "a".repeat(64), "hcm-scope-" + "b".repeat(40), "111",
                "NORMAL", "PERFORMANCE_FEEDBACK",
                "per008.feedback-request-options.fields.v1");
    }
}
