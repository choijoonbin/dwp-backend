package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;

/** Readiness boundary for systems that are deliberately outside Meeting Core. */
public interface MeetingContentDependencies {

    Status status();

    /** Uses the already-probed recording capability to keep one command on one readiness view. */
    default Status status(MeetingRecordingProvider.Capability recordingCapability) {
        return status();
    }

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

/** Fail-closed fallback used unless governed providers and their probes are configured. */
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
    private final MeetingRecordingProvider recording;
    private final MeetingRecordingDeletionReadiness deletionReadiness;
    private final MeetingTranscriptDeletionReadiness transcriptDeletionReadiness;

    GovernedMeetingContentDependencies(
            JdbcTemplate jdbc,
            MeetingIntelligenceHttpProperties intelligence,
            MeetingTranscriptHttpProperties transcript,
            MeetingTranscriptSource transcriptSource,
            MeetingIntelligencePayloadProtector protector,
            MeetingRecordingProvider recording,
            MeetingRecordingDeletionReadiness deletionReadiness,
            MeetingTranscriptDeletionReadiness transcriptDeletionReadiness) {
        this.jdbc = jdbc;
        this.intelligence = intelligence;
        this.transcript = transcript;
        this.transcriptSource = transcriptSource;
        this.protector = protector;
        this.recording = recording;
        this.deletionReadiness = deletionReadiness;
        this.transcriptDeletionReadiness = transcriptDeletionReadiness;
    }

    @Override
    public Status status() {
        return status(recording.capability());
    }

    @Override
    public Status status(MeetingRecordingProvider.Capability recordingCapability) {
        boolean trustedTranscriptStorage = "http".equals(transcript.getProvider())
                && transcriptSource.available() && transcriptDeletionReadiness.ready();
        boolean languageModel = "http".equals(intelligence.getProvider());
        boolean kms = protector.available() && protector.ready();
        boolean governedRecording = deletionReadiness.ready(recordingCapability);
        return new Status(
                governedRecording && recordingCapability.egressAvailable(),
                (governedRecording && recordingCapability.storageAvailable())
                        || trustedTranscriptStorage,
                kms,
                governedRecording
                        && recordingCapability.speechToTextAvailable(),
                languageModel,
                auditWritable());
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
            "'${dwp.meeting.recording.provider:disabled}' == 'http' || "
                    + "('${dwp.meeting.intelligence.provider:disabled}' == 'http' && "
                    + "'${dwp.meeting.transcript-source.provider:disabled}' == 'http')")
    @ConditionalOnMissingBean(MeetingContentDependencies.class)
    MeetingContentDependencies governedMeetingContentDependencies(
            JdbcTemplate jdbc,
            MeetingIntelligenceHttpProperties intelligence,
            MeetingTranscriptHttpProperties transcript,
            MeetingTranscriptSource transcriptSource,
            MeetingIntelligencePayloadProtector protector,
            MeetingRecordingProvider recording,
            MeetingRecordingDeletionReadiness deletionReadiness,
            MeetingTranscriptDeletionReadiness transcriptDeletionReadiness) {
        return new GovernedMeetingContentDependencies(
                jdbc, intelligence, transcript, transcriptSource, protector,
                recording, deletionReadiness, transcriptDeletionReadiness);
    }

    @Bean
    @ConditionalOnMissingBean(MeetingContentDependencies.class)
    MeetingContentDependencies meetingContentDependencies() {
        return new DisabledMeetingContentDependencies();
    }
}
