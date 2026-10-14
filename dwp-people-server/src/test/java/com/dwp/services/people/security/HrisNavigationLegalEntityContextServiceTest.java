package com.dwp.services.people.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.people.security.HrisNavigationLegalEntityContextDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HrisNavigationLegalEntityContextServiceTest {

    private static final long TENANT = 42L;
    private static final long ACTOR = 101L;
    private static final UUID PERSON = UUID.randomUUID();
    private static final UUID EMPLOYER = UUID.randomUUID();

    private final HcmPopulationRepository populations = mock(HcmPopulationRepository.class);
    private final HcmPopulationScopeService scopes = mock(HcmPopulationScopeService.class);
    private final HrisNavigationLegalEntityContextRepository employers =
            mock(HrisNavigationLegalEntityContextRepository.class);
    private final HrisNavigationLegalEntityContextService service =
            new HrisNavigationLegalEntityContextService(populations, scopes, employers);

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
    }

    @Test
    void verifiesManagementScopeAndEmployerAgainstCurrentOperationsPopulation() {
        PeopleRequestContext.set(
                ACTOR, TENANT, PERSON, Set.of("HR_ADMIN"), Set.of("APP.HCM:VIEW"));
        HcmPopulationScopeService.ResolvedPopulation operations = operations();
        when(scopes.findOperations("READ")).thenReturn(Optional.of(operations));
        when(populations.tenantRevision(TENANT)).thenReturn(Optional.of("7:11"));
        when(employers.isCurrentOperationsLegalEmployer(
                TENANT, operations.scope(), EMPLOYER)).thenReturn(true);
        String source = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.management",
                "RS_HCM_CONFIG", "RESOURCE_SET");
        String selected = HcmEligibilityScopeKeys.derived(
                TENANT, ACTOR, "hcm.management", source,
                "auth-responsibility", "config:7:11");

        VerifyResult result = service.verify(new VerifyRequest(
                TENANT, ACTOR, ContextSurface.MANAGEMENT, selected, EMPLOYER));

        assertThat(result.verified()).isTrue();
        assertThat(result.legalEmployerPublicId()).isEqualTo(EMPLOYER);
        assertThat(result.ownerEvidence()).matches("people-owner-sha256:[a-f0-9]{64}");
        verify(employers).isCurrentOperationsLegalEmployer(
                TENANT, operations.scope(), EMPLOYER);
    }

    @Test
    void rejectsStaleSelectedScopeBeforeEmployerLookup() {
        PeopleRequestContext.set(
                ACTOR, TENANT, PERSON, Set.of("HR_ADMIN"), Set.of("APP.HCM:VIEW"));
        when(scopes.findOperations("READ")).thenReturn(Optional.of(operations()));
        when(populations.tenantRevision(TENANT)).thenReturn(Optional.of("7:11"));

        assertThatThrownBy(() -> service.verify(new VerifyRequest(
                TENANT, ACTOR, ContextSurface.MANAGEMENT,
                "hcm-scope-" + "a".repeat(40), EMPLOYER)))
                .hasMessageContaining("SELECTED_SCOPE_STALE");
        verifyNoInteractions(employers);
    }

    private HcmPopulationScopeService.ResolvedPopulation operations() {
        HcmPopulationRepository.ActorWorkforce actor =
                new HcmPopulationRepository.ActorWorkforce(
                        71L, PERSON, "Operator", "A-71", "HRBP", "People",
                        2L, 3L, 5L);
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        71L, "A-71", true, Set.of(),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "policy-9");
        return new HcmPopulationScopeService.ResolvedPopulation(
                actor, scope, new HcmPopulationRepository.PopulationEvidence(12, "pop-4"));
    }
}
