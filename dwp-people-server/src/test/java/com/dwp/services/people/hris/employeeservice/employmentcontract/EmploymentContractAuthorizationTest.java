package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmploymentContractAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final UUID ACTOR = UUID.fromString(
            "11111111-1111-4111-8111-111111111111");
    private static final UUID TARGET = UUID.fromString(
            "22222222-2222-4222-8222-222222222222");

    @Test
    void exactSevenLayerRouteAndLockedPopulationAreRequired() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HcmPopulationRepository repository = mock(HcmPopulationRepository.class);
        HcmPopulationScopeService.ResolvedPopulation resolved = population(
                Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populations.requireOperationsForMutation("READ")).thenReturn(resolved);
        when(repository.lockWorkerInPopulation(71L, resolved.scope(), 91L))
                .thenReturn(true);
        EmploymentContractAuthorization authorization = authorization(
                actor(Set.of(),
                        Set.of(EmploymentContractAuthorization.PERMISSION)),
                evidence(), populations, repository);

        EmploymentContractAuthorization.Decision initial =
                authorization.authorize(null);
        EmploymentContractAuthorization.Decision completed =
                authorization.requireMutationPopulation(initial, 91L, TARGET);

        assertThat(completed.population()).isSameAs(resolved);
        verify(populations).requireOperationsForMutation("READ");
        verify(repository).lockWorkerInPopulation(71L, resolved.scope(), 91L);
    }

    @Test
    void exactRouteWorksWithoutDuplicatedGlobalRolesButPermissionIsRequired() {
        EmploymentContractAuthorization withoutGlobalRoles = authorization(
                actor(Set.of(), Set.of(EmploymentContractAuthorization.PERMISSION)),
                evidence(), mock(HcmPopulationScopeService.class),
                mock(HcmPopulationRepository.class));
        assertThat(withoutGlobalRoles.authorize(null).actor().roles()).isEmpty();

        EmploymentContractAuthorization missingPermission = authorization(
                actor(Set.of(), Set.of()), evidence(),
                mock(HcmPopulationScopeService.class),
                mock(HcmPopulationRepository.class));
        assertThatThrownBy(() -> missingPermission.authorize(null))
                .isInstanceOf(EmploymentContractException.class);

        EmploymentContractAuthorization correct = authorization(
                actor(Set.of(),
                        Set.of(EmploymentContractAuthorization.PERMISSION)),
                evidence(), mock(HcmPopulationScopeService.class),
                mock(HcmPopulationRepository.class));
        assertThatThrownBy(() -> correct.authorize("PAYROLL_PROCESSING"))
                .isInstanceOf(EmploymentContractException.class);
    }

    @Test
    void noApproveOwnChangeAndOutOfPopulationAreDenied() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HcmPopulationRepository repository = mock(HcmPopulationRepository.class);
        EmploymentContractAuthorization authorization = authorization(
                actor(Set.of(),
                        Set.of(EmploymentContractAuthorization.PERMISSION)),
                evidence(), populations, repository);
        EmploymentContractAuthorization.Decision initial = authorization.authorize(null);

        assertThatThrownBy(() -> authorization.requireMutationPopulation(
                initial, 91L, ACTOR))
                .isInstanceOf(EmploymentContractException.class)
                .hasMessageContaining("NO_APPROVE_OWN_CHANGE");

        HcmPopulationScopeService.ResolvedPopulation resolved =
                population(Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populations.requireOperationsForMutation("READ")).thenReturn(resolved);
        when(repository.lockWorkerInPopulation(71L, resolved.scope(), 91L))
                .thenReturn(false);
        assertThatThrownBy(() -> authorization.requireMutationPopulation(
                initial, 91L, TARGET))
                .isInstanceOf(EmploymentContractException.class)
                .hasMessageContaining("outside the authorized population");
    }

    private EmploymentContractAuthorization authorization(
            PeopleRequestContext.Actor actor,
            HcmPepContext.Evidence evidence,
            HcmPopulationScopeService populations,
            HcmPopulationRepository repository) {
        return new EmploymentContractAuthorization(
                () -> actor, () -> evidence, populations, repository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PeopleRequestContext.Actor actor(Set<String> roles, Set<String> permissions) {
        return new PeopleRequestContext.Actor(7L, 71L, ACTOR, roles, permissions);
    }

    private HcmPepContext.Evidence evidence() {
        HcmV3PepRegistry.RouteAuthority route = new HcmV3PepRegistry.RouteAuthority(
                EmploymentContractAuthorization.ROUTE, "ACTION",
                EmploymentContractAuthorization.PROFILE, false,
                Set.of("predicate.hcm-workforce-visible-person.v1",
                        "predicate.people.object-version.v1"),
                Set.of("OBJECT", "TARGET_POPULATION"),
                EmploymentContractAuthorization.BINDING,
                EmploymentContractAuthorization.CAPABILITY,
                "STEPUP-MGMT-HIGH-V1", "POST",
                EmploymentContractAuthorization.BROWSER_PATH,
                new HcmV3PepRegistry.StepUpBinding(
                        EmploymentContractAuthorization.TARGET_TYPE,
                        null, List.of("relationshipPublicId"),
                        "people", "dwp-people-server"), null, null);
        return new HcmPepContext.Evidence(
                route, "psr-" + "a".repeat(64),
                OffsetDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC),
                "psc-" + "b".repeat(64), "scope-v1", "111", "NORMAL",
                EmploymentContractAuthorization.PURPOSE,
                EmploymentContractAuthorization.FIELD_POLICY);
    }

    private HcmPopulationScopeService.ResolvedPopulation population(Set<String> fields) {
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        9L, null, true, Set.of(), fields, "policy-v1");
        return new HcmPopulationScopeService.ResolvedPopulation(
                null, scope,
                new HcmPopulationRepository.PopulationEvidence(1, "evidence-v1"));
    }
}
