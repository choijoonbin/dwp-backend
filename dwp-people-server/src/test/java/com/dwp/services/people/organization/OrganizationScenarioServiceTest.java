package com.dwp.services.people.organization;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrganizationScenarioServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 7L;

    private final OrganizationScenarioRepository repository =
            mock(OrganizationScenarioRepository.class);
    private final OrganizationChartService chartService = mock(OrganizationChartService.class);
    private final OrganizationScenarioDecisionService decisionService =
            mock(OrganizationScenarioDecisionService.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final OrganizationScenarioService service =
            new OrganizationScenarioService(repository, chartService, decisionService, audit);

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, Set.of("HR_ADMIN"));
    }

    @AfterEach
    void clearContext() {
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
        PeopleRequestContext.clear();
    }

    @Test
    void exactAppConfigAuthorityDoesNotRequireGlobalHrAdminRole() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, Set.of(),
                Set.of("ACTION.WORKFORCE_ORG_DESIGN:VIEW"));
        String route = "route.hcm.management.org-scenarios.page";
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set",
                new HcmPepContext.Evidence(
                        new HcmV3PepRegistry.RouteAuthority(
                                route, "PAGE", "full-management", true,
                                Set.of("predicate.hcm-configuration-scope.v1"),
                                Set.of("RESOURCE_SET"), route + ".binding.01",
                                "hcm.org-design.view", null, "GET",
                                "/api/people/v1/workforce/organization/scenarios", null),
                        "psr-" + "a".repeat(64),
                        OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                        "hcm.management", "scope-config", "110"));
        when(repository.scenarios(TENANT_ID)).thenReturn(List.of());

        assertThat(service.scenarios()).isEmpty();
        verify(repository).scenarios(TENANT_ID);
    }

    @Test
    void requesterCannotApproveTheirOwnOrganizationScenario() {
        UUID scenarioId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        when(repository.approval(TENANT_ID, scenarioId)).thenReturn(Optional.of(
                new OrganizationScenarioRepository.ApprovalRecord(
                        approvalId,
                        scenarioId,
                        "HR_ADMIN",
                        true,
                        "PENDING",
                        USER_ID,
                        Instant.now().plusSeconds(3600),
                        0L)));

        OrganizationScenarioDtos.DecideScenarioRequest request =
                new OrganizationScenarioDtos.DecideScenarioRequest(
                        "APPROVED", "Validated the future organization design.", 0L);

        assertThatThrownBy(() -> service.decide(scenarioId, request, "corr-sod"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Separation of duties");
        verifyNoInteractions(audit);
    }

    @Test
    void organizationMoveCannotCreateASupervisoryCycle() {
        UUID rootId = UUID.randomUUID();
        UUID divisionId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now().plusDays(30);
        OrganizationChartDtos.OrganizationChart chart = chart(
                effectiveDate,
                rootId,
                List.of(
                        organization(rootId, "Company", null),
                        organization(divisionId, "Division", rootId),
                        organization(teamId, "Team", divisionId)),
                List.of(
                        relationship(divisionId, rootId),
                        relationship(teamId, divisionId)));
        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(
                scenario(scenarioId, effectiveDate, "DRAFT", fingerprintPlaceholder(), 0L)));
        when(repository.moves(TENANT_ID, scenarioId)).thenReturn(List.of());
        when(chartService.get(effectiveDate, null, 10)).thenReturn(chart);

        OrganizationScenarioDtos.AddOrganizationMoveRequest request =
                new OrganizationScenarioDtos.AddOrganizationMoveRequest(
                        divisionId, teamId, 0L);

        assertThatThrownBy(() -> service.addMove(scenarioId, request, "corr-cycle"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("supervisory cycle");
        verifyNoInteractions(audit);
    }

    @Test
    void positionPlanRequiresCostAndCurrencyTogether() {
        UUID scenarioId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now().plusDays(30);
        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(
                scenario(scenarioId, effectiveDate, "DRAFT", fingerprintPlaceholder(), 0L)));

        OrganizationScenarioDtos.CreatePositionRequest request =
                new OrganizationScenarioDtos.CreatePositionRequest(
                        "PLAN-AI-001", "AI Platform Engineer", UUID.randomUUID(),
                        UUID.randomUUID(), "REGULAR", "HIGH", BigDecimal.ONE,
                        new BigDecimal("140000000"), null, effectiveDate, 0L);

        assertThatThrownBy(() -> service.createPosition(scenarioId, request, "corr-cost"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cost and currency");
        verifyNoInteractions(audit);
    }

    @Test
    void cloneCreatesAnIndependentDraftWithSourceLineage() {
        UUID sourceScenarioId = UUID.randomUUID();
        UUID clonedScenarioId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now().plusDays(45);
        OrganizationScenarioRepository.ScenarioRecord source = scenario(
                sourceScenarioId, effectiveDate, "IN_REVIEW", fingerprintPlaceholder(), 3L);
        OrganizationScenarioDtos.CloneScenarioRequest request =
                new OrganizationScenarioDtos.CloneScenarioRequest(
                        "org-design-alternative", "Organization design alternative",
                        "Lower-cost operating model.", effectiveDate.plusDays(15));
        OrganizationScenarioDtos.Scenario summary = mock(OrganizationScenarioDtos.Scenario.class);

        when(repository.scenario(TENANT_ID, sourceScenarioId)).thenReturn(Optional.of(source));
        when(repository.changes(TENANT_ID, sourceScenarioId)).thenReturn(List.of());
        when(repository.cloneScenario(TENANT_ID, source, request, USER_ID))
                .thenReturn(clonedScenarioId);
        when(summary.scenarioId()).thenReturn(clonedScenarioId);
        when(repository.scenarios(TENANT_ID)).thenReturn(List.of(summary));

        service.cloneScenario(sourceScenarioId, request, "corr-clone");

        verify(repository).cloneScenario(TENANT_ID, source, request, USER_ID);
    }

    @Test
    void positionScheduledForClosureCannotBeMoved() {
        UUID scenarioId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now().plusDays(30);
        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(
                scenario(scenarioId, effectiveDate, "DRAFT", fingerprintPlaceholder(), 0L)));
        when(repository.positionMoves(TENANT_ID, scenarioId)).thenReturn(List.of());
        when(repository.positionCloses(TENANT_ID, scenarioId)).thenReturn(List.of(
                new OrganizationScenarioRepository.PositionCloseRecord(UUID.randomUUID(), positionId)));

        OrganizationScenarioDtos.AddPositionMoveRequest request =
                new OrganizationScenarioDtos.AddPositionMoveRequest(positionId, parentId, 0L);

        assertThatThrownBy(() -> service.addPositionMove(scenarioId, request, "corr-conflict"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("scheduled for closure");
        verifyNoInteractions(audit);
    }

    @Test
    void positionWithIncumbentCannotBeClosed() {
        UUID scenarioId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now().plusDays(30);
        OrganizationScenarioRepository.ScenarioRecord scenario = scenario(
                scenarioId, effectiveDate, "DRAFT", fingerprintPlaceholder(), 0L);
        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(scenario));
        when(repository.positionMoves(TENANT_ID, scenarioId)).thenReturn(List.of());
        when(repository.positionCloses(TENANT_ID, scenarioId)).thenReturn(List.of());
        when(chartService.get(effectiveDate, null, 12, scenarioId)).thenReturn(chart(
                effectiveDate, rootId,
                List.of(organization(rootId, "Company", null)), List.of(),
                List.of(new OrganizationChartDtos.Position(
                        positionId, "POS-001", "Platform Engineer", rootId, null,
                        "FILLED", "REGULAR", "HIGH", BigDecimal.ONE,
                        new BigDecimal("140000000"), "KRW", null, null,
                        effectiveDate, List.of(UUID.randomUUID()), 0))));

        assertThatThrownBy(() -> service.closePosition(
                scenarioId, positionId,
                new OrganizationScenarioDtos.ClosePositionRequest(0L), "corr-close"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("incumbent");
        verifyNoInteractions(audit);
    }

    @Test
    void approvedScenarioCannotPublishAfterItsBaselineDrifts() {
        UUID rootId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        LocalDate baselineDate = LocalDate.now();
        LocalDate effectiveDate = baselineDate.plusDays(30);
        OrganizationScenarioRepository.ScenarioRecord scenario = new OrganizationScenarioRepository.ScenarioRecord(
                scenarioId,
                "org-future-state",
                "Future state",
                baselineDate,
                effectiveDate,
                fingerprintPlaceholder(),
                "APPROVED",
                USER_ID + 1,
                4L);
        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(scenario));
        OrganizationScenarioDtos.DecisionPack decision =
                mock(OrganizationScenarioDtos.DecisionPack.class);
        when(decisionService.validateForWorkflow(scenarioId, "PUBLISH", "corr-drift"))
                .thenReturn(decision);
        when(decision.blockingIssueCount()).thenReturn(0);
        when(decision.validationRunId()).thenReturn(UUID.randomUUID());
        when(chartService.get(baselineDate, null, 10)).thenReturn(chart(
                baselineDate,
                rootId,
                List.of(
                        organization(rootId, "Company", null),
                        organization(teamId, "Team", rootId)),
                List.of(relationship(teamId, rootId))));

        assertThatThrownBy(() -> service.publish(
                scenarioId,
                new OrganizationScenarioDtos.PublishScenarioRequest(4L),
                "corr-drift"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Rebase");
        verifyNoInteractions(audit);
    }

    @Test
    void scenarioMakerCannotPublishTheirOwnApprovedScenario() {
        UUID scenarioId = UUID.randomUUID();
        OrganizationScenarioRepository.ScenarioRecord scenario =
                new OrganizationScenarioRepository.ScenarioRecord(
                        scenarioId, "maker-owned", "Maker owned",
                        LocalDate.now(), LocalDate.now().plusDays(30),
                        fingerprintPlaceholder(), "APPROVED", USER_ID, 6L);
        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(scenario));

        assertThatThrownBy(() -> service.publish(
                scenarioId, new OrganizationScenarioDtos.PublishScenarioRequest(6L),
                "corr-maker-publish"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(com.dwp.core.common.ErrorCode.SOD_CONFLICT));

        verify(decisionService, never()).validateForWorkflow(
                scenarioId, "PUBLISH", "corr-maker-publish");
        verify(repository, never()).publish(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(audit);
    }

    @Test
    void submissionBindsTheApprovalRequestToValidationEvidence() {
        UUID scenarioId = UUID.randomUUID();
        UUID validationRunId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now().plusDays(30);
        OrganizationScenarioRepository.ScenarioRecord scenario = scenario(
                scenarioId, effectiveDate, "DRAFT", fingerprintPlaceholder(), 2L);
        OrganizationScenarioDtos.Change change = new OrganizationScenarioDtos.Change(
                UUID.randomUUID(), 1, "MOVE_ORGANIZATION", 1,
                "ORGANIZATION", teamId.toString(), rootId.toString(), effectiveDate,
                "{}", "{}", 0, 0, null, null, "VALID", null, 0);
        OrganizationChartDtos.OrganizationChart effectiveChart = chart(
                effectiveDate,
                rootId,
                List.of(
                        organization(rootId, "Company", null),
                        organization(teamId, "Team", rootId)),
                List.of(relationship(teamId, rootId)));
        OrganizationScenarioDtos.DecisionPack decision =
                mock(OrganizationScenarioDtos.DecisionPack.class);
        OrganizationScenarioDtos.Scenario summary = mock(OrganizationScenarioDtos.Scenario.class);

        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(scenario));
        when(repository.changes(TENANT_ID, scenarioId)).thenReturn(List.of(change));
        when(repository.moves(TENANT_ID, scenarioId)).thenReturn(List.of());
        when(repository.positionMoves(TENANT_ID, scenarioId)).thenReturn(List.of());
        when(chartService.get(effectiveDate, null, 10)).thenReturn(effectiveChart);
        when(chartService.get(effectiveDate, null, 12, scenarioId)).thenReturn(effectiveChart);
        when(decisionService.validateForWorkflow(scenarioId, "SUBMIT", "corr-submit"))
                .thenReturn(decision);
        when(decision.blockingIssueCount()).thenReturn(0);
        when(decision.validationRunId()).thenReturn(validationRunId);
        when(repository.submit(
                TENANT_ID, scenario, "Independent review required.",
                validationRunId, USER_ID, 2L)).thenReturn(true);
        when(summary.scenarioId()).thenReturn(scenarioId);
        when(repository.scenarios(TENANT_ID)).thenReturn(List.of(summary));

        service.submit(
                scenarioId,
                new OrganizationScenarioDtos.SubmitScenarioRequest(
                        "Independent review required.", 2L),
                "corr-submit");

        verify(repository).submit(
                TENANT_ID, scenario, "Independent review required.",
                validationRunId, USER_ID, 2L);
    }

    @Test
    void cancellationClosesAnInReviewScenarioWithItsPendingApproval() {
        UUID scenarioId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now().plusDays(30);
        OrganizationScenarioRepository.ScenarioRecord scenario = scenario(
                scenarioId, effectiveDate, "IN_REVIEW", fingerprintPlaceholder(), 3L);
        OrganizationScenarioDtos.Scenario summary = mock(OrganizationScenarioDtos.Scenario.class);

        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(scenario));
        when(repository.cancel(
                TENANT_ID, scenarioId, USER_ID, "Business priority changed.", 3L))
                .thenReturn(true);
        when(summary.scenarioId()).thenReturn(scenarioId);
        when(repository.scenarios(TENANT_ID)).thenReturn(List.of(summary));

        service.cancel(
                scenarioId,
                new OrganizationScenarioDtos.CancelScenarioRequest(
                        "Business priority changed.", 3L),
                "corr-cancel");

        verify(repository).cancel(
                TENANT_ID, scenarioId, USER_ID, "Business priority changed.", 3L);
    }

    @Test
    void cancellationRejectsAStaleScenarioVersion() {
        UUID scenarioId = UUID.randomUUID();
        LocalDate effectiveDate = LocalDate.now().plusDays(30);
        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(
                scenario(scenarioId, effectiveDate, "DRAFT", fingerprintPlaceholder(), 4L)));

        assertThatThrownBy(() -> service.cancel(
                scenarioId,
                new OrganizationScenarioDtos.CancelScenarioRequest("No longer needed.", 3L),
                "corr-stale"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("changed");

        verifyNoInteractions(audit);
    }

    private OrganizationScenarioRepository.ScenarioRecord scenario(
            UUID scenarioId,
            LocalDate effectiveDate,
            String state,
            String fingerprint,
            long version) {
        return new OrganizationScenarioRepository.ScenarioRecord(
                scenarioId,
                "org-design-test",
                "Organization design test",
                LocalDate.now(),
                effectiveDate,
                fingerprint,
                state,
                USER_ID,
                version);
    }

    private OrganizationChartDtos.Organization organization(
            UUID id,
            String name,
            UUID parentId) {
        return new OrganizationChartDtos.Organization(
                id,
                name.toUpperCase(),
                name,
                name,
                parentId == null ? "COMPANY" : "DEPARTMENT",
                parentId == null ? "Company" : "Department",
                parentId,
                null,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                null,
                List.of(),
                parentId == null ? 0 : 1,
                0,
                0,
                "HEALTHY",
                List.of());
    }

    private OrganizationChartDtos.Relationship relationship(UUID childId, UUID parentId) {
        return new OrganizationChartDtos.Relationship(
                childId, parentId, "SUPERVISORY", true);
    }

    private OrganizationChartDtos.OrganizationChart chart(
            LocalDate asOf,
            UUID rootId,
            List<OrganizationChartDtos.Organization> organizations,
            List<OrganizationChartDtos.Relationship> relationships) {
        return chart(asOf, rootId, organizations, relationships, List.of());
    }

    private OrganizationChartDtos.OrganizationChart chart(
            LocalDate asOf,
            UUID rootId,
            List<OrganizationChartDtos.Organization> organizations,
            List<OrganizationChartDtos.Relationship> relationships,
            List<OrganizationChartDtos.Position> positions) {
        return new OrganizationChartDtos.OrganizationChart(
                asOf,
                new OrganizationChartDtos.Company(rootId, "ROOT", "Company", null),
                null,
                new OrganizationChartDtos.Metrics(
                        0, 0, 0, 0, organizations.size(), 0, 0, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, "KRW"),
                new OrganizationChartDtos.Analysis(
                        100, 100, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
                        new OrganizationChartDtos.DesignPolicy(1, 8, 6, 20, 15),
                        List.of()),
                organizations,
                List.of(),
                positions,
                relationships,
                List.of());
    }

    private String fingerprintPlaceholder() {
        return "0".repeat(64);
    }
}
