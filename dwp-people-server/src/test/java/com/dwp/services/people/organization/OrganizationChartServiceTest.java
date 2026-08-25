package com.dwp.services.people.organization;

import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

class OrganizationChartServiceTest {

    private static final long TENANT_ID = 1L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 10);

    private final OrganizationChartRepository repository = mock(OrganizationChartRepository.class);
    private final OrganizationScenarioRepository scenarioRepository =
            mock(OrganizationScenarioRepository.class);
    private final WorkforceAccessPolicyService accessPolicyService =
            mock(WorkforceAccessPolicyService.class);
    private final HcmPopulationScopeService populationScopes =
            mock(HcmPopulationScopeService.class);
    private final OrganizationChartService service =
            new OrganizationChartService(
                    repository, scenarioRepository, accessPolicyService, populationScopes);

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(7L, TENANT_ID, Set.of("AUDITOR"));
        when(accessPolicyService.require("READ")).thenReturn(
                new WorkforceAccessPolicyService.Decision(
                        true,
                        Set.of(),
                        Set.of("DIRECTORY", "EMPLOYMENT", "JOB_GRADE"),
                        "READ"));
    }

    @AfterEach
    void clearContext() {
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
        PeopleRequestContext.clear();
    }

    @Test
    void organizationScopedChartCannotLeakAnExternalManagerOrExternalReportCount() {
        UUID teamId = UUID.randomUUID();
        UUID managerId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID outsideId = UUID.randomUUID();
        when(accessPolicyService.require("READ")).thenReturn(
                new WorkforceAccessPolicyService.Decision(
                        false, Set.of(teamId), Set.of("DIRECTORY", "EMPLOYMENT"), "READ"));
        when(repository.organizations(TENANT_ID, AS_OF)).thenReturn(List.of(
                new OrganizationChartRepository.OrganizationRow(
                        1L, teamId, "TEAM-A", "Team A", "Team A", "SUPERVISORY",
                        "Team", 10, true, "Scoped team", null, "BLUE", null, managerId)));
        when(repository.people(TENANT_ID, AS_OF)).thenReturn(List.of(
                person(managerId, "ASG-MANAGER", null, teamId, "SK0001", "G7", 7),
                person(memberId, "ASG-MEMBER", "ASG-MANAGER", teamId, "SK0002", "G3", 3),
                person(outsideId, "ASG-OUTSIDE", "ASG-MANAGER", UUID.randomUUID(),
                        "SK9999", "G3", 3)));
        when(repository.designPolicy(TENANT_ID)).thenReturn(Optional.empty());

        OrganizationChartDtos.OrganizationChart result = service.get(AS_OF, null, 6);

        assertThat(result.people()).extracting(OrganizationChartDtos.Person::personId)
                .containsExactlyInAnyOrder(managerId, memberId)
                .doesNotContain(outsideId);
        assertThat(result.people()).filteredOn(person -> person.personId().equals(managerId))
                .singleElement().satisfies(manager ->
                        assertThat(manager.directReportCount()).isEqualTo(1));
    }

    @Test
    void exactAppConfigAuthorityUsesTheConfigurationScopeWithoutLegacyWorkforcePolicy() {
        HcmV3PepRegistry.RouteAuthority authority = mock(HcmV3PepRegistry.RouteAuthority.class);
        when(authority.routeContractKey()).thenReturn("route.hcm.management.org-design.page");
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set",
                new HcmPepContext.Evidence(
                        authority, "psr-" + "a".repeat(64),
                        OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                        "hcm.management", "hcm-config-scope", "110"));
        UUID rootId = UUID.randomUUID();
        when(repository.organizations(TENANT_ID, AS_OF)).thenReturn(List.of(
                new OrganizationChartRepository.OrganizationRow(
                        1L, rootId, "ROOT", "SKAX", "SKAX", "COMPANY",
                        "Company", 10, true, "Company", "CC-0000", "RED", null, null)));
        when(repository.designPolicy(TENANT_ID)).thenReturn(Optional.empty());

        OrganizationChartDtos.OrganizationChart result = service.get(AS_OF, null, 6);

        assertThat(result.company().organizationId()).isEqualTo(rootId);
        verify(populationScopes).requireConfigurationScope();
        verify(accessPolicyService, never()).require("READ");
    }

    @Test
    void buildsEffectiveHierarchyMetricsAndMasksRestrictedWorkerNumbers() {
        UUID rootId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID leaderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        when(repository.organizations(TENANT_ID, AS_OF)).thenReturn(List.of(
                new OrganizationChartRepository.OrganizationRow(
                        1L, rootId, "ROOT", "SKAX", "SKAX", "COMPANY",
                        "Company", 10, true,
                        "Company", "CC-0000", "SK_RED", null, leaderId),
                new OrganizationChartRepository.OrganizationRow(
                        2L, teamId, "TEAM-A", "AI Engineering", "AI Engineering",
                        "SUPERVISORY", "Team", 50, false,
                        "AI team", "CC-1000", "VIOLET", rootId, memberId)));
        when(repository.people(TENANT_ID, AS_OF)).thenReturn(List.of(
                person(leaderId, "ASG-LEADER", null, rootId, "SK0001", "G7", 7),
                person(memberId, "ASG-MEMBER", "ASG-LEADER", teamId, "SK0042", "G3", 3)));
        when(repository.relationships(TENANT_ID, AS_OF)).thenReturn(List.of(
                new OrganizationChartRepository.RelationshipRow(
                        teamId, rootId, "SUPERVISORY", true)));
        when(repository.positions(TENANT_ID, AS_OF)).thenReturn(List.of(
                new OrganizationChartRepository.PositionRow(
                        UUID.randomUUID(), "OPEN-AI-01", "AI Engineer", teamId,
                        null, "OPEN", "REGULAR", "HIGH",
                        BigDecimal.ONE, new BigDecimal("105000000"), "KRW",
                        "AI Engineer", "Seoul", AS_OF.plusDays(30))));
        when(repository.designPolicy(TENANT_ID)).thenReturn(Optional.of(
                new OrganizationChartRepository.DesignPolicyRow(
                        1, 8, 6, new BigDecimal("20"), new BigDecimal("18"))));

        OrganizationChartDtos.OrganizationChart result = service.get(AS_OF, null, 10);

        assertThat(result.company().name()).isEqualTo("SKAX");
        assertThat(result.metrics().headcount()).isEqualTo(2);
        assertThat(result.metrics().managerCount()).isEqualTo(1);
        assertThat(result.metrics().openPositionCount()).isEqualTo(1);
        assertThat(result.analysis().dataQualityScore()).isEqualTo(100);
        assertThat(result.positions()).hasSize(1);
        assertThat(result.organizations())
                .filteredOn(organization -> organization.organizationId().equals(rootId))
                .singleElement()
                .satisfies(root -> assertThat(root.totalHeadcount()).isEqualTo(2));
        assertThat(result.people())
                .filteredOn(person -> person.personId().equals(leaderId))
                .singleElement()
                .satisfies(leader -> {
                    assertThat(leader.directReportCount()).isEqualTo(1);
                    assertThat(leader.workerNumber()).isNull();
                });

        OrganizationChartDtos.OrganizationChart directory = service.getDirectory(AS_OF, null, 10);
        assertThat(directory.positions()).isEmpty();
        assertThat(directory.openPositions()).isEmpty();
        assertThat(directory.metrics().workforceCostAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(directory.organizations()).allSatisfy(organization ->
                assertThat(organization.costCenterKey()).isNull());
        assertThat(directory.people()).allSatisfy(person -> {
            assertThat(person.workerNumber()).isNull();
            assertThat(person.jobGradeKey()).isNull();
            assertThat(person.positionId()).isNull();
        });
    }

    @Test
    void scenarioProjectionHonorsAnExplicitEffectiveDate() {
        UUID scenarioId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        LocalDate baselineDate = AS_OF;
        LocalDate effectiveDate = AS_OF.plusMonths(3);
        when(scenarioRepository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(
                new OrganizationScenarioRepository.ScenarioRecord(
                        scenarioId, "future-state", "Future state", baselineDate,
                        effectiveDate, "0".repeat(64), "DRAFT", 7L, 0L)));
        when(repository.organizations(TENANT_ID, effectiveDate)).thenReturn(List.of(
                new OrganizationChartRepository.OrganizationRow(
                        1L, rootId, "ROOT", "SKAX", "SKAX", "COMPANY",
                        "Company", 10, true,
                        "Company", "CC-0000", "SK_RED", null, null)));
        when(repository.designPolicy(TENANT_ID)).thenReturn(Optional.empty());

        OrganizationChartDtos.OrganizationChart result = service.get(
                effectiveDate, null, 10, scenarioId);

        assertThat(result.asOf()).isEqualTo(effectiveDate);
        assertThat(result.scenario()).isNotNull();
        assertThat(result.scenario().baseAsOf()).isEqualTo(baselineDate);
    }

    private OrganizationChartRepository.PersonRow person(
            UUID personId,
            String assignmentKey,
            String managerAssignmentKey,
            UUID organizationId,
            String workerNumber,
            String gradeKey,
            int gradeOrder) {
        return new OrganizationChartRepository.PersonRow(
                personId,
                "Person " + workerNumber,
                workerNumber,
                "EMPLOYEE",
                "ACTIVE",
                assignmentKey,
                managerAssignmentKey,
                "Engineer",
                BigDecimal.ONE,
                organizationId,
                UUID.randomUUID(),
                "POS-" + workerNumber,
                "Engineer",
                managerAssignmentKey == null ? "EXECUTIVE" : "INDIVIDUAL",
                gradeKey,
                gradeKey,
                gradeOrder,
                "SEOUL",
                "Seoul",
                workerNumber.toLowerCase() + "@sk.com");
    }
}
