package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.RetentionHealth;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingIntelligenceRetentionServiceTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 28, 9, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void recentSuccessfulWorkerIsReady() {
        Fixture fixture = fixture();
        when(fixture.repository.retentionHealth()).thenReturn(Optional.of(
                health(NOW.minusMinutes(1), null)));

        assertThat(fixture.service.ready()).isTrue();
    }

    @Test
    void missingWorkerSuccessFailsClosed() {
        Fixture fixture = fixture();
        when(fixture.repository.retentionHealth()).thenReturn(Optional.of(
                health(null, null)));

        assertThat(fixture.service.ready()).isFalse();
    }

    @Test
    void staleWorkerSuccessFailsClosed() {
        Fixture fixture = fixture();
        when(fixture.repository.retentionHealth()).thenReturn(Optional.of(
                health(NOW.minusMinutes(16), null)));

        assertThat(fixture.service.ready()).isFalse();
    }

    @Test
    void failureAfterLastSuccessFailsClosed() {
        Fixture fixture = fixture();
        when(fixture.repository.retentionHealth()).thenReturn(Optional.of(
                health(NOW.minusMinutes(2), NOW.minusMinutes(1))));

        assertThat(fixture.service.ready()).isFalse();
    }

    @Test
    void databaseProbeFailureFailsClosed() {
        Fixture fixture = fixture();
        when(fixture.repository.retentionHealth()).thenThrow(new IllegalStateException());

        assertThat(fixture.service.ready()).isFalse();
    }

    @Test
    void disabledWorkerIsNeverReady() {
        Fixture fixture = fixture();
        fixture.properties.setEnabled(false);

        assertThat(fixture.service.ready()).isFalse();
        verify(fixture.repository, never()).retentionHealth();
    }

    @Test
    void successfulPurgeRecordsAttemptAndSuccess() {
        Fixture fixture = fixture();
        when(fixture.transactions.purgeAndSucceed(any(), anyInt(), anyString(), any()))
                .thenReturn(new VideoMeetingIntelligenceModels.RetentionPurgeResult(3, false));
        when(fixture.transactions.attempt(any(), any(), any())).thenReturn(true);

        assertThat(fixture.service.purgeExpired()).isEqualTo(3);
        verify(fixture.transactions).attempt(any(), any(), any(UUID.class));
        verify(fixture.transactions, never()).fail(any(), any(UUID.class));
    }

    @Test
    void purgePermissionFailureRecordsFailureAndBlocksNewRuns() {
        Fixture fixture = fixture();
        when(fixture.transactions.attempt(any(), any(), any())).thenReturn(true);
        when(fixture.transactions.purgeAndSucceed(any(), anyInt(), anyString(), any()))
                .thenThrow(new IllegalStateException("permission denied"));
        when(fixture.repository.retentionHealth()).thenReturn(Optional.of(
                health(NOW.minusMinutes(1), null)));

        assertThat(fixture.service.purgeExpired()).isEqualTo(-1);
        verify(fixture.transactions).fail(any(), any(UUID.class));
        assertThat(fixture.service.ready()).isFalse();
    }

    @Test
    void disabledWorkerDoesNotAttemptPurge() {
        Fixture fixture = fixture();
        fixture.properties.setEnabled(false);

        assertThat(fixture.service.purgeExpired()).isZero();
        verify(fixture.transactions, never()).attempt(any(), any(), any(UUID.class));
    }

    @Test
    void anotherWorkerHoldingTheLeaseSkipsWithoutClaimingSuccess() {
        Fixture fixture = fixture();
        when(fixture.transactions.attempt(any(), any(), any())).thenReturn(false);

        assertThat(fixture.service.purgeExpired()).isZero();
        verify(fixture.transactions, never()).purgeAndSucceed(
                any(), anyInt(), anyString(), any());
    }

    private RetentionHealth health(
            OffsetDateTime success, OffsetDateTime failure) {
        return new RetentionHealth(
                success, success, failure,
                failure == null ? null : "RETENTION_PURGE_FAILED", null, null, 1);
    }

    private Fixture fixture() {
        VideoMeetingIntelligenceRepository repository =
                mock(VideoMeetingIntelligenceRepository.class);
        MeetingIntelligenceRetentionTransactions transactions =
                mock(MeetingIntelligenceRetentionTransactions.class);
        MeetingIntelligenceRetentionProperties properties =
                new MeetingIntelligenceRetentionProperties();
        properties.setEnabled(true);
        properties.setBatchSize(100);
        properties.setPollDelay(Duration.ofMinutes(5));
        properties.setLeaseDuration(Duration.ofMinutes(1));
        properties.setWorkerId("retention-worker-1");
        MeetingIntelligenceRetentionService service =
                new MeetingIntelligenceRetentionService(
                        repository, transactions, properties,
                        Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        return new Fixture(repository, transactions, properties, service);
    }

    private record Fixture(
            VideoMeetingIntelligenceRepository repository,
            MeetingIntelligenceRetentionTransactions transactions,
            MeetingIntelligenceRetentionProperties properties,
            MeetingIntelligenceRetentionService service) {
    }
}
