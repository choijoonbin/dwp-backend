package com.dwp.services.people.workforce;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.directory.PeopleDirectoryService;
import com.dwp.services.people.directory.PeopleDtos;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class People360ServiceTest {

    private static final long TENANT_ID = 7L;
    private static final UUID PERSON_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_PERSON_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 17);

    private final People360Repository repository = mock(People360Repository.class);
    private final PeopleDirectoryService directory = mock(PeopleDirectoryService.class);
    private final HcmPopulationScopeService populationScopes =
            mock(HcmPopulationScopeService.class);
    private final HcmPopulationRepository populations = mock(HcmPopulationRepository.class);
    private final People360ProjectionPolicyProvider projectionPolicies =
            new CompatibilityPeople360ProjectionPolicyProvider();
    private final People360Service service = new People360Service(
            repository, directory, populationScopes, populations, projectionPolicies, true);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void disabledRuntimeFailsEveryPublicEntryPointBeforeOwnerReads() {
        PeopleRequestContext.set(
                10L, TENANT_ID, PERSON_ID,
                Set.of("HR_OPERATOR", "MANAGER"), Set.of("DATA.WORKFORCE:VIEW"));
        People360Service disabled = new People360Service(
                repository, directory, populationScopes, populations,
                projectionPolicies, false);

        assertForbidden(() -> disabled.search(null, null, null, 25, AS_OF));
        assertForbidden(() -> disabled.get(PERSON_ID, AS_OF));
        assertForbidden(() -> disabled.getSelf(AS_OF));
        assertForbidden(() -> disabled.getTeam(PERSON_ID, AS_OF));

        verifyNoInteractions(repository, directory, populationScopes, populations);
    }

    @Test
    void selfReceivesTheUniqueAsOfProjectionWithoutRoleInference() {
        PeopleRequestContext.set(
                11L, TENANT_ID, PERSON_ID, Set.of("EMPLOYEE"), Set.of("APP.HCM:VIEW"));
        stubPersonAndEmployments(List.of(employment(71L, true)));

        People360Dtos.Snapshot result = service.getSelf(AS_OF);

        assertThat(result.asOf()).isEqualTo(AS_OF);
        assertThat(result.state()).isEqualTo(People360Dtos.ProjectionState.READY);
        assertThat(result.access().archetype()).isEqualTo(People360Dtos.Archetype.SELF);
        assertThat(result.access().scope()).isEqualTo("SELF");
        assertThat(result.employment().workerNumber()).isEqualTo("SYN-0042");
        assertThat(result.primaryAssignment().assignmentKey()).isEqualTo("SYN-ASG-0042");
        assertThat(decisions(result).values())
                .containsOnly(People360Dtos.FieldDecisionValue.VIEW);
        verify(repository).findCurrentEmployments(TENANT_ID, 42L, AS_OF);
        verify(populationScopes).requireSelfScope();
        verify(populationScopes, never()).findOperations("READ");
    }

    @Test
    void workforceDetailDoesNotTreatTheTargetAsSelfWithoutWorkforceAuthority() {
        PeopleRequestContext.set(
                20L, TENANT_ID, PERSON_ID, Set.of("EMPLOYEE"), Set.of("APP.HCM:VIEW"));
        when(populationScopes.findOperations("READ")).thenReturn(Optional.empty());

        assertNotFound(() -> service.get(PERSON_ID, AS_OF));

        verify(populationScopes, never()).requireSelfScope();
        verify(populationScopes, never()).findTeam();
        verify(populationScopes, never()).requireTeam();
        verify(populationScopes).findOperations("READ");
        verifyNoInteractions(repository, directory, populations);
    }

    @Test
    void listAuthorityFailureDoesNotReadTheDirectory() {
        PeopleRequestContext.set(
                24L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("HR_OPERATOR"), Set.of("DATA.WORKFORCE:VIEW"));
        when(populationScopes.requireOperations("READ")).thenThrow(
                new BaseException(ErrorCode.FORBIDDEN, "Synthetic missing authority."));

        assertForbidden(() -> service.search(null, null, null, 25, AS_OF));

        verifyNoInteractions(repository, directory, populations);
    }

    @Test
    void teamAuthorityFailureDoesNotReadTheTarget() {
        PeopleRequestContext.set(
                25L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("MANAGER"), Set.of("APP.HCM:VIEW"));
        when(populationScopes.requireTeam()).thenThrow(
                new BaseException(ErrorCode.FORBIDDEN, "Synthetic missing team authority."));

        assertForbidden(() -> service.getTeam(PERSON_ID, AS_OF));

        verifyNoInteractions(repository, directory, populations);
    }

    @Test
    void managerMembershipMasksIdentifiersAndOmitsOutOfPurposeFields() {
        PeopleRequestContext.set(
                12L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("MANAGER"), Set.of("APP.HCM:VIEW"));
        People360Repository.CurrentEmploymentRow employment = employment(71L, true);
        stubPersonAndEmployments(List.of(employment));
        HcmPopulationScopeService.ResolvedPopulation team = population(
                55L, "SYN-MANAGER", false,
                Set.of(), Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populationScopes.requireTeam()).thenReturn(team);
        when(populations.containsWorker(TENANT_ID, team.scope(), 71L))
                .thenReturn(true);

        People360Dtos.Snapshot result = service.getTeam(PERSON_ID, AS_OF);

        assertThat(result.state()).isEqualTo(People360Dtos.ProjectionState.READY);
        assertThat(result.access().archetype()).isEqualTo(People360Dtos.Archetype.MANAGER);
        assertThat(result.employment().workerNumber()).isEqualTo(People360Dtos.MASK_LITERAL);
        assertThat(result.employment().originalHireDate()).isNull();
        assertThat(result.primaryAssignment().assignmentKey()).isNull();
        assertThat(result.primaryAssignment().jobGradeName()).isNull();
        assertThat(decisions(result))
                .containsEntry(
                        "employment.workerNumber",
                        People360Dtos.FieldDecisionValue.MASK)
                .containsEntry(
                        "primaryAssignment.assignmentKey",
                        People360Dtos.FieldDecisionValue.OMIT);
        verify(populationScopes).requireTrustedScope(
                team, "hcm.team", "TARGET_POPULATION",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TEAM/ORG_UNIT");
        InOrder authorityBeforeRead = org.mockito.Mockito.inOrder(
                populationScopes, repository);
        authorityBeforeRead.verify(populationScopes).requireTeam();
        authorityBeforeRead.verify(populationScopes).requireTrustedScope(
                team, "hcm.team", "TARGET_POPULATION",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TEAM/ORG_UNIT");
        authorityBeforeRead.verify(repository).findPerson(TENANT_ID, PERSON_ID);
    }

    @Test
    void delegatedOrganizationMembershipUsesTheResolvedTeamScope() {
        PeopleRequestContext.set(
                22L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("MANAGER"), Set.of("APP.HCM:VIEW"));
        stubPersonAndEmployments(List.of(employment(71L, true)));
        UUID delegatedOrganization =
                UUID.fromString("40000000-0000-0000-0000-000000000001");
        HcmPopulationScopeService.ResolvedPopulation delegated = population(
                55L, "SYN-MANAGER", false,
                Set.of(delegatedOrganization), Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populationScopes.requireTeam()).thenReturn(delegated);
        when(populations.containsWorker(TENANT_ID, delegated.scope(), 71L)).thenReturn(true);

        People360Dtos.Snapshot result = service.getTeam(PERSON_ID, AS_OF);

        assertThat(result.access().archetype()).isEqualTo(People360Dtos.Archetype.MANAGER);
        assertThat(result.access().scope()).isEqualTo("TEAM");
        verify(populations).containsWorker(TENANT_ID, delegated.scope(), 71L);
    }

    @Test
    void directoryOnlyTeamPolicyOmitsAllEmploymentAndAssignmentData() {
        PeopleRequestContext.set(
                23L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("MANAGER"), Set.of("APP.HCM:VIEW"));
        stubPersonAndEmployments(List.of(employment(71L, true)));
        HcmPopulationScopeService.ResolvedPopulation directoryOnly = population(
                55L, "SYN-MANAGER", false,
                Set.of(UUID.fromString("40000000-0000-0000-0000-000000000001")),
                Set.of("DIRECTORY"));
        when(populationScopes.requireTeam()).thenReturn(directoryOnly);
        when(populations.containsWorker(TENANT_ID, directoryOnly.scope(), 71L))
                .thenReturn(true);

        People360Dtos.Snapshot result = service.getTeam(PERSON_ID, AS_OF);

        assertThat(result.employment()).isNull();
        assertThat(result.primaryAssignment()).isNull();
        assertThat(decisions(result).entrySet())
                .filteredOn(entry -> entry.getKey().startsWith("employment.")
                        || entry.getKey().startsWith("primaryAssignment."))
                .allSatisfy(entry -> assertThat(entry.getValue())
                        .isEqualTo(People360Dtos.FieldDecisionValue.OMIT));
        assertThat(result.access().policyRevision()).startsWith("effective-policy-");
    }

    @Test
    void operationsRouteUsesOperationsScopeWhenTheActorAlsoManagesTheTarget() {
        PeopleRequestContext.set(
                13L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("HR_OPERATOR", "MANAGER"), Set.of("DATA.WORKFORCE:VIEW"));
        stubPersonAndEmployments(List.of(employment(71L, true)));
        HcmPopulationScopeService.ResolvedPopulation team = population(
                55L, "SYN-MANAGER", false,
                Set.of(), Set.of("DIRECTORY", "EMPLOYMENT"));
        HcmPopulationScopeService.ResolvedPopulation operations = population(
                80L, null, false,
                Set.of(UUID.fromString("40000000-0000-0000-0000-000000000001")),
                Set.of("DIRECTORY", "EMPLOYMENT", "WORKER_IDENTIFIERS", "JOB_GRADE"));
        when(populationScopes.findTeam()).thenReturn(Optional.of(team));
        when(populationScopes.findOperations("READ")).thenReturn(Optional.of(operations));
        when(populations.containsWorker(
                eq(TENANT_ID), any(HcmPopulationRepository.PopulationScope.class), eq(71L)))
                .thenReturn(true);

        People360Dtos.Snapshot result = service.get(PERSON_ID, AS_OF);

        assertThat(result.access().archetype())
                .isEqualTo(People360Dtos.Archetype.HR_OPERATOR);
        assertThat(result.employment().workerNumber()).isEqualTo("SYN-0042");
        assertThat(result.primaryAssignment().assignmentKey()).isEqualTo("SYN-ASG-0042");
        assertThat(result.primaryAssignment().jobGradeName()).isEqualTo("Synthetic Grade");
        assertThat(decisions(result))
                .containsEntry(
                        "employment.workerNumber",
                        People360Dtos.FieldDecisionValue.VIEW)
                .containsEntry(
                        "person.preferredLocale",
                        People360Dtos.FieldDecisionValue.OMIT);
        verify(populationScopes, never()).findTeam();
        verify(populationScopes).requireTrustedScope(
                operations, "hcm.operations", "TARGET_POPULATION",
                "WORKFORCE_TARGET_POPULATION", "ORG_UNIT/LEGAL_ENTITY");
        InOrder authorityBeforeRead = org.mockito.Mockito.inOrder(
                populationScopes, repository);
        authorityBeforeRead.verify(populationScopes).findOperations("READ");
        authorityBeforeRead.verify(populationScopes).requireTrustedScope(
                operations, "hcm.operations", "TARGET_POPULATION",
                "WORKFORCE_TARGET_POPULATION", "ORG_UNIT/LEGAL_ENTITY");
        authorityBeforeRead.verify(repository).findPerson(TENANT_ID, PERSON_ID);
    }

    @Test
    void managerTeamRouteDoesNotFallBackToOperationsForAnOutOfTeamTarget() {
        PeopleRequestContext.set(
                21L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("MANAGER", "HR_OPERATOR"),
                Set.of("APP.HCM:VIEW", "DATA.WORKFORCE:VIEW"));
        stubPersonAndEmployments(List.of(employment(71L, true)));
        HcmPopulationScopeService.ResolvedPopulation team = population(
                55L, "SYN-MANAGER", false,
                Set.of(), Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populationScopes.requireTeam()).thenReturn(team);
        when(populations.containsWorker(
                eq(TENANT_ID), any(HcmPopulationRepository.PopulationScope.class), eq(71L)))
                .thenReturn(false);

        assertNotFound(() -> service.getTeam(PERSON_ID, AS_OF));

        verify(populationScopes, never()).findOperations("READ");
        verify(populationScopes).requireTrustedScope(
                team, "hcm.team", "TARGET_POPULATION",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TEAM/ORG_UNIT");
    }

    @Test
    void auditorWithNormalViewPermissionCannotUnmaskIdentifiers() {
        PeopleRequestContext.set(
                14L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("HR_AUDITOR"),
                Set.of("DATA.WORKFORCE:VIEW"));
        stubPersonAndEmployments(List.of(employment(71L, true)));
        HcmPopulationScopeService.ResolvedPopulation operations = population(
                0L, null, true, Set.of(),
                Set.of("DIRECTORY", "EMPLOYMENT", "WORKER_IDENTIFIERS", "JOB_GRADE"));
        when(populationScopes.findOperations("READ")).thenReturn(Optional.of(operations));
        when(populations.containsWorker(TENANT_ID, operations.scope(), 71L)).thenReturn(true);

        People360Dtos.Snapshot result = service.get(PERSON_ID, AS_OF);

        assertThat(result.access().archetype()).isEqualTo(People360Dtos.Archetype.AUDITOR);
        assertThat(result.employment().workerNumber()).isEqualTo(People360Dtos.MASK_LITERAL);
        assertThat(result.primaryAssignment().assignmentKey()).isNull();
        assertThat(result.person().timeZone()).isNull();
    }

    @Test
    void crossTenantAndOutOfPopulationTargetsAreIndistinguishableNotFound() {
        PeopleRequestContext.set(
                15L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("HR_OPERATOR"), Set.of("DATA.WORKFORCE:VIEW"));
        HcmPopulationScopeService.ResolvedPopulation operations = population(
                80L, null, false, Set.of(UUID.randomUUID()),
                Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populationScopes.findOperations("READ")).thenReturn(Optional.of(operations));
        when(repository.findPerson(TENANT_ID, PERSON_ID)).thenReturn(Optional.empty());

        assertNotFound(() -> service.get(PERSON_ID, AS_OF));
        verify(repository, never()).findCurrentEmployments(TENANT_ID, 42L, AS_OF);

        stubPersonAndEmployments(List.of(employment(71L, true)));
        when(populations.containsWorker(TENANT_ID, operations.scope(), 71L)).thenReturn(false);

        assertNotFound(() -> service.get(PERSON_ID, AS_OF));
    }

    @Test
    void noPrimaryOrMultiplePrimaryAssignmentsFailClosed() {
        PeopleRequestContext.set(
                16L, TENANT_ID, PERSON_ID, Set.of("EMPLOYEE"), Set.of("APP.HCM:VIEW"));
        stubPersonAndEmployments(List.of(employment(71L, false)));

        assertConflict(() -> service.getSelf(AS_OF), "No primary assignment");

        stubPersonAndEmployments(List.of(
                employment(71L, true),
                employment(72L, true)));

        assertConflict(() -> service.getSelf(AS_OF), "Multiple primary assignments");
    }

    @Test
    void missingAsOfFailsClosedBeforeAnyOwnerRead() {
        PeopleRequestContext.set(
                16L, TENANT_ID, PERSON_ID, Set.of("EMPLOYEE"), Set.of("APP.HCM:VIEW"));

        assertThatThrownBy(() -> service.get(PERSON_ID, null))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(repository, never()).findPerson(TENANT_ID, PERSON_ID);
    }

    @Test
    void selfAccessRequiresAVerifiedPersonIdentity() {
        PeopleRequestContext.set(
                16L, TENANT_ID, null, Set.of("EMPLOYEE"), Set.of("APP.HCM:VIEW"));

        assertThatThrownBy(() -> service.getSelf(AS_OF))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).findPerson(eq(TENANT_ID), any(UUID.class));
    }

    @Test
    void injectedTenantEffectivePolicyControlsProjectionAndItsRevision() {
        PeopleRequestContext.set(
                18L, TENANT_ID, PERSON_ID, Set.of("EMPLOYEE"), Set.of("APP.HCM:VIEW"));
        stubPersonAndEmployments(List.of(employment(71L, true)));
        People360ProjectionPolicyProvider provider =
                mock(People360ProjectionPolicyProvider.class);
        Map<String, People360Dtos.FieldDecisionValue> fields = new LinkedHashMap<>();
        People360ProjectionPolicyProvider.FIELD_REGISTRY.forEach(field -> fields.put(
                field, People360Dtos.FieldDecisionValue.VIEW));
        fields.put("person.timeZone", People360Dtos.FieldDecisionValue.OMIT);
        fields.put("person.displayName", People360Dtos.FieldDecisionValue.MASK);
        fields.put(
                "primaryAssignment.businessTitle",
                People360Dtos.FieldDecisionValue.MASK);
        when(provider.resolve(any())).thenReturn(
                new People360ProjectionPolicyProvider.ProjectionPolicy(
                        "tenant-policy-2026-09-v4", fields));
        People360Service configured = new People360Service(
                repository, directory, populationScopes, populations, provider, true);

        People360Dtos.Snapshot result = configured.getSelf(AS_OF);

        assertThat(result.person().timeZone()).isNull();
        assertThat(result.person().displayName()).isEqualTo(People360Dtos.MASK_LITERAL);
        assertThat(result.primaryAssignment().businessTitle())
                .isEqualTo(People360Dtos.MASK_LITERAL);
        assertThat(result.access().policyRevision()).startsWith("effective-policy-");
        assertThat(result.projectionRevision()).startsWith("projection-");
        ArgumentCaptor<People360ProjectionPolicyProvider.PolicyInput> input =
                ArgumentCaptor.forClass(People360ProjectionPolicyProvider.PolicyInput.class);
        verify(provider).resolve(input.capture());
        assertThat(input.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(input.getValue().asOf()).isEqualTo(AS_OF);
        assertThat(input.getValue().purpose())
                .isEqualTo(People360ProjectionPolicyProvider.ProjectionPurpose.DETAIL);
        assertThat(input.getValue().archetype()).isEqualTo(People360Dtos.Archetype.SELF);
        assertThat(input.getValue().authorityRevision()).startsWith("self-");
        assertThat(input.getValue().fieldGroups()).containsExactlyInAnyOrder(
                "DIRECTORY", "WORKER_IDENTIFIERS", "EMPLOYMENT", "JOB_GRADE");
    }

    @Test
    void projectionPolicyCannotApplyTextMaskSemanticsToDates() {
        PeopleRequestContext.set(
                19L, TENANT_ID, PERSON_ID, Set.of("EMPLOYEE"), Set.of("APP.HCM:VIEW"));
        stubPersonAndEmployments(List.of(employment(71L, true)));
        People360ProjectionPolicyProvider provider =
                mock(People360ProjectionPolicyProvider.class);
        Map<String, People360Dtos.FieldDecisionValue> fields = new LinkedHashMap<>();
        People360ProjectionPolicyProvider.FIELD_REGISTRY.forEach(field -> fields.put(
                field, People360Dtos.FieldDecisionValue.VIEW));
        fields.put(
                "primaryAssignment.effectiveStartDate",
                People360Dtos.FieldDecisionValue.MASK);
        when(provider.resolve(any())).thenReturn(
                new People360ProjectionPolicyProvider.ProjectionPolicy(
                        "invalid-date-mask-v1", fields));
        People360Service configured = new People360Service(
                repository, directory, populationScopes, populations, provider, true);

        assertThatThrownBy(() -> configured.getSelf(AS_OF))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void people360ListReprojectsEverySelectedPersonAndPreservesCursorEnvelope() {
        PeopleRequestContext.set(
                17L, TENANT_ID, ACTOR_PERSON_ID,
                Set.of("HR_OPERATOR"), Set.of("DATA.WORKFORCE:VIEW"));
        when(directory.searchWorkforce("synthetic", "ACTIVE", "cursor-1", 25, AS_OF))
                .thenReturn(new PeopleDtos.CursorPage<>(
                        List.of(summary()), "cursor-2", 1, true, AS_OF));
        stubPersonAndEmployments(List.of(employment(71L, true)));
        HcmPopulationScopeService.ResolvedPopulation operations = population(
                0L, null, true, Set.of(),
                Set.of("DIRECTORY", "EMPLOYMENT", "WORKER_IDENTIFIERS"));
        when(populationScopes.requireOperations("READ")).thenReturn(operations);
        when(populations.containsWorker(TENANT_ID, operations.scope(), 71L)).thenReturn(true);

        People360Dtos.Page result = service.search(
                "synthetic", "ACTIVE", "cursor-1", 25, AS_OF);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.person().personId()).isEqualTo(PERSON_ID);
            assertThat(item.access().fieldDecisions()).hasSize(22);
            assertThat(item.person().timeZone()).isNull();
            assertThat(item.employment().workerNumber()).isNull();
            assertThat(item.primaryAssignment().jobGradeName()).isNull();
            assertThat(decisions(item))
                    .containsEntry(
                            "person.timeZone", People360Dtos.FieldDecisionValue.OMIT)
                    .containsEntry(
                            "employment.workerStatus", People360Dtos.FieldDecisionValue.VIEW);
        });
        assertThat(result.nextCursor()).isEqualTo("cursor-2");
        assertThat(result.hasMore()).isTrue();
        verify(repository).findCurrentEmployments(TENANT_ID, 42L, AS_OF);
        InOrder authorityBeforeDirectory = org.mockito.Mockito.inOrder(
                populationScopes, directory);
        authorityBeforeDirectory.verify(populationScopes).requireOperations("READ");
        authorityBeforeDirectory.verify(populationScopes).requireTrustedScope(
                operations, "hcm.operations", "TARGET_POPULATION",
                "WORKFORCE_TARGET_POPULATION", "ORG_UNIT/LEGAL_ENTITY");
        authorityBeforeDirectory.verify(directory).searchWorkforce(
                "synthetic", "ACTIVE", "cursor-1", 25, AS_OF);
    }

    private void stubPersonAndEmployments(
            List<People360Repository.CurrentEmploymentRow> employments) {
        when(repository.findPerson(TENANT_ID, PERSON_ID)).thenReturn(Optional.of(person()));
        when(repository.findCurrentEmployments(TENANT_ID, 42L, AS_OF))
                .thenReturn(employments);
    }

    private People360Repository.PersonRow person() {
        return new People360Repository.PersonRow(
                42L, PERSON_ID, "Synthetic Person", "ko-KR", "Asia/Seoul", "ACTIVE", 4L);
    }

    private People360Repository.CurrentEmploymentRow employment(
            long internalWorkerId,
            boolean primary) {
        return new People360Repository.CurrentEmploymentRow(
                internalWorkerId,
                UUID.nameUUIDFromBytes(("worker-" + internalWorkerId).getBytes()),
                "SYN-0042", "EMPLOYEE", "ACTIVE", LocalDate.of(2024, 1, 1), 3L,
                UUID.nameUUIDFromBytes(("relationship-" + internalWorkerId).getBytes()),
                "EMPLOYEE", LocalDate.of(2024, 1, 1), null, 2L,
                "Synthetic Legal Employer",
                primary ? UUID.nameUUIDFromBytes(("assignment-" + internalWorkerId).getBytes()) : null,
                primary ? "SYN-ASG-0042" : null,
                primary ? "ACTIVE" : null,
                primary ? "Synthetic Engineer" : null,
                primary ? LocalDate.of(2026, 1, 1) : null,
                null,
                primary ? 5L : null,
                primary ? UUID.fromString("40000000-0000-0000-0000-000000000001") : null,
                primary ? "Synthetic Organization" : null,
                primary ? "Synthetic Job" : null,
                primary ? "Synthetic Grade" : null,
                primary ? "Synthetic Location" : null,
                primary ? UUID.fromString("50000000-0000-0000-0000-000000000001") : null,
                primary ? "Synthetic Manager" : null);
    }

    private HcmPopulationScopeService.ResolvedPopulation population(
            long actorWorkerId,
            String managerAssignmentKey,
            boolean tenantWide,
            Set<UUID> organizations,
            Set<String> fields) {
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        actorWorkerId, managerAssignmentKey, tenantWide,
                        organizations, fields, "synthetic-policy-v3");
        HcmPopulationRepository.ActorWorkforce actor = managerAssignmentKey == null
                ? null
                : new HcmPopulationRepository.ActorWorkforce(
                        actorWorkerId, ACTOR_PERSON_ID, "Synthetic Manager",
                        managerAssignmentKey, "Manager", "Synthetic Organization",
                        1L, 2L, 3L);
        return new HcmPopulationScopeService.ResolvedPopulation(
                actor, scope, new HcmPopulationRepository.PopulationEvidence(
                        1L, "synthetic-population-v9"));
    }

    private PeopleDtos.PersonSummary summary() {
        return new PeopleDtos.PersonSummary(
                PERSON_ID, "Synthetic Person", "ko-KR", "Asia/Seoul", "ACTIVE",
                "SYN-0042", "EMPLOYEE", "ACTIVE", "SYN-ASG-0042",
                "Synthetic Engineer",
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                "SYN-ORG", "Synthetic Organization", "Synthetic Job", "INDIVIDUAL",
                "SYN-GRADE", "Synthetic Grade", "SYN-LOC", "Synthetic Location",
                "synthetic.person@example.invalid", null, LocalDate.of(2026, 1, 1),
                UUID.fromString("50000000-0000-0000-0000-000000000001"),
                "Synthetic Manager", 0,
                new PeopleDtos.DataAccess("RESTRICTED", false, List.of()));
    }

    private Map<String, People360Dtos.FieldDecisionValue> decisions(
            People360Dtos.Snapshot snapshot) {
        return snapshot.access().fieldDecisions().stream().collect(
                java.util.stream.Collectors.toMap(
                        People360Dtos.FieldDecision::field,
                        People360Dtos.FieldDecision::decision));
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private void assertConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            String message) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(exception.getMessage()).contains(message);
                });
    }

    private void assertForbidden(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
