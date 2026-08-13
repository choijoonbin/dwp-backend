package com.dwp.services.people.workforce;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkforceExportMaintenanceTest {

    @Test
    void capsTheExpiryBatchAndExpiresOnlyAfterArtifactCleanup() {
        WorkforceExportRepository repository = mock(WorkforceExportRepository.class);
        WorkforceExportArtifactWriter writer = mock(WorkforceExportArtifactWriter.class);
        WorkforceExportService service = mock(WorkforceExportService.class);
        WorkforceExportRepository.RequestRow row = completedRow();
        when(repository.dueArtifacts(500)).thenReturn(List.of(row));
        WorkforceExportMaintenance maintenance =
                new WorkforceExportMaintenance(repository, writer, service, 900);

        maintenance.expireArtifacts();

        verify(writer).discard(new WorkforceExportDtos.ArtifactEvidence(
                row.artifactReference(), row.artifactSha256(),
                row.artifactSizeBytes(), row.artifactExpiresAt()));
        verify(service).expireArtifact(row);
    }

    @Test
    void preservesDatabaseEvidenceWhenArtifactCleanupFails() {
        WorkforceExportRepository repository = mock(WorkforceExportRepository.class);
        WorkforceExportArtifactWriter writer = mock(WorkforceExportArtifactWriter.class);
        WorkforceExportService service = mock(WorkforceExportService.class);
        WorkforceExportRepository.RequestRow row = completedRow();
        when(repository.dueArtifacts(100)).thenReturn(List.of(row));
        doThrow(new IllegalStateException("storage unavailable"))
                .when(writer).discard(any());
        WorkforceExportMaintenance maintenance =
                new WorkforceExportMaintenance(repository, writer, service, 100);

        maintenance.expireArtifacts();

        verify(service, never()).expireArtifact(any());
    }

    private WorkforceExportRepository.RequestRow completedRow() {
        Instant now = Instant.now();
        return new WorkforceExportRepository.RequestRow(
                UUID.randomUUID(), 7L, 41L, "WORKFORCE_DIRECTORY", "{}", "TENANT",
                List.of(), List.of("DIRECTORY", "EMPLOYMENT"), "CSV",
                "WORKFORCE_MINIMUM", "request=watermarked", "governor@skax.com",
                "Quarterly workforce control evidence", "GRC-2026-Q3-1042", "COMPLETED",
                true, List.of(), "{}", "b".repeat(64), "staging://exports/request.csv",
                "c".repeat(64), 1024L, now.minusSeconds(60), 1, 1, 0, null, null,
                now.minusSeconds(120), 4L, now.minusSeconds(180), now.minusSeconds(120));
    }
}
