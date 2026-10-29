package com.dwp.services.messaging.retention;

import com.dwp.services.messaging.attachment.AttachmentStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class MessagingRetentionServiceTest {

    private static final long TENANT_ID = 71;
    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WORKER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);

    private MessagingRetentionRepository repository;
    private AttachmentStorage storage;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        repository = mock(MessagingRetentionRepository.class);
        storage = mock(AttachmentStorage.class);
        transactions = mock(TransactionTemplate.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation ->
                ((TransactionCallback) invocation.getArgument(0)).doInTransaction(status));
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(status);
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(repository.tryStartRun(
                eq(TENANT_ID), any(), any(), eq(WORKER_ID), any()))
                .thenReturn(Optional.of(RUN_ID));
        when(repository.finishRun(
                eq(RUN_ID), eq(WORKER_ID), any(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(true);
    }

    @Test
    void dryRunRecordsCandidatesWithoutMutatingMessages() {
        when(repository.candidateStats(eq(TENANT_ID), any()))
                .thenReturn(new MessagingRetentionRepository.CandidateStats(12, 3));
        MessagingRetentionService service = service(properties(
                MessagingRetentionProperties.ExecutionMode.DRY_RUN, false, false));

        service.runTenant(new MessagingRetentionRepository.TenantRetentionPolicy(TENANT_ID, 30));

        verify(repository).finishRun(
                RUN_ID, WORKER_ID, "COMPLETED", 12, 0, 3, 0, null);
        verify(repository, never()).nextCandidates(anyLong(), any(), anyInt());
        verify(repository, never()).purgeMessageContent(anyLong(), any(), any());
    }

    @Test
    void enforcementIsBlockedBeforeCandidateReadsWithoutHoldAuthority() {
        MessagingRetentionService service = service(properties(
                MessagingRetentionProperties.ExecutionMode.ENFORCING, false, true));

        service.runTenant(new MessagingRetentionRepository.TenantRetentionPolicy(TENANT_ID, 30));

        verify(repository).finishRun(
                RUN_ID, WORKER_ID, "BLOCKED", 0, 0, 0, 0,
                "LEGAL_HOLD_AUTHORITY_UNAVAILABLE");
        verify(repository, never()).candidateStats(anyLong(), any());
    }

    @Test
    void authorizedEnforcementPurgesContentThroughTheGovernedTransaction() {
        UUID messageId = UUID.randomUUID();
        when(repository.candidateStats(eq(TENANT_ID), any()))
                .thenReturn(new MessagingRetentionRepository.CandidateStats(1, 0));
        when(repository.renewLease(RUN_ID, WORKER_ID, Duration.ofMinutes(15)))
                .thenReturn(true);
        when(repository.pendingAttachmentDeletions(TENANT_ID, 1_000)).thenReturn(List.of());
        when(repository.nextCandidates(eq(TENANT_ID), any(), eq(100)))
                .thenReturn(List.of(messageId));
        when(repository.lockEligibleMessage(eq(TENANT_ID), eq(messageId), any()))
                .thenReturn(Optional.of(
                        new MessagingRetentionRepository.LockedMessage(messageId, UUID.randomUUID())));
        when(repository.purgeMessageContent(TENANT_ID, RUN_ID, messageId)).thenReturn(1);
        MessagingRetentionService service = service(properties(
                MessagingRetentionProperties.ExecutionMode.ENFORCING, true, true));

        service.runTenant(new MessagingRetentionRepository.TenantRetentionPolicy(TENANT_ID, 30));

        verify(repository).purgeMessageContent(TENANT_ID, RUN_ID, messageId);
        verify(repository).finishRun(
                RUN_ID, WORKER_ID, "COMPLETED", 1, 1, 0, 0, null);
    }

    @Test
    void unresolvedAttachmentBacklogStopsNewMessagePurges() {
        UUID deletionId = UUID.randomUUID();
        when(repository.candidateStats(eq(TENANT_ID), any()))
                .thenReturn(new MessagingRetentionRepository.CandidateStats(8, 0));
        when(repository.renewLease(RUN_ID, WORKER_ID, Duration.ofMinutes(15)))
                .thenReturn(true);
        when(repository.pendingAttachmentDeletions(TENANT_ID, 1_000)).thenReturn(List.of(
                new MessagingRetentionRepository.PendingAttachmentDeletion(
                        deletionId, "71/attachment")));
        when(repository.pendingAttachmentDeletionCount(TENANT_ID)).thenReturn(1L);
        org.mockito.Mockito.doThrow(new IllegalStateException("storage unavailable"))
                .when(storage).delete("71/attachment");
        MessagingRetentionService service = service(properties(
                MessagingRetentionProperties.ExecutionMode.ENFORCING, true, true));

        service.runTenant(new MessagingRetentionRepository.TenantRetentionPolicy(TENANT_ID, 30));

        verify(repository).failAttachmentDeletion(eq(deletionId), any());
        verify(repository).finishRun(
                RUN_ID, WORKER_ID, "FAILED", 8, 0, 0, 1,
                "ATTACHMENT_DELETION_BACKLOG");
        verify(repository, never()).nextCandidates(anyLong(), any(), anyInt());
    }

    @Test
    void authorizedEnforcementDeletesDraftsAndRedactsTerminalScheduledPayloads() {
        when(repository.candidateStats(eq(TENANT_ID), any()))
                .thenReturn(new MessagingRetentionRepository.CandidateStats(3, 0));
        when(repository.renewLease(RUN_ID, WORKER_ID, Duration.ofMinutes(15)))
                .thenReturn(true);
        when(repository.pendingAttachmentDeletions(TENANT_ID, 1_000)).thenReturn(List.of());
        when(repository.nextCandidates(eq(TENANT_ID), any(), eq(100)))
                .thenReturn(List.of());
        when(repository.purgeContinuityContent(eq(TENANT_ID), any(), eq(100)))
                .thenReturn(
                        new MessagingRetentionRepository.ContinuityPurgeResult(1, 2),
                        new MessagingRetentionRepository.ContinuityPurgeResult(0, 0));
        MessagingRetentionService service = service(properties(
                MessagingRetentionProperties.ExecutionMode.ENFORCING, true, true));

        service.runTenant(new MessagingRetentionRepository.TenantRetentionPolicy(TENANT_ID, 30));

        verify(repository).purgeContinuityContent(eq(TENANT_ID), any(), eq(100));
        verify(repository).finishRun(
                RUN_ID, WORKER_ID, "COMPLETED", 3, 3, 0, 0, null);
    }

    @Test
    void continuityCleanupHasAnIndependentBudgetDuringPersistentMessageBacklog() {
        UUID messageId = UUID.randomUUID();
        when(repository.candidateStats(eq(TENANT_ID), any()))
                .thenReturn(new MessagingRetentionRepository.CandidateStats(1_001, 0));
        when(repository.renewLease(RUN_ID, WORKER_ID, Duration.ofMinutes(15)))
                .thenReturn(true);
        when(repository.pendingAttachmentDeletions(TENANT_ID, 1_000)).thenReturn(List.of());
        when(repository.nextCandidates(eq(TENANT_ID), any(), eq(100)))
                .thenReturn(java.util.Collections.nCopies(100, messageId));
        when(repository.purgeContinuityContent(eq(TENANT_ID), any(), eq(100)))
                .thenReturn(
                        new MessagingRetentionRepository.ContinuityPurgeResult(1, 0),
                        new MessagingRetentionRepository.ContinuityPurgeResult(0, 0));
        MessagingRetentionService service = service(properties(
                MessagingRetentionProperties.ExecutionMode.ENFORCING, true, true));

        service.runTenant(new MessagingRetentionRepository.TenantRetentionPolicy(TENANT_ID, 30));

        verify(repository).purgeContinuityContent(eq(TENANT_ID), any(), eq(100));
        verify(repository).finishRun(
                RUN_ID, WORKER_ID, "COMPLETED", 1_001, 1, 0, 0, null);
    }

    private MessagingRetentionService service(MessagingRetentionProperties properties) {
        return new MessagingRetentionService(
                repository, storage, properties, transactions, new SimpleMeterRegistry(),
                CLOCK, WORKER_ID);
    }

    private MessagingRetentionProperties properties(
            MessagingRetentionProperties.ExecutionMode mode,
            boolean holdReady,
            boolean downstreamReady) {
        return new MessagingRetentionProperties(
                true, mode, false, holdReady, downstreamReady,
                100, 10, 1_000, Duration.ofMinutes(15));
    }
}
