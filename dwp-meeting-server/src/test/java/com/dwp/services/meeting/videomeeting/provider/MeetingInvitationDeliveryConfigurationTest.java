package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryProperties;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
