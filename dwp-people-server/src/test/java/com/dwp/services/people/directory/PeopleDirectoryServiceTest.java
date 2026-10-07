package com.dwp.services.people.directory;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeopleDirectoryServiceTest {

    private static final long TENANT_ID = 3L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 11);

    private final PeopleDirectoryRepository repository = mock(PeopleDirectoryRepository.class);
    private final PeopleCursorCodec cursorCodec = mock(PeopleCursorCodec.class);
    private final WorkforceAccessPolicyService accessPolicyService =
            mock(WorkforceAccessPolicyService.class);
    private final HcmPopulationScopeService populationScopes =
            mock(HcmPopulationScopeService.class);
    private final PeopleDirectoryService service = new PeopleDirectoryService(
            repository, cursorCodec, accessPolicyService, populationScopes);
    private final HcmPopulationRepository.PopulationScope workforceScope =
            new HcmPopulationRepository.PopulationScope(
                    0L, null, true, Set.of(),
                    Set.of("DIRECTORY", "WORKER_IDENTIFIERS", "EMPLOYMENT", "JOB_GRADE"),
                    "policy-v1");
    private final HcmPopulationScopeService.ResolvedPopulation workforcePopulation =
            new HcmPopulationScopeService.ResolvedPopulation(
                    null, workforceScope,
                    new HcmPopulationRepository.PopulationEvidence(3L, "population-v1"));

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(9L, TENANT_ID, Set.of("USER"));
        when(cursorCodec.fingerprint(any(), any(), any()))
                .thenReturn("directory-fingerprint");
        when(repository.search(eq(TENANT_ID), anyLong(), any(), any(), eq(AS_OF), eq(21)))
                .thenReturn(List.of(directoryRow()));
        WorkforceAccessPolicyService.Decision decision = new WorkforceAccessPolicyService.Decision(
                true, Set.of(),
                Set.of("DIRECTORY", "WORKER_IDENTIFIERS", "EMPLOYMENT", "JOB_GRADE"),
                "READ");
        when(accessPolicyService.require("READ")).thenReturn(decision);
        when(populationScopes.requireOperations("READ"))
                .thenReturn(workforcePopulation);
        when(repository.searchWithinPopulation(
                eq(TENANT_ID), anyLong(), any(), any(), eq(AS_OF), eq(21),
                eq(true), eq(true), eq(workforceScope)))
                .thenReturn(List.of(directoryRow()));
    }

    @AfterEach
    void clearContext() {
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
        PeopleRequestContext.clear();
    }

    @Test
    void publicPeopleDirectorySuppressesWorkforceRestrictedFields() {
        PeopleDtos.PersonSummary person = service.search(null, null, null, 20, AS_OF)
                .items()
                .getFirst();

        assertThat(person.workerNumber()).isNull();
        assertThat(person.assignmentKey()).isNull();
        assertThat(person.jobGradeKey()).isNull();
        assertThat(person.jobGradeName()).isNull();
        assertThat(person.assignmentEffectiveFrom()).isNull();
        assertThat(person.dataAccess().workerNumberMasked()).isTrue();
        assertThat(person.dataAccess().excludedFieldGroups())
                .contains("workerIdentifiers", "employmentHistory", "jobGrade");
    }

    @Test
    void workforceDirectoryRetainsOperationalFields() {
        PeopleDtos.PersonSummary person = service.searchWorkforce(null, null, null, 20, AS_OF)
                .items()
                .getFirst();

        assertThat(person.workerNumber()).isEqualTo("SK000042");
        assertThat(person.assignmentKey()).isEqualTo("ASG-0042");
        assertThat(person.jobGradeKey()).isEqualTo("G4");
        assertThat(person.jobGradeName()).isEqualTo("Senior");
        assertThat(person.assignmentEffectiveFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(person.dataAccess().workerNumberMasked()).isFalse();
        assertThat(person.dataAccess().excludedFieldGroups())
                .doesNotContain("workerIdentifiers", "employmentHistory", "jobGrade");
    }

    @Test
    void preResolvedWorkforceSearchDoesNotSendMaskedFieldPredicatesToTheRepository() {
        HcmPopulationRepository.PopulationScope restrictedScope =
                new HcmPopulationRepository.PopulationScope(
                        91L, null, true, Set.of(),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "restricted-policy-v1");
        HcmPopulationScopeService.ResolvedPopulation restrictedPopulation =
                new HcmPopulationScopeService.ResolvedPopulation(
                        null, restrictedScope,
                        new HcmPopulationRepository.PopulationEvidence(
                                2L, "restricted-population-v1"));
        when(repository.searchWithinPopulation(
                TENANT_ID, 0L, "SK000042", null, AS_OF, 21,
                false, false, restrictedScope))
                .thenReturn(List.of());

        service.searchWorkforce(
                "SK000042", null, null, 20, AS_OF, restrictedPopulation);

        verify(repository).searchWithinPopulation(
                TENANT_ID, 0L, "SK000042", null, AS_OF, 21,
                false, false, restrictedScope);
        verify(populationScopes, never()).requireOperations("READ");
        verify(accessPolicyService, never()).require("READ");
    }

    @Test
    void workforceDetailSeparatesPersonWorkerRelationshipAndAssignment() {
        UUID personId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        PeopleDirectoryRepository.DirectoryRow person = directoryRow();
        when(repository.findByPublicId(TENANT_ID, personId, AS_OF))
                .thenReturn(Optional.of(person));
        when(repository.findByPublicIdWithinPopulation(
                TENANT_ID, personId, AS_OF, workforceScope))
                .thenReturn(Optional.of(person));
        when(repository.findAssignmentsWithinPopulation(
                TENANT_ID, person.internalPersonId(), AS_OF, workforceScope))
                .thenReturn(List.of(new PeopleDirectoryRepository.AssignmentRow(
                        "ASG-0042", "ACTIVE", true,
                        LocalDate.of(2025, 1, 1), null, "Enterprise Architect",
                        "AI Platform Team", "Enterprise Architect", "Senior", "Seoul",
                        "ASG-0001", "PROMOTION")));
        when(repository.findWorkforceEntitiesWithinPopulation(
                TENANT_ID, person.internalPersonId(), AS_OF, workforceScope))
                .thenReturn(List.of(new PeopleDirectoryRepository.WorkforceEntityRow(
                        workerId, "SK000042", "EMPLOYEE", "ACTIVE",
                        LocalDate.of(2020, 2, 3), relationshipId, "REL-0042", "EMPLOYEE",
                        true, LocalDate.of(2020, 2, 3), null, null,
                        "SKAX", "SK AX", "KR", assignmentId, "ASG-0042", "ACTIVE",
                        true, LocalDate.of(2025, 1, 1), null, 1,
                        "Enterprise Architect", UUID.randomUUID(), "AI-PLATFORM",
                        "AI Platform Team", "Enterprise Architect", "Senior",
                        "SEOUL", "Seoul", "ASG-0001", "PROMOTION")));

        PeopleDtos.PersonDetail directory = service.get(personId, AS_OF);
        PeopleDtos.PersonDetail workforce = service.getWorkforce(personId, AS_OF);

        assertThat(directory.workers()).isEmpty();
        assertThat(directory.assignments()).isEmpty();
        assertThat(workforce.workers()).singleElement().satisfies(worker -> {
            assertThat(worker.workerId()).isEqualTo(workerId);
            assertThat(worker.workRelationships()).singleElement().satisfies(relationship -> {
                assertThat(relationship.workRelationshipId()).isEqualTo(relationshipId);
                assertThat(relationship.assignments()).singleElement().satisfies(assignment ->
                        assertThat(assignment.assignmentId()).isEqualTo(assignmentId));
            });
        });
    }

    @Test
    void personalMePredicateNeverReturnsAnotherTenantPersonRow() {
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PeopleRequestContext.set(9L, TENANT_ID, self, Set.of("USER"), Set.of());
        setPep("route.hcm.personal.me.page");

        assertThatThrownBy(() -> service.get(other, AS_OF))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(repository, never()).findByPublicId(TENANT_ID, other, AS_OF);
    }

    @Test
    void explicitDirectoryViewCanResolveAnotherVisibleTenantPerson() {
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PeopleRequestContext.set(9L, TENANT_ID, self, Set.of("USER"), Set.of());
        setPep("route.hcm.personal.directory-person-detail.data");
        when(repository.findByPublicId(TENANT_ID, other, AS_OF))
                .thenReturn(Optional.of(directoryRow()));

        assertThat(service.get(other, AS_OF)).isNotNull();
        verify(repository).findByPublicId(TENANT_ID, other, AS_OF);
    }

    @Test
    void workforceDetailNeverFallsBackToAnUnscopedTenantLookup() {
        UUID requested = UUID.randomUUID();
        UUID allowedOrganization = UUID.randomUUID();
        HcmPopulationRepository.PopulationScope scoped =
                new HcmPopulationRepository.PopulationScope(
                        91L, "MGR-91", false, Set.of(allowedOrganization),
                        Set.of("DIRECTORY"), "policy-scoped");
        HcmPopulationScopeService.ResolvedPopulation resolved =
                new HcmPopulationScopeService.ResolvedPopulation(
                        null, scoped,
                        new HcmPopulationRepository.PopulationEvidence(1L, "population-scoped"));
        when(populationScopes.requireOperations("READ")).thenReturn(resolved);
        when(repository.findByPublicIdWithinPopulation(
                TENANT_ID, requested, AS_OF, scoped))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWorkforce(requested, AS_OF))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(repository).findByPublicIdWithinPopulation(
                TENANT_ID, requested, AS_OF, scoped);
        verify(repository, never()).findByPublicId(TENANT_ID, requested, AS_OF);
    }

    @Test
    void workforceCursorBindsPopulationRevisionAndFillsAcrossExcludedIdGaps() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(cursorCodec.fingerprint(
                null, null,
                AS_OF + "|target-population|operator|population-v1:policy-v1"))
                .thenReturn("population-fingerprint");
        when(cursorCodec.decode(
                "before-page", TENANT_ID, "population-fingerprint"))
                .thenReturn(100L);
        when(repository.searchWithinPopulation(
                TENANT_ID, 100L, null, null, AS_OF, 3,
                true, true, workforceScope))
                .thenReturn(List.of(
                        directoryRow(101L, first),
                        directoryRow(103L, second),
                        directoryRow(104L, UUID.randomUUID())));
        when(cursorCodec.encode(TENANT_ID, 103L, "population-fingerprint"))
                .thenReturn("after-second-allowed");

        PeopleDtos.CursorPage<PeopleDtos.PersonSummary> page =
                service.searchWorkforce(null, null, "before-page", 2, AS_OF);

        assertThat(page.items())
                .extracting(PeopleDtos.PersonSummary::personId)
                .containsExactly(first, second);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("after-second-allowed");
        verify(repository).searchWithinPopulation(
                TENANT_ID, 100L, null, null, AS_OF, 3,
                true, true, workforceScope);
        verify(accessPolicyService, never()).require("READ");
    }

    @Test
    void preResolvedPopulationRevisionBindsCursorWithoutResolvingAuthorityAgain() {
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        91L, null, true, Set.of(),
                        Set.of("DIRECTORY", "WORKER_IDENTIFIERS", "EMPLOYMENT", "JOB_GRADE"),
                        "synthetic-policy-v3");
        HcmPopulationScopeService.ResolvedPopulation population =
                new HcmPopulationScopeService.ResolvedPopulation(
                        null, scope,
                        new HcmPopulationRepository.PopulationEvidence(
                                3L, "synthetic-population-v9"));
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        String populationFingerprint =
                AS_OF + "|target-population|operator|"
                        + "synthetic-population-v9:synthetic-policy-v3";
        when(cursorCodec.fingerprint(null, null, populationFingerprint))
                .thenReturn("pre-resolved-population-fingerprint");
        when(cursorCodec.decode(
                "cursor-before-page", TENANT_ID, "pre-resolved-population-fingerprint"))
                .thenReturn(100L);
        when(repository.searchWithinPopulation(
                TENANT_ID, 100L, null, null, AS_OF, 3,
                true, true, scope))
                .thenReturn(List.of(
                        directoryRow(101L, firstId),
                        directoryRow(103L, secondId),
                        directoryRow(104L, UUID.randomUUID())));
        when(cursorCodec.encode(
                TENANT_ID, 103L, "pre-resolved-population-fingerprint"))
                .thenReturn("cursor-after-second-allowed");

        PeopleDtos.CursorPage<PeopleDtos.PersonSummary> result = service.searchWorkforce(
                null, null, "cursor-before-page", 2, AS_OF, population);

        assertThat(result.items())
                .extracting(PeopleDtos.PersonSummary::personId)
                .containsExactly(firstId, secondId);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isEqualTo("cursor-after-second-allowed");
        verify(repository).searchWithinPopulation(
                TENANT_ID, 100L, null, null, AS_OF, 3,
                true, true, scope);
        verify(populationScopes, never()).requireOperations("READ");
        verify(accessPolicyService, never()).require("READ");
    }

    private void setPep(String route) {
        HcmV3PepRegistry.RouteAuthority authority = new HcmV3PepRegistry.RouteAuthority(
                route, route.endsWith(".page") ? "PAGE" : "DATA", "self", true,
                Set.of("predicate.self-person.v1"), Set.of("SELF"),
                route + ".binding.01", null, null, "GET", "/api/people", null);
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set",
                new HcmPepContext.Evidence(
                        authority, "psr-" + "a".repeat(64),
                        OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                        "hcm.personal", "scope-self", "110"));
    }

    private PeopleDirectoryRepository.DirectoryRow directoryRow() {
        return directoryRow(42L, UUID.randomUUID());
    }

    private PeopleDirectoryRepository.DirectoryRow directoryRow(
            long internalPersonId, UUID publicId) {
        return new PeopleDirectoryRepository.DirectoryRow(
                internalPersonId,
                publicId,
                "Kim DWP",
                "ko-KR",
                "Asia/Seoul",
                "ACTIVE",
                "SK000042",
                "EMPLOYEE",
                "ACTIVE",
                LocalDate.of(2020, 2, 3),
                "ASG-0042",
                "Enterprise Architect",
                "ASG-0001",
                LocalDate.of(2025, 1, 1),
                UUID.randomUUID(),
                "AI-PLATFORM",
                "AI Platform Team",
                "Enterprise Architect",
                "INDIVIDUAL",
                "G4",
                "Senior",
                "SEOUL",
                "Seoul",
                "SKAX",
                UUID.randomUUID(),
                "Manager Kim",
                0,
                "kim.dwp@example.com",
                "profiles/42.webp");
    }
}
