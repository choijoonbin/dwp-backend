package com.dwp.services.meeting.videomeeting.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingDeletionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        MeetingRecordingHttpProperties.class,
        MeetingRecordingDeletionProperties.class
})
public class MeetingRecordingProviderConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "dwp.meeting.recording",
            name = "provider",
            havingValue = "http")
    MeetingRecordingProvider governedHttpMeetingRecordingProvider(
            MeetingRecordingHttpProperties properties,
            ObjectMapper mapper) {
        return new GovernedHttpMeetingRecordingProvider(
                properties, mapper, new MeetingWorkloadAssertionSigner(properties));
    }

    @Bean
    @ConditionalOnMissingBean(MeetingRecordingProvider.class)
    MeetingRecordingProvider disabledMeetingRecordingProvider() {
        return new DisabledMeetingRecordingProvider();
    }
}

final class DisabledMeetingRecordingProvider implements MeetingRecordingProvider {

    @Override
    public Capability capability() {
        return Capability.unavailable();
    }

    @Override
    public Receipt start(Command command) {
        throw new IllegalStateException("Meeting recording provider is disabled.");
    }

    @Override
    public Receipt stop(Command command) {
        throw new IllegalStateException("Meeting recording provider is disabled.");
    }

    @Override
    public AccessTicket issueAccessTicket(AccessRequest request) {
        throw new IllegalStateException("Meeting recording provider is disabled.");
    }

    @Override
    public DeletionReceipt delete(DeleteRequest request) {
        throw new IllegalStateException("Meeting recording provider is disabled.");
    }
}
