package com.dwp.services.people.organization;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationBaselineFingerprintTest {

    @Test
    void usesStableBusinessKeysInsteadOfEnvironmentSpecificIdentifiers() {
        String first = OrganizationBaselineFingerprint.compute(chart());
        String second = OrganizationBaselineFingerprint.compute(chart());

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo(
                "5bcdcddc9dc6e1a62aa9796341479a88a4820fc48012f9be19040d963bea20a4");
    }

    private OrganizationChartDtos.OrganizationChart chart() {
        UUID rootId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        OrganizationChartDtos.Organization root = organization(
                rootId, "ROOT", "SKAX", "COMPANY", null, "CC-0");
        OrganizationChartDtos.Organization team = organization(
                teamId, "ORG-AI", "AI", "TEAM", rootId, "CC-1");
        OrganizationChartDtos.Person person = new OrganizationChartDtos.Person(
                personId, "A1", "Alex", "a@x", "Engineer", "Engineer",
                "G1", "Grade 1", 1, "INDIVIDUAL_CONTRIBUTOR", teamId, null,
                false, positionId, "P1", "W1", "EMPLOYEE", "ACTIVE",
                "SEOUL", "Seoul", 0, BigDecimal.ONE);
        OrganizationChartDtos.Position position = new OrganizationChartDtos.Position(
                positionId, "P1", "Engineer", teamId, null, "FILLED", "REGULAR",
                "MEDIUM", BigDecimal.ONE, new BigDecimal("100"), "KRW",
                "Engineer", "Seoul", null, List.of(personId), 0);
        return new OrganizationChartDtos.OrganizationChart(
                LocalDate.of(2026, 8, 10),
                new OrganizationChartDtos.Company(rootId, "ROOT", "SKAX", null),
                null, null, null, List.of(team, root), List.of(person), List.of(position),
                List.of(new OrganizationChartDtos.Relationship(
                        teamId, rootId, "SUPERVISORY", true)),
                List.of());
    }

    private OrganizationChartDtos.Organization organization(
            UUID id,
            String key,
            String name,
            String type,
            UUID parentId,
            String costCenter) {
        return new OrganizationChartDtos.Organization(
                id, key, name, name, type, type, parentId, null, costCenter, null,
                0, 0, 0, 0, 0, null, List.of(), 1, 0, 0,
                "HEALTHY", List.of());
    }
}
