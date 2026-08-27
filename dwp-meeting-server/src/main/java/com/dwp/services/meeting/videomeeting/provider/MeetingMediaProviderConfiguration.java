package com.dwp.services.meeting.videomeeting.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MeetingMediaProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(MeetingMediaProvider.class)
    DisabledMeetingMediaProvider disabledMeetingMediaProvider(
            MeetingMediaProperties properties) {
        return new DisabledMeetingMediaProvider(properties);
    }
}
