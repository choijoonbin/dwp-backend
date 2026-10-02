package com.dwp.services.people.workforce;

import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class People360OwnerAuthorityFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-29T01:00:00Z");
    private static final String PERSON = "10000000-0000-0000-0000-000000000001";

    @Test
    void permitsAnExactOperationsDetailBinding() throws Exception {
        HcmPepContext.Evidence evidence = evidence(
                People360OwnerAuthorityFilter.DETAIL_ROUTE,
                "hcm.operations.workforce.read",
                "predicate.hcm-workforce-visible-person.v1",
                "TARGET_POPULATION",
                "hcm.people360.operations-detail.v1",
                "People360SnapshotV1",
                NOW.plusSeconds(60));
        People360OwnerAuthorityFilter filter = filter(evidence);
        MockHttpServletRequest request = request(
                "/v1/workforce/people/" + PERSON);
        request.addParameter("projection", "people360");
        request.addParameter("asOf", "2026-09-29");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingDuplicateOrUnexpectedQueryValuesBeforeTheOwner() throws Exception {
        People360OwnerAuthorityFilter filter = filter(evidence(
                People360OwnerAuthorityFilter.TEAM_ROUTE,
                null,
                "predicate.team-target-population.v1",
                "TARGET_POPULATION",
                "hcm.people360.team-detail.v1",
                "People360SnapshotV1",
                NOW.plusSeconds(60)));
        MockHttpServletRequest missing = request("/v1/hr/team");
        missing.addParameter("projection", "people360");
        missing.addParameter("personId", PERSON);
        MockHttpServletRequest duplicate = request("/v1/hr/team");
        duplicate.addParameter("projection", "people360");
        duplicate.addParameter("asOf", "2026-09-29", "2026-09-30");
        duplicate.addParameter("personId", PERSON);
        MockHttpServletRequest unexpected = request("/v1/hr/team");
        unexpected.addParameter("projection", "people360");
        unexpected.addParameter("asOf", "2026-09-29");
        unexpected.addParameter("personId", PERSON);
        unexpected.addParameter("tenantId", "999");
        FilterChain chain = mock(FilterChain.class);

        assertRejected(filter, missing, chain, 400);
        assertRejected(filter, duplicate, chain, 400);
        assertRejected(filter, unexpected, chain, 400);
        verify(chain, never()).doFilter(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsACompatibleLegacyRouteAsInsufficientPeople360Authority() throws Exception {
        People360OwnerAuthorityFilter filter = filter(evidence(
                "route.hcm.personal.home.page",
                null,
                "predicate.self-person.v1",
                "SELF",
                "hcm.people360.self-detail.v1",
                "People360SnapshotV1",
                NOW.plusSeconds(60)));
        MockHttpServletRequest request = request("/v1/hr/home");
        request.addParameter("projection", "people360");
        request.addParameter("asOf", "2026-09-29");

        assertRejected(filter, request, mock(FilterChain.class), 403);
    }

    @Test
    void rejectsMissingOrExpiredCurrentAuthority() throws Exception {
        MockHttpServletRequest request = request("/v1/hr/home");
        request.addParameter("projection", "people360");
        request.addParameter("asOf", "2026-09-29");

        assertRejected(filter(null), request, mock(FilterChain.class), 503);
        assertRejected(filter(evidence(
                People360OwnerAuthorityFilter.SELF_ROUTE,
                null,
                "predicate.self-person.v1",
                "SELF",
                "hcm.people360.self-detail.v1",
                "People360SnapshotV1",
                NOW)), request, mock(FilterChain.class), 503);
    }

    @Test
    void rejectsProjectionPolicyOrResponseSchemaDrift() throws Exception {
        People360OwnerAuthorityFilter filter = filter(evidence(
                People360OwnerAuthorityFilter.SEARCH_ROUTE,
                "hcm.operations.workforce.read",
                "predicate.hcm-workforce-visible-person.v1",
                "TARGET_POPULATION",
                "legacy.people.page.v1",
                "People360PageV1",
                NOW.plusSeconds(60)));
        MockHttpServletRequest request = request("/v1/workforce/people");
        request.addParameter("projection", "people360");
        request.addParameter("asOf", "2026-09-29");

        assertRejected(filter, request, mock(FilterChain.class), 403);
    }

    private People360OwnerAuthorityFilter filter(HcmPepContext.Evidence evidence) {
        return new People360OwnerAuthorityFilter(
                new ObjectMapper().findAndRegisterModules(),
                () -> evidence, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private HcmPepContext.Evidence evidence(
            String route,
            String capability,
            String predicate,
            String target,
            String projection,
            String schema,
            Instant revalidateAt) {
        HcmV3PepRegistry.RouteAuthority authority = new HcmV3PepRegistry.RouteAuthority(
                route, "DATA", "full-work", true, Set.of(predicate), Set.of(target),
                route + ".binding.01", capability, "ACTIVE", "GET",
                "/v1/people360", null, projection, schema);
        return new HcmPepContext.Evidence(
                authority, "psr-" + "c".repeat(64),
                OffsetDateTime.ofInstant(revalidateAt, ZoneOffset.UTC),
                "psc-" + "a".repeat(64), "hcm-scope-" + "b".repeat(40), "111");
    }

    private void assertRejected(
            People360OwnerAuthorityFilter filter,
            MockHttpServletRequest request,
            FilterChain chain,
            int expectedStatus) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
    }
}
