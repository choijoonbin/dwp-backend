package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Hrm011QueryAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");

    @Test
    void exactDataEvidenceAndPermissionWorkWithoutGlobalRolesButNeverInferAction() {
        HcmPopulationScopeService populations = populations();
        Hrm011QueryAuthority authority = authority(
                actor(Set.of(Hrm011QueryAuthority.PERMISSION)), populations);

        Hrm011QueryAuthority.Decision decision = authority.authorize();

        assertThat(decision.actor().roles()).isEmpty();
        assertThat(decision.preliminaryIssueAllowed()).isFalse();
        assertThat(decision.fields()).containsEntry(
                "COMPENSATION_BASIS", Hrm011QueryAuthority.FieldDecision.VIEW);
        assertThat(decision.fields()).containsEntry(
                "CONTRACT_METADATA", Hrm011QueryAuthority.FieldDecision.VIEW);
    }

    @Test
    void exactDataEvidenceWithoutResourcePermissionIsDenied() {
        Hrm011QueryAuthority authority = authority(actor(Set.of()), populations());

        assertThatThrownBy(authority::authorize)
                .isInstanceOf(EmploymentContractException.class)
                .satisfies(error -> assertThat(
                        ((EmploymentContractException) error).status()).isEqualTo(403));
    }

    private Hrm011QueryAuthority authority(
            PeopleRequestContext.Actor actor, HcmPopulationScopeService populations) {
        return new Hrm011QueryAuthority(
                () -> actor, this::evidence, populations,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PeopleRequestContext.Actor actor(Set<String> permissions) {
        return new PeopleRequestContext.Actor(
                7L, 71L,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                Set.of(), permissions);
    }

    private HcmPopulationScopeService populations() {
        HcmPopulationScopeService service = mock(HcmPopulationScopeService.class);
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        9L, null, true, Set.of(),
                        Set.of("DIRECTORY", "EMPLOYMENT"),
                        "policy-v1");
        HcmPopulationScopeService.ResolvedPopulation resolved =
                new HcmPopulationScopeService.ResolvedPopulation(
                        null, scope,
                        new HcmPopulationRepository.PopulationEvidence(1, "evidence-v1"));
        when(service.requireOperations("READ")).thenReturn(resolved);
        return service;
    }

    private HcmPepContext.Evidence evidence() {
        HcmV3PepRegistry.RouteAuthority route = new HcmV3PepRegistry.RouteAuthority(
                Hrm011QueryAuthority.ROUTE, "DATA", Hrm011QueryAuthority.PROFILE, true,
                Set.of("predicate.hcm-workforce-visible-person.v1"),
                Set.of("TARGET_POPULATION"), Hrm011QueryAuthority.BINDING,
                "hcm.operations.people.view", null, "GET",
                Hrm011QueryAuthority.BROWSER_PATH, null,
                Hrm011QueryAuthority.PROJECTION, Hrm011QueryAuthority.RESPONSE);
        return new HcmPepContext.Evidence(
                route, "psr-" + "a".repeat(64),
                OffsetDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC),
                "psc-" + "b".repeat(64), "scope-v1", "111", "NORMAL",
                Hrm011QueryAuthority.PURPOSE, Hrm011QueryAuthority.FIELD_POLICY);
    }
}
