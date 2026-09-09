package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryProperties;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
            ObjectMapper mapper) {
        if (!properties.validDispatchConfiguration()) {
            throw new IllegalArgumentException(
                    "Meeting invitation dispatch configuration is invalid.");
        }
        return new GovernedHttpMeetingInvitationNotificationGateway(properties, mapper);
    }
}
