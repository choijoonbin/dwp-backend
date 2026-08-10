package com.dwp.services.people.organization;

import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrganizationChartServiceTest {

    private static final long TENANT_ID = 1L;
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 10);

    private final OrganizationChartRepository repository = mock(OrganizationChartRepository.class);
    private final OrganizationChartService service = new OrganizationChartService(repository);

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(7L, TENANT_ID, Set.of("AUDITOR"));
    }

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
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
                        "Company", "CC-0000", "SK_RED", null),
                new OrganizationChartRepository.OrganizationRow(
                        2L, teamId, "TEAM-A", "AI Engineering", "AI Engineering",
                        "SUPERVISORY", "AI team", "CC-1000", "VIOLET", rootId)));
        when(repository.people(TENANT_ID, AS_OF)).thenReturn(List.of(
                person(leaderId, "ASG-LEADER", null, rootId, "SK0001", "G7", 7),
                person(memberId, "ASG-MEMBER", "ASG-LEADER", teamId, "SK0042", "G3", 3)));
        when(repository.relationships(TENANT_ID, AS_OF)).thenReturn(List.of(
                new OrganizationChartRepository.RelationshipRow(
                        teamId, rootId, "SUPERVISORY", true)));
        when(repository.openPositions(TENANT_ID)).thenReturn(List.of(
                new OrganizationChartRepository.OpenPositionRow(
                        "OPEN-AI-01", "AI Engineer", teamId,
                        "AI Engineer", "Seoul", AS_OF.plusDays(30))));

        OrganizationChartDtos.OrganizationChart result = service.get(AS_OF, null, 10);

        assertThat(result.company().name()).isEqualTo("SKAX");
        assertThat(result.metrics().headcount()).isEqualTo(2);
        assertThat(result.metrics().managerCount()).isEqualTo(1);
        assertThat(result.metrics().openPositionCount()).isEqualTo(1);
        assertThat(result.organizations())
                .filteredOn(organization -> organization.organizationId().equals(rootId))
                .singleElement()
                .satisfies(root -> assertThat(root.totalHeadcount()).isEqualTo(2));
        assertThat(result.people())
                .filteredOn(person -> person.personId().equals(leaderId))
                .singleElement()
                .satisfies(leader -> {
                    assertThat(leader.directReportCount()).isEqualTo(1);
                    assertThat(leader.workerNumber()).isEqualTo("******0001");
                });
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
                organizationId,
                "Engineer",
                managerAssignmentKey == null ? "EXECUTIVE" : "INDIVIDUAL",
                gradeKey,
                gradeKey,
                gradeOrder,
                "SEOUL",
                "Seoul",
                workerNumber.toLowerCase() + "@skax.example");
    }
}
