package com.dwp.services.people.organization;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationScenarioDecisionServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 17L;

    private final OrganizationScenarioRepository repository =
            mock(OrganizationScenarioRepository.class);
    private final OrganizationChartService chartService = mock(OrganizationChartService.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final OrganizationScenarioDecisionService service =
            new OrganizationScenarioDecisionService(
                    repository, chartService, new ObjectMapper(), audit);

    @BeforeEach
    void setContext() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, Set.of("HR_ADMIN"));
    }

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void validationPersistsWeightedDecisionEvidenceAndAudit() {
        UUID scenarioId = UUID.randomUUID();
        UUID validationRunId = UUID.randomUUID();
        LocalDate baselineDate = LocalDate.now();
        LocalDate effectiveDate = baselineDate.plusDays(30);
        UUID rootId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        OrganizationChartDtos.OrganizationChart baseline = chart(
                baselineDate, rootId, teamId);
        OrganizationScenarioRepository.ScenarioRecord scenario = scenario(
                scenarioId, baselineDate, effectiveDate,
                OrganizationBaselineFingerprint.compute(baseline), 3L);

        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(scenario));
        when(chartService.get(baselineDate, null, 12)).thenReturn(baseline);
        when(chartService.get(baselineDate, null, 12, scenarioId)).thenReturn(baseline);
        when(repository.changes(TENANT_ID, scenarioId)).thenReturn(List.of(
                moveChange(teamId, rootId, effectiveDate)));
        when(repository.approval(TENANT_ID, scenarioId)).thenReturn(Optional.empty());
        when(repository.recordValidation(
                eq(TENANT_ID), eq(scenario), eq("MANUAL"),
                any(OrganizationScenarioDtos.DecisionPack.class),
                anyString(), anyString(), anyString(), anyString(),
                eq(USER_ID), eq("corr-validation")))
                .thenReturn(validationRunId);

        OrganizationScenarioDtos.DecisionPack result = service.validate(
                scenarioId,
                new OrganizationScenarioDtos.ValidateScenarioRequest(3L),
                "corr-validation");

        assertThat(result.validationRunId()).isEqualTo(validationRunId);
        assertThat(result.decisionState()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.readinessScore()).isEqualTo(93);
        assertThat(result.checks()).hasSize(10);
        assertThat(result.checks()).anySatisfy(check -> {
            assertThat(check.checkCode()).isEqualTo("CHANGE_IMPACT");
            assertThat(check.outcome()).isEqualTo("PASS");
        });
        verify(repository).recordValidation(
                eq(TENANT_ID), eq(scenario), eq("MANUAL"),
                any(OrganizationScenarioDtos.DecisionPack.class),
                anyString(), anyString(), anyString(), anyString(),
                eq(USER_ID), eq("corr-validation"));
        verify(audit).record(any(AuditEvent.class));
    }

    @Test
    void baselineDriftProducesABlockingDecision() {
        UUID scenarioId = UUID.randomUUID();
        LocalDate baselineDate = LocalDate.now();
        LocalDate effectiveDate = baselineDate.plusDays(30);
        UUID rootId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        OrganizationChartDtos.OrganizationChart baseline = chart(
                baselineDate, rootId, teamId);
        OrganizationScenarioRepository.ScenarioRecord scenario = scenario(
                scenarioId, baselineDate, effectiveDate, "0".repeat(64), 1L);

        when(repository.scenario(TENANT_ID, scenarioId)).thenReturn(Optional.of(scenario));
        when(chartService.get(baselineDate, null, 12)).thenReturn(baseline);
        when(chartService.get(baselineDate, null, 12, scenarioId)).thenReturn(baseline);
        when(repository.changes(TENANT_ID, scenarioId)).thenReturn(List.of(
                moveChange(teamId, rootId, effectiveDate)));
        when(repository.approval(TENANT_ID, scenarioId)).thenReturn(Optional.empty());

        OrganizationScenarioDtos.DecisionPack result = service.preview(scenarioId);

        assertThat(result.decisionState()).isEqualTo("BLOCKED");
        assertThat(result.baselineCurrent()).isFalse();
        assertThat(result.blockingIssueCount()).isEqualTo(1);
        assertThat(result.readinessScore()).isEqualTo(63);
        assertThat(result.checks()).anySatisfy(check -> {
            assertThat(check.checkCode()).isEqualTo("BASELINE_CURRENT");
            assertThat(check.outcome()).isEqualTo("BLOCK");
            assertThat(check.severity()).isEqualTo("CRITICAL");
        });
    }

    private OrganizationScenarioRepository.ScenarioRecord scenario(
            UUID scenarioId,
            LocalDate baselineDate,
            LocalDate effectiveDate,
            String fingerprint,
            long version) {
        return new OrganizationScenarioRepository.ScenarioRecord(
                scenarioId, "future-workforce", "Future workforce",
                baselineDate, effectiveDate, fingerprint, "DRAFT", USER_ID, version);
    }

    private OrganizationScenarioDtos.Change moveChange(
            UUID organizationId,
            UUID parentId,
            LocalDate effectiveDate) {
        return new OrganizationScenarioDtos.Change(
                UUID.randomUUID(), 1, "MOVE_ORGANIZATION", 1,
                "ORGANIZATION", organizationId.toString(), parentId.toString(), effectiveDate,
                "{}", "{}", 0, 0, null, null, "VALID", null, 0);
    }

    private OrganizationChartDtos.OrganizationChart chart(
            LocalDate asOf,
            UUID rootId,
            UUID teamId) {
        return new OrganizationChartDtos.OrganizationChart(
                asOf,
                new OrganizationChartDtos.Company(rootId, "ROOT", "Company", null),
                null,
                new OrganizationChartDtos.Metrics(
                        0, 0, 0, 0, 2, 0, 0, 0,
                        BigDecimal.ZERO, BigDecimal.ZERO, "KRW"),
                new OrganizationChartDtos.Analysis(
                        100, 100, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0,
                        new OrganizationChartDtos.DesignPolicy(1, 8, 6, 20, 15),
                        List.of()),
                List.of(
                        organization(rootId, "ROOT", "Company", null),
                        organization(teamId, "TEAM", "Team", rootId)),
                List.of(),
                List.of(),
                List.of(new OrganizationChartDtos.Relationship(
                        teamId, rootId, "SUPERVISORY", true)),
                List.of());
    }

    private OrganizationChartDtos.Organization organization(
            UUID id,
            String key,
            String name,
            UUID parentId) {
        return new OrganizationChartDtos.Organization(
                id, key, name, name, parentId == null ? "COMPANY" : "DEPARTMENT",
                parentId == null ? "Company" : "Department",
                parentId, null, null, null, 0, 0, 0, 0, 0, null,
                List.of(), parentId == null ? 0 : 1, 0, 0, "HEALTHY", List.of());
    }
}
