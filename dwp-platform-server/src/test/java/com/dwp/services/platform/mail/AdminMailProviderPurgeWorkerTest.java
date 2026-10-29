package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMailProviderPurgeWorkerTest {

    @Test
    void unsupportedProviderPurgeNeverEmitsCompletionEventOrCompletedAudit() {
        AdminMailCompletionRepository repository = mock(AdminMailCompletionRepository.class);
        AdminMailPurgeTransactions transactions = mock(AdminMailPurgeTransactions.class);
        MailPurgeExecutionAuthority authority = mock(MailPurgeExecutionAuthority.class);
        MailPurgeDomainEventPort events = mock(MailPurgeDomainEventPort.class);
        MailProviderPurgeCoordinator provider = mock(MailProviderPurgeCoordinator.class);
        UUID jobId = UUID.randomUUID();
        AdminMailCompletionRepository.PurgeLeaseRow job =
                new AdminMailCompletionRepository.PurgeLeaseRow(
                        jobId, 7L, UUID.randomUUID(), 11L, List.of(), List.of(), 1);
        AdminMailCompletionRepository.DeleteCounts counts =
                new AdminMailCompletionRepository.DeleteCounts(0, 0, 0);
        when(repository.claimPurgeJobs(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(job));
        when(repository.purgeDeletionReceiptIds(7L, jobId)).thenReturn(Set.of());
        when(repository.purgeDeletionReceiptCounts(7L, jobId)).thenReturn(counts);
        when(repository.checkpointPurgeJob(anyLong(), any(), anyString(), anyString(),
                anyList(), any())).thenReturn(1);
        when(provider.execute(7L, 11L, jobId)).thenReturn(
                new MailProviderPurgeCoordinator.Result(
                        MailProviderPurgeCoordinator.State.NOT_SUPPORTED, 1, 0));
        when(repository.remainingPurgeCandidates(7L, List.of())).thenReturn(0L);
        when(repository.completeLeasedPurgeJob(anyLong(), any(), anyString(), anyString(),
                any(), anyList(), anyString(), anyString())).thenReturn(1);
        AdminMailPurgeWorker worker = new AdminMailPurgeWorker(
                repository, transactions, authority, events, provider, null,
                true, 5, 30, 5, "test");

        worker.executePending();

        verify(events, never()).record(any(), any());
        verify(repository, never()).ensurePurgeCompletionEvent(
                anyLong(), any(), anyLong(), any());
        verify(repository).audit(7L, 11L,
                "mail.purge.provider-reconciliation-required", "MAIL_PURGE_JOB",
                jobId.toString(), null, Map.of("attempt", 1),
                Map.of("result", "PARTIAL", "deletedThreads", 0,
                        "deletedMessages", 0, "remaining", 0L));
    }

    @Test
    void unresolvedProviderPurgeClosesFailedAtMaximumAttemptWithoutCompletionEvent() {
        AdminMailCompletionRepository repository = mock(AdminMailCompletionRepository.class);
        AdminMailPurgeTransactions transactions = mock(AdminMailPurgeTransactions.class);
        MailPurgeExecutionAuthority authority = mock(MailPurgeExecutionAuthority.class);
        MailPurgeDomainEventPort events = mock(MailPurgeDomainEventPort.class);
        MailProviderPurgeCoordinator provider = mock(MailProviderPurgeCoordinator.class);
        UUID jobId = UUID.randomUUID();
        AdminMailCompletionRepository.PurgeLeaseRow job =
                new AdminMailCompletionRepository.PurgeLeaseRow(
                        jobId, 7L, UUID.randomUUID(), 11L, List.of(), List.of(), 5);
        AdminMailCompletionRepository.DeleteCounts counts =
                new AdminMailCompletionRepository.DeleteCounts(0, 0, 0);
        when(repository.claimPurgeJobs(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(job));
        when(repository.purgeDeletionReceiptIds(7L, jobId)).thenReturn(Set.of());
        when(repository.purgeDeletionReceiptCounts(7L, jobId)).thenReturn(counts);
        when(repository.checkpointPurgeJob(anyLong(), any(), anyString(), anyString(),
                anyList(), any())).thenReturn(1);
        when(provider.execute(7L, 11L, jobId)).thenReturn(
                new MailProviderPurgeCoordinator.Result(
                        MailProviderPurgeCoordinator.State.RECONCILIATION_REQUIRED, 1, 0));
        when(repository.remainingPurgeCandidates(7L, List.of())).thenReturn(0L);
        when(repository.completeLeasedPurgeJob(anyLong(), any(), anyString(), anyString(),
                any(), anyList(), anyString(), anyString())).thenReturn(1);
        AdminMailPurgeWorker worker = new AdminMailPurgeWorker(
                repository, transactions, authority, events, provider, null,
                true, 5, 30, 5, "test");

        worker.executePending();

        verify(repository).completeLeasedPurgeJob(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(jobId), anyString(),
                org.mockito.ArgumentMatchers.eq("FAILED"),
                org.mockito.ArgumentMatchers.eq(counts), anyList(),
                org.mockito.ArgumentMatchers.eq("UNKNOWN"),
                org.mockito.ArgumentMatchers.eq(
                        "PROVIDER_PURGE_RECONCILIATION_REQUIRED"));
        verify(events, never()).record(any(), any());
        verify(repository, never()).ensurePurgeCompletionEvent(
                anyLong(), any(), anyLong(), any());
        verify(repository, never()).audit(
                anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq("mail.purge.completed"),
                anyString(), anyString(), any(), any(), any());
    }
}
