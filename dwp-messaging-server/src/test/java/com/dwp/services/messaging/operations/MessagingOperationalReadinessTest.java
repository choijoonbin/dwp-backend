package com.dwp.services.messaging.operations;

import com.dwp.services.messaging.attachment.AttachmentProperties;
import com.dwp.services.messaging.attachment.AttachmentScanner;
import com.dwp.services.messaging.attachment.AttachmentStorage;
import com.dwp.services.messaging.domain.MessagingDtos;
import com.dwp.services.messaging.meeting.MeetingProvider;
import com.dwp.services.messaging.meeting.MeetingProviderCapability;
import com.dwp.services.messaging.presence.MessagingPresenceHealthIndicator;
import com.dwp.services.messaging.presence.MessagingPresenceService;
import com.dwp.services.messaging.realtime.MessagingRealtimeFanoutProbe;
import com.dwp.services.messaging.retention.MessagingRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagingOperationalReadinessTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime VERIFIED_AT = OffsetDateTime.now(CLOCK);

    private AttachmentStorage storage;
    private AttachmentScanner scanner;
    private MeetingProvider meeting;
    private MessagingPresenceService presence;
    private MessagingPresenceHealthIndicator presenceHealth;
    private MessagingRetentionService retention;
    private MessagingScheduledDeliveryOperations scheduled;
    private MessagingDeliveryReadinessOperations delivery;
    private MessagingRealtimeFanoutProbe fanout;

    @BeforeEach
    void setUp() {
        storage = mock(AttachmentStorage.class);
        scanner = mock(AttachmentScanner.class);
        meeting = mock(MeetingProvider.class);
        presence = mock(MessagingPresenceService.class);
        presenceHealth = mock(MessagingPresenceHealthIndicator.class);
        retention = mock(MessagingRetentionService.class);
        scheduled = mock(MessagingScheduledDeliveryOperations.class);
        delivery = mock(MessagingDeliveryReadinessOperations.class);
        fanout = mock(MessagingRealtimeFanoutProbe.class);
        when(meeting.capability()).thenReturn(new MeetingProviderCapability(
                false, "disabled", "MEETING_PROVIDER_DISABLED",
                false, false, false, false, 0));
        when(presence.enabled()).thenReturn(true);
        when(retention.status(91)).thenReturn(new MessagingRetentionService.RetentionStatus(
                true, "ENFORCING", null, VERIFIED_AT, VERIFIED_AT, "COMPLETED",
                "ENFORCING", 4, 4, 0, 0));
        when(scheduled.status(91)).thenReturn(
                new MessagingScheduledDeliveryOperations.ScheduledDeliveryStatus(
                        true, true, "READY", null, VERIFIED_AT, null,
                        0, 0, 0, null));
        when(delivery.evidence(91)).thenReturn(
                new MessagingDeliveryReadinessOperations.DeliveryEvidence(
                        new MessagingDeliveryReadinessOperations.DurableLogStatus(
                                true, "READY", null, VERIFIED_AT),
                        new MessagingDeliveryReadinessOperations.NotificationOutboxStatus(
                                true, true, "READY", null, VERIFIED_AT, 0)));
        when(fanout.configured()).thenReturn(true);
        when(fanout.probe(VERIFIED_AT)).thenReturn(new MessagingRealtimeFanoutProbe.ProbeResult(
                true, true, "READY", null, VERIFIED_AT));
    }

    @Test
    void reportsNeverVerifiedProvidersAsUnavailableInsteadOfReady() {
        MessagingOperationalReadiness readiness = readiness();

        MessagingDtos.RuntimeReadiness snapshot = readiness.snapshot(91);

        assertThat(capability(snapshot, "ATTACHMENT_STORAGE"))
                .extracting(
                        MessagingDtos.RuntimeCapability::effective,
                        MessagingDtos.RuntimeCapability::state,
                        MessagingDtos.RuntimeCapability::freshness)
                .containsExactly(false, "UNAVAILABLE", "NEVER");
        assertThat(snapshot.overallState()).isEqualTo("BLOCKED");
    }

    @Test
    void exposesActualProbeEvidenceAndKeepsDevelopmentProvidersDegraded() {
        when(storage.health()).thenReturn(new AttachmentStorage.ProviderHealth(
                true, false, "LOCAL_STORAGE_DEVELOPMENT_ONLY"));
        when(scanner.health()).thenReturn(new AttachmentScanner.ProviderHealth(
                true, true, null));
        when(presenceHealth.health()).thenReturn(Health.up().build());
        MessagingOperationalReadiness readiness = readiness();

        readiness.refreshProviderEvidence();
        MessagingDtos.RuntimeReadiness snapshot = readiness.snapshot(91);

        MessagingDtos.RuntimeCapability storageCapability =
                capability(snapshot, "ATTACHMENT_STORAGE");
        assertThat(storageCapability.provider()).isEqualTo("local");
        assertThat(storageCapability.effective()).isTrue();
        assertThat(storageCapability.state()).isEqualTo("DEGRADED");
        assertThat(storageCapability.reasonCode())
                .isEqualTo("LOCAL_STORAGE_DEVELOPMENT_ONLY");
        assertThat(storageCapability.freshness()).isEqualTo("CURRENT");
        assertThat(capability(snapshot, "ATTACHMENT_SCANNER").state()).isEqualTo("READY");
        assertThat(capability(snapshot, "PRESENCE").state()).isEqualTo("READY");
        assertThat(capability(snapshot, "REALTIME_FANOUT").state()).isEqualTo("READY");
        assertThat(capability(snapshot, "DURABLE_COMMIT").state()).isEqualTo("READY");
        assertThat(capability(snapshot, "SSE_RESUME").state()).isEqualTo("READY");
        assertThat(capability(snapshot, "NOTIFICATION_OUTBOX").state()).isEqualTo("READY");
        assertThat(capability(snapshot, "RETENTION_ENFORCEMENT").state()).isEqualTo("READY");
        assertThat(capability(snapshot, "SCHEDULED_DELIVERY").state()).isEqualTo("READY");
    }

    @Test
    void providerHealthContractDoesNotPromoteUnverifiedConfiguration() {
        MessagingOperationalReadiness readiness = readiness();

        Health health = new MessagingProviderReadinessHealthIndicator(readiness).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("overallState", "BLOCKED");
    }

    @Test
    void dryRunSuccessCannotPromoteEnforcingRuntimeToReady() {
        when(retention.status(91)).thenReturn(new MessagingRetentionService.RetentionStatus(
                true, "ENFORCING", null, VERIFIED_AT, VERIFIED_AT, "COMPLETED",
                "DRY_RUN", 4, 0, 0, 0));
        MessagingOperationalReadiness readiness = readiness();

        MessagingDtos.RuntimeCapability capability = capability(
                readiness.snapshot(91), "RETENTION_ENFORCEMENT");

        assertThat(capability.effective()).isFalse();
        assertThat(capability.state()).isEqualTo("DEGRADED");
        assertThat(capability.reasonCode()).isEqualTo("RETENTION_AWAITING_ENFORCING_SUCCESS");
    }

    @Test
    void durableLogFailureBlocksCommitAndReplayWithoutClaimingFanoutFailure() {
        when(delivery.evidence(91)).thenReturn(
                new MessagingDeliveryReadinessOperations.DeliveryEvidence(
                        new MessagingDeliveryReadinessOperations.DurableLogStatus(
                                false, "BLOCKED", "DURABLE_EVENT_LOG_UNAVAILABLE", VERIFIED_AT),
                        new MessagingDeliveryReadinessOperations.NotificationOutboxStatus(
                                true, true, "READY", null, VERIFIED_AT, 0)));
        MessagingOperationalReadiness readiness = readiness();

        MessagingDtos.RuntimeReadiness snapshot = readiness.snapshot(91);

        assertThat(capability(snapshot, "DURABLE_COMMIT").reasonCode())
                .isEqualTo("DURABLE_EVENT_LOG_UNAVAILABLE");
        assertThat(capability(snapshot, "SSE_RESUME").reasonCode())
                .isEqualTo("SSE_REPLAY_LOG_UNAVAILABLE");
        assertThat(snapshot.overallState()).isEqualTo("BLOCKED");
    }

    @Test
    void fanoutRequiresRoundTripEvidenceButRemainsAnOptionalAccelerator() {
        when(fanout.probe(VERIFIED_AT)).thenReturn(new MessagingRealtimeFanoutProbe.ProbeResult(
                true, false, "DEGRADED", "REALTIME_FANOUT_ACK_TIMEOUT", VERIFIED_AT));
        when(storage.health()).thenReturn(new AttachmentStorage.ProviderHealth(true, true, null));
        when(scanner.health()).thenReturn(new AttachmentScanner.ProviderHealth(true, true, null));
        when(presenceHealth.health()).thenReturn(Health.up().build());
        MessagingOperationalReadiness readiness = readiness();

        readiness.refreshProviderEvidence();
        MessagingDtos.RuntimeReadiness snapshot = readiness.snapshot(91);

        assertThat(capability(snapshot, "REALTIME_FANOUT"))
                .extracting(
                        MessagingDtos.RuntimeCapability::required,
                        MessagingDtos.RuntimeCapability::effective,
                        MessagingDtos.RuntimeCapability::state,
                        MessagingDtos.RuntimeCapability::reasonCode)
                .containsExactly(false, false, "DEGRADED", "REALTIME_FANOUT_ACK_TIMEOUT");
    }

    @Test
    void fallbackSearchTelemetryAndMeetingNeverClaimUnverifiedReadiness() {
        when(meeting.capability()).thenReturn(new MeetingProviderCapability(
                true, "livekit", null, true, true, true, true, 300));

        MessagingDtos.RuntimeReadiness snapshot = readiness().snapshot(91);

        assertThat(capability(snapshot, "SEARCH"))
                .extracting(
                        MessagingDtos.RuntimeCapability::required,
                        MessagingDtos.RuntimeCapability::configured,
                        MessagingDtos.RuntimeCapability::effective,
                        MessagingDtos.RuntimeCapability::state,
                        MessagingDtos.RuntimeCapability::reasonCode)
                .containsExactly(true, true, true, "DEGRADED", "SQL_FALLBACK_ACTIVE");
        assertThat(capability(snapshot, "OBSERVABILITY"))
                .extracting(
                        MessagingDtos.RuntimeCapability::required,
                        MessagingDtos.RuntimeCapability::configured,
                        MessagingDtos.RuntimeCapability::effective,
                        MessagingDtos.RuntimeCapability::state,
                        MessagingDtos.RuntimeCapability::reasonCode)
                .containsExactly(
                        true, true, false, "DEGRADED", "TELEMETRY_EXPORT_UNVERIFIED");
        assertThat(capability(snapshot, "MEETING"))
                .extracting(
                        MessagingDtos.RuntimeCapability::required,
                        MessagingDtos.RuntimeCapability::configured,
                        MessagingDtos.RuntimeCapability::effective,
                        MessagingDtos.RuntimeCapability::state,
                        MessagingDtos.RuntimeCapability::reasonCode)
                .containsExactly(
                        false, true, false, "DEGRADED",
                        "MEETING_PROVIDER_CONNECTIVITY_UNVERIFIED");
    }

    @Test
    void providerProbeFailuresAreConvertedToFailClosedCapabilities() {
        doThrow(new IllegalStateException("storage unavailable")).when(storage).health();
        doThrow(new IllegalStateException("scanner unavailable")).when(scanner).health();
        when(presenceHealth.health()).thenReturn(Health.unknown().build());
        doThrow(new IllegalStateException("fanout unavailable")).when(fanout).probe(VERIFIED_AT);
        MessagingOperationalReadiness readiness = readiness();

        readiness.refreshProviderEvidence();
        MessagingDtos.RuntimeReadiness snapshot = readiness.snapshot(91);

        assertThat(capability(snapshot, "ATTACHMENT_STORAGE").reasonCode())
                .isEqualTo("ATTACHMENT_STORAGE_PROBE_FAILED");
        assertThat(capability(snapshot, "ATTACHMENT_SCANNER").reasonCode())
                .isEqualTo("ATTACHMENT_SCANNER_PROBE_FAILED");
        assertThat(capability(snapshot, "PRESENCE").reasonCode())
                .isEqualTo("PRESENCE_AUTHORITY_UNAVAILABLE");
        assertThat(capability(snapshot, "REALTIME_FANOUT").reasonCode())
                .isEqualTo("REALTIME_FANOUT_PROBE_FAILED");
        assertThat(snapshot.overallState()).isEqualTo("BLOCKED");
    }

    @Test
    void appliesConfiguredRetentionEvidenceMaximumAgeToAdminReadiness() {
        OffsetDateTime staleEvidence = VERIFIED_AT.minusMinutes(30);
        when(retention.status(91)).thenReturn(new MessagingRetentionService.RetentionStatus(
                true, "ENFORCING", null, staleEvidence, staleEvidence, "COMPLETED",
                "ENFORCING", 4, 4, 0, 0));

        MessagingDtos.RuntimeCapability retentionCapability = capability(
                readiness(Duration.ofMinutes(15)).snapshot(91), "RETENTION_ENFORCEMENT");

        assertThat(retentionCapability)
                .extracting(
                        MessagingDtos.RuntimeCapability::effective,
                        MessagingDtos.RuntimeCapability::state,
                        MessagingDtos.RuntimeCapability::reasonCode,
                        MessagingDtos.RuntimeCapability::freshness)
                .containsExactly(false, "DEGRADED", "RETENTION_EVIDENCE_STALE", "STALE");
    }

    @Test
    void rejectsUnsafeRetentionEvidenceMaximumAge() {
        assertThatThrownBy(() -> readiness(Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 minute to 24 hours");
        assertThatThrownBy(() -> readiness(Duration.ofHours(25)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 minute to 24 hours");
    }

    private MessagingOperationalReadiness readiness() {
        return readiness(Duration.ofHours(2));
    }

    private MessagingOperationalReadiness readiness(Duration retentionEvidenceMaximumAge) {
        AttachmentProperties properties = new AttachmentProperties(
                "local", Path.of(".dwp-data/messaging-attachments"), "clamav",
                Duration.ofMinutes(10), Duration.ofMinutes(1), 100, 2,
                "localhost", 3310, Duration.ofSeconds(10));
        return new MessagingOperationalReadiness(
                storage, scanner, properties, meeting, presence, presenceHealth, retention,
                scheduled, delivery, fanout,
                true, Duration.ofMinutes(3), retentionEvidenceMaximumAge, CLOCK);
    }

    private MessagingDtos.RuntimeCapability capability(
            MessagingDtos.RuntimeReadiness readiness, String name) {
        return readiness.capabilities().stream()
                .filter(capability -> name.equals(capability.capability()))
                .findFirst()
                .orElseThrow();
    }
}
