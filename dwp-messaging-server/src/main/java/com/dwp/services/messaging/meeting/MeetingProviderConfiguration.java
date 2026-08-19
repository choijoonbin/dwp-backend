package com.dwp.services.messaging.meeting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MeetingProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(MeetingProvider.class)
    DisabledMeetingProvider disabledMeetingProvider(MeetingProperties properties) {
        return new DisabledMeetingProvider(properties);
    }
}
