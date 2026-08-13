package com.dwp.services.people.workforce;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkforceExportWorkerTest {

    private final WorkforceExportRepository repository = mock(WorkforceExportRepository.class);
    private final WorkforceExportArtifactWriter writer = mock(WorkforceExportArtifactWriter.class);
    private final WorkforceExportService service = mock(WorkforceExportService.class);
    private final WorkforceExportPolicy policy = new WorkforceExportPolicy(
            true, "WORKFORCE_MINIMUM", "request={{requestId}}", 24, 3, 1, "");
    private final WorkforceExportWorker worker = new WorkforceExportWorker(
            repository, writer, service, policy, 10, "worker-seoul-1");

    @Test
    void completesAClaimedRequestWithGovernedArtifactEvidence() {
        WorkforceExportRepository.RequestRow row = row("RUNNING", 1, 1);
        WorkforceExportDtos.ArtifactEvidence artifact = artifact(Instant.now().plusSeconds(3600));
        when(repository.claim(10, "worker-seoul-1")).thenReturn(List.of(row));
        when(repository.findForWorker(row.tenantId(), row.requestId()))
                .thenReturn(Optional.of(row));
        when(writer.write(row)).thenReturn(artifact);

        worker.processPending();

        verify(service).completeWorkerAttempt(row, artifact, "worker-seoul-1");
        verify(writer, never()).discard(any());
        verify(service, never()).failWorkerAttempt(any(), any(), any(), any(), any(), any());
    }

    @Test
    void schedulesAControlledRetryWithASecretFreeFailureMessage() {
        WorkforceExportRepository.RequestRow row = row("RUNNING", 1, 2);
        when(repository.claim(10, "worker-seoul-1")).thenReturn(List.of(row));
        when(repository.findForWorker(row.tenantId(), row.requestId()))
                .thenReturn(Optional.of(row));
        when(writer.write(row)).thenThrow(new IllegalArgumentException("secret=do-not-persist"));

        worker.processPending();

        verify(service).failWorkerAttempt(
                eq(row), eq("RETRY_WAIT"), any(Instant.class), eq("ARTIFACT_WRITE_FAILED"),
                eq("Artifact writer failed (IllegalArgumentException). Review secured worker logs."),
                eq("worker-seoul-1"));
    }

    @Test
    void discardsAStagedArtifactWhenCancellationWinsThePublicationRace() {
        WorkforceExportRepository.RequestRow running = row("RUNNING", 1, 1);
        WorkforceExportRepository.RequestRow cancelling = copyWithState(running, "CANCEL_REQUESTED");
        WorkforceExportDtos.ArtifactEvidence artifact = artifact(Instant.now().plusSeconds(3600));
        when(repository.claim(10, "worker-seoul-1")).thenReturn(List.of(running));
        when(repository.findForWorker(running.tenantId(), running.requestId()))
                .thenReturn(Optional.of(running))
                .thenReturn(Optional.of(cancelling));
        when(writer.write(running)).thenReturn(artifact);

        worker.processPending();

        verify(writer).discard(artifact);
        verify(service).failWorkerAttempt(
                cancelling, "CANCELLED", null, "CANCELLED_BY_USER",
                "Cancellation was requested before artifact publication.", "worker-seoul-1");
        verify(service, never()).completeWorkerAttempt(any(), any(), any());
    }

    @Test
    void discardsAnArtifactOutsideTheApprovedRetentionWindow() {
        WorkforceExportRepository.RequestRow row = row("RUNNING", 1, 1);
        WorkforceExportDtos.ArtifactEvidence artifact = artifact(Instant.now().plusSeconds(48 * 3600));
        when(repository.claim(10, "worker-seoul-1")).thenReturn(List.of(row));
        when(repository.findForWorker(row.tenantId(), row.requestId()))
                .thenReturn(Optional.of(row));
        when(writer.write(row)).thenReturn(artifact);

        worker.processPending();

        verify(writer).discard(artifact);
        verify(service).failWorkerAttempt(
                eq(row), eq("RETRY_WAIT"), any(Instant.class), eq("ARTIFACT_WRITE_FAILED"),
                eq("Artifact writer failed (IllegalStateException). Review secured worker logs."),
                eq("worker-seoul-1"));
        verify(service, never()).completeWorkerAttempt(any(), any(), any());
    }

    @Test
    void honoursCancellationBeforeCreatingAnArtifact() {
        WorkforceExportRepository.RequestRow claimed = row("RUNNING", 1, 1);
        WorkforceExportRepository.RequestRow cancelling = copyWithState(claimed, "CANCEL_REQUESTED");
        when(repository.claim(10, "worker-seoul-1")).thenReturn(List.of(claimed));
        when(repository.findForWorker(claimed.tenantId(), claimed.requestId()))
                .thenReturn(Optional.of(cancelling));

        worker.processPending();

        verify(writer, never()).write(any());
        verify(service).failWorkerAttempt(
                eq(cancelling), eq("CANCELLED"), isNull(), eq("CANCELLED_BY_USER"),
                eq("Cancellation was requested before artifact publication."),
                eq("worker-seoul-1"));
    }

    private WorkforceExportDtos.ArtifactEvidence artifact(Instant expiresAt) {
        return new WorkforceExportDtos.ArtifactEvidence(
                "staging://exports/request.csv", "a".repeat(64), 4096L, expiresAt);
    }

    private WorkforceExportRepository.RequestRow row(
            String state,
            int retryCycleAttemptCount,
            int attemptCount) {
        Instant now = Instant.now();
        return new WorkforceExportRepository.RequestRow(
                UUID.randomUUID(), 7L, 41L, "WORKFORCE_DIRECTORY", "{}", "TENANT",
                List.of(), List.of("DIRECTORY", "EMPLOYMENT"), "CSV",
                "WORKFORCE_MINIMUM", "request=watermarked", "governor@skax.com",
                "Quarterly workforce control evidence", "GRC-2026-Q3-1042", state,
                true, List.of(), "{}", "b".repeat(64), null, null, null, null,
                attemptCount, retryCycleAttemptCount, 0, null, null, null, 4L, now, now);
    }

    private WorkforceExportRepository.RequestRow copyWithState(
            WorkforceExportRepository.RequestRow row,
            String state) {
        return new WorkforceExportRepository.RequestRow(
                row.requestId(), row.tenantId(), row.requestedBy(), row.datasetKey(),
                row.selection(), row.populationType(), row.organizationIds(), row.fieldGroups(),
                row.exportFormat(), row.maskingProfile(), row.watermarkText(),
                row.recipientReference(), row.purpose(), row.sourceReference(), state,
                row.executionEnabled(), row.blockers(), row.policySnapshot(), row.requestSha256(),
                row.artifactReference(), row.artifactSha256(), row.artifactSizeBytes(),
                row.artifactExpiresAt(), row.attemptCount(), row.retryCycleAttemptCount(),
                row.manualRetryCount(), row.nextAttemptAt(), row.cancellationRequestedAt(),
                row.completedAt(), row.version(), row.createdAt(), row.updatedAt());
    }
}
