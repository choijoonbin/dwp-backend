package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryProperties;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryReadiness;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Locale;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeetingInvitationDeliveryProperties.class)
public class MeetingInvitationDeliveryConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "dwp.meeting.invitation-delivery",
            name = "enabled",
            havingValue = "true")
    MeetingInvitationNotificationGateway meetingInvitationNotificationGateway(
            MeetingInvitationDeliveryProperties properties,
            ObjectMapper mapper,
            Environment environment) {
        if (production(environment)) {
            throw new IllegalStateException(
                    "Meeting invitation delivery remains disabled in production until "
                            + "external readiness evidence is approved.");
        }
        if (!properties.validDispatchConfiguration()) {
            throw new IllegalArgumentException(
                    "Meeting invitation dispatch configuration is invalid.");
        }
        return new GovernedHttpMeetingInvitationNotificationGateway(properties, mapper);
    }

    @Bean
    MeetingInvitationDeliveryReadiness meetingInvitationDeliveryReadiness(
            MeetingInvitationDeliveryProperties properties,
            ObjectProvider<MeetingInvitationNotificationGateway> gateway,
            Environment environment) {
        return new MeetingInvitationDeliveryReadiness(properties, gateway, environment);
    }

    private boolean production(Environment environment) {
        return productionValue(environment.getProperty("dwp.environment"))
                || productionValue(environment.getProperty("DWP_ENVIRONMENT"));
    }

    private boolean productionValue(String value) {
        String normalized = value == null
                ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }
}
