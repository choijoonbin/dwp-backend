package com.dwp.services.people.hris.employeeservice.certificate;

import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CertificateAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-10-18T03:00:00Z");
    private static final UUID ACTOR =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TARGET =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Set<String> SEALED_COMMAND_FIELDS = Set.of(
            "DIRECTORY", "DOCUMENT_CONTENT", "EMPLOYMENT");

    @Test
    void selfAndOperationsQueriesRequireTheirExactSealedRouteTuples() {
        HcmPopulationScopeService selfPopulations = mock(HcmPopulationScopeService.class);
        CertificateAuthority self = authority(
                selfPopulations,
                actor(Set.of("HRIS_EMPLOYEE", "HRIS_DUTY_SELF_REQUEST_READER"),
                        Set.of("ACTION.HRIS_CERTIFICATE:CREATE")),
                queryEvidence(CertificateQueryDefinition.SELF_REQUESTS));

        CertificateAuthority.QueryDecision selfDecision =
                self.authorizeQuery(CertificateQueryDefinition.SELF_REQUESTS);

        assertThat(selfDecision.populationKind()).isEqualTo("SELF");
        assertThat(selfDecision.fieldGroups()).containsExactlyInAnyOrder(
                "REQUEST_SUMMARY", "REQUEST_STATUS", "COMMAND_RECEIPT");
        assertThat(selfDecision.allowedActions())
                .containsExactly(CertificateDtos.REQUEST_CERTIFICATE_ACTION);
        verify(selfPopulations).requireSelfScope("hcm.personal");

        HcmPopulationScopeService operationsPopulations =
                mock(HcmPopulationScopeService.class);
        HcmPopulationScopeService.ResolvedPopulation resolved =
                population(SEALED_COMMAND_FIELDS);
        when(operationsPopulations.requireOperations("READ")).thenReturn(resolved);
        CertificateAuthority operations = authority(
                operationsPopulations,
                actor(Set.of("HRIS_HR_BUSINESS_PARTNER", "HRIS_DUTY_CORE_VIEWER"),
                        Set.of("ACTION.HRIS_CERTIFICATE:ISSUE")),
                queryEvidence(CertificateQueryDefinition.OPERATIONS_CERTIFICATES));

        CertificateAuthority.QueryDecision operationsDecision =
                operations.authorizeQuery(
                        CertificateQueryDefinition.OPERATIONS_CERTIFICATES);

        assertThat(operationsDecision.populationKind()).isEqualTo("LEGAL_ENTITY");
        assertThat(operationsDecision.allowedActions())
                .containsExactly(CertificateDtos.REQUEST_CERTIFICATE_ACTION);
        verify(operationsPopulations).requireTrustedScope(
                resolved, "hcm.operations", "TARGET_POPULATION",
                "LEGAL_ENTITY", "ORG_TREE", "ORG_UNIT", "NAMED_POPULATION",
                "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
    }

    @Test
    void operationsQueryNeverAdvertisesIssueWithoutExactDocumentContentAuthority() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HcmPopulationScopeService.ResolvedPopulation resolved =
                population(Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populations.requireOperations("READ")).thenReturn(resolved);
        CertificateAuthority authority = authority(
                populations,
                actor(Set.of("HRIS_HR_BUSINESS_PARTNER", "HRIS_DUTY_CORE_VIEWER"),
                        Set.of("ACTION.HRIS_CERTIFICATE:ISSUE")),
                queryEvidence(CertificateQueryDefinition.OPERATIONS_CERTIFICATES));

        CertificateAuthority.QueryDecision decision = authority.authorizeQuery(
                CertificateQueryDefinition.OPERATIONS_CERTIFICATES);

        assertThat(decision.allowedActions()).isEmpty();
        assertThat(decision.fieldGroups())
                .containsExactlyInAnyOrder("DIRECTORY", "EMPLOYMENT");
    }

    @Test
    void selfAndOperationsCommandBranchesAreChosenOnlyByTrustedSubjectEquality() {
        HcmPopulationScopeService selfPopulations = mock(HcmPopulationScopeService.class);
        CertificateAuthority self = authority(
                selfPopulations,
                actor(Set.of("HRIS_EMPLOYEE",
                                "HRIS_DUTY_SELF_CERTIFICATE_REQUESTER"),
                        Set.of("ACTION.HRIS_CERTIFICATE:CREATE")),
                commandEvidence(true, CertificateAuthority.SELF_PURPOSE));

        CertificateAuthority.CommandDecision selfAuthorized = self.authorizeCommand(
                ACTOR, CertificateAuthority.SELF_PURPOSE, null);
        verifyNoInteractions(selfPopulations);
        CertificateAuthority.CommandDecision selfDecision =
                self.requireMutationScope(selfAuthorized);

        assertThat(selfDecision.self()).isTrue();
        assertThat(selfDecision.profile()).isEqualTo(
                CertificateAuthority.SELF_PROFILE);
        assertThat(selfDecision.capability()).isEqualTo(
                CertificateAuthority.SELF_CAPABILITY);
        verify(selfPopulations).requireSelfScope("hcm.personal");

        HcmPopulationScopeService operationsPopulations =
                mock(HcmPopulationScopeService.class);
        HcmPopulationScopeService.ResolvedPopulation resolved =
                population(SEALED_COMMAND_FIELDS);
        when(operationsPopulations.requireOperationsForMutation("READ"))
                .thenReturn(resolved);
        CertificateAuthority operations = authority(
                operationsPopulations,
                actor(Set.of("HRIS_HR_BUSINESS_PARTNER",
                                "HRIS_DUTY_CERTIFICATE_ISSUER"),
                        Set.of("ACTION.HRIS_CERTIFICATE:ISSUE")),
                commandEvidence(false, CertificateAuthority.OPERATIONS_PURPOSE));

        CertificateAuthority.CommandDecision operationsAuthorized =
                operations.authorizeCommand(
                        TARGET, CertificateAuthority.OPERATIONS_PURPOSE,
                        CertificateAuthority.OPERATIONS_PURPOSE);
        verifyNoInteractions(operationsPopulations);
        CertificateAuthority.CommandDecision operationsDecision =
                operations.requireMutationScope(operationsAuthorized);

        assertThat(operationsDecision.self()).isFalse();
        assertThat(operationsDecision.profile()).isEqualTo(
                CertificateAuthority.OPERATIONS_PROFILE);
        assertThat(operationsDecision.fieldGroups())
                .containsExactlyInAnyOrderElementsOf(SEALED_COMMAND_FIELDS);
    }

    @Test
    void teamManagementIsDeniedBeforePopulationResolutionWithNoFallback() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        CertificateAuthority authority = authority(
                populations,
                actor(Set.of("HRIS_EMPLOYEE", "HRIS_DUTY_SELF_CERTIFICATE_REQUESTER",
                                "HRIS_HR_BUSINESS_PARTNER",
                                "HRIS_DUTY_CERTIFICATE_ISSUER"),
                        Set.of("ACTION.HRIS_CERTIFICATE:CREATE",
                                "ACTION.HRIS_CERTIFICATE:ISSUE")),
                commandEvidence(true, CertificateAuthority.DEFERRED_TEAM_PURPOSE));

        assertOpaque(() -> authority.authorizeCommand(
                ACTOR, CertificateAuthority.DEFERRED_TEAM_PURPOSE, null));

        verifyNoInteractions(populations);
    }

    @Test
    void operationsCommandFailsClosedWhenDocumentContentDecisionIsAbsent() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        when(populations.requireOperationsForMutation("READ"))
                .thenReturn(population(Set.of("DIRECTORY", "EMPLOYMENT")));
        CertificateAuthority authority = authority(
                populations,
                actor(Set.of("HRIS_HR_BUSINESS_PARTNER",
                                "HRIS_DUTY_CERTIFICATE_ISSUER"),
                        Set.of("ACTION.HRIS_CERTIFICATE:ISSUE")),
                commandEvidence(false, CertificateAuthority.OPERATIONS_PURPOSE));

        CertificateAuthority.CommandDecision authorized = authority.authorizeCommand(
                TARGET, CertificateAuthority.OPERATIONS_PURPOSE, null);
        assertOpaque(() -> authority.requireMutationScope(authorized));
    }

    @Test
    void highStepUpBindingAndExactProfileCapabilityPurposeAreMandatory() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        PeopleRequestContext.Actor actor = actor(
                Set.of("HRIS_EMPLOYEE", "HRIS_DUTY_SELF_CERTIFICATE_REQUESTER"),
                Set.of("ACTION.HRIS_CERTIFICATE:CREATE"));
        HcmV3PepRegistry.RouteAuthority lowRisk = new HcmV3PepRegistry.RouteAuthority(
                CertificateAuthority.ACTION_ROUTE, "ACTION",
                CertificateAuthority.SELF_PROFILE, false,
                Set.of("predicate.self-person.v1",
                        "predicate.people.object-version.v1"),
                Set.of("SELF", "OBJECT"), "local-only-binding",
                CertificateAuthority.SELF_CAPABILITY, "STEPUP-MGMT-HIGH-V1",
                "POST", CertificateAuthority.ACTION_BROWSER_PATH, null);
        CertificateAuthority authority = authority(
                populations, actor,
                evidence(lowRisk, CertificateAuthority.SELF_PURPOSE));

        assertOpaque(() -> authority.authorizeCommand(
                ACTOR, CertificateAuthority.SELF_PURPOSE, null));
        verifyNoInteractions(populations);
    }

    private CertificateAuthority authority(
            HcmPopulationScopeService populations,
            PeopleRequestContext.Actor actor,
            HcmPepContext.Evidence evidence) {
        return new CertificateAuthority(
                populations, Clock.fixed(NOW, ZoneOffset.UTC), () -> actor,
                () -> evidence);
    }

    private PeopleRequestContext.Actor actor(
            Set<String> roles,
            Set<String> permissions) {
        return new PeopleRequestContext.Actor(7L, 41L, ACTOR, roles, permissions);
    }

    private HcmPepContext.Evidence queryEvidence(
            CertificateQueryDefinition definition) {
        return evidence(new HcmV3PepRegistry.RouteAuthority(
                definition.routeContractKey(), "DATA", definition.profileKey(), true,
                definition.predicatePolicyKeys(), definition.targetBindingKinds(),
                "local-query-binding", definition.capability(), null, "GET",
                definition.browserPath(), null), definition.purpose());
    }

    private HcmPepContext.Evidence commandEvidence(
            boolean self,
            String purpose) {
        String profile = self ? CertificateAuthority.SELF_PROFILE
                : CertificateAuthority.OPERATIONS_PROFILE;
        String capability = self ? CertificateAuthority.SELF_CAPABILITY
                : CertificateAuthority.OPERATIONS_CAPABILITY;
        Set<String> predicates = self
                ? Set.of("predicate.self-person.v1",
                        "predicate.people.object-version.v1")
                : Set.of("predicate.hcm-workforce-visible-person.v1",
                        "predicate.people.object-version.v1");
        Set<String> bindings = self
                ? Set.of("SELF", "OBJECT")
                : Set.of("OBJECT", "TARGET_POPULATION");
        return evidence(new HcmV3PepRegistry.RouteAuthority(
                CertificateAuthority.ACTION_ROUTE, "ACTION", profile, false,
                predicates, bindings, "local-action-binding", capability,
                "STEPUP-MGMT-HIGH-V1", "POST",
                CertificateAuthority.ACTION_BROWSER_PATH,
                new HcmV3PepRegistry.StepUpBinding(
                        CertificateAuthority.TARGET_TYPE, null,
                        List.of("personPublicId"), "people", "dwp-people-server")),
                purpose);
    }

    private HcmPepContext.Evidence evidence(
            HcmV3PepRegistry.RouteAuthority route,
            String purpose) {
        return new HcmPepContext.Evidence(
                route, "psr-" + "c".repeat(64),
                OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC),
                "psc-" + "a".repeat(64), "hcm-scope-" + "b".repeat(40),
                "111", "NORMAL", purpose, "local-field-policy");
    }

    private HcmPopulationScopeService.ResolvedPopulation population(
            Set<String> fieldGroups) {
        HcmPopulationRepository.ActorWorkforce workforce =
                new HcmPopulationRepository.ActorWorkforce(
                        11L, ACTOR, "Taylor", "assignment:1", "Advisor", "People",
                        2L, 3L, 4L);
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        11L, null, true, Set.of(), fieldGroups, "policy:17");
        return new HcmPopulationScopeService.ResolvedPopulation(
                workforce, scope,
                new HcmPopulationRepository.PopulationEvidence(2L, "population:17"));
    }

    private void assertOpaque(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(CertificateException.class)
                .satisfies(value -> {
                    CertificateException failure = (CertificateException) value;
                    assertThat(failure.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(failure.code()).isEqualTo("CERTIFICATE_ACCESS_DENIED");
                });
    }
}
