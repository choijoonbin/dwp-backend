package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingInvitationDeliveryReadinessTest {

    @Test
    void disabledProductionCapabilityIsTruthfulWithoutExposingConfiguration() {
        MeetingInvitationDeliveryProperties properties = properties(false);
        properties.setBaseUrl("https://private-notification.example.com");
        properties.setServiceToken("meeting-notification-service-token");
        MeetingInvitationDeliveryReadiness readiness = readiness(
                properties, new MockEnvironment().withProperty(
                        "dwp.environment", "production"), false, clock());

        var health = readiness.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("state", "DISABLED_EXTERNAL_GATES")
                .containsEntry("scope", "CONFIGURATION_AND_LOCAL_SCHEDULER_ONLY")
                .containsEntry("remoteRouteVerification", "NOT_CLAIMED")
                .containsEntry("externalProductionEvidence", "REQUIRED");
        assertThat(health.getDetails().toString())
                .doesNotContain("private-notification", "meeting-notification-service-token");
    }

    @Test
    void eitherProductionEnvironmentSignalFailsClosedWhenTheyConflict() {
        MeetingInvitationDeliveryReadiness readiness = readiness(
                properties(false), new MockEnvironment()
                        .withProperty("dwp.environment", "local")
                        .withProperty("DWP_ENVIRONMENT", "production"),
                false, clock());

        assertThat(readiness.health().getDetails())
                .containsEntry("state", "DISABLED_EXTERNAL_GATES")
                .containsEntry("externalProductionEvidence", "REQUIRED");
    }

    @Test
    void enabledLocalCapabilityRequiresAFreshSuccessfulPoll() {
        MutableClock clock = clock();
        MeetingInvitationDeliveryReadiness readiness = readiness(
                properties(true), new MockEnvironment(), true, clock);

        assertThat(readiness.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(readiness.health().getDetails()).containsEntry("state", "STARTING");

        clock.advance(Duration.ofSeconds(31));
        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(readiness.health().getDetails()).containsEntry("state", "POLL_STALE");

        readiness.recordSuccessfulPoll(4);
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
        assertThat(readiness.health().getDetails())
                .containsEntry("state", "READY_NON_PRODUCTION")
                .containsEntry("lastProcessedCount", 4);

        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(readiness.health().getDetails()).containsEntry("state", "POLL_STALE");
    }

    @Test
    void localProgressPreventsAHealthyLongBatchFromLookingStale() {
        MutableClock clock = clock();
        MeetingInvitationDeliveryReadiness readiness = readiness(
                properties(true), new MockEnvironment(), true, clock);
        readiness.recordPollStarted();

        for (int processed = 0; processed < 20; processed++) {
            clock.advance(Duration.ofSeconds(10));
            readiness.recordLocalProgress();
        }

        assertThat(readiness.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(readiness.health().getDetails())
                .containsEntry("state", "STARTING")
                .containsEntry("schedulerStale", false);

        readiness.recordSuccessfulPoll(20);
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aLocalFailureBlocksUntilANewerSuccessfulPoll() {
        MutableClock clock = clock();
        MeetingInvitationDeliveryReadiness readiness = readiness(
                properties(true), new MockEnvironment(), true, clock);
        readiness.recordSuccessfulPoll(0);
        clock.advance(Duration.ofSeconds(1));

        readiness.recordLocalFailure("CLAIM_UNAVAILABLE");

        assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(readiness.health().getDetails())
                .containsEntry("state", "LOCAL_FAILURE")
                .containsEntry("lastLocalFailure", "CLAIM_UNAVAILABLE");

        clock.advance(Duration.ofSeconds(1));
        readiness.recordSuccessfulPoll(0);
        assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
        assertThat(readiness.health().getDetails())
                .containsEntry("lastLocalFailure", "NONE");
    }

    @Test
    void invalidConfigurationOrMissingGatewayFailsClosed() {
        MeetingInvitationDeliveryProperties invalid = properties(true);
        invalid.setBatchSize(0);
        assertThat(readiness(
                invalid, new MockEnvironment(), true, clock()).health().getStatus())
                .isEqualTo(Status.DOWN);
        assertThat(readiness(
                properties(true), new MockEnvironment(), false, clock())
                .health().getStatus()).isEqualTo(Status.DOWN);
    }

    private MeetingInvitationDeliveryProperties properties(boolean enabled) {
        MeetingInvitationDeliveryProperties properties =
                new MeetingInvitationDeliveryProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private MeetingInvitationDeliveryReadiness readiness(
            MeetingInvitationDeliveryProperties properties,
            MockEnvironment environment,
            boolean gatewayAvailable,
            MutableClock clock) {
        ObjectProvider<MeetingInvitationNotificationGateway> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gatewayAvailable
                ? mock(MeetingInvitationNotificationGateway.class) : null);
        return new MeetingInvitationDeliveryReadiness(
                properties, provider, environment, clock);
    }

    private MutableClock clock() {
        return new MutableClock(Instant.parse("2026-10-29T00:00:00Z"));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
