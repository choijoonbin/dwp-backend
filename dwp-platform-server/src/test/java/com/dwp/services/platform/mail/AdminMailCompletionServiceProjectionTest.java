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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMailCompletionServiceProjectionTest {

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

    private AdminMailCompletionRepository.PurgePreviewRow purgePreview(
            UUID snapshotId, String fingerprint) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new AdminMailCompletionRepository.PurgePreviewRow(
                snapshotId, 90, Map.of("tenant", true), List.of("MESSAGES", "THREADS"),
                now.minusDays(400), fingerprint, 3, 0, 3, List.of(), 4,
                UUID.randomUUID(), now.plusMinutes(10), now);
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
