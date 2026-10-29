package com.dwp.services.platform.mail;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class MailSyncJobWorkerTest {

    @Test
    void partialFailurePersistsRetryWithoutStoppingOtherLeasedJobs() {
        MailSyncJobRepository jobs = mock(MailSyncJobRepository.class);
        MailSyncExecutor executor = mock(MailSyncExecutor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MailSyncJobRepository.SyncJob first = job(1);
        MailSyncJobRepository.SyncJob second = job(1);
        when(jobs.claim(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(first, second));
        when(jobs.releaseExpiredLeases())
                .thenReturn(new MailSyncJobRepository.LeaseRecovery(0, 0));
        when(executor.execute(first)).thenThrow(
                new MailSyncExecutor.SyncWorkFailure("SYNC_PROVIDER_UNAVAILABLE", false));
        MailSyncExecutor.ExecutionResult success =
                new MailSyncExecutor.ExecutionResult(1, 1, 2, 0, 0);
        when(executor.execute(second)).thenReturn(success);
        when(jobs.completeFailure(
                any(), anyString(), anyString(), anyBoolean(),
                anyString(), any(), any()))
                .thenReturn(new MailSyncJobRepository.FailureDisposition(true, false));
        when(jobs.completeSuccess(any(), anyString(), any())).thenReturn(true);
        MailSyncJobWorker worker = new MailSyncJobWorker(
                jobs, executor, new MailMetrics(registry), true,
                10, 30, "test-worker");

        worker.executePending();

        verify(jobs).completeFailure(
                eq(first), workerId(), eq("SYNC_PROVIDER_UNAVAILABLE"), eq(false),
                eq("TRANSIENT"), eq(null), eq(java.util.Map.of()));
        verify(jobs).completeSuccess(eq(second), workerId(), eq(success));
        assertThat(registry.get("dwp.mail.sync.jobs")
                .tag("outcome", "retry_wait").counter().count()).isOne();
        assertThat(registry.get("dwp.mail.sync.jobs")
                .tag("outcome", "succeeded").counter().count()).isOne();
    }

    @Test
    void pageBudgetContinuationReturnsTheLeaseToTheDurableQueue() {
        MailSyncJobRepository jobs = mock(MailSyncJobRepository.class);
        MailSyncExecutor executor = mock(MailSyncExecutor.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MailSyncJobRepository.SyncJob job = job(1);
        MailSyncExecutor.ExecutionResult continuation =
                new MailSyncExecutor.ExecutionResult(1, 20, 10, 2, 0, true);
        when(jobs.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(job));
        when(jobs.releaseExpiredLeases())
                .thenReturn(new MailSyncJobRepository.LeaseRecovery(0, 0));
        when(executor.execute(job)).thenReturn(continuation);
        when(jobs.completeContinuation(any(), anyString(), any())).thenReturn(true);
        MailSyncJobWorker worker = new MailSyncJobWorker(
                jobs, executor, new MailMetrics(registry), true,
                10, 30, "test-worker");

        worker.executePending();

        verify(jobs).completeContinuation(eq(job), workerId(), eq(continuation));
        verify(jobs, never()).completeSuccess(any(), anyString(), any());
        assertThat(registry.get("dwp.mail.sync.jobs")
                .tag("outcome", "continued").counter().count()).isOne();
    }

    private MailSyncJobRepository.SyncJob job(int attempt) {
        return new MailSyncJobRepository.SyncJob(
                UUID.randomUUID(), 7, UUID.randomUUID(), UUID.randomUUID(),
                91, 0, "MANUAL", attempt, 5, "corr-sync");
    }

    private String workerId() {
        return org.mockito.ArgumentMatchers.argThat(value ->
                value != null && value.startsWith("test-worker:"));
    }
}
