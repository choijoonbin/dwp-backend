package com.dwp.services.messaging.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagingRetentionHealthIndicatorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void reportsBlockedWhenEnforcementLacksLegalHoldAuthority() {
        MessagingRetentionProperties properties = new MessagingRetentionProperties(
                true, MessagingRetentionProperties.ExecutionMode.ENFORCING,
                false, false, true, 100, 10, 1_000, Duration.ofMinutes(15));

        var health = indicator(properties, null).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails())
                .containsEntry("state", "BLOCKED")
                .containsEntry("reasonCode", "LEGAL_HOLD_AUTHORITY_UNAVAILABLE");
    }

    @Test
    void reportsUpOnlyForFullyAuthorizedEnforcement() {
        MessagingRetentionProperties properties = new MessagingRetentionProperties(
                true, MessagingRetentionProperties.ExecutionMode.ENFORCING,
                false, true, true, 100, 10, 1_000, Duration.ofMinutes(15));

        MessagingRetentionRepository.RetentionHealthSummary evidence =
                new MessagingRetentionRepository.RetentionHealthSummary(
                        2, 0, 0, 0, 0, 0, OffsetDateTime.now(CLOCK));
        var health = indicator(properties, evidence).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("state", "ENFORCING");
    }

    @Test
    void enforcingFailsClosedForBacklogFailureOrMissingSuccessEvidence() {
        MessagingRetentionProperties properties = new MessagingRetentionProperties(
                true, MessagingRetentionProperties.ExecutionMode.ENFORCING,
                false, true, true, 100, 10, 1_000, Duration.ofMinutes(15));
        var evidence = new MessagingRetentionRepository.RetentionHealthSummary(
                3, 1, 1, 4, 1, 0, OffsetDateTime.now(CLOCK));

        var health = indicator(properties, evidence).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails())
                .containsEntry("reasonCode", "ATTACHMENT_DELETION_BACKLOG")
                .containsEntry("pendingAttachmentCount", 4L);
    }

    private MessagingRetentionHealthIndicator indicator(
            MessagingRetentionProperties properties,
            MessagingRetentionRepository.RetentionHealthSummary evidence) {
        MessagingRetentionRepository repository = mock(MessagingRetentionRepository.class);
        if (evidence != null) when(repository.retentionHealth(org.mockito.ArgumentMatchers.any()))
                .thenReturn(evidence);
        return new MessagingRetentionHealthIndicator(
                properties, repository, Duration.ofHours(2), CLOCK);
    }
}
