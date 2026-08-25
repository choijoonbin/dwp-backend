package com.dwp.services.people.workforce;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;
import java.time.OffsetDateTime;

import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class WorkforceExportServiceTest {

    private final WorkforceAccessPolicyService access = mock(WorkforceAccessPolicyService.class);
    private final WorkforceExportRepository repository = mock(WorkforceExportRepository.class);
    private final WorkforceExportPolicy policy = new WorkforceExportPolicy(
            false, "WORKFORCE_MINIMUM", "request={{requestId}}", 24, 5, 1, "D-09,D-12");
    private final HcmPopulationScopeService populationScopes =
            mock(HcmPopulationScopeService.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final WorkforceExportService service = new WorkforceExportService(
            access, repository, policy, populationScopes, audit,
            new ObjectMapper().findAndRegisterModules());

    @AfterEach
    void clearContext() {
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
        PeopleRequestContext.clear();
    }

    @Test
    void bindsTheDatabaseDatasetVersionScopeAndFullCreateCommandInStepUpEnvelope() {
        HcmHighRiskCommandGuard highRisk = mock(HcmHighRiskCommandGuard.class);
        WorkforceExportService exactService = new WorkforceExportService(
                access, repository, policy, populationScopes, audit,
                new ObjectMapper().findAndRegisterModules(), highRisk);
        PeopleRequestContext.set(41L, 7L, Set.of(), Set.of());
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set",
                new HcmPepContext.Evidence(
                        null, "psr-" + "a".repeat(64),
                        OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                        "hcm.management", "hcm-scope-1234", "110"));
        when(access.requireForMutation("EXPORT")).thenReturn(
                new WorkforceAccessPolicyService.Decision(
                        true, Set.of(), Set.of("DIRECTORY", "EMPLOYMENT"), "EXPORT"));
        when(populationScopes.requireOperationsForMutation("EXPORT")).thenReturn(
                new HcmPopulationScopeService.ResolvedPopulation(null, null, null));
        when(repository.datasetForShare("WORKFORCE_DIRECTORY")).thenReturn(java.util.Optional.of(
                new WorkforceExportRepository.DatasetRow(
                        "WORKFORCE_DIRECTORY", "Workforce directory", "Directory",
                        List.of("DIRECTORY", "EMPLOYMENT"),
                        List.of("status"), "ACTIVE", 3L)));
        WorkforceExportDtos.CreateRequest command = new WorkforceExportDtos.CreateRequest(
                "idem-1", "WORKFORCE_DIRECTORY", Map.of("status", "ACTIVE"), "CSV",
                "governor@example.test", "Quarterly controlled workforce evidence",
                "GRC-2026-Q3-1042");
        HcmStepUpHeaders headers = new HcmStepUpHeaders(
                "signed", "idem-1", "psr-" + "a".repeat(64), 3L);
        doThrow(new BaseException(ErrorCode.STEP_UP_REQUIRED))
                .when(highRisk).require(any(), any(), any(), eq(3L), any(), any(), eq(headers));

        assertThatThrownBy(() -> exactService.create(command, "correlation-1", headers))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.STEP_UP_REQUIRED));

        ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
        verify(highRisk).require(
                eq("hcm.controlled-export.create"), eq("EXPORT_DATASET"),
                eq("WORKFORCE_DIRECTORY@v3:hcm-scope-1234"), eq(3L),
                eq("/api/people/v1/workforce/exports"), envelope.capture(), eq(headers));
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(envelope.getValue());
        assertThat(json.path("dataset").asText()).isEqualTo("WORKFORCE_DIRECTORY@v3");
        assertThat(json.path("population").asText()).isEqualTo("hcm-scope-1234");
        assertThat(json.path("command").path("idempotencyKey").asText()).isEqualTo("idem-1");
        assertThat(json.path("command").path("datasetKey").asText())
                .isEqualTo("WORKFORCE_DIRECTORY");
        assertThat(json.path("command").path("recipientReference").asText())
                .isEqualTo("governor@example.test");
        assertThat(json.path("command").path("purpose").asText())
                .isEqualTo("Quarterly controlled workforce evidence");
        verify(repository).datasetForShare("WORKFORCE_DIRECTORY");
    }

    @Test
    void previewsTheExactDatasetBoundaryWithoutEnablingExecution() {
        PeopleRequestContext.set(41L, 7L, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        when(access.require("EXPORT")).thenReturn(new WorkforceAccessPolicyService.Decision(
                true, Set.of(), Set.of("DIRECTORY", "EMPLOYMENT"), "EXPORT"));
        when(repository.dataset("WORKFORCE_DIRECTORY")).thenReturn(java.util.Optional.of(
                new WorkforceExportRepository.DatasetRow(
                        "WORKFORCE_DIRECTORY", "Workforce directory", "Directory",
                        List.of("DIRECTORY", "EMPLOYMENT"),
                        List.of("status", "asOf"), "ACTIVE", 3L)));

        WorkforceExportDtos.Preview preview = service.preview(
                new WorkforceExportDtos.PreviewRequest(
                        "WORKFORCE_DIRECTORY", Map.of("status", "ACTIVE")));

        assertThat(preview.datasetKey()).isEqualTo("WORKFORCE_DIRECTORY");
        assertThat(preview.executionEnabled()).isFalse();
        assertThat(preview.blockers()).containsExactly("D-09", "D-12");
        verify(audit).record(any());
    }

    @Test
    void rejectsUnsupportedSelectionAndMissingRequiredFieldGroups() {
        PeopleRequestContext.set(41L, 7L, Set.of("HR_ADMIN"));
        when(access.require("EXPORT")).thenReturn(new WorkforceAccessPolicyService.Decision(
                true, Set.of(), Set.of("DIRECTORY"), "EXPORT"));
        when(repository.dataset("WORKFORCE_DIRECTORY")).thenReturn(java.util.Optional.of(
                new WorkforceExportRepository.DatasetRow(
                        "WORKFORCE_DIRECTORY", "Workforce directory", "Directory",
                        List.of("DIRECTORY", "EMPLOYMENT"),
                        List.of("status"), "ACTIVE", 1L)));

        assertThatThrownBy(() -> service.preview(new WorkforceExportDtos.PreviewRequest(
                "WORKFORCE_DIRECTORY", Map.of("unknown", "value"))))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void expiresArtifactEvidenceAndAuditInOneServiceBoundary() {
        WorkforceExportRepository.RequestRow expired = completedRow("COMPLETED");
        when(repository.expireArtifact(expired)).thenReturn(true);

        assertThat(service.expireArtifact(expired)).isTrue();

        verify(repository).expireArtifact(expired);
        verify(audit).record(any());
    }

    @Test
    void skipsExpiryAuditWhenAnotherWorkerAlreadyFinalizedTheArtifact() {
        WorkforceExportRepository.RequestRow expired = completedRow("COMPLETED");
        when(repository.expireArtifact(expired)).thenReturn(false);

        assertThat(service.expireArtifact(expired)).isFalse();

        verify(audit, never()).record(any());
    }

    @Test
    void completesStateAndAuditThroughTheTransactionalWorkerBoundary() {
        WorkforceExportRepository.RequestRow running = completedRow("RUNNING");
        WorkforceExportDtos.ArtifactEvidence artifact = new WorkforceExportDtos.ArtifactEvidence(
                "staging://exports/request.csv", "c".repeat(64), 1024L,
                Instant.now().plusSeconds(3600));

        service.completeWorkerAttempt(running, artifact, "worker-seoul-1");

        verify(repository).complete(running, artifact, "worker-seoul-1");
        verify(audit).record(any());
    }

    @Test
    void doesNotTreatAnAdministratorRoleAsGovernanceWhenExplicitPermissionsExist() {
        PeopleRequestContext.set(41L, 7L, Set.of("ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = tenantPopulation();
        when(populationScopes.requireOperations("EXPORT")).thenReturn(population);
        when(repository.listWithinPopulation(
                7L, 41L, false, true, Set.of(), population.scope().fieldGroups()))
                .thenReturn(List.of());

        service.list();

        verify(repository).listWithinPopulation(
                7L, 41L, false, true, Set.of(), population.scope().fieldGroups());
    }

    @Test
    void permitsExplicitWorkforceGovernorsToReviewAllTenantRequests() {
        PeopleRequestContext.set(41L, 7L, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE", "ADMIN.WORKFORCE_ACCESS:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = tenantPopulation();
        when(populationScopes.requireOperations("EXPORT")).thenReturn(population);
        when(repository.listWithinPopulation(
                7L, 41L, true, true, Set.of(), population.scope().fieldGroups()))
                .thenReturn(List.of());

        service.list();

        verify(repository).listWithinPopulation(
                7L, 41L, true, true, Set.of(), population.scope().fieldGroups());
    }

    @Test
    void organizationScopedGovernorCannotCancelATenantWideExport() {
        PeopleRequestContext.set(41L, 7L, Set.of("HR_ADMIN"),
                Set.of("ADMIN.WORKFORCE_ACCESS:MANAGE"));
        UUID organizationId = UUID.randomUUID();
        HcmPopulationScopeService.ResolvedPopulation population = new
                HcmPopulationScopeService.ResolvedPopulation(
                null,
                new HcmPopulationRepository.PopulationScope(
                        0L, null, false, Set.of(organizationId),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "policy"),
                new HcmPopulationRepository.PopulationEvidence(2L, "population"));
        when(populationScopes.requireOperationsForMutation("EXPORT")).thenReturn(population);
        WorkforceExportRepository.RequestRow tenantExport = completedRow("QUEUED");
        when(repository.findForUpdate(7L, 41L, tenantExport.requestId(), true))
                .thenReturn(java.util.Optional.of(tenantExport));

        assertThatThrownBy(() -> service.cancel(
                tenantExport.requestId(),
                new WorkforceExportDtos.DecisionRequest(
                        tenantExport.version(), "Cancel outside governed population"),
                "correlation-1"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).cancel(
                anyLong(), anyLong(), any(), anyLong(), any(), anyBoolean());
    }

    private HcmPopulationScopeService.ResolvedPopulation tenantPopulation() {
        return new HcmPopulationScopeService.ResolvedPopulation(
                null,
                new HcmPopulationRepository.PopulationScope(
                        0L, null, true, Set.of(),
                        Set.of("DIRECTORY", "EMPLOYMENT"), "policy"),
                new HcmPopulationRepository.PopulationEvidence(2L, "population"));
    }

    private WorkforceExportRepository.RequestRow completedRow(String state) {
        Instant now = Instant.now();
        return new WorkforceExportRepository.RequestRow(
                UUID.randomUUID(), 7L, 41L, "WORKFORCE_DIRECTORY", "{}", "TENANT",
                List.of(), List.of("DIRECTORY", "EMPLOYMENT"), "CSV",
                "WORKFORCE_MINIMUM", "request=watermarked", "governor@skax.com",
                "Quarterly workforce control evidence", "GRC-2026-Q3-1042", state,
                true, List.of(), "{}", "b".repeat(64), null, "c".repeat(64), 1024L,
                now.plusSeconds(3600), 1, 1, 0, null, null, now, 4L, now, now);
    }
}
