package com.dwp.services.people.hris.workbench.hrm007;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmEligibilityScopeKeys;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm007AuthorityTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID ACTOR =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @Test
    void exactCapabilityMethodReadOnlyPublicBindingAndDefinitionAreAccepted() {
        for (Hrm007QueryDefinition definition : Hrm007QueryDefinition.values()) {
            Hrm007Authority authority = authority(
                    definition, actor(definition, true), evidence(definition),
                    population(definition));

            Hrm007Authority.Decision decision = authority.authorize(definition);

            assertThat(decision.tenantId()).isEqualTo(41L);
            assertThat(decision.authorizationDecisionRevision())
                    .isEqualTo("psr-" + "c".repeat(64));
            assertThat(decision.fieldDecisions()).containsOnlyKeys(
                    definition.fieldGroups().toArray(String[]::new));
        }
    }

    @Test
    void exactGeneratedDataTupleIsRequired() {
        Hrm007QueryDefinition definition = Hrm007QueryDefinition.APPOINTMENTS;
        HcmPepContext.Evidence evidence = evidence(definition);
        assertThat(evidence.authority().routeContractKey())
                .isEqualTo(definition.routeContractKey());

        assertThat(authority(
                definition, actor(definition, true), evidence,
                population(definition)).authorize(definition)).isNotNull();

        HcmV3PepRegistry.RouteAuthority wrong = authorityTuple(
                definition, "route.hcm.operations.people-appointments.page",
                definition.routeProfileKey(), definition.bindingKey(),
                definition.projectionPolicyKey(), definition.responseSchemaKey(),
                definition.capability(), definition.predicatePolicyKeys());
        assertOpaque(() -> authority(definition, actor(definition, true),
                trustedEvidence(definition, wrong, definition.purpose(),
                        definition.fieldPolicyKey(), NOW.plusSeconds(60)),
                population(definition)).authorize(definition));
    }

    @Test
    void trustedScopeCandidatePreservesExactPopulationWhenAvailable() {
        PeopleRequestContext.Actor actor = actor(
                Hrm007QueryDefinition.APPOINTMENTS, true);
        HcmPopulationRepository.ActorWorkforce workforce =
                new HcmPopulationRepository.ActorWorkforce(
                        11L, ACTOR, "Taylor", "assignment:1", "Engineer",
                        "People", 2L, 3L, 4L);
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        11L, null, false, Set.of(UUID.fromString(
                        "cccccccc-cccc-4ccc-8ccc-cccccccccccc")),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "policy:17");
        HcmPopulationScopeService.ResolvedPopulation resolved =
                new HcmPopulationScopeService.ResolvedPopulation(
                        workforce, scope,
                        new HcmPopulationRepository.PopulationEvidence(1L, "population:17"));
        String sourceKey = ProductSurfaceScopeKey.key(
                actor.tenantId(), actor.userId(), "hcm", "hcm.operations",
                "ORG_TREE", "TARGET_POPULATION");
        String scopeKey = HcmEligibilityScopeKeys.derived(
                actor.tenantId(), actor.userId(), "hcm.operations", sourceKey,
                resolved.relationshipRevision(), resolved.targetPopulationRevision());
        HcmPepContext.Evidence evidence = new HcmPepContext.Evidence(
                null, null, null, null, scopeKey, null);

        String matched = Hrm007Authority.requireTrustedResolverSource(
                resolved, actor, evidence, "hcm.operations", "TARGET_POPULATION",
                "LEGAL_ENTITY", "ORG_TREE", "ORG_UNIT", "NAMED_POPULATION",
                "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");

        assertThat(matched).isEqualTo("ORG_TREE");
        assertThat(Hrm007Authority.operationsPopulationKind(matched, scope))
                .isEqualTo("ORG_TREE");
        assertThat(Hrm007Authority.teamPopulationKind(
                "DIRECT_REPORTS",
                new HcmPopulationRepository.PopulationScope(
                        11L, "assignment:1", false, Set.of(),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "DIRECT_REPORT")))
                .isEqualTo("DIRECT_REPORTS");
    }

    @Test
    void wrongProfileDutyPopulationCapabilityAndRevisionShareOneOpaqueDenial() {
        for (Hrm007QueryDefinition definition : Hrm007QueryDefinition.values()) {
            var wrongDuty = actor(definition, false);
            var wrongCapability = evidence(definition, "wrong.capability",
                    definition.browserPath(), NOW.plusSeconds(60));
            var stale = evidence(definition, definition.capability(),
                    definition.browserPath(), NOW.minusSeconds(1));
            var wrongPredicate = evidence(
                    definition, definition.capability(), definition.browserPath(),
                    NOW.plusSeconds(60), Set.of("predicate.unrelated.v1"));
            String wrongPopulationKind = definition == Hrm007QueryDefinition.PEOPLE_CHANGES
                    ? "LEGAL_ENTITY" : "DIRECT_REPORTS";
            var wrongPopulation = new Hrm007Authority.TargetPopulation(
                    wrongPopulationKind, true, 11L, null, Set.of(),
                    Set.of("DIRECTORY", "EMPLOYMENT"), "population:17");

            assertOpaque(() -> authority(
                    definition, wrongDuty, evidence(definition),
                    population(definition)).authorize(definition));
            assertOpaque(() -> authority(
                    definition, actor(definition, true), wrongCapability,
                    population(definition)).authorize(definition));
            assertOpaque(() -> authority(
                    definition, actor(definition, true), stale,
                    population(definition)).authorize(definition));
            assertOpaque(() -> authority(
                    definition, actor(definition, true), wrongPredicate,
                    population(definition)).authorize(definition));
            assertOpaque(() -> authority(
                    definition, actor(definition, true), evidence(definition),
                    wrongPopulation).authorize(definition));
        }
    }

    @Test
    void wrongProfileBindingProjectionSchemaPurposeAndFieldPolicyShareOpaqueDenial() {
        Hrm007QueryDefinition definition = Hrm007QueryDefinition.PEOPLE_CHANGES;
        var actor = actor(definition, true);
        var population = population(definition);
        java.util.List<HcmPepContext.Evidence> wrong = java.util.List.of(
                trustedEvidence(definition, authorityTuple(definition,
                        definition.routeContractKey(), "wrong-profile", definition.bindingKey(),
                        definition.projectionPolicyKey(), definition.responseSchemaKey(),
                        definition.capability(), definition.predicatePolicyKeys()),
                        definition.purpose(), definition.fieldPolicyKey(), NOW.plusSeconds(60)),
                trustedEvidence(definition, authorityTuple(definition,
                        definition.routeContractKey(), definition.routeProfileKey(),
                        "wrong-binding", definition.projectionPolicyKey(),
                        definition.responseSchemaKey(), definition.capability(),
                        definition.predicatePolicyKeys()), definition.purpose(),
                        definition.fieldPolicyKey(), NOW.plusSeconds(60)),
                trustedEvidence(definition, authorityTuple(definition,
                        definition.routeContractKey(), definition.routeProfileKey(),
                        definition.bindingKey(), "wrong-projection",
                        definition.responseSchemaKey(), definition.capability(),
                        definition.predicatePolicyKeys()), definition.purpose(),
                        definition.fieldPolicyKey(), NOW.plusSeconds(60)),
                trustedEvidence(definition, authorityTuple(definition,
                        definition.routeContractKey(), definition.routeProfileKey(),
                        definition.bindingKey(), definition.projectionPolicyKey(),
                        "wrong-schema", definition.capability(),
                        definition.predicatePolicyKeys()), definition.purpose(),
                        definition.fieldPolicyKey(), NOW.plusSeconds(60)),
                trustedEvidence(definition, authorityTuple(definition,
                        definition.routeContractKey(), definition.routeProfileKey(),
                        definition.bindingKey(), definition.projectionPolicyKey(),
                        definition.responseSchemaKey(), definition.capability(),
                        definition.predicatePolicyKeys()), "WRONG_PURPOSE",
                        definition.fieldPolicyKey(), NOW.plusSeconds(60)),
                trustedEvidence(definition, authorityTuple(definition,
                        definition.routeContractKey(), definition.routeProfileKey(),
                        definition.bindingKey(), definition.projectionPolicyKey(),
                        definition.responseSchemaKey(), definition.capability(),
                        definition.predicatePolicyKeys()), definition.purpose(),
                        "wrong-field-policy", NOW.plusSeconds(60)));
        wrong.forEach(value -> assertOpaque(() -> authority(
                definition, actor, value, population).authorize(definition)));
    }

    @Test
    void configurationRejectsEveryScopeShapeNotProducedByItsResolver() {
        Hrm007QueryDefinition definition = Hrm007QueryDefinition.PEOPLE_EMPLOYMENT;
        Set<Hrm007Authority.TargetPopulation> invalid = Set.of(
                new Hrm007Authority.TargetPopulation(
                        "POLICY_NODE", true, 0L, null, Set.of(),
                        definition.fieldGroups().stream().collect(
                        java.util.stream.Collectors.toSet()), "config:17"),
                new Hrm007Authority.TargetPopulation(
                        "LEGAL_ENTITY", true, 0L, null, Set.of(),
                        Set.of(), "config:17"),
                new Hrm007Authority.TargetPopulation(
                        "CONFIG_SCOPE", false, 0L, null, Set.of(),
                        Set.of(), "config:17"),
                new Hrm007Authority.TargetPopulation(
                        "CONFIG_SCOPE", true, 0L, "manager:1", Set.of(),
                        Set.of(), "config:17"),
                new Hrm007Authority.TargetPopulation(
                        "CONFIG_SCOPE", true, 0L, null,
                        Set.of(UUID.fromString(
                                "cccccccc-cccc-4ccc-8ccc-cccccccccccc")),
                        Set.of(), "config:17"));

        invalid.forEach(population -> assertOpaque(() -> authority(
                definition, actor(definition, true), evidence(definition),
                population).authorize(definition)));
    }

    private Hrm007Authority authority(
            Hrm007QueryDefinition definition,
            PeopleRequestContext.Actor actor,
            HcmPepContext.Evidence evidence,
            Hrm007Authority.TargetPopulation population) {
        return new Hrm007Authority(
                () -> actor, () -> evidence, ignored -> population,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PeopleRequestContext.Actor actor(
            Hrm007QueryDefinition definition,
            boolean includeDuty) {
        Set<String> roles = includeDuty
                ? Set.of(definition.profile(), definition.duty())
                : Set.of(definition.profile());
        return new PeopleRequestContext.Actor(7L, 41L, ACTOR, roles, Set.of());
    }

    private HcmPepContext.Evidence evidence(Hrm007QueryDefinition definition) {
        return evidence(definition, definition.capability(),
                definition.browserPath(), NOW.plusSeconds(60));
    }

    private HcmPepContext.Evidence evidence(
            Hrm007QueryDefinition definition,
            String capability,
            String publicPath,
            Instant revalidateAt) {
        return evidence(definition, capability, publicPath, revalidateAt,
                definition.predicatePolicyKeys());
    }

    private HcmPepContext.Evidence evidence(
            Hrm007QueryDefinition definition,
            String capability,
            String publicPath,
            Instant revalidateAt,
            Set<String> predicatePolicyKeys) {
        HcmV3PepRegistry.RouteAuthority route = authorityTuple(
                definition, definition.routeContractKey(), definition.routeProfileKey(),
                definition.bindingKey(), definition.projectionPolicyKey(),
                definition.responseSchemaKey(), capability, predicatePolicyKeys);
        if (!definition.browserPath().equals(publicPath)) {
            route = new HcmV3PepRegistry.RouteAuthority(
                    route.routeContractKey(), route.routeKind(), route.profileKey(),
                    route.readOnly(), route.predicatePolicyKeys(), route.targetBindingKinds(),
                    route.bindingKey(), route.capabilityContractKey(), route.activationPolicy(),
                    route.method(), publicPath, route.stepUpBinding(),
                    route.projectionPolicyKey(), route.responseSchemaKey());
        }
        return trustedEvidence(definition, route, definition.purpose(),
                definition.fieldPolicyKey(), revalidateAt);
    }

    private HcmV3PepRegistry.RouteAuthority authorityTuple(
            Hrm007QueryDefinition definition,
            String routeKey,
            String profileKey,
            String bindingKey,
            String projectionPolicyKey,
            String responseSchemaKey,
            String capability,
            Set<String> predicatePolicyKeys) {
        return new HcmV3PepRegistry.RouteAuthority(
                routeKey, "DATA", profileKey, true,
                predicatePolicyKeys, definition.targetBindingKinds(),
                bindingKey, capability, null, "GET",
                definition.browserPath(), null, projectionPolicyKey, responseSchemaKey);
    }

    private HcmPepContext.Evidence trustedEvidence(
            Hrm007QueryDefinition definition,
            HcmV3PepRegistry.RouteAuthority route,
            String purpose,
            String fieldPolicyKey,
            Instant revalidateAt) {
        return new HcmPepContext.Evidence(
                route, "psr-" + "c".repeat(64),
                OffsetDateTime.ofInstant(revalidateAt, ZoneOffset.UTC),
                "psc-" + "a".repeat(64), "hcm-scope-" + "b".repeat(40), "111",
                "NORMAL", purpose, fieldPolicyKey);
    }

    private Hrm007Authority.TargetPopulation population(
            Hrm007QueryDefinition definition) {
        return switch (definition) {
            case PEOPLE_CHANGES -> new Hrm007Authority.TargetPopulation(
                    "DIRECT_REPORTS", false, 11L, "manager:1", Set.of(),
                    Set.of("DIRECTORY", "EMPLOYMENT"), "population:17");
            case APPOINTMENTS -> new Hrm007Authority.TargetPopulation(
                    "LEGAL_ENTITY", true, 11L, null, Set.of(),
                    Set.of("DIRECTORY", "EMPLOYMENT"), "population:17");
            case PEOPLE_EMPLOYMENT -> new Hrm007Authority.TargetPopulation(
                    "CONFIG_SCOPE", true, 0L, null, Set.of(),
                    Set.of("POLICY_METADATA", "EMPLOYMENT_RULE", "IMPACT_SUMMARY"),
                    "config:17");
        };
    }

    private void assertOpaque(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(Hrm007Exception.class)
                .satisfies(value -> {
                    Hrm007Exception failure = (Hrm007Exception) value;
                    assertThat(failure.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(failure.code()).isEqualTo("HRIS_WORKBENCH_ACCESS_DENIED");
                });
    }
}
