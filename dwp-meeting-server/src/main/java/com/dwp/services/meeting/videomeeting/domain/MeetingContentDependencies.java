package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Readiness boundary for systems that are deliberately outside Meeting Core. */
public interface MeetingContentDependencies {

    Status status();

    static Status failClosedStatus() {
        return new Status(false, false, false, false, false, false);
    }

    record Status(
            boolean egressAvailable,
            boolean storageAvailable,
            boolean kmsAvailable,
            boolean speechToTextAvailable,
            boolean languageModelAvailable,
            boolean auditAvailable) {
    }
}

/**
 * P1 ships the truthful control plane, not fake media processing. A production
 * adapter must replace this component only after every governed dependency has
 * an operational readiness probe and tested credentials.
 */
final class DisabledMeetingContentDependencies implements MeetingContentDependencies {

    @Override
    public Status status() {
        return MeetingContentDependencies.failClosedStatus();
    }
}

@Configuration(proxyBeanMethods = false)
class MeetingContentDependencyConfiguration {

    @Bean
    @ConditionalOnMissingBean(MeetingContentDependencies.class)
    MeetingContentDependencies meetingContentDependencies() {
        return new DisabledMeetingContentDependencies();
    }
}
