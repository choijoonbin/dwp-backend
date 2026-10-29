package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.platform.contract.MailSyncProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailSyncExecutorTest {

    @Test
    void providerIoRunsWithoutAmbientDatabaseTransaction() {
        AdminMailCompletionRepository repository = mock(AdminMailCompletionRepository.class);
        MailExternalSyncMaterializer materializer = mock(MailExternalSyncMaterializer.class);
        UUID connectionId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(repository.connection(7, connectionId)).thenReturn(java.util.Optional.of(
                new AdminMailCompletionRepository.ConnectionRow(
                        connectionId, "DWP_SANDBOX", "ACTIVE", null,
                        "example.test", 0, null, null, now)));
        AdminMailCompletionRepository.AccountRow account =
                new AdminMailCompletionRepository.AccountRow(
                        accountId, "owner@example.test", "provider-account", "cursor", 0);
        when(repository.connectionAccounts(7, connectionId)).thenReturn(List.of(account));
        when(materializer.materialize(anyLong(), anyLong(), any(), any()))
                .thenReturn(new MailExternalSyncMaterializer.SyncResult(0, 0, 0, false));
        AtomicBoolean observedTransaction = new AtomicBoolean(true);
        MailConnectorPort connector = new DwpSandboxMailConnector() {
            @Override
            public SyncBatch synchronize(SyncRequest request) {
                observedTransaction.set(
                        TransactionSynchronizationManager.isActualTransactionActive());
                return new SyncBatch(List.of(), "next", false, false);
            }
        };
        MailSyncExecutor executor = new MailSyncExecutor(
                repository, new MailConnectorRegistry(List.of(connector)), materializer);
        MailSyncJobRepository.SyncJob job = new MailSyncJobRepository.SyncJob(
                UUID.randomUUID(), 7, UUID.randomUUID(), connectionId,
                91, 0, "MANUAL", 1, 5, "corr");

        MailSyncExecutor.ExecutionResult result = executor.execute(job);

        assertThat(observedTransaction).isFalse();
        assertThat(result.accounts()).isOne();
        assertThat(result.batches()).isOne();
    }

    @Test
    void typedRateLimitPreservesSafeCodeClassificationAndRetryAfter() {
        Instant retryAfter = Instant.parse("2026-10-28T01:02:03Z");
        MailConnectorPort connector = new DwpSandboxMailConnector() {
            @Override
            public SyncBatch synchronize(SyncRequest request) {
                throw new MailSyncProviderException(
                        MailSyncProviderException.Category.RATE_LIMIT,
                        "GRAPH_RATE_LIMITED", retryAfter, null);
            }
        };
        Fixture fixture = fixture(connector, mock(MailSyncJobRepository.class));

        MailSyncExecutor.SyncWorkFailure failure = assertThrows(
                MailSyncExecutor.SyncWorkFailure.class,
                () -> fixture.executor().execute(fixture.job()));

        assertThat(failure.code()).isEqualTo("GRAPH_RATE_LIMITED");
        assertThat(failure.classification()).isEqualTo("RATE_LIMIT");
        assertThat(failure.terminal()).isFalse();
        assertThat(failure.retryAt().toInstant()).isEqualTo(retryAfter);
    }

    @Test
    void typedCursorResetPassesOpaqueSeedIntoFencedRecoveryTransition() {
        String fullSyncSeed = "graph:full-sync-seed";
        MailConnectorPort connector = new DwpSandboxMailConnector() {
            @Override
            public SyncBatch synchronize(SyncRequest request) {
                throw new MailSyncProviderException(
                        MailSyncProviderException.Category.CURSOR_RESET,
                        "GRAPH_DELTA_CURSOR_EXPIRED", null, fullSyncSeed);
            }
        };
        MailSyncJobRepository syncJobs = mock(MailSyncJobRepository.class);
        Fixture fixture = fixture(connector, syncJobs);
        MailSyncJobRepository.ResetTransition transition =
                new MailSyncJobRepository.ResetTransition(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        java.util.Map.of("resetCursorHash", "a".repeat(64)));
        when(syncJobs.resetCursorAndEnqueueRecovery(
                fixture.job(), fixture.account(), fullSyncSeed)).thenReturn(transition);

        MailSyncExecutor.SyncWorkFailure failure = assertThrows(
                MailSyncExecutor.SyncWorkFailure.class,
                () -> fixture.executor().execute(fixture.job()));

        assertThat(failure.code()).isEqualTo("SYNC_CURSOR_RESET_REQUIRED");
        assertThat(failure.classification()).isEqualTo("CURSOR_RESET");
        assertThat(failure.resetEvidence()).isEqualTo(transition.evidence());
        verify(syncJobs).resetCursorAndEnqueueRecovery(
                fixture.job(), fixture.account(), fullSyncSeed);
    }

    @Test
    void boundedPageRunReturnsDurableContinuationInsteadOfTerminalFailure() {
        AdminMailCompletionRepository repository = mock(AdminMailCompletionRepository.class);
        MailExternalSyncMaterializer materializer = mock(MailExternalSyncMaterializer.class);
        UUID connectionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(repository.connection(7, connectionId)).thenReturn(java.util.Optional.of(
                new AdminMailCompletionRepository.ConnectionRow(
                        connectionId, "DWP_SANDBOX", "ACTIVE", null,
                        "example.test", 0, null, null, now)));
        AdminMailCompletionRepository.AccountRow account =
                new AdminMailCompletionRepository.AccountRow(
                        UUID.randomUUID(), "owner@example.test",
                        "provider-account", "cursor-0", 0);
        when(repository.connectionAccounts(7, connectionId)).thenReturn(List.of(account));
        when(materializer.materialize(anyLong(), anyLong(), any(), any()))
                .thenReturn(new MailExternalSyncMaterializer.SyncResult(1, 0, 0, true));
        AtomicInteger pages = new AtomicInteger();
        MailConnectorPort connector = new DwpSandboxMailConnector() {
            @Override
            public SyncBatch synchronize(SyncRequest request) {
                int page = pages.incrementAndGet();
                return new SyncBatch(List.of(), "cursor-" + page, false, true);
            }
        };
        MailSyncExecutor executor = new MailSyncExecutor(
                repository, new MailConnectorRegistry(List.of(connector)), materializer);
        MailSyncJobRepository.SyncJob job = new MailSyncJobRepository.SyncJob(
                UUID.randomUUID(), 7, UUID.randomUUID(), connectionId,
                91, 0, "MANUAL", 1, 5, "corr");

        MailSyncExecutor.ExecutionResult result = executor.execute(job);

        assertThat(result.continuationRequired()).isTrue();
        assertThat(result.batches()).isEqualTo(20);
        assertThat(pages).hasValue(20);
        verify(materializer, times(20)).materialize(
                anyLong(), anyLong(), any(), any());
    }

    private Fixture fixture(
            MailConnectorPort connector,
            MailSyncJobRepository syncJobs) {
        AdminMailCompletionRepository repository = mock(AdminMailCompletionRepository.class);
        MailExternalSyncMaterializer materializer = mock(MailExternalSyncMaterializer.class);
        UUID connectionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(repository.connection(7, connectionId)).thenReturn(java.util.Optional.of(
                new AdminMailCompletionRepository.ConnectionRow(
                        connectionId, "DWP_SANDBOX", "ACTIVE", null,
                        "example.test", 0, null, null, now)));
        AdminMailCompletionRepository.AccountRow account =
                new AdminMailCompletionRepository.AccountRow(
                        UUID.randomUUID(), "owner@example.test",
                        "provider-account", "cursor", 3);
        when(repository.connectionAccounts(7, connectionId)).thenReturn(List.of(account));
        MailSyncJobRepository.SyncJob job = new MailSyncJobRepository.SyncJob(
                UUID.randomUUID(), 7, UUID.randomUUID(), connectionId,
                91, 0, "MANUAL", 1, 5, "corr");
        MailSyncExecutor executor = new MailSyncExecutor(
                repository, new MailConnectorRegistry(List.of(connector)),
                materializer, syncJobs);
        return new Fixture(executor, job, account);
    }

    private record Fixture(
            MailSyncExecutor executor,
            MailSyncJobRepository.SyncJob job,
            AdminMailCompletionRepository.AccountRow account) { }
}
