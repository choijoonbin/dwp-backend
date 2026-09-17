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
    @SuppressWarnings({"rawtypes", "unchecked"})
    void legalHoldReleasePreviewClassifiesOnlySafeAggregateImpact() {
        UUID holdId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID safeThread = UUID.randomUUID();
        UUID providerThread = UUID.randomUUID();
        UUID immutableThread = UUID.randomUUID();
        UUID otherHoldThread = UUID.randomUUID();
        var hold = legalHoldRow(holdId, "ACTIVE", 3);
        var otherHold = new AdminMailCompletionRepository.LegalHoldRow(
                UUID.randomUUID(), "Other hold", "CASE-OTHER",
                Map.of("threadId", otherHoldThread.toString()), "ACTIVE",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(10), null, 1);
        var candidates = new AdminMailCompletionRepository.CandidateSet(List.of(
                new AdminMailCompletionRepository.CandidateRow(
                        safeThread, accountId, 2, 1, 1, "DWP_SANDBOX", false),
                new AdminMailCompletionRepository.CandidateRow(
                        providerThread, accountId, 3, 0, 0, "MICROSOFT_GRAPH", false),
                new AdminMailCompletionRepository.CandidateRow(
                        immutableThread, accountId, 1, 2, 0, "DWP_SANDBOX", true),
                new AdminMailCompletionRepository.CandidateRow(
                        otherHoldThread, accountId, 4, 1, 2, "DWP_SANDBOX", false)));
        when(repository.legalHold(7, holdId)).thenReturn(Optional.of(hold));
        when(repository.policy(7)).thenReturn(policy(4));
        when(repository.activeLegalHolds(7)).thenReturn(List.of(hold, otherHold));
        when(repository.purgeCandidates(eq(7L), any())).thenReturn(candidates);
        when(repository.insertLegalHoldReleasePreview(
                anyLong(), any(), anyLong(), anyLong(), anyLong(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(previewId);
        when(repository.legalHoldReleasePreview(7, previewId)).thenReturn(Optional.of(
                holdReleasePreview(previewId, holdId, 90, "7".repeat(64))));

        service.previewLegalHoldRelease(
                7, 90, holdId, "corr-preview",
                new LegalHoldReleasePreviewRequest(key, 3L, 4L));

        ArgumentCaptor<Map> affected = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> held = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> safe = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> protectedAfter = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map> providerRequired = ArgumentCaptor.forClass(Map.class);
        verify(repository).insertLegalHoldReleasePreview(
                eq(7L), eq(holdId), eq(90L), eq(3L), eq(4L), eq(hold.scope()),
                any(), any(), any(), affected.capture(), held.capture(), safe.capture(),
                protectedAfter.capture(), providerRequired.capture(), eq(key), any());
        assertThat(affected.getValue()).containsAllEntriesOf(Map.of(
                "THREADS", 4L, "MESSAGES", 10L, "ATTACHMENTS", 4L, "DRAFTS", 3L));
        assertThat(held.getValue()).containsAllEntriesOf(affected.getValue());
        assertThat(safe.getValue()).containsAllEntriesOf(Map.of(
                "THREADS", 1L, "MESSAGES", 2L, "ATTACHMENTS", 1L, "DRAFTS", 1L));
        assertThat(protectedAfter.getValue()).containsAllEntriesOf(Map.of(
                "THREADS", 2L, "MESSAGES", 5L, "ATTACHMENTS", 3L, "DRAFTS", 2L));
        assertThat(providerRequired.getValue()).containsAllEntriesOf(Map.of(
                "THREADS", 1L, "MESSAGES", 3L, "ATTACHMENTS", 0L, "DRAFTS", 0L));
    }

    @Test
    void legalHoldReleaseApprovalRejectsTheRequester() {
        UUID previewId = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        var preview = holdReleasePreview(previewId, UUID.randomUUID(), 90, fingerprint);
        when(repository.legalHoldReleasePreview(7, previewId))
                .thenReturn(Optional.of(preview));

        var request = new LegalHoldReleaseApprovalRequest(
                "APPROVE", UUID.randomUUID(), fingerprint, 3L, 4L);

        assertThatThrownBy(() -> service.approveLegalHoldRelease(
                7, 90, previewId, "corr-self", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("LEGAL_HOLD_RELEASE_SELF_APPROVAL_FORBIDDEN");
        verify(repository, never()).insertLegalHoldReleaseApproval(
                anyLong(), any(), anyLong(), any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void legalHoldReleaseExecutionRequiresADistinctApproval() {
        UUID holdId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        String fingerprint = "b".repeat(64);
        var preview = holdReleasePreview(previewId, holdId, 90, fingerprint);
        var hold = legalHoldRow(holdId, "ACTIVE", 3);
        when(repository.legalHoldReleasePreview(7, previewId))
                .thenReturn(Optional.of(preview));
        when(repository.legalHold(7, holdId)).thenReturn(Optional.of(hold));
        when(repository.policy(7)).thenReturn(policy(4));
        when(repository.legalHoldReleaseApprovals(7, previewId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.executeLegalHoldRelease(
                7, 91, previewId, "corr-no-approval",
                new LegalHoldReleaseExecuteRequest(
                        UUID.randomUUID(), fingerprint, 3L, 4L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("LEGAL_HOLD_RELEASE_APPROVAL_REQUIRED");
        verify(repository, never()).releaseLegalHold(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void legalHoldReleaseExecutionRejectsAStaleImpactSnapshot() {
        UUID holdId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        String fingerprint = "c".repeat(64);
        var preview = holdReleasePreview(previewId, holdId, 90, fingerprint);
        var hold = legalHoldRow(holdId, "ACTIVE", 3);
        var approval = holdReleaseApproval(previewId, 92, fingerprint);
        when(repository.legalHoldReleasePreview(7, previewId))
                .thenReturn(Optional.of(preview));
        when(repository.legalHold(7, holdId)).thenReturn(Optional.of(hold));
        when(repository.policy(7)).thenReturn(policy(4));
        when(repository.legalHoldReleaseApprovals(7, previewId))
                .thenReturn(List.of(approval));
        when(repository.activeLegalHolds(7)).thenReturn(List.of(hold));
        when(repository.purgeCandidates(7, preview.retentionBoundary()))
                .thenReturn(new AdminMailCompletionRepository.CandidateSet(List.of()));

        assertThatThrownBy(() -> service.executeLegalHoldRelease(
                7, 91, previewId, "corr-stale",
                new LegalHoldReleaseExecuteRequest(
                        UUID.randomUUID(), fingerprint, 3L, 4L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("LEGAL_HOLD_RELEASE_PREVIEW_STALE");
        verify(repository, never()).releaseLegalHold(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void legalHoldReleaseDoesNotStartPurgeAndReturnsDurableEvidence() {
        UUID holdId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        long requesterId = 90;
        long approverId = 92;
        long executorId = 91;
        var hold = legalHoldRow(holdId, "ACTIVE", 3);
        OffsetDateTime boundary = OffsetDateTime.now(ZoneOffset.UTC).minusDays(365);
        Map<String, Long> zeros = zeroResourceCounts();
        String fingerprint = new AdminMailCommandFingerprint(
                new ObjectMapper().findAndRegisterModules()).digest(
                "LEGAL_HOLD_RELEASE_SNAPSHOT", 7L, hold.id(), hold.version(),
                hold.scope(), hold.status(), hold.startsAt(), hold.expiresAt(),
                4L, boundary, List.of(), List.of(), zeros, zeros, zeros, zeros, zeros);
        var preview = holdReleasePreview(
                previewId, holdId, requesterId, fingerprint, boundary);
        var approval = holdReleaseApproval(previewId, approverId, fingerprint);
        var released = new AdminMailCompletionRepository.LegalHoldRow(
                hold.id(), hold.name(), hold.caseRef(), hold.scope(), "RELEASED",
                hold.startsAt(), hold.expiresAt(), 4);
        when(repository.legalHoldReleasePreview(7, previewId))
                .thenReturn(Optional.of(preview));
        when(repository.legalHold(7, holdId))
                .thenReturn(Optional.of(hold), Optional.of(released));
        when(repository.policy(7)).thenReturn(policy(4));
        when(repository.legalHoldReleaseApprovals(7, previewId))
                .thenReturn(List.of(approval));
        when(repository.activeLegalHolds(7)).thenReturn(List.of(hold));
        when(repository.purgeCandidates(7, boundary))
                .thenReturn(new AdminMailCompletionRepository.CandidateSet(List.of()));
        when(repository.releaseLegalHold(7, holdId, 3, executorId)).thenReturn(1);
        when(repository.insertLegalHoldReleaseExecution(
                eq(7L), eq(previewId), eq(holdId), eq(requesterId), eq(approverId),
                eq(executorId), eq(3L), eq(4L), eq(fingerprint), any(), any()))
                .thenReturn(executionId);

        LegalHoldReleaseExecution result = service.executeLegalHoldRelease(
                7, executorId, previewId, "corr-release",
                new LegalHoldReleaseExecuteRequest(
                        UUID.randomUUID(), fingerprint, 3L, 4L));

        assertThat(result.executionId()).isEqualTo(executionId);
        assertThat(result.hold().status()).isEqualTo("RELEASED");
        assertThat(result.replayed()).isFalse();
        verify(repository, never()).insertPurgeJob(
                anyLong(), any(), anyLong(), any(), any(), any());
        verify(repository, never()).deletePurgeCandidates(
                anyLong(), org.mockito.ArgumentMatchers.<List<UUID>>any());
    }

    @Test
    void legalHoldReleaseExecutionReplayIsIdempotent() {
        UUID holdId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String fingerprint = "d".repeat(64);
        long executorId = 91;
        String requestFingerprint = new AdminMailCommandFingerprint(
                new ObjectMapper().findAndRegisterModules()).digest(
                "LEGAL_HOLD_RELEASE_EXECUTE", executorId, previewId,
                fingerprint, 3L, 4L);
        var execution = new AdminMailCompletionRepository.LegalHoldReleaseExecutionRow(
                UUID.randomUUID(), previewId, holdId, 90, 92, executorId,
                3, 4, 4, fingerprint, requestFingerprint, key,
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(repository.legalHoldReleaseExecutionByCommand(7, executorId, key))
                .thenReturn(Optional.of(execution));
        when(repository.legalHold(7, holdId)).thenReturn(Optional.of(
                legalHoldRow(holdId, "RELEASED", 4)));

        LegalHoldReleaseExecution result = service.executeLegalHoldRelease(
                7, executorId, previewId, "corr-replay",
                new LegalHoldReleaseExecuteRequest(key, fingerprint, 3L, 4L));

        assertThat(result.replayed()).isTrue();
        verify(repository, never()).releaseLegalHold(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void legalHoldReleasePreviewCannotBeExecutedByASecondCommand() {
        UUID holdId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        String fingerprint = "9".repeat(64);
        var existing = new AdminMailCompletionRepository.LegalHoldReleaseExecutionRow(
                UUID.randomUUID(), previewId, holdId, 90, 92, 93,
                3, 4, 4, fingerprint, "8".repeat(64), UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        when(repository.legalHoldReleaseExecutionByPreview(7, previewId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.executeLegalHoldRelease(
                7, 91, previewId, "corr-duplicate",
                new LegalHoldReleaseExecuteRequest(
                        UUID.randomUUID(), fingerprint, 3L, 4L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("LEGAL_HOLD_RELEASE_PREVIEW_ALREADY_EXECUTED");
        verify(repository, never()).releaseLegalHold(anyLong(), any(), anyLong(), anyLong());
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
        verify(repository, never()).deletePurgeCandidates(anyLong(),
                org.mockito.ArgumentMatchers.<List<UUID>>any());
    }

    @Test
    void activeLegalHoldCannotReduceItsProtectedSelectorSet() {
        UUID holdId = UUID.randomUUID();
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var current = new AdminMailCompletionRepository.LegalHoldRow(
                holdId, "Investigation hold", "CASE-REDACTED",
                Map.of("accountIds", List.of(accountA.toString(), accountB.toString())),
                "ACTIVE", now.minusDays(30), null, 3);
        when(repository.claimAdminReceipt(
                eq(7L), eq(91L), eq("LEGAL_HOLD_UPDATE"), any(), any(), eq("corr-hold")))
                .thenReturn(true);
        when(repository.legalHold(7, holdId)).thenReturn(Optional.of(current));
        LegalHoldRequest request = new LegalHoldRequest(
                "Investigation hold", "CASE-REDACTED",
                Map.of("accountIds", List.of(accountA.toString())),
                current.startsAt(), null, UUID.randomUUID(), 3L);

        assertThatThrownBy(() -> service.updateLegalHold(
                7, 91, holdId, "corr-hold", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");

        verify(repository, never()).updateLegalHold(
                anyLong(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void activeLegalHoldCannotShortenItsProtectedTimeWindow() {
        UUID holdId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var current = new AdminMailCompletionRepository.LegalHoldRow(
                holdId, "Investigation hold", "CASE-REDACTED", Map.of("tenant", true),
                "ACTIVE", now.minusDays(30), null, 3);
        when(repository.claimAdminReceipt(
                eq(7L), eq(91L), eq("LEGAL_HOLD_UPDATE"), any(), any(), eq("corr-hold")))
                .thenReturn(true);
        when(repository.legalHold(7, holdId)).thenReturn(Optional.of(current));
        LegalHoldRequest request = new LegalHoldRequest(
                "Investigation hold", "CASE-REDACTED", Map.of("tenant", true),
                current.startsAt().plusDays(1), now.plusDays(1), UUID.randomUUID(), 3L);

        assertThatThrownBy(() -> service.updateLegalHold(
                7, 91, holdId, "corr-hold", request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");

        verify(repository, never()).updateLegalHold(
                anyLong(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong());
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
        verify(repository, never()).deletePurgeCandidates(anyLong(),
                org.mockito.ArgumentMatchers.<List<UUID>>any());
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
        verify(repository, never()).deletePurgeCandidates(anyLong(),
                org.mockito.ArgumentMatchers.<List<UUID>>any());
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
    void revokedSendAuthorizationIsProjectedAndRecoveryIsPersistentlyBlocked() {
        UUID deliveryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var row = new AdminMailCompletionRepository.DeliveryRow(
                deliveryId, UUID.randomUUID(), UUID.randomUUID(), "FAILED", 1,
                now, null, null, null, null, "MAIL_SEND_AUTHORIZATION_REVOKED", "corr",
                null, now.minusMinutes(1), 91, now.minusSeconds(10), 8,
                "Support", "DWP_SANDBOX");
        when(repository.deliveries(7, "", "", 50, 0)).thenReturn(List.of(row));
        when(repository.deliveryCount(7, "", "")).thenReturn(1L);
        when(repository.recoveryEvents(7, deliveryId)).thenReturn(List.of());

        DeliveryAuditItem projection = service.deliveryAudit(
                7, "", "", 0, 50).items().getFirst();

        assertThat(projection.stage()).isEqualTo("BLOCKED_BY_ACCESS");
        assertThat(projection.state()).isEqualTo("BLOCKED_BY_ACCESS");
        assertThat(projection.retryEligibility()).isEqualTo("INELIGIBLE");

        when(repository.recoveryCommand(7, 91, key)).thenReturn(Optional.empty());
        when(repository.delivery(7, deliveryId)).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.recoverDelivery(
                7, 91, deliveryId, "RETRY", "corr-blocked",
                new DeliveryRecoveryRequest(key, 8L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MAIL_SEND_AUTHORIZATION_REVOKED");

        verify(repository).insertRecoveryEvent(
                eq(7L), eq(deliveryId), eq(91L), eq("RETRY"), eq("BLOCKED"),
                any(), eq(key), any(), eq("corr-blocked"));
        verify(repository, never()).retryDelivery(anyLong(), any(), anyLong());
    }

    @Test
    void recoveryPersistsAConcurrentAuthorizationRevocationAsTerminalAccessBlock() {
        UUID deliveryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        var row = delivery(deliveryId, "FAILED", null, null, null, 8);
        when(repository.recoveryCommand(7, 91, key)).thenReturn(Optional.empty());
        when(repository.delivery(7, deliveryId)).thenReturn(Optional.of(row));
        when(repository.deliveryAuthorization(7, deliveryId)).thenReturn(Optional.empty());
        when(repository.blockDeliveryAccess(
                7, deliveryId, 8, "MAIL_SEND_AUTHORIZATION_REVOKED")).thenReturn(1);

        assertThatThrownBy(() -> service.recoverDelivery(
                7, 91, deliveryId, "RETRY", "corr-revoked",
                new DeliveryRecoveryRequest(key, 8L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("MAIL_SEND_AUTHORIZATION_REVOKED");

        verify(repository).blockDeliveryAccess(
                7, deliveryId, 8, "MAIL_SEND_AUTHORIZATION_REVOKED");
        verify(repository).insertRecoveryEvent(
                eq(7L), eq(deliveryId), eq(91L), eq("RETRY"), eq("BLOCKED"),
                any(), eq(key), any(), eq("corr-revoked"));
        verify(repository, never()).retryDelivery(anyLong(), any(), anyLong());
    }

    @Test
    void successfulRecoveryResponseIsRedactedWithoutBothAuditPermissions() {
        UUID deliveryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        var before = delivery(deliveryId, "FAILED", null, null, null, 8);
        var after = delivery(deliveryId, "RETRY_WAIT", null, null, null, 9);
        when(repository.recoveryCommand(7, 91, key)).thenReturn(Optional.empty());
        when(repository.delivery(7, deliveryId))
                .thenReturn(Optional.of(before), Optional.of(after));
        when(repository.deliveryAuthorization(7, deliveryId)).thenReturn(Optional.of(
                new AdminMailCompletionRepository.DeliveryAuthorizationRow(
                        UUID.randomUUID(), UUID.randomUUID(), "DWP_SANDBOX", null,
                        "mail.example", 91, "ACCOUNT", "TEXT", false, 0)));
        when(repository.retryDelivery(7, deliveryId, 8)).thenReturn(1);
        when(repository.recoveryEvents(7, deliveryId)).thenReturn(List.of(
                new AdminMailCompletionRepository.RecoveryRow(
                        UUID.randomUUID(), "RETRY", "SUCCEEDED", Map.of(),
                        "private-correlation", OffsetDateTime.now(ZoneOffset.UTC))));
        when(repository.deliveryEvidence(eq(7L), any())).thenReturn(List.of(
                new AdminMailCompletionRepository.DeliveryEvidenceRow(
                        "PROVIDER_RECEIPT", "SUCCEEDED", "private-code",
                        OffsetDateTime.now(ZoneOffset.UTC), "provider-private", "VERIFIED")));

        DeliveryAuditItem response = service.recoverDelivery(
                7, 91, deliveryId, "RETRY", "corr-action-only",
                new DeliveryRecoveryRequest(key, 8L), false);

        assertRedactedDelivery(response);
        verify(repository).retryDelivery(7, deliveryId, 8);
    }

    @Test
    void recoveryReplayResponseRemainsRedactedWithoutBothAuditPermissions() {
        UUID deliveryId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String fingerprint = new AdminMailCommandFingerprint(
                new ObjectMapper().findAndRegisterModules()).digest(
                "DELIVERY_RECOVERY", 91L, deliveryId, "RETRY", 8L);
        when(repository.recoveryCommand(7, 91, key)).thenReturn(Optional.of(
                new AdminMailCompletionRepository.RecoveryCommandRow(
                        UUID.randomUUID(), deliveryId, "RETRY", "SUCCEEDED", fingerprint)));
        when(repository.delivery(7, deliveryId)).thenReturn(Optional.of(
                delivery(deliveryId, "RETRY_WAIT", null, null, null, 9)));
        when(repository.recoveryEvents(7, deliveryId)).thenReturn(List.of());
        when(repository.deliveryEvidence(eq(7L), any())).thenReturn(List.of(
                new AdminMailCompletionRepository.DeliveryEvidenceRow(
                        "PROVIDER_RECEIPT", "SUCCEEDED", "private-code",
                        OffsetDateTime.now(ZoneOffset.UTC), "provider-private", "VERIFIED")));

        DeliveryAuditItem response = service.recoverDelivery(
                7, 91, deliveryId, "RETRY", "corr-replay",
                new DeliveryRecoveryRequest(key, 8L), false);

        assertRedactedDelivery(response);
        verify(repository, never()).deliveryAuthorization(anyLong(), any());
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
    void actionOnlyPurgeOperatorReceivesMinimumRedactedCandidateEvidence() {
        UUID snapshotId = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        var row = purgePreview(snapshotId, fingerprint);
        when(repository.activePurgePreviews(7, 50)).thenReturn(List.of(row));
        when(repository.purgeCandidateRows(7, snapshotId)).thenReturn(List.of());

        PurgePreview projection = service.activePurgePreviews(7, false).getFirst();

        assertThat(projection.candidateSnapshotId()).isEqualTo(snapshotId);
        assertThat(projection.fingerprint()).isEqualTo(fingerprint);
        assertThat(projection.totalCandidates()).isEqualTo(3);
        assertThat(projection.policyVersion()).isEqualTo(4);
        assertThat(projection.resourceTypes()).containsExactly("MESSAGES", "THREADS");
        assertThat(projection.scope()).isEmpty();
        assertThat(projection.before()).isNull();
        assertThat(projection.resourceCounts()).isEmpty();
        assertThat(projection.heldResourceCounts()).isEmpty();
        assertThat(projection.exclusionReasonCounts()).isEmpty();
    }

    @Test
    void actionOnlyPurgeOperatorCannotReadJobStepPayloads() {
        UUID snapshotId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var row = new AdminMailCompletionRepository.PurgeJobRow(
                jobId, snapshotId, 90, "FAILED", 2, 3,
                List.of(Map.of("providerPayload", "sensitive")), "UNVERIFIED",
                "PROVIDER_SECRET_DETAIL", UUID.randomUUID(), now.minusMinutes(1), now);
        when(repository.purgeJob(7, jobId)).thenReturn(Optional.of(row));

        PurgeJob projection = service.purgeJob(7, jobId, false);

        assertThat(projection.jobId()).isEqualTo(jobId);
        assertThat(projection.candidateSnapshotId()).isEqualTo(snapshotId);
        assertThat(projection.state()).isEqualTo("FAILED");
        assertThat(projection.stepResults()).isEmpty();
        assertThat(projection.errorCode()).isNull();
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

    private AdminMailCompletionRepository.LegalHoldReleasePreviewRow holdReleasePreview(
            UUID previewId, UUID holdId, long requesterId, String fingerprint) {
        return holdReleasePreview(
                previewId, holdId, requesterId, fingerprint,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(365));
    }

    private AdminMailCompletionRepository.LegalHoldReleasePreviewRow holdReleasePreview(
            UUID previewId, UUID holdId, long requesterId, String fingerprint,
            OffsetDateTime boundary) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, Object> zeros = Map.of(
                "THREADS", 0L, "MESSAGES", 0L,
                "ATTACHMENTS", 0L, "DRAFTS", 0L);
        return new AdminMailCompletionRepository.LegalHoldReleasePreviewRow(
                previewId, holdId, requesterId, 3, 4, Map.of("tenant", true),
                boundary, fingerprint, "e".repeat(64), zeros, zeros, zeros,
                zeros, zeros, UUID.randomUUID(), now, now.plusMinutes(10));
    }

    private AdminMailCompletionRepository.LegalHoldReleaseApprovalRow holdReleaseApproval(
            UUID previewId, long approverId, String fingerprint) {
        return new AdminMailCompletionRepository.LegalHoldReleaseApprovalRow(
                UUID.randomUUID(), previewId, approverId, "APPROVE", 3, 4,
                fingerprint, "f".repeat(64), UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private AdminMailCompletionRepository.LegalHoldRow legalHoldRow(
            UUID holdId, String state, long version) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AdminMailCompletionRepository.LegalHoldRow(
                holdId, "Investigation hold", "CASE-REDACTED",
                Map.of("tenant", true), state, now.minusDays(30), null, version);
    }

    private Map<String, Long> zeroResourceCounts() {
        return Map.of(
                "THREADS", 0L, "MESSAGES", 0L,
                "ATTACHMENTS", 0L, "DRAFTS", 0L);
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

    private void assertRedactedDelivery(DeliveryAuditItem response) {
        assertThat(response.actorName()).isEqualTo("REDACTED");
        assertThat(response.accountName()).isEqualTo("REDACTED");
        assertThat(response.providerType()).isEqualTo("REDACTED");
        assertThat(response.correlationId()).isEmpty();
        assertThat(response.timeline()).isNotEmpty().allSatisfy(event -> {
            assertThat(event.source()).isEqualTo("EVIDENCE_REDACTED");
            assertThat(event.code()).isNull();
        });
    }
}
