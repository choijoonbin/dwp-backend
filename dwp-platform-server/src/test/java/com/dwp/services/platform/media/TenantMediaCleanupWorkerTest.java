package com.dwp.services.platform.media;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantMediaCleanupWorkerTest {

    @Test
    void completesClaimedCleanupAfterStorageDeletion() {
        TenantMediaCleanupOutbox outbox = mock(TenantMediaCleanupOutbox.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        TenantMediaCleanupOutbox.CleanupJob job = job(1);
        when(outbox.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(outbox.beginDelete(eq(job), anyString())).thenReturn(true);
        TenantMediaCleanupWorker worker = worker(outbox, storage, true);

        worker.cleanPending();

        verify(outbox).releaseExpiredLeases();
        verify(storage).delete(job.tenantId(), job.storageKey());
        verify(outbox).completeDelete(eq(job), anyString());
    }

    @Test
    void completesWithoutDeletingWhenTheAssetWasReferencedAgain() {
        TenantMediaCleanupOutbox outbox = mock(TenantMediaCleanupOutbox.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        TenantMediaCleanupOutbox.CleanupJob job = job(1);
        when(outbox.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(outbox.beginDelete(eq(job), anyString())).thenReturn(false);
        TenantMediaCleanupWorker worker = worker(outbox, storage, true);

        worker.cleanPending();

        verify(storage, never()).delete(any(), anyString());
        verify(outbox).complete(eq(job), anyString());
        verify(outbox, never()).completeDelete(any(), anyString());
    }

    @Test
    void retriesAStorageFailureWithoutEscapingThePollingLoop() {
        TenantMediaCleanupOutbox outbox = mock(TenantMediaCleanupOutbox.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        TenantMediaCleanupOutbox.CleanupJob job = job(2);
        when(outbox.claim(anyString(), eq(10), eq(30))).thenReturn(List.of(job));
        when(outbox.beginDelete(eq(job), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("offline"))
                .when(storage).delete(job.tenantId(), job.storageKey());
        TenantMediaCleanupWorker worker = worker(outbox, storage, true);

        worker.cleanPending();

        verify(outbox).fail(
                eq(job), anyString(), eq(8), eq("ILLEGALSTATEEXCEPTION"), any());
        verify(outbox, never()).complete(any(), anyString());
    }

    @Test
    void disabledWorkerDoesNotTouchTheQueue() {
        TenantMediaCleanupOutbox outbox = mock(TenantMediaCleanupOutbox.class);
        TenantMediaCleanupWorker worker = worker(outbox, mock(TenantMediaStorage.class), false);

        worker.cleanPending();

        verify(outbox, never()).releaseExpiredLeases();
    }

    private TenantMediaCleanupWorker worker(
            TenantMediaCleanupOutbox outbox, TenantMediaStorage storage, boolean enabled) {
        return new TenantMediaCleanupWorker(outbox, storage, enabled, 10, 30, 8, "test");
    }

    private TenantMediaCleanupOutbox.CleanupJob job(int attemptCount) {
        return new TenantMediaCleanupOutbox.CleanupJob(
                UUID.randomUUID(), 1L, "1/workplace/floors/example.png", attemptCount);
    }
}
