package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MeetingPreparationMaterialRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-05T03:00:00Z");
    private static final OffsetDateTime NOW_OFFSET = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void configuredPollDelayControlsFreshHeartbeatAndDatabaseFailuresFailClosed() {
        MeetingPreparationMaterialRetentionTransactions transactions =
                mock(MeetingPreparationMaterialRetentionTransactions.class);
        MeetingPreparationMaterialRetentionService retention =
                new MeetingPreparationMaterialRetentionService(
                        transactions, CLOCK, Duration.ofMinutes(5));
        OffsetDateTime threshold = NOW_OFFSET.minusMinutes(15);

        when(transactions.ready(threshold, NOW_OFFSET)).thenReturn(true);
        assertThat(retention.ready()).isTrue();
        verify(transactions).ready(threshold, NOW_OFFSET);

        when(transactions.ready(threshold, NOW_OFFSET))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThat(retention.ready()).isFalse();
    }

    @Test
    void invalidPollDelayFailsClosedWithoutReadingDurableHealth() {
        MeetingPreparationMaterialRetentionTransactions transactions =
                mock(MeetingPreparationMaterialRetentionTransactions.class);
        MeetingPreparationMaterialRetentionService retention =
                new MeetingPreparationMaterialRetentionService(
                        transactions, CLOCK, Duration.ZERO);

        assertThat(retention.ready()).isFalse();
        verifyNoInteractions(transactions);
    }

    @Test
    void unpersistedFailureRequiresANewerSuccessfulPollBeforeReadinessRecovers() {
        MeetingPreparationMaterialRetentionTransactions transactions =
                mock(MeetingPreparationMaterialRetentionTransactions.class);
        MeetingPreparationMaterialRetentionService retention =
                new MeetingPreparationMaterialRetentionService(
                        transactions, CLOCK, Duration.ofMinutes(5));
        IllegalStateException purgeFailure = new IllegalStateException("purge failed");
        IllegalStateException evidenceFailure = new IllegalStateException("evidence failed");
        when(transactions.purge(NOW_OFFSET, 200))
                .thenThrow(purgeFailure)
                .thenReturn(0);
        doThrow(evidenceFailure).when(transactions).recordFailure(NOW_OFFSET);

        assertThatThrownBy(retention::purgeExpired).isSameAs(purgeFailure);
        assertThat(purgeFailure.getSuppressed()).containsExactly(evidenceFailure);
        when(transactions.ready(NOW_OFFSET, NOW_OFFSET)).thenReturn(false);
        assertThat(retention.ready()).isFalse();
        verify(transactions).ready(NOW_OFFSET, NOW_OFFSET);

        assertThat(retention.purgeExpired()).isZero();
        OffsetDateTime freshThreshold = NOW_OFFSET.minusMinutes(15);
        when(transactions.ready(freshThreshold, NOW_OFFSET)).thenReturn(true);
        assertThat(retention.ready()).isTrue();
    }
}
