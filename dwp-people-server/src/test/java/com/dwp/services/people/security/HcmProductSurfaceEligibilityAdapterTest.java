package com.dwp.services.people.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class HcmProductSurfaceEligibilityAdapterTest {

    private static final long TENANT = 7L;
    private static final long ACTOR = 19L;
    private static final UUID PERSON = UUID.randomUUID();
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 25, 2, 0, 0, 0, ZoneOffset.UTC);

    private final HcmPopulationRepository repository = mock(HcmPopulationRepository.class);
    private final HcmPopulationScopeService populations =
            mock(HcmPopulationScopeService.class);
    private final HcmProductSurfaceEligibilityAdapter adapter =
            new HcmProductSurfaceEligibilityAdapter(repository, populations);

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
    }

    @Test
    void derivesAnActorAndTenantBoundTeamScopeFromLivePopulationEvidence() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("MANAGER"), Set.of());
        HcmPopulationScopeService.ResolvedPopulation population = teamPopulation();
        when(populations.findTeam()).thenReturn(Optional.of(population));
        String source = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.team",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.team", source, "TARGET_POPULATION", null));

        assertThat(result.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(source);
            assertThat(scope.key()).startsWith("hcm-scope-");
            assertThat(scope.isDefault()).isTrue();
            assertThat(scope.readOnly()).isFalse();
        });
        assertThat(result.productRelationshipRevision()).startsWith("hcm-rel-");
        assertThat(result.targetPopulationRevision()).startsWith("hcm-pop-");
    }

    @Test
    void derivesTheExactTeamSelectorOnTheOperationsEmployeeChangeSurface() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("MANAGER"), Set.of());
        HcmPopulationScopeService.ResolvedPopulation population = teamPopulation();
        when(populations.findTeam()).thenReturn(Optional.of(population));
        String operationsTeam = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TARGET_POPULATION");
        String teamSurfaceReplay = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.team",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult allowed = adapter.evaluate(
                request("hcm.operations", operationsTeam, "TARGET_POPULATION", null));
        ProductSurfaceEligibilityDtos.EligibilityResult denied = adapter.evaluate(
                request("hcm.operations", teamSurfaceReplay, "TARGET_POPULATION", null));

        assertThat(allowed.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(allowed.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(operationsTeam);
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
            assertThat(scope.key()).startsWith("hcm-scope-");
        });
        assertThat(denied.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
        assertThat(denied.scopes()).isEmpty();
        verify(populations).findTeam();
    }

    @Test
    void hrm011WorkforceSelectorRebindsToOwnerDerivedOperationsPopulation() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("HR_ADMIN"), Set.of());
        when(populations.findOperations("READ")).thenReturn(Optional.of(teamPopulation()));
        String authSelector = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations",
                "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.operations", authSelector, "TARGET_POPULATION", null));

        assertThat(result.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(authSelector);
            assertThat(scope.key()).startsWith("hcm-scope-");
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
            assertThat(scope.isDefault()).isTrue();
        });
        verify(populations).findOperations("READ");
    }

    @Test
    void derivesASelfScopeFromTheLivePersonBindingWithoutTargetPopulationEvidence() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("WORKSPACE_MEMBER"), Set.of());
        HcmPopulationRepository.ActorWorkforce actor =
                new HcmPopulationRepository.ActorWorkforce(
                        41L, PERSON, "Member", "A-MEMBER", "Engineer", "Platform",
                        2L, 4L, 7L);
        when(repository.actor(TENANT, PERSON)).thenReturn(Optional.of(actor));
        String source = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.personal", "SELF", "SELF");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.personal", source, "SELF", null));

        assertThat(result.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(source);
            assertThat(scope.key()).startsWith("hcm-scope-");
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.isDefault()).isTrue();
            assertThat(scope.readOnly()).isFalse();
        });
        assertThat(result.productRelationshipRevision()).startsWith("hcm-rel-");
        assertThat(result.targetPopulationRevision()).startsWith("hcm-pop-");
        verifyNoInteractions(populations);
    }

    @Test
    void derivesOnlyTheExactUtilitySelfSelectorFromTheCurrentActorBinding() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("WORKSPACE_MEMBER"), Set.of());
        HcmPopulationRepository.ActorWorkforce actor =
                new HcmPopulationRepository.ActorWorkforce(
                        41L, PERSON, "Member", "A-MEMBER", "Engineer", "Platform",
                        2L, 4L, 7L);
        when(repository.actor(TENANT, PERSON)).thenReturn(Optional.of(actor));
        String utilitySelf = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.utility", "SELF", "SELF");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.utility", utilitySelf, "SELF", null));

        assertThat(result.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(utilitySelf);
            assertThat(scope.key()).startsWith("hcm-scope-");
            assertThat(scope.kind()).isEqualTo("SELF");
            assertThat(scope.isDefault()).isTrue();
        });
        verifyNoInteractions(populations);
    }

    @Test
    void rejectsPersonalReplayWrongUtilitySourceKindAndUnknownSurface() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("WORKSPACE_MEMBER"), Set.of());
        String personalSelf = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.personal", "SELF", "SELF");
        String utilityTarget = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.utility", "SELF", "TARGET_POPULATION");
        String utilityUnknown = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.utility", "UNKNOWN", "SELF");

        for (ProductSurfaceEligibilityDtos.EvaluateRequest rejected : List.of(
                request("hcm.utility", personalSelf, "SELF", null),
                request("hcm.utility", utilityTarget, "TARGET_POPULATION", null),
                request("hcm.utility", utilityUnknown, "SELF", null),
                request("hcm.unknown", utilityUnknown, "SELF", null))) {
            ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(rejected);
            assertThat(result.decision()).isIn(
                    ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID,
                    ProductSurfaceEligibilityDtos.Decision.SURFACE_DENIED);
            assertThat(result.scopes()).isEmpty();
        }
        verify(repository, times(3)).tenantRevision(TENANT);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(populations);
    }

    @Test
    void utilityEligibilityFailsClosedWithoutTheVerifiedActorContext() {
        String utilitySelf = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.utility", "SELF", "SELF");

        assertThatThrownBy(() -> adapter.evaluate(
                request("hcm.utility", utilitySelf, "SELF", null)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository, populations);
    }

    @Test
    void rejectsAnOpaqueScopeIssuedForAnotherTenantOrResolver() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("MANAGER"), Set.of());
        when(populations.findTeam()).thenReturn(Optional.of(teamPopulation()));
        String crossTenant = ProductSurfaceScopeKey.key(
                TENANT + 1, ACTOR, "hcm", "hcm.team", "TEAM/ORG_UNIT",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.team", crossTenant, "TARGET_POPULATION", null));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
        assertThat(result.scopes()).isEmpty();
    }

    @Test
    void invalidatesAPreviouslySelectedDerivedScopeAfterPopulationChange() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("MANAGER"), Set.of());
        when(populations.findTeam()).thenReturn(Optional.of(teamPopulation()));
        String source = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.team", "TEAM/ORG_UNIT",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.team", source, "TARGET_POPULATION", "hcm-scope-stale"));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
        assertThat(result.reasonCode()).isEqualTo("HCM_SCOPE_CONTEXT_EXPIRED");
        assertThat(result.scopes()).isEmpty();
    }

    @Test
    void failsClosedWhenNoLiveTargetPopulationExists() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("HR_ADMIN"), Set.of());
        when(populations.findOperations("READ")).thenReturn(Optional.empty());
        when(repository.tenantRevision(TENANT)).thenReturn(Optional.of("3:9"));
        String source = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "TIME_TARGET_POPULATION",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.operations", source, "TARGET_POPULATION", null));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
        assertThat(result.scopes()).isEmpty();
    }

    @Test
    void materializesOnlySignedAssignedReviewAndSelfSelectorsOnOperationsSurface() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("WORKSPACE_MEMBER"), Set.of());
        HcmPopulationRepository.ActorWorkforce actor =
                new HcmPopulationRepository.ActorWorkforce(
                        41L, PERSON, "Reviewer", "A-REVIEWER", "Reviewer", "Talent",
                        2L, 4L, 7L);
        when(repository.actor(TENANT, PERSON)).thenReturn(Optional.of(actor));
        when(populations.findOperations("READ")).thenReturn(Optional.of(teamPopulation()));
        String assigned = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations",
                "ASSIGNED_REVIEW_POPULATION", "TARGET_POPULATION");
        String self = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "SELF", "SELF");

        ProductSurfaceEligibilityDtos.EligibilityResult assignedResult = adapter.evaluate(
                request("hcm.operations", assigned, "TARGET_POPULATION", null));
        ProductSurfaceEligibilityDtos.EligibilityResult selfResult = adapter.evaluate(
                request("hcm.operations", self, "SELF", null));

        assertThat(assignedResult.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(assignedResult.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(assigned);
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
        });
        assertThat(selfResult.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(selfResult.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(self);
            assertThat(scope.kind()).isEqualTo("SELF");
        });

        String inferred = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations",
                "SURVEY_RATER_ASSIGNMENT", "TARGET_POPULATION");
        ProductSurfaceEligibilityDtos.EligibilityResult rejected = adapter.evaluate(
                request("hcm.operations", inferred, "TARGET_POPULATION", null));
        assertThat(rejected.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
    }

    @Test
    void payrollFoundationAliasUsesTheCanonicalPayDerivedScopeOnlyForOperations() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("TENANT_ADMIN"), Set.of());
        when(populations.findOperations("READ")).thenReturn(Optional.of(teamPopulation()));
        String canonicalSource = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "PAY_TARGET_POPULATION",
                "TARGET_POPULATION");
        String actionSource = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "PAYROLL_LEGAL_ENTITY_SCOPE",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult page = adapter.evaluate(
                request("hcm.operations", canonicalSource, "TARGET_POPULATION", null));
        String selectedPageScope = page.scopes().getFirst().key();
        ProductSurfaceEligibilityDtos.EligibilityResult action = adapter.evaluate(
                request("hcm.operations", actionSource, "TARGET_POPULATION", selectedPageScope));

        assertThat(page.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(action.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(action.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(actionSource);
            assertThat(scope.key()).isEqualTo(selectedPageScope);
            assertThat(scope.isDefault()).isTrue();
        });

        String nonOperationsAlias = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.team", "PAYROLL_LEGAL_ENTITY_SCOPE",
                "TARGET_POPULATION");
        ProductSurfaceEligibilityDtos.EligibilityResult rejected = adapter.evaluate(
                request("hcm.team", nonOperationsAlias, "TARGET_POPULATION", null));
        assertThat(rejected.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
    }

    @Test
    void pay003LegalEntityGroupResolverCanonicalizesToTheExistingPayPopulationMaterial() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("PAYROLL_ADMIN"), Set.of());
        when(populations.findOperations("READ")).thenReturn(Optional.of(teamPopulation()));
        String canonicalSource = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "PAY_TARGET_POPULATION",
                "TARGET_POPULATION");
        String statutorySource = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations",
                "PAYROLL_LEGAL_ENTITY_GROUP_SCOPE", "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult canonical = adapter.evaluate(
                request("hcm.operations", canonicalSource, "TARGET_POPULATION", null));
        ProductSurfaceEligibilityDtos.EligibilityResult statutory = adapter.evaluate(
                request("hcm.operations", statutorySource, "TARGET_POPULATION",
                        canonical.scopes().getFirst().key()));

        assertThat(canonical.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(statutory.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(statutory.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.sourceScopeKey()).isEqualTo(statutorySource);
            assertThat(scope.key()).isEqualTo(canonical.scopes().getFirst().key());
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
        });

        String replayOnTeam = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.team",
                "PAYROLL_LEGAL_ENTITY_GROUP_SCOPE", "TARGET_POPULATION");
        assertThat(adapter.evaluate(request(
                "hcm.team", replayOnTeam, "TARGET_POPULATION", null)).decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.SCOPE_INVALID);
    }

    @Test
    void preservesBothPayAliasesWithIdenticalCanonicalDerivedMaterial() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("TENANT_ADMIN"), Set.of());
        when(populations.findOperations("READ")).thenReturn(Optional.of(teamPopulation()));
        String canonicalSource = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "PAY_TARGET_POPULATION",
                "TARGET_POPULATION");
        String requestedActionSource = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "PAYROLL_LEGAL_ENTITY_SCOPE",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.operations", List.of(
                        new ProductSurfaceEligibilityDtos.CandidateScope(
                                requestedActionSource, "TARGET_POPULATION"),
                        new ProductSurfaceEligibilityDtos.CandidateScope(
                                canonicalSource, "TARGET_POPULATION")), null));

        assertThat(result.decision()).isEqualTo(ProductSurfaceEligibilityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).hasSize(2);
        assertThat(result.scopes())
                .extracting(ProductSurfaceEligibilityDtos.EligibleScope::sourceScopeKey)
                .containsExactly(requestedActionSource, canonicalSource);
        assertThat(result.scopes())
                .extracting(ProductSurfaceEligibilityDtos.EligibleScope::key)
                .containsOnly(result.scopes().getFirst().key());
        assertThat(result.scopes())
                .allSatisfy(scope -> {
                    assertThat(scope.key()).startsWith("hcm-scope-");
                    assertThat(scope.isDefault()).isTrue();
                });
    }

    @Test
    void failsClosedWhenCanonicalPayAliasesResolveAgainstDifferentOwnerSnapshots() {
        PeopleRequestContext.set(ACTOR, TENANT, PERSON, Set.of("TENANT_ADMIN"), Set.of());
        HcmPopulationScopeService.ResolvedPopulation first = teamPopulation();
        HcmPopulationScopeService.ResolvedPopulation changed = new HcmPopulationScopeService
                .ResolvedPopulation(first.actor(), first.scope(),
                        new HcmPopulationRepository.PopulationEvidence(
                                first.evidence().count(), "changed-population-revision"));
        when(populations.findOperations("READ"))
                .thenReturn(Optional.of(first), Optional.of(changed));
        String canonicalSource = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "PAY_TARGET_POPULATION",
                "TARGET_POPULATION");
        String actionSource = ProductSurfaceScopeKey.key(
                TENANT, ACTOR, "hcm", "hcm.operations", "PAYROLL_LEGAL_ENTITY_SCOPE",
                "TARGET_POPULATION");

        ProductSurfaceEligibilityDtos.EligibilityResult result = adapter.evaluate(
                request("hcm.operations", List.of(
                        new ProductSurfaceEligibilityDtos.CandidateScope(
                                canonicalSource, "TARGET_POPULATION"),
                        new ProductSurfaceEligibilityDtos.CandidateScope(
                                actionSource, "TARGET_POPULATION")), null));

        assertThat(result.decision())
                .isEqualTo(ProductSurfaceEligibilityDtos.Decision.AUTHORITY_UNAVAILABLE);
        assertThat(result.scopes()).isEmpty();
    }

    private ProductSurfaceEligibilityDtos.EvaluateRequest request(
            String surface,
            String key,
            String kind,
            String selected) {
        return request(surface, List.of(
                new ProductSurfaceEligibilityDtos.CandidateScope(key, kind)), selected);
    }

    private ProductSurfaceEligibilityDtos.EvaluateRequest request(
            String surface,
            List<ProductSurfaceEligibilityDtos.CandidateScope> candidates,
            String selected) {
        return new ProductSurfaceEligibilityDtos.EvaluateRequest(
                TENANT, ACTOR, "hcm", surface,
                ProductSurfaceEligibilityDtos.AccessMode.NORMAL, NOW,
                candidates, selected);
    }

    private HcmPopulationScopeService.ResolvedPopulation teamPopulation() {
        HcmPopulationRepository.ActorWorkforce actor =
                new HcmPopulationRepository.ActorWorkforce(
                        41L, PERSON, "Manager", "A-MANAGER", "Lead", "Operations",
                        2L, 4L, 7L);
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        41L, "A-MANAGER", false, Set.of(),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "DIRECT_REPORT");
        return new HcmPopulationScopeService.ResolvedPopulation(
                actor, scope, new HcmPopulationRepository.PopulationEvidence(
                        3L, "population-revision"));
    }
}
