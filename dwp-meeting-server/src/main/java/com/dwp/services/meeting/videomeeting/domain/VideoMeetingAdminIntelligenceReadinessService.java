package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingAdminIntelligenceDtos;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingAdminIntelligenceDtos.ReadinessSignal;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Produces one fail-closed operations view of the complete meeting-content vertical.
 * Configuration alone never becomes READY when a live probe or durable worker is required.
 */
@Service
public class VideoMeetingAdminIntelligenceReadinessService {

    private static final String VERSION = "meeting-intelligence-readiness-v1";

    private final VideoMeetingRepository meetings;
    private final MeetingMediaProvider media;
    private final MeetingContentDependencies dependencies;
    private final MeetingIntelligenceProvider intelligence;
    private final MeetingIntelligenceRetentionService retention;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public VideoMeetingAdminIntelligenceReadinessService(
            VideoMeetingRepository meetings,
            MeetingMediaProvider media,
            MeetingContentDependencies dependencies,
            MeetingIntelligenceProvider intelligence,
            MeetingIntelligenceRetentionService retention,
            JdbcTemplate jdbc) {
        this(meetings, media, dependencies, intelligence, retention, jdbc, Clock.systemUTC());
    }

    VideoMeetingAdminIntelligenceReadinessService(
            VideoMeetingRepository meetings,
            MeetingMediaProvider media,
            MeetingContentDependencies dependencies,
            MeetingIntelligenceProvider intelligence,
            MeetingIntelligenceRetentionService retention,
            JdbcTemplate jdbc,
            Clock clock) {
        this.meetings = meetings;
        this.media = media;
        this.dependencies = dependencies;
        this.intelligence = intelligence;
        this.retention = retention;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public VideoMeetingAdminIntelligenceDtos.ReadinessResponse readiness() {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        TenantPolicy policy = meetings.policy(subject.tenantId()).orElseThrow(() ->
                new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "The meeting tenant policy was not found."));
        MeetingMediaProvider.Capability mediaCapability = mediaCapability();
        boolean mediaOperational = mediaCapability.available() && mediaOperational();
        MeetingContentDependencies.Status dependencyStatus = dependencyStatus();
        MeetingIntelligenceProvider.Capability intelligenceCapability =
                intelligenceCapability(subject.tenantId());
        boolean retentionReady = retentionReady();
        boolean databaseReady = intelligenceDatabaseReady();
        boolean safeModel = enterpriseSafe(intelligenceCapability);
        boolean modelOperational = safeModel
                && dependencyStatus.languageModelAvailable()
                && languageModelOperationalEvidence(
                        subject.tenantId(), intelligenceCapability);

        Map<String, ReadinessSignal> dependencySignals = new LinkedHashMap<>();
        dependencySignals.put("provider", dependency(
                mediaOperational, "REALTIME_PROVIDER_LIVENESS_NOT_READY"));
        dependencySignals.put("region", dependency(
                safeModel && validRegion(intelligenceCapability.processingRegion()),
                "PROCESSING_REGION_NOT_VERIFIED"));
        dependencySignals.put("kms", dependency(
                dependencyStatus.kmsAvailable(), "KMS_NOT_READY"));
        dependencySignals.put("audit", dependency(
                dependencyStatus.auditAvailable(), "AUDIT_NOT_READY"));
        dependencySignals.put("egress", dependency(
                dependencyStatus.egressAvailable(), "EGRESS_NOT_READY"));
        dependencySignals.put("storage", dependency(
                dependencyStatus.storageAvailable(), "STORAGE_NOT_READY"));
        dependencySignals.put("stt", dependency(
                dependencyStatus.speechToTextAvailable(), "STT_NOT_READY"));
        dependencySignals.put("llm", dependency(
                modelOperational, "LLM_OPERATIONAL_EVIDENCE_NOT_READY"));
        dependencySignals.put("retention", dependency(
                retentionReady, "RETENTION_WORKER_NOT_READY"));

        boolean recordingReady = mediaOperational
                && dependencyStatus.egressAvailable()
                && dependencyStatus.storageAvailable()
                && dependencyStatus.kmsAvailable()
                && dependencyStatus.auditAvailable();
        boolean transcriptReady = recordingReady && dependencyStatus.speechToTextAvailable();
        boolean intelligenceReady = transcriptReady && modelOperational
                && retentionReady && databaseReady;

        Map<String, ReadinessSignal> capabilities = new LinkedHashMap<>();
        capabilities.put("recording", capability(policy, recordingReady));
        capabilities.put("transcript", capability(policy, transcriptReady));
        capabilities.put("aiNotes", capability(policy, intelligenceReady));

        Map<String, ReadinessSignal> governance = new LinkedHashMap<>();
        governance.put("humanReview", verifiedControl(databaseReady));
        governance.put("explicitPublish", verifiedControl(databaseReady));
        governance.put("adminContentAccess", verifiedControl(databaseReady));
        governance.put("legalHold", ReadinessSignal.notVerified(
                "LEGAL_HOLD_ADMIN_WORKFLOW_NOT_CONFIGURED"));
        Map<String, ReadinessSignal> retentionSignals = retentionSignals(
                retentionReady, databaseReady);
        governance.put("deletionEvidence", retentionSignals.values().stream()
                .allMatch(signal -> "READY".equals(signal.state()))
                ? ReadinessSignal.ready()
                : ReadinessSignal.notVerified(
                        "COMPLETE_DELETION_EVIDENCE_NOT_VERIFIED"));

        return new VideoMeetingAdminIntelligenceDtos.ReadinessResponse(
                VERSION,
                OffsetDateTime.now(clock),
                policy.recordingPolicy(),
                safeModel ? intelligenceCapability.providerCode() : "disabled",
                safeModel ? intelligenceCapability.model() : "none",
                safeModel ? intelligenceCapability.processingRegion() : "none",
                Map.copyOf(capabilities),
                Map.copyOf(dependencySignals),
                Map.copyOf(governance),
                new VideoMeetingAdminIntelligenceDtos.RetentionReadiness(
                        policy.retentionDays(), policy.artifactRetentionDays(),
                        policy.chatRetentionDays(), retentionReady,
                        Map.copyOf(retentionSignals)));
    }

    private MeetingMediaProvider.Capability mediaCapability() {
        try {
            MeetingMediaProvider.Capability capability = media.capability();
            return capability == null ? unavailableMedia() : capability;
        } catch (RuntimeException exception) {
            return unavailableMedia();
        }
    }

    private boolean mediaOperational() {
        try {
            return media.operationallyReady();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private MeetingContentDependencies.Status dependencyStatus() {
        try {
            MeetingContentDependencies.Status status = dependencies.status();
            return status == null ? MeetingContentDependencies.failClosedStatus() : status;
        } catch (RuntimeException exception) {
            return MeetingContentDependencies.failClosedStatus();
        }
    }

    private MeetingIntelligenceProvider.Capability intelligenceCapability(long tenantId) {
        UUID scope = UUID.nameUUIDFromBytes(
                ("meeting-admin-readiness:" + tenantId).getBytes(StandardCharsets.UTF_8));
        try {
            MeetingIntelligenceProvider.Capability capability = intelligence.capability(
                    new MeetingIntelligenceProvider.ExecutionContext(
                            tenantId, scope, scope, "meeting-admin-readiness"));
            return capability == null
                    ? MeetingIntelligenceProvider.Capability.unavailable() : capability;
        } catch (RuntimeException exception) {
            return MeetingIntelligenceProvider.Capability.unavailable();
        }
    }

    private boolean retentionReady() {
        try {
            return retention.ready();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean intelligenceDatabaseReady() {
        try {
            Boolean ready = jdbc.queryForObject("""
                    SELECT to_regclass('public.vm_meeting_intelligence_reports') IS NOT NULL
                       AND to_regclass('public.vm_meeting_intelligence_reviews') IS NOT NULL
                       AND to_regclass('public.vm_meeting_content_acl') IS NOT NULL
                       AND to_regclass('public.vm_meeting_intelligence_deletions') IS NOT NULL
                       AND has_table_privilege(
                           current_user, 'public.vm_meeting_intelligence_reports',
                           'SELECT,INSERT,UPDATE')
                       AND has_table_privilege(
                           current_user, 'public.vm_meeting_intelligence_reviews',
                           'SELECT,INSERT')
                       AND has_table_privilege(
                           current_user, 'public.vm_meeting_content_acl',
                           'SELECT,INSERT,UPDATE')
                       AND has_table_privilege(
                           current_user, 'public.vm_meeting_intelligence_deletions',
                           'SELECT,INSERT')
                    """, Boolean.class);
            return Boolean.TRUE.equals(ready);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean languageModelOperationalEvidence(
            long tenantId,
            MeetingIntelligenceProvider.Capability capability) {
        try {
            Boolean ready = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM vm_meeting_intelligence_runs
                         WHERE tenant_id = ?
                           AND run_state = 'SUCCEEDED'
                           AND provider_code = ?
                           AND provider_model = ?
                           AND processing_region = ?
                           AND completed_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours')
                    """, Boolean.class, tenantId, capability.providerCode(),
                    capability.model(), capability.processingRegion());
            return Boolean.TRUE.equals(ready);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean enterpriseSafe(MeetingIntelligenceProvider.Capability capability) {
        return capability.available()
                && capability.customerDataTrainingDisabled()
                && capability.providerRetentionDisabled()
                && validRegion(capability.processingRegion())
                && capability.schemaVersions() != null
                && capability.schemaVersions().contains("meeting-intelligence-v1");
    }

    private boolean validRegion(String region) {
        return region != null && region.matches("^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$");
    }

    private ReadinessSignal capability(TenantPolicy policy, boolean ready) {
        if (!policy.meetingsEnabled()) {
            return ReadinessSignal.blocked("MEETINGS_DISABLED_BY_POLICY");
        }
        if ("NEVER".equals(policy.recordingPolicy())) {
            return ReadinessSignal.blocked("POLICY_NEVER");
        }
        return ready
                ? ReadinessSignal.ready()
                : ReadinessSignal.connectionRequired("CAPABILITY_NOT_READY");
    }

    private ReadinessSignal dependency(boolean ready, String reason) {
        return ready ? ReadinessSignal.ready() : ReadinessSignal.connectionRequired(reason);
    }

    private ReadinessSignal verifiedControl(boolean databaseReady) {
        return databaseReady
                ? ReadinessSignal.ready()
                : ReadinessSignal.notVerified("WORKFLOW_ENFORCEMENT_NOT_VERIFIED");
    }

    private Map<String, ReadinessSignal> retentionSignals(
            boolean retentionReady,
            boolean databaseReady) {
        Map<String, ReadinessSignal> signals = new LinkedHashMap<>();
        signals.put("intelligenceReports", retentionReady && databaseReady
                ? ReadinessSignal.ready()
                : ReadinessSignal.connectionRequired(
                        "INTELLIGENCE_REPORT_RETENTION_NOT_READY"));
        signals.put("meetingRecords", ReadinessSignal.notVerified(
                "MEETING_RECORD_RETENTION_WORKER_NOT_CONFIGURED"));
        signals.put("artifacts", ReadinessSignal.notVerified(
                "ARTIFACT_RETENTION_WORKER_NOT_CONFIGURED"));
        signals.put("chat", ReadinessSignal.notVerified(
                "CHAT_RETENTION_WORKER_NOT_CONFIGURED"));
        return signals;
    }

    private MeetingMediaProvider.Capability unavailableMedia() {
        return new MeetingMediaProvider.Capability(
                false, "disabled", "MEETING_PROVIDER_UNAVAILABLE",
                false, false, false, false, 0);
    }
}
