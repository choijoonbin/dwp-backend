package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        verify(repository, never()).insertPurgeJob(anyLong(), any(), anyLong(), any());
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
