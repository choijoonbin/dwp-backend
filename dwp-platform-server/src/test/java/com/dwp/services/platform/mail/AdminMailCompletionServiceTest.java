package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMailCompletionServiceTest {

    private AdminMailCompletionRepository repository;
    private AdminMailCompletionService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminMailCompletionRepository.class);
        service = new AdminMailCompletionService(
                repository,
                new MailConnectorRegistry(List.of(new DwpSandboxMailConnector())),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void purgeExecutionFailsClosedWhenAnActiveLegalHoldExists() {
        UUID snapshotId = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        var preview = purgePreview(snapshotId, fingerprint);
        when(repository.purgePreview(7, snapshotId)).thenReturn(Optional.of(preview));
        when(repository.purgeJobByCommand(eq(7L), eq(91L), any()))
                .thenReturn(Optional.empty());
        when(repository.policy(7)).thenReturn(policy(4));
        when(repository.activeHoldCount(7)).thenReturn(1);

        PurgeExecuteRequest request = new PurgeExecuteRequest(UUID.randomUUID(), 4L, fingerprint);

        assertThatThrownBy(() -> service.executePurge(
                7, 91, snapshotId, "corr-1", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ACTIVE_LEGAL_HOLD_BLOCKS_PURGE");
        verify(repository).lockRetentionLifecycle(7);
        verify(repository, never()).insertPurgeJob(anyLong(), any(), anyLong(), any());
        verify(repository, never()).deletePurgeCandidates(anyLong(), any());
    }

    @Test
    void purgePreviewRejectsSelectorsOutsideTheTenantWideContract() {
        PurgePreviewRequest request = new PurgePreviewRequest(
                Map.of("tenant", true, "accountId", UUID.randomUUID().toString()),
                List.of("THREADS", "MESSAGES"),
                OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID(), 4L);

        assertThatThrownBy(() -> service.previewPurge(7, 91, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PURGE_SCOPE_UNSUPPORTED");

        verify(repository, never()).policy(anyLong());
    }

    @Test
    void purgeReplayRejectsAChangedFingerprint() {
        UUID snapshotId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        when(repository.purgePreview(7, snapshotId))
                .thenReturn(Optional.of(purgePreview(snapshotId, fingerprint)));
        when(repository.purgeJobByCommand(7, 91, key))
                .thenReturn(Optional.of(purgeJob(snapshotId, 91, key)));

        assertThatThrownBy(() -> service.executePurge(
                7, 91, snapshotId, "corr-replay",
                new PurgeExecuteRequest(key, 4L, "b".repeat(64))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("IDEMPOTENCY_FINGERPRINT_MISMATCH");
        verify(repository, never()).deletePurgeCandidates(anyLong(), any());
    }

    @Test
    void purgeSnapshotCannotBeExecutedByASecondCommand() {
        UUID snapshotId = UUID.randomUUID();
        UUID existingKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        when(repository.purgePreview(7, snapshotId))
                .thenReturn(Optional.of(purgePreview(snapshotId, fingerprint)));
        when(repository.purgeJobByCommand(eq(7L), eq(91L), any()))
                .thenReturn(Optional.empty());
        when(repository.purgeJobBySnapshot(7, snapshotId))
                .thenReturn(Optional.of(purgeJob(snapshotId, 90, existingKey)));

        assertThatThrownBy(() -> service.executePurge(
                7, 91, snapshotId, "corr-second",
                new PurgeExecuteRequest(UUID.randomUUID(), 4L, fingerprint)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PURGE_SNAPSHOT_ALREADY_EXECUTED");
        verify(repository, never()).deletePurgeCandidates(anyLong(), any());
    }

    @Test
    void purgeExecutionRequiresTwoDistinctApprovers() {
        UUID snapshotId = UUID.randomUUID();
        String fingerprint = "b".repeat(64);
        when(repository.purgePreview(7, snapshotId))
                .thenReturn(Optional.of(purgePreview(snapshotId, fingerprint)));
        when(repository.purgeJobByCommand(eq(7L), eq(91L), any()))
                .thenReturn(Optional.empty());
        when(repository.policy(7)).thenReturn(policy(4));
        when(repository.activeHoldCount(7)).thenReturn(0);
        when(repository.distinctApprovals(7, snapshotId, 4)).thenReturn(1);

        assertThatThrownBy(() -> service.executePurge(
                7, 91, snapshotId, "corr-2",
                new PurgeExecuteRequest(UUID.randomUUID(), 4L, fingerprint)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("TWO_DISTINCT_APPROVERS_REQUIRED");
        verify(repository, never()).insertPurgeJob(anyLong(), any(), anyLong(), any());
    }

    @Test
    void retryFailsClosedWhenProviderAcceptanceCannotBeExcluded() {
        UUID deliveryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        when(repository.recoveryCommand(7, 91, key)).thenReturn(Optional.empty());
        when(repository.delivery(7, deliveryId)).thenReturn(Optional.of(delivery(
                deliveryId, "FAILED", "provider-message-present", null, null, 8)));

        assertThatThrownBy(() -> service.recoverDelivery(
                7, 91, deliveryId, "RETRY", "corr-3",
                new DeliveryRecoveryRequest(key, 8L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RETRY_EVIDENCE_INSUFFICIENT");
        verify(repository, never()).retryDelivery(anyLong(), any(), anyLong());
        verify(repository, never()).insertRecoveryEvent(
                anyLong(), any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void retryFailsClosedWhenProviderResultIsUnknown() {
        UUID deliveryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var row = new AdminMailCompletionRepository.DeliveryRow(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), "FAILED", 1,
                now, null, null, null, null, "MAIL_PROVIDER_RESULT_UNKNOWN", "corr",
                null, now.minusMinutes(1), 91, now.minusSeconds(10), 8,
                "Support", "MICROSOFT_GRAPH");
        when(repository.recoveryCommand(7, 91, key)).thenReturn(Optional.empty());
        when(repository.delivery(7, deliveryId)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.recoverDelivery(
                7, 91, deliveryId, "RETRY", "corr-unknown",
                new DeliveryRecoveryRequest(key, 8L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RETRY_EVIDENCE_INSUFFICIENT");
        verify(repository, never()).retryDelivery(anyLong(), any(), anyLong());
    }

    @Test
    void connectionOperationExactReplayIgnoresLaterConnectionVersionDrift() {
        UUID connectionId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        ConnectionOperationRequest request = new ConnectionOperationRequest(
                "SEND", "TENANT", null, false, key, 0L);
        String fingerprint = new AdminMailCommandFingerprint(
                new ObjectMapper().findAndRegisterModules()).digest(
                "CONNECTION_OPERATION", 91L, connectionId, "DIAGNOSTIC",
                "SEND", "TENANT", "", false, 0L);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(repository.connectionOperation(7, 91, key)).thenReturn(Optional.of(
                new AdminMailCompletionRepository.ConnectionOperationRow(
                        UUID.randomUUID(), connectionId, "DIAGNOSTIC", "SUCCEEDED",
                        key, fingerprint, "corr-replay", now, null,
                        now.minusMinutes(1), now)));

        ConnectionOperation replay = service.connectionOperation(
                7, 91, connectionId, "DIAGNOSTIC", "corr-new", request);

        assertThat(replay.replayed()).isTrue();
        verify(repository, never()).connection(anyLong(), any());
    }

    @Test
    void synchronizationDelegatesNonemptyProviderBatchToMaterializer() {
        UUID connectionId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        MailConnectorPort connector = mock(MailConnectorPort.class);
        MailExternalSyncMaterializer materializer = mock(MailExternalSyncMaterializer.class);
        MailConnectorPort.ProviderMessage message = new MailConnectorPort.ProviderMessage(
                "provider-message", "provider-thread", "provider-folder",
                Instant.parse("2026-09-17T00:00:00Z"), "sender@example.test",
                List.of("recipient@example.test"), "Subject", "Body", Map.of());
        MailConnectorPort.SyncBatch batch = new MailConnectorPort.SyncBatch(
                List.of(message), "cursor-2", false, true);
        MailConnectorPort.SyncBatch finalBatch = new MailConnectorPort.SyncBatch(
                List.of(), "cursor-3", false, false);
        when(connector.manifest()).thenReturn(new MailConnectorPort.Manifest(
                MailConnectorPort.ProviderFamily.DWP_SANDBOX, "test", "test",
                java.util.Set.of(MailConnectorPort.Capability.READ)));
        when(connector.synchronize(any())).thenReturn(batch).thenReturn(finalBatch);
        service = new AdminMailCompletionService(
                repository, new MailConnectorRegistry(List.of(connector)),
                new ObjectMapper().findAndRegisterModules(), materializer);
        ConnectionOperationRequest request = new ConnectionOperationRequest(
                "READ", "TENANT", null, false, key, 0L);
        String fingerprint = new AdminMailCommandFingerprint(
                new ObjectMapper().findAndRegisterModules()).digest(
                "CONNECTION_OPERATION", 91L, connectionId, "SYNC",
                "READ", "TENANT", "", false, 0L);
        AdminMailCompletionRepository.ConnectionOperationRow completed =
                new AdminMailCompletionRepository.ConnectionOperationRow(
                        operationId, connectionId, "SYNC", "SUCCEEDED", key,
                        fingerprint, "corr-sync", now, null,
                        now.minusSeconds(1), now);
        when(repository.connectionOperation(7, 91, key))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(completed));
        when(repository.connection(7, connectionId)).thenReturn(Optional.of(
                new AdminMailCompletionRepository.ConnectionRow(
                        connectionId, "DWP_SANDBOX", "ACTIVE", null,
                        "example.test", 0, null, null, now)));
        when(repository.insertConnectionOperation(
                eq(7L), eq(91L), eq(connectionId), eq("SYNC"), eq("TENANT"),
                any(), eq(key), eq(fingerprint), eq("corr-sync")))
                .thenReturn(Optional.of(operationId));
        AdminMailCompletionRepository.AccountRow account =
                new AdminMailCompletionRepository.AccountRow(
                        accountId, "recipient@example.test", "provider-account", "cursor-1");
        when(repository.connectionAccounts(7, connectionId)).thenReturn(List.of(account));
        when(repository.markConnectionSynchronized(7, connectionId, 0, 91)).thenReturn(1);

        ConnectionOperation operation = service.connectionOperation(
                7, 91, connectionId, "SYNC", "corr-sync", request);

        assertThat(operation.state()).isEqualTo("SUCCEEDED");
        verify(materializer).materialize(7, 91, account, batch);
        verify(materializer).materialize(
                7, 91,
                new AdminMailCompletionRepository.AccountRow(
                        accountId, "recipient@example.test",
                        "provider-account", "cursor-2"),
                finalBatch);
        verify(repository).markConnectionSynchronized(7, connectionId, 0, 91);
    }

    @Test
    void testSendClaimsDurablyBeforeProviderAndUsesDurableOperationId() {
        UUID connectionId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        MailConnectorPort connector = mock(MailConnectorPort.class);
        AdminMailOperationDurability durability = mock(AdminMailOperationDurability.class);
        when(connector.manifest()).thenReturn(new MailConnectorPort.Manifest(
                MailConnectorPort.ProviderFamily.DWP_SANDBOX, "test", "test",
                java.util.Set.of(MailConnectorPort.Capability.SEND)));
        when(connector.send(any())).thenReturn(new MailConnectorPort.DeliveryReceipt(
                "provider-message", "provider-thread", Instant.now()));
        service = new AdminMailCompletionService(
                repository, new MailConnectorRegistry(List.of(connector)),
                new ObjectMapper().findAndRegisterModules(), null, durability);
        ConnectionOperationRequest request = new ConnectionOperationRequest(
                "SEND", "TENANT", "recipient@example.test", true, key, 0L);
        String fingerprint = new AdminMailCommandFingerprint(
                new ObjectMapper().findAndRegisterModules()).digest(
                "CONNECTION_OPERATION", 91L, connectionId, "TEST_SEND",
                "SEND", "TENANT", "recipient@example.test", true, 0L);
        AdminMailCompletionRepository.ConnectionRow connection =
                new AdminMailCompletionRepository.ConnectionRow(
                        connectionId, "DWP_SANDBOX", "ACTIVE", null,
                        "example.test", 0, null, null, now);
        AdminMailCompletionRepository.ConnectionOperationRow completed =
                new AdminMailCompletionRepository.ConnectionOperationRow(
                        operationId, connectionId, "TEST_SEND", "SUCCEEDED", key,
                        fingerprint, "corr-test", now, null,
                        now.minusSeconds(1), now);
        when(repository.connectionOperation(7, 91, key)).thenReturn(Optional.empty());
        when(repository.connection(7, connectionId)).thenReturn(Optional.of(connection));
        when(repository.connectionAccounts(7, connectionId)).thenReturn(List.of(
                new AdminMailCompletionRepository.AccountRow(
                        accountId, "sender@example.test", "provider-account", null)));
        when(durability.claimTestSend(
                eq(7L), eq(91L), eq(connectionId), eq("TENANT"), any(),
                eq(key), eq(fingerprint), eq("corr-test")))
                .thenReturn(new AdminMailOperationDurability.Claim(operationId, true, null));
        when(durability.complete(
                eq(7L), eq(91L), eq(key), eq(operationId),
                eq("SUCCEEDED"), eq(null), any()))
                .thenReturn(completed);

        ConnectionOperation result = service.connectionOperation(
                7, 91, connectionId, "TEST_SEND", "corr-test", request);

        ArgumentCaptor<MailConnectorPort.SendRequest> providerRequest =
                ArgumentCaptor.forClass(MailConnectorPort.SendRequest.class);
        verify(connector).send(providerRequest.capture());
        assertThat(providerRequest.getValue().idempotencyKey()).isEqualTo(operationId);
        assertThat(result.state()).isEqualTo("SUCCEEDED");
        InOrder order = inOrder(durability, connector);
        order.verify(durability).claimTestSend(
                eq(7L), eq(91L), eq(connectionId), eq("TENANT"), any(),
                eq(key), eq(fingerprint), eq("corr-test"));
        order.verify(connector).send(any());
        order.verify(durability).complete(
                eq(7L), eq(91L), eq(key), eq(operationId),
                eq("SUCCEEDED"), eq(null), any());
    }

    @Test
    void ambiguousTestSendIsDurablyUnknownAndExactReplayDoesNotResend() {
        UUID connectionId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        MailConnectorPort connector = mock(MailConnectorPort.class);
        AdminMailOperationDurability durability = mock(AdminMailOperationDurability.class);
        when(connector.manifest()).thenReturn(new MailConnectorPort.Manifest(
                MailConnectorPort.ProviderFamily.DWP_SANDBOX, "test", "test",
                java.util.Set.of(MailConnectorPort.Capability.SEND)));
        when(connector.send(any())).thenThrow(new IllegalStateException("connection reset"));
        service = new AdminMailCompletionService(
                repository, new MailConnectorRegistry(List.of(connector)),
                new ObjectMapper().findAndRegisterModules(), null, durability);
        ConnectionOperationRequest request = new ConnectionOperationRequest(
                "SEND", "TENANT", "recipient@example.test", true, key, 0L);
        String fingerprint = new AdminMailCommandFingerprint(
                new ObjectMapper().findAndRegisterModules()).digest(
                "CONNECTION_OPERATION", 91L, connectionId, "TEST_SEND",
                "SEND", "TENANT", "recipient@example.test", true, 0L);
        AdminMailCompletionRepository.ConnectionOperationRow unknown =
                new AdminMailCompletionRepository.ConnectionOperationRow(
                        operationId, connectionId, "TEST_SEND", "UNKNOWN", key,
                        fingerprint, "corr-test", now, "CONNECTOR_RESULT_UNCERTAIN",
                        now.minusSeconds(1), now);
        when(repository.connectionOperation(7, 91, key))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(unknown));
        when(repository.connection(7, connectionId)).thenReturn(Optional.of(
                new AdminMailCompletionRepository.ConnectionRow(
                        connectionId, "DWP_SANDBOX", "ACTIVE", null,
                        "example.test", 0, null, null, now)));
        when(repository.connectionAccounts(7, connectionId)).thenReturn(List.of(
                new AdminMailCompletionRepository.AccountRow(
                        accountId, "sender@example.test", "provider-account", null)));
        when(durability.claimTestSend(
                eq(7L), eq(91L), eq(connectionId), eq("TENANT"), any(),
                eq(key), eq(fingerprint), eq("corr-test")))
                .thenReturn(new AdminMailOperationDurability.Claim(operationId, true, null));
        when(durability.complete(
                eq(7L), eq(91L), eq(key), eq(operationId), eq("UNKNOWN"),
                eq("CONNECTOR_RESULT_UNCERTAIN"), any()))
                .thenReturn(unknown);

        ConnectionOperation first = service.connectionOperation(
                7, 91, connectionId, "TEST_SEND", "corr-test", request);
        ConnectionOperation replay = service.connectionOperation(
                7, 91, connectionId, "TEST_SEND", "corr-replay", request);

        assertThat(first.state()).isEqualTo("UNKNOWN");
        assertThat(replay.state()).isEqualTo("UNKNOWN");
        assertThat(replay.replayed()).isTrue();
        verify(connector, times(1)).send(any());
        verify(durability, times(1)).claimTestSend(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deliveryExportDownloadIsScopedToItsCreatingAdministrator() {
        UUID exportId = UUID.randomUUID();
        when(repository.export(7, 91, exportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deliveryExportJson(7, 91, exportId))
                .isInstanceOf(ResponseStatusException.class);

        verify(repository, never()).deliveries(anyLong(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void deliveryExportMaterializesOneImmutablePayloadWithExplicitTruncationEvidence() {
        UUID key = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DeliveryExportRequest request = new DeliveryExportRequest(
                Map.of(), "Incident evidence", key);
        when(repository.deliveries(7, "", "", 10_001, 0)).thenReturn(List.of(
                delivery(deliveryId, "DELIVERED", "provider-message", null, null, 2)));
        when(repository.recoveryEvents(7, deliveryId)).thenReturn(List.of());
        AtomicReference<AdminMailCompletionRepository.ExportRow> persisted =
                new AtomicReference<>();
        when(repository.insertExport(
                any(), eq(7L), eq(91L), eq(Map.of()), eq("Incident evidence"),
                any(), eq(key), any(), any(), any(), eq(1), eq(false), any()))
                .thenAnswer(invocation -> {
                    UUID exportId = invocation.getArgument(0);
                    String watermark = invocation.getArgument(5);
                    OffsetDateTime expiresAt = invocation.getArgument(7);
                    String payload = invocation.getArgument(8);
                    String hash = invocation.getArgument(9);
                    OffsetDateTime cutoff = invocation.getArgument(12);
                    persisted.set(new AdminMailCompletionRepository.ExportRow(
                            exportId, 91, Map.of(), "Incident evidence", "READY",
                            "DATABASE_SNAPSHOT:" + hash, watermark, key, cutoff,
                            expiresAt, payload, hash, 1, false, cutoff));
                    return Optional.of(exportId);
                });
        when(repository.export(eq(7L), eq(91L), any()))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));

        DeliveryExport export = service.createDeliveryExport(7, 91, request);
        String first = service.deliveryExportJson(7, 91, export.exportId());
        String second = service.deliveryExportJson(7, 91, export.exportId());

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("\"itemCount\":1")
                .contains("\"truncated\":false")
                .contains("\"itemLimit\":10000");
        assertThat(export.itemCount()).isOne();
        assertThat(export.truncated()).isFalse();
        assertThat(export.payloadSha256()).hasSize(64);
        verify(repository, times(1)).deliveries(7, "", "", 10_001, 0);
    }

    @Test
    void expiredExternalLeaseCannotBeReconciledWithoutProviderEvidence() {
        UUID deliveryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        when(repository.recoveryCommand(7, 91, key)).thenReturn(Optional.empty());
        when(repository.delivery(7, deliveryId)).thenReturn(Optional.of(delivery(
                deliveryId, "LEASED", null, "worker-1",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), 3,
                "MICROSOFT_GRAPH")));

        assertThatThrownBy(() -> service.recoverDelivery(
                7, 91, deliveryId, "RECONCILE", "corr-4",
                new DeliveryRecoveryRequest(key, 3L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RECONCILIATION_EVIDENCE_INSUFFICIENT");
        verify(repository, never()).failExpiredSandboxLease(anyLong(), any(), anyLong());
    }

    @Test
    void manageGrantRequiresReadAndAssign() {
        AccessPermissions invalid = new AccessPermissions(false, false, false, false, true);
        SharedInboxMemberRequest request = new SharedInboxMemberRequest(
                55L, "Member", null, invalid, null, false, UUID.randomUUID(), 0L);

        assertThatThrownBy(() -> service.addSharedInboxMember(
                7, 91, UUID.randomUUID(), "corr-5", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MANAGE_REQUIRES_READ_AND_ASSIGN");
        verify(repository, never()).insertAccessGrant(
                anyLong(), any(), anyLong(), any(), any(), any(),
                eq(false), eq(false), eq(false), eq(false), eq(true), any(), anyLong());
    }

    @Test
    void deliveryProjectionNeverClaimsRecipientDeliveryFromProviderAcceptance() {
        UUID deliveryId = UUID.randomUUID();
        OffsetDateTime acceptedAt = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10);
        var row = delivery(
                deliveryId, "DELIVERED", "provider-message", null, null, 2);
        row = new AdminMailCompletionRepository.DeliveryRow(
                row.id(), row.threadId(), row.messageId(), row.status(), row.attemptCount(),
                row.nextAttemptAt(), row.leaseOwner(), row.leaseExpiresAt(),
                row.providerMessageRef(), "provider-thread", row.errorCode(),
                row.correlationId(), acceptedAt, row.createdAt(), row.actorId(),
                row.updatedAt(), row.version(), row.accountName(), row.providerType());
        when(repository.deliveries(7, "", "", 50, 0)).thenReturn(List.of(row));
        when(repository.deliveryCount(7, "", "")).thenReturn(1L);
        when(repository.recoveryEvents(7, deliveryId)).thenReturn(List.of());

        DeliveryAuditItem item = service.deliveryAudit(7, "", "", 0, 50).items().getFirst();

        assertThat(item.state()).isEqualTo("ACCEPTED_BY_PROVIDER");
        assertThat(item.stage()).isEqualTo("ACCEPTED_BY_PROVIDER");
        assertThat(item.timeline()).noneMatch(event ->
                "DELIVERED_CONFIRMED".equals(event.stage()));
    }

    @Test
    void administratorFingerprintNormalizesTimestampOffsetAndDatabasePrecision() {
        AdminMailCommandFingerprint fingerprint = new AdminMailCommandFingerprint(
                new ObjectMapper().findAndRegisterModules());
        OffsetDateTime seoul = OffsetDateTime.of(
                2026, 9, 17, 11, 12, 13, 123_456_789,
                ZoneOffset.ofHours(9));
        OffsetDateTime persisted = seoul.toInstant()
                .atOffset(ZoneOffset.UTC)
                .withNano(123_456_000);

        assertThat(fingerprint.digest(seoul)).isEqualTo(fingerprint.digest(persisted));
    }

    private AdminMailCompletionRepository.PurgePreviewRow purgePreview(
            UUID snapshotId, String fingerprint) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AdminMailCompletionRepository.PurgePreviewRow(
                snapshotId, 90, Map.of("tenant", true), List.of("MESSAGES", "THREADS"),
                now.minusDays(400), fingerprint, 3, 0, 3, List.of(), 4,
                UUID.randomUUID(), now.plusMinutes(10), now);
    }

    private AdminMailCompletionRepository.PolicyRow policy(long version) {
        return new AdminMailCompletionRepository.PolicyRow(
                true, true, true, true, true, false,
                365, 25, version, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private AdminMailCompletionRepository.PurgeJobRow purgeJob(
            UUID snapshotId, long actorId, UUID key) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AdminMailCompletionRepository.PurgeJobRow(
                UUID.randomUUID(), snapshotId, actorId, "SUCCEEDED", 1, 1,
                List.of(), "VERIFIED", null, key, now.minusSeconds(1), now);
    }

    private AdminMailCompletionRepository.DeliveryRow delivery(
            UUID deliveryId, String state, String providerMessageRef,
            String leaseOwner, OffsetDateTime leaseExpiresAt, long version) {
        return delivery(deliveryId, state, providerMessageRef, leaseOwner,
                leaseExpiresAt, version, "DWP_SANDBOX");
    }

    private AdminMailCompletionRepository.DeliveryRow delivery(
            UUID deliveryId, String state, String providerMessageRef,
            String leaseOwner, OffsetDateTime leaseExpiresAt, long version,
            String providerType) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AdminMailCompletionRepository.DeliveryRow(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), state, 1,
                now, leaseOwner, leaseExpiresAt, providerMessageRef, null,
                "ERR", "corr", null, now.minusMinutes(1), 91,
                now.minusSeconds(10), version, "Support", providerType);
    }
}
