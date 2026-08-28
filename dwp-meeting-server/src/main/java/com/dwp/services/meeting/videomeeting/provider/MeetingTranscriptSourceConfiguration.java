package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeetingTranscriptHttpProperties.class)
public class MeetingTranscriptSourceConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "dwp.meeting.transcript-source",
            name = "provider",
            havingValue = "http")
    MeetingTranscriptSource governedHttpMeetingTranscriptSource(
            MeetingTranscriptHttpProperties properties,
            ObjectMapper objectMapper,
            MeetingWorkloadAssertionSigner signer) {
        return new GovernedHttpMeetingTranscriptSource(properties, objectMapper, signer);
    }
}
