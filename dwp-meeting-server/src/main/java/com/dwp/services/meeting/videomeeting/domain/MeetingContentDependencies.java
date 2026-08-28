package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;

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

/** Runtime probes for the configured governed intelligence vertical. */
final class GovernedMeetingContentDependencies implements MeetingContentDependencies {

    private final JdbcTemplate jdbc;
    private final MeetingIntelligenceHttpProperties intelligence;
    private final MeetingTranscriptHttpProperties transcript;
    private final MeetingTranscriptSource transcriptSource;
    private final MeetingIntelligencePayloadProtector protector;

    GovernedMeetingContentDependencies(
            JdbcTemplate jdbc,
            MeetingIntelligenceHttpProperties intelligence,
            MeetingTranscriptHttpProperties transcript,
            MeetingTranscriptSource transcriptSource,
            MeetingIntelligencePayloadProtector protector) {
        this.jdbc = jdbc;
        this.intelligence = intelligence;
        this.transcript = transcript;
        this.transcriptSource = transcriptSource;
        this.protector = protector;
    }

    @Override
    public Status status() {
        boolean storage = "http".equals(transcript.getProvider())
                && transcriptSource.available();
        boolean languageModel = "http".equals(intelligence.getProvider());
        boolean kms = protector.available() && protector.ready();
        return new Status(false, storage, kms, false, languageModel, auditWritable());
    }

    private boolean auditWritable() {
        try {
            Boolean ready = jdbc.queryForObject("""
                    SELECT to_regclass('public.sys_audit_outbox') IS NOT NULL
                       AND has_table_privilege(
                           current_user, 'public.sys_audit_outbox', 'INSERT')
                    """, Boolean.class);
            return Boolean.TRUE.equals(ready);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

@Configuration(proxyBeanMethods = false)
class MeetingContentDependencyConfiguration {

    @Bean
    @ConditionalOnExpression(
            "'${dwp.meeting.intelligence.provider:disabled}' == 'http' && "
                    + "'${dwp.meeting.transcript-source.provider:disabled}' == 'http'")
    @ConditionalOnMissingBean(MeetingContentDependencies.class)
    MeetingContentDependencies governedMeetingContentDependencies(
            JdbcTemplate jdbc,
            MeetingIntelligenceHttpProperties intelligence,
            MeetingTranscriptHttpProperties transcript,
            MeetingTranscriptSource transcriptSource,
            MeetingIntelligencePayloadProtector protector) {
        return new GovernedMeetingContentDependencies(
                jdbc, intelligence, transcript, transcriptSource, protector);
    }

    @Bean
    @ConditionalOnMissingBean(MeetingContentDependencies.class)
    MeetingContentDependencies meetingContentDependencies() {
        return new DisabledMeetingContentDependencies();
    }
}
