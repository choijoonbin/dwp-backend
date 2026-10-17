package com.dwp.services.people.hris.organization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.organization.OrganizationChartDtos;
import com.dwp.services.people.organization.OrganizationChartService;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrganizationGraphServiceTest {

    private final OrganizationChartService charts = mock(OrganizationChartService.class);
    private final WorkforceAccessPolicyService policies =
            mock(WorkforceAccessPolicyService.class);
    private final OrganizationGraphService service =
            new OrganizationGraphService(charts, policies);

    @Test
    void depthZeroQueriesDepthOneThenProjectsOnlyRootAndItsEffectivePositions() {
        UUID root = uuid("00000000-0000-0000-0000-000000000010");
        UUID child = uuid("00000000-0000-0000-0000-000000000020");
        UUID rootPosition = uuid("00000000-0000-0000-0000-000000000030");
        UUID childPosition = uuid("00000000-0000-0000-0000-000000000040");
        UUID incumbentB = uuid("00000000-0000-0000-0000-000000000102");
        UUID incumbentA = uuid("00000000-0000-0000-0000-000000000101");
        Instant instant = Instant.parse("2026-10-16T15:30:00Z");
        LocalDate effectiveDate = LocalDate.parse("2026-10-16");
        when(policies.require("READ")).thenReturn(decision(true));
        when(charts.get(effectiveDate, root, 1)).thenReturn(chart(
                effectiveDate,
                root,
                List.of(organization(child, "Child", root), organization(root, "Root", null)),
                List.of(
                        position(childPosition, "CHILD", child, rootPosition, List.of()),
                        position(rootPosition, "ROOT", root, null,
                                List.of(incumbentB, incumbentA)))));

        OrganizationGraphWithPositionsV2 response = service.query(
                instant, root, 0, "REPORTING", true);

        assertThat(response.schemaVersion()).isEqualTo(2);
        assertThat(response.asOf()).isEqualTo(instant);
        assertThat(response.effectiveDate()).isEqualTo(effectiveDate);
        assertThat(response.depth()).isZero();
        assertThat(response.relationshipType()).isEqualTo("REPORTING");
        assertThat(response.positionsIncluded()).isTrue();
        assertThat(response.nodes()).extracting(
                OrganizationGraphWithPositionsV2.OrganizationNodeV2::organizationPublicId)
                .containsExactly(root);
        assertThat(response.nodes().getFirst().parentPublicId()).isNull();
        assertThat(response.positions()).extracting(
                OrganizationGraphWithPositionsV2.OrganizationPositionV2::positionPublicId)
                .containsExactly(rootPosition);
        assertThat(response.positions().getFirst().incumbentPersonPublicIds())
                .containsExactly(incumbentA, incumbentB);
        verify(charts).get(effectiveDate, root, 1);
    }

    @Test
    void offsetEquivalentInstantUsesUtcDateAndEchoesCanonicalInstant() {
        UUID root = UUID.randomUUID();
        Instant canonical = Instant.parse("2026-10-16T15:30:00Z");
        LocalDate utcDate = LocalDate.parse("2026-10-16");
        when(charts.get(utcDate, root, 2)).thenReturn(chart(
                utcDate, root, List.of(organization(root, "Root", null)), List.of()));

        OrganizationGraphWithPositionsV2 response = service.query(
                canonical, root, 2, "REPORTING", false);

        assertThat(response.asOf().toString()).isEqualTo("2026-10-16T15:30:00Z");
        assertThat(response.effectiveDate()).isEqualTo(utcDate);
        verify(charts).get(utcDate, root, 2);
        verify(policies, never()).require("READ");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void supportedDepthIsPassedUnchangedToTheExistingEngine(int depth) {
        UUID root = UUID.randomUUID();
        LocalDate date = LocalDate.parse("2026-10-17");
        when(charts.get(date, root, depth)).thenReturn(chart(
                date, root, List.of(organization(root, "Root", null)), List.of()));

        OrganizationGraphWithPositionsV2 response = service.query(
                date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                root, depth, "REPORTING", false);

        assertThat(response.depth()).isEqualTo(depth);
        verify(charts).get(date, root, depth);
    }

    @Test
    void nodesPositionsAndIncumbentsHaveSealedStableOrdering() {
        UUID root = uuid("00000000-0000-0000-0000-000000000100");
        UUID childB = uuid("00000000-0000-0000-0000-000000000220");
        UUID childA = uuid("00000000-0000-0000-0000-000000000210");
        UUID privateParent = uuid("00000000-0000-0000-0000-000000000999");
        UUID supplementary = uuid("00000000-0000-0000-0000-000000000320");
        UUID bmp = uuid("00000000-0000-0000-0000-000000000310");
        UUID childPosition = uuid("00000000-0000-0000-0000-000000000330");
        LocalDate date = LocalDate.parse("2026-10-17");
        when(policies.require("READ")).thenReturn(decision(true));
        when(charts.get(date, root, 2)).thenReturn(chart(
                date,
                root,
                List.of(
                        organization(childB, "B", root),
                        organization(root, "Root", null),
                        organization(childA, "A", root)),
                List.of(
                        position(supplementary, "\uD800\uDC00", root, privateParent,
                                List.of()),
                        position(childPosition, "A", childB, supplementary, List.of()),
                        position(bmp, "\uE000", root, null, List.of()))));

        OrganizationGraphWithPositionsV2 response = service.query(
                date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                root, 2, "REPORTING", true);

        assertThat(response.nodes()).extracting(
                OrganizationGraphWithPositionsV2.OrganizationNodeV2::organizationPublicId)
                .containsExactly(root, childA, childB);
        assertThat(response.positions()).extracting(
                OrganizationGraphWithPositionsV2.OrganizationPositionV2::positionPublicId)
                .containsExactly(bmp, supplementary, childPosition);
        assertThat(response.positions().get(1).reportsToPositionPublicId()).isNull();
        assertThat(response.positions().get(1).subordinatePositionCount()).isEqualTo(1);
    }

    @Test
    void includePositionsFalseReturnsAnEmptyArrayWithoutPositionAuthorityLookup() {
        UUID root = UUID.randomUUID();
        LocalDate date = LocalDate.parse("2026-10-17");
        when(charts.get(date, root, 1)).thenReturn(chart(
                date, root, List.of(organization(root, "Root", null)),
                List.of(position(UUID.randomUUID(), "P", root, null, List.of()))));

        OrganizationGraphWithPositionsV2 response = service.query(
                date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                root, 1, "REPORTING", false);

        assertThat(response.positionsIncluded()).isFalse();
        assertThat(response.positions()).isEmpty();
        verifyNoInteractions(policies);
    }

    @Test
    void legalAndCostFailBeforeAuthorityOrExistingEngineAccess() {
        assertThatThrownBy(() -> service.query(
                Instant.EPOCH, UUID.randomUUID(), 1, "LEGAL", false))
                .isInstanceOfSatisfying(OrganizationGraphProblem.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo(OrganizationGraphService.UNSUPPORTED_MODEL));
        assertThatThrownBy(() -> service.query(
                Instant.EPOCH, UUID.randomUUID(), 1, "COST", false))
                .isInstanceOfSatisfying(OrganizationGraphProblem.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo(OrganizationGraphService.UNSUPPORTED_MODEL));
        verifyNoInteractions(charts, policies);
    }

    @Test
    void invalidDepthAndRelationshipNeverClampOrReachTheEngine() {
        assertThatThrownBy(() -> service.query(
                Instant.EPOCH, UUID.randomUUID(), 13, "REPORTING", false))
                .isInstanceOfSatisfying(OrganizationGraphProblem.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo(OrganizationGraphService.INVALID_QUERY));
        assertThatThrownBy(() -> service.query(
                Instant.EPOCH, UUID.randomUUID(), 1, "MATRIX", false))
                .isInstanceOf(OrganizationGraphProblem.class);
        verifyNoInteractions(charts, policies);
    }

    @Test
    void missingPositionFieldFailsClosedBeforeGraphAccess() {
        when(policies.require("READ")).thenReturn(decision(false));

        assertThatThrownBy(() -> service.query(
                Instant.EPOCH, UUID.randomUUID(), 1, "REPORTING", true))
                .isInstanceOfSatisfying(OrganizationGraphProblem.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo(OrganizationGraphService.AUTHORIZATION_DENIED));

        verifyNoInteractions(charts);
    }

    @Test
    void wrongTenantOrPopulationIsOpaqueAndMissingRootIsNotDisclosed() {
        UUID root = UUID.randomUUID();
        when(charts.get(LocalDate.parse("1970-01-01"), root, 1))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.query(
                Instant.EPOCH, root, 1, "REPORTING", false))
                .isInstanceOfSatisfying(OrganizationGraphProblem.class, problem -> {
                    assertThat(problem.code())
                            .isEqualTo(OrganizationGraphService.AUTHORIZATION_DENIED);
                    assertThat(problem.getMessage()).doesNotContain(root.toString());
                });

        UUID absentRoot = UUID.randomUUID();
        when(charts.get(LocalDate.parse("1970-01-01"), absentRoot, 1)).thenReturn(chart(
                LocalDate.parse("1970-01-01"), UUID.randomUUID(), List.of(), List.of()));
        assertThatThrownBy(() -> service.query(
                Instant.EPOCH, absentRoot, 1, "REPORTING", false))
                .isInstanceOfSatisfying(OrganizationGraphProblem.class,
                        problem -> assertThat(problem.code())
                                .isEqualTo(OrganizationGraphService.GRAPH_NOT_FOUND));
    }

    private WorkforceAccessPolicyService.Decision decision(boolean employment) {
        return new WorkforceAccessPolicyService.Decision(
                true, Set.of(), employment ? Set.of("DIRECTORY", "EMPLOYMENT")
                        : Set.of("DIRECTORY"), "READ");
    }

    private OrganizationChartDtos.OrganizationChart chart(
            LocalDate date,
            UUID root,
            List<OrganizationChartDtos.Organization> organizations,
            List<OrganizationChartDtos.Position> positions) {
        return new OrganizationChartDtos.OrganizationChart(
                date,
                new OrganizationChartDtos.Company(root, "ROOT", "Root", null),
                null,
                new OrganizationChartDtos.Metrics(
                        0, 0, 0, 0, organizations.size(), 0, 0, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, null),
                new OrganizationChartDtos.Analysis(
                        100, 100, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
                        new OrganizationChartDtos.DesignPolicy(1, 8, 6, 20, 15),
                        List.of()),
                organizations,
                List.of(),
                positions,
                List.of(),
                List.of());
    }

    private OrganizationChartDtos.Organization organization(
            UUID id,
            String name,
            UUID parent) {
        return new OrganizationChartDtos.Organization(
                id, name.toUpperCase(), name, name, parent == null ? "COMPANY" : "TEAM",
                parent == null ? "Company" : "Team", parent, null, null, null,
                0, 0, 0, 0, 0, null, List.of(), parent == null ? 1 : 2,
                0, 0, "HEALTHY", List.of());
    }

    private OrganizationChartDtos.Position position(
            UUID id,
            String key,
            UUID organization,
            UUID parent,
            List<UUID> incumbents) {
        return new OrganizationChartDtos.Position(
                id, key, "Title " + key, organization, parent,
                "OPEN", "REGULAR", "NORMAL", BigDecimal.ONE,
                null, null, null, null, null, incumbents, 0);
    }

    private UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
