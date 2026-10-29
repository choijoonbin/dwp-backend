package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryProperties;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryReadiness;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingInvitationDeliveryConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withUserConfiguration(MeetingInvitationDeliveryConfiguration.class);

    @Test
    void productionDefaultKeepsTheDirectNotificationClientDisabled() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MeetingInvitationDeliveryProperties.class);
            assertThat(context.getBean(MeetingInvitationDeliveryProperties.class).isEnabled())
                    .isFalse();
            assertThat(context).doesNotHaveBean(MeetingInvitationNotificationGateway.class);
            assertThat(context).hasSingleBean(MeetingInvitationDeliveryReadiness.class);
            assertThat(context.getBean(MeetingInvitationDeliveryReadiness.class)
                    .health().getDetails()).containsEntry("state", "DISABLED");
        });
    }

    @Test
    void explicitSafeConfigurationInstallsTheStrictGateway() {
        runner.withPropertyValues(
                "dwp.meeting.invitation-delivery.enabled=true",
                "dwp.meeting.invitation-delivery.base-url=https://notification.example.com",
                "dwp.meeting.invitation-delivery.service-token="
                        + "meeting-notification-service-token")
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            MeetingInvitationNotificationGateway.class);
                    assertThat(context.getBean(MeetingInvitationDeliveryProperties.class)
                            .validDispatchConfiguration()).isTrue();
                    assertThat(context.getBean(MeetingInvitationDeliveryReadiness.class)
                            .health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
                });
    }

    @Test
    void enabledConfigurationFailsStartupWhenTheLeaseCannotFenceARequest() {
        runner.withPropertyValues(
                "dwp.meeting.invitation-delivery.enabled=true",
                "dwp.meeting.invitation-delivery.base-url=https://notification.example.com",
                "dwp.meeting.invitation-delivery.service-token="
                        + "meeting-notification-service-token",
                "dwp.meeting.invitation-delivery.request-timeout=PT3S",
                "dwp.meeting.invitation-delivery.lease-duration=PT2S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionActivationIsBlockedEvenWithAFormallySafeTransport() {
        safeConfiguration()
                .withPropertyValues("dwp.environment=production")
                .run(context -> assertThat(context).hasFailed());
        safeConfiguration()
                .withPropertyValues("DWP_ENVIRONMENT=production")
                .run(context -> assertThat(context).hasFailed());
        safeConfiguration()
                .withPropertyValues(
                        "dwp.environment=local",
                        "DWP_ENVIRONMENT=production")
                .run(context -> assertThat(context).hasFailed());

        runner.withPropertyValues("dwp.environment=prod")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            MeetingInvitationNotificationGateway.class);
                    var health = context.getBean(
                            MeetingInvitationDeliveryReadiness.class).health();
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails())
                            .containsEntry("state", "DISABLED_EXTERNAL_GATES")
                            .containsEntry("externalProductionEvidence", "REQUIRED");
                });
    }

    @Test
    void unsafeActivationSettingsFailContextCreation() {
        runner.withPropertyValues("dwp.meeting.invitation-delivery.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        for (String invalid : new String[]{
                "dwp.meeting.invitation-delivery.base-url=http://notification.example.com",
                "dwp.meeting.invitation-delivery.base-url=https://user@notification.example.com",
                "dwp.meeting.invitation-delivery.base-url=https://notification.example.com/path",
                "dwp.meeting.invitation-delivery.service-token=short",
                "dwp.meeting.invitation-delivery.service-token=" + "x".repeat(31),
                "dwp.meeting.invitation-delivery.service-token="
                        + "meeting-notification,service-token",
                "dwp.meeting.invitation-delivery.service-token=" + "x".repeat(513),
                "dwp.meeting.invitation-delivery.connect-timeout=PT0.099S",
                "dwp.meeting.invitation-delivery.request-timeout=PT10.001S",
                "dwp.meeting.invitation-delivery.maximum-response-bytes=1023",
                "dwp.meeting.invitation-delivery.batch-size=101",
                "dwp.meeting.invitation-delivery.maximum-attempts=33",
                "dwp.meeting.invitation-delivery.poll-delay=PT0.5S",
                "dwp.meeting.invitation-delivery.retry-delay=PT25H",
                "dwp.meeting.invitation-delivery.lease-duration=PT16M",
                "dwp.meeting.invitation-delivery.health-startup-grace=PT5S",
                "dwp.meeting.invitation-delivery.health-stale-after=PT3S",
                "dwp.meeting.invitation-delivery.health-stale-after=PT10S"
        }) {
            safeConfiguration().withPropertyValues(invalid)
                    .run(context -> assertThat(context).as(invalid).hasFailed());
        }
        safeConfiguration().withPropertyValues(
                        "dwp.meeting.invitation-delivery.poll-delay=PT1S",
                        "dwp.meeting.invitation-delivery.request-timeout=PT3S",
                        "dwp.meeting.invitation-delivery.health-stale-after=PT3S")
                .run(context -> assertThat(context)
                        .as("health staleness must exceed one bounded request")
                        .hasFailed());
    }

    @Test
    void applicationContractKeepsProductionOffAndIncludesCapabilityReadiness()
            throws Exception {
        var sources = new YamlPropertySourceLoader().load(
                "meeting-application", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();
        assertThat(sources.getFirst().getProperty(
                "dwp.meeting.invitation-delivery.enabled"))
                .isEqualTo("${DWP_MEETING_INVITATION_DELIVERY_ENABLED:false}");
        assertThat(sources.getFirst().getProperty(
                "management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,meetingInvitationDeliveryReadiness");
        assertThat(sources.getFirst().getProperty(
                "management.endpoint.health.group.readiness.show-components"))
                .isEqualTo("always");
        assertThat(sources.getFirst().getProperty(
                "management.endpoint.health.group.readiness.show-details"))
                .isEqualTo("always");
    }

    private ApplicationContextRunner safeConfiguration() {
        return runner.withPropertyValues(
                "dwp.meeting.invitation-delivery.enabled=true",
                "dwp.meeting.invitation-delivery.base-url=https://notification.example.com",
                "dwp.meeting.invitation-delivery.service-token="
                        + "meeting-notification-service-token");
    }
}
