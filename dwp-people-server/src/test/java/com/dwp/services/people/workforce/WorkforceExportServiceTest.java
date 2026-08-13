package com.dwp.services.people.workforce;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class WorkforceExportServiceTest {

    private final WorkforceAccessPolicyService access = mock(WorkforceAccessPolicyService.class);
    private final WorkforceExportRepository repository = mock(WorkforceExportRepository.class);
    private final WorkforceExportPolicy policy = new WorkforceExportPolicy(
            false, "WORKFORCE_MINIMUM", "request={{requestId}}", 24, 5, 1, "D-09,D-12");
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final WorkforceExportService service = new WorkforceExportService(
            access, repository, policy, audit, new ObjectMapper().findAndRegisterModules());

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
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
        when(repository.list(7L, 41L, false)).thenReturn(List.of());

        service.list();

        verify(repository).list(7L, 41L, false);
        verify(repository, never()).list(7L, 41L, true);
    }

    @Test
    void permitsExplicitWorkforceGovernorsToReviewAllTenantRequests() {
        PeopleRequestContext.set(41L, 7L, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE", "ADMIN.WORKFORCE_ACCESS:MANAGE"));
        when(repository.list(7L, 41L, true)).thenReturn(List.of());

        service.list();

        verify(repository).list(7L, 41L, true);
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
