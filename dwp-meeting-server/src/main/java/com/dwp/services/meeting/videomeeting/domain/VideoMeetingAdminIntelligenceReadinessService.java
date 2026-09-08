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
    private final MeetingRecordingDeletionReadiness recordingDeletion;
    private final MeetingTranscriptDeletionReadiness transcriptDeletion;
    private final MeetingChatRetentionService chatRetention;
    private final MeetingRecordRetentionService recordRetention;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public VideoMeetingAdminIntelligenceReadinessService(
            VideoMeetingRepository meetings,
            MeetingMediaProvider media,
            MeetingContentDependencies dependencies,
            MeetingIntelligenceProvider intelligence,
            MeetingIntelligenceRetentionService retention,
            MeetingRecordingDeletionReadiness recordingDeletion,
            MeetingTranscriptDeletionReadiness transcriptDeletion,
            MeetingChatRetentionService chatRetention,
            MeetingRecordRetentionService recordRetention,
            JdbcTemplate jdbc) {
        this(meetings, media, dependencies, intelligence, retention,
                recordingDeletion, transcriptDeletion, chatRetention, recordRetention,
                jdbc, Clock.systemUTC());
    }

    VideoMeetingAdminIntelligenceReadinessService(
            VideoMeetingRepository meetings,
            MeetingMediaProvider media,
            MeetingContentDependencies dependencies,
            MeetingIntelligenceProvider intelligence,
            MeetingIntelligenceRetentionService retention,
            MeetingRecordingDeletionReadiness recordingDeletion,
            MeetingTranscriptDeletionReadiness transcriptDeletion,
            MeetingChatRetentionService chatRetention,
            MeetingRecordRetentionService recordRetention,
            JdbcTemplate jdbc,
            Clock clock) {
        this.meetings = meetings;
        this.media = media;
        this.dependencies = dependencies;
        this.intelligence = intelligence;
        this.retention = retention;
        this.recordingDeletion = recordingDeletion;
        this.transcriptDeletion = transcriptDeletion;
        this.chatRetention = chatRetention;
        this.recordRetention = recordRetention;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public VideoMeetingAdminIntelligenceDtos.ReadinessResponse readiness() {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        TenantPolicy policy = meetings.policy(subject.tenantId()).orElseThrow(() ->
                new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND,
                        "The meeting tenant policy was not found."));
        CapabilityReadiness runtime = capabilityReadiness(subject.tenantId());
        boolean recordingDeletionReady = recordingDeletionReady();
        boolean transcriptDeletionReady = transcriptDeletionReady();
        boolean chatRetentionReady = chatRetentionReady();

        Map<String, ReadinessSignal> dependencySignals = new LinkedHashMap<>();
        dependencySignals.put("provider", dependency(
                runtime.mediaOperational(), "REALTIME_PROVIDER_LIVENESS_NOT_READY"));
        dependencySignals.put("region", dependency(
                runtime.safeModel()
                        && validRegion(runtime.intelligenceCapability().processingRegion()),
                "PROCESSING_REGION_NOT_VERIFIED"));
        dependencySignals.put("kms", dependency(
                runtime.dependencyStatus().kmsAvailable(), "KMS_NOT_READY"));
        dependencySignals.put("audit", dependency(
                runtime.dependencyStatus().auditAvailable(), "AUDIT_NOT_READY"));
        dependencySignals.put("egress", dependency(
                runtime.dependencyStatus().egressAvailable(), "EGRESS_NOT_READY"));
        dependencySignals.put("storage", dependency(
                runtime.dependencyStatus().storageAvailable(), "STORAGE_NOT_READY"));
        dependencySignals.put("stt", dependency(
                runtime.dependencyStatus().speechToTextAvailable(), "STT_NOT_READY"));
        dependencySignals.put("llm", dependency(
                runtime.modelOperational(), "LLM_OPERATIONAL_EVIDENCE_NOT_READY"));
        dependencySignals.put("retention", dependency(
                runtime.retentionReady(), "RETENTION_WORKER_NOT_READY"));

        Map<String, ReadinessSignal> capabilities = new LinkedHashMap<>();
        capabilities.put("recording", capability(policy, runtime.recordingReady()));
        capabilities.put("transcript", capability(policy, runtime.transcriptReady()));
        capabilities.put("aiNotes", capability(policy, runtime.intelligenceReady()));

        Map<String, ReadinessSignal> governance = new LinkedHashMap<>();
        governance.put("humanReview", verifiedControl(runtime.databaseReady()));
        governance.put("explicitPublish", verifiedControl(runtime.databaseReady()));
        governance.put("adminContentAccess", verifiedControl(runtime.databaseReady()));
        governance.put("workFollowUpPromotion", ReadinessSignal.notVerified(
                "WORK_FOLLOWUP_AUTHORITY_UNVERIFIED"));
        governance.put("followUpReassignment", ReadinessSignal.notVerified(
                "PEOPLE_TARGET_ELIGIBILITY_UNVERIFIED"));
        governance.put("legalHold", ReadinessSignal.notVerified(
                "LEGAL_HOLD_ADMIN_WORKFLOW_NOT_CONFIGURED"));
        Map<String, ReadinessSignal> retentionSignals = retentionSignals(
                runtime.retentionReady(), runtime.databaseReady(),
                recordingDeletionReady, transcriptDeletionReady, chatRetentionReady);
        governance.put("deletionEvidence", retentionSignals.values().stream()
                .allMatch(signal -> "READY".equals(signal.state()))
                ? ReadinessSignal.ready()
                : ReadinessSignal.notVerified(
                        "COMPLETE_DELETION_EVIDENCE_NOT_VERIFIED"));

        return new VideoMeetingAdminIntelligenceDtos.ReadinessResponse(
                VERSION,
                OffsetDateTime.now(clock),
                policy.recordingPolicy(),
                runtime.safeModel()
                        ? runtime.intelligenceCapability().providerCode() : "disabled",
                runtime.safeModel() ? runtime.intelligenceCapability().model() : "none",
                runtime.safeModel()
                        ? runtime.intelligenceCapability().processingRegion() : "none",
                Map.copyOf(capabilities),
                Map.copyOf(dependencySignals),
                Map.copyOf(governance),
                new VideoMeetingAdminIntelligenceDtos.RetentionReadiness(
                        policy.retentionDays(), policy.artifactRetentionDays(),
                        policy.chatRetentionDays(), runtime.retentionReady(),
                        Map.copyOf(retentionSignals)));
    }

    /**
     * Projects the same live, fail-closed probes used by the operations readiness endpoint for
     * policy editing. Policy state is intentionally excluded: an administrator must be able to
     * move from NEVER to an enabled recording policy once the governed runtime is actually ready.
     */
    public PolicyCapabilities policyCapabilities() {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        CapabilityReadiness runtime = capabilityReadiness(subject.tenantId());
        return new PolicyCapabilities(runtime.recordingReady(), runtime.intelligenceReady());
    }

    private CapabilityReadiness capabilityReadiness(long tenantId) {
        MeetingMediaProvider.Capability mediaCapability = mediaCapability();
        boolean mediaOperational = mediaCapability.available() && mediaOperational();
        MeetingContentDependencies.Status dependencyStatus = dependencyStatus();
        MeetingIntelligenceProvider.Capability intelligenceCapability =
                intelligenceCapability(tenantId);
        boolean retentionReady = retentionReady();
        boolean databaseReady = intelligenceDatabaseReady();
        boolean safeModel = enterpriseSafe(intelligenceCapability);
        boolean modelOperational = safeModel
                && dependencyStatus.languageModelAvailable()
                && languageModelOperationalEvidence(tenantId, intelligenceCapability);
        boolean recordingReady = mediaOperational
                && dependencyStatus.egressAvailable()
                && dependencyStatus.storageAvailable()
                && dependencyStatus.kmsAvailable()
                && dependencyStatus.auditAvailable();
        boolean transcriptReady = recordingReady
                && dependencyStatus.speechToTextAvailable();
        boolean intelligenceReady = transcriptReady && modelOperational
                && retentionReady && databaseReady;
        return new CapabilityReadiness(
                mediaOperational, dependencyStatus, intelligenceCapability,
                retentionReady, databaseReady, safeModel, modelOperational,
                recordingReady, transcriptReady, intelligenceReady);
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

    private boolean recordingDeletionReady() {
        try {
            return recordingDeletion.ready();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean transcriptDeletionReady() {
        try {
            return transcriptDeletion.ready();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean chatRetentionReady() {
        try {
            return chatRetention.ready();
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
            boolean databaseReady,
            boolean recordingDeletionReady,
            boolean transcriptDeletionReady,
            boolean chatRetentionReady) {
        Map<String, ReadinessSignal> signals = new LinkedHashMap<>();
        signals.put("intelligenceReports", retentionReady && databaseReady
                ? ReadinessSignal.ready()
                : ReadinessSignal.connectionRequired(
                        "INTELLIGENCE_REPORT_RETENTION_NOT_READY"));
        signals.put("meetingRecords", recordRetentionReady() ? ReadinessSignal.ready()
                : ReadinessSignal.notVerified("MEETING_RECORD_RETENTION_WORKER_NOT_READY"));
        signals.put("artifacts", recordingDeletionReady && transcriptDeletionReady
                ? ReadinessSignal.ready()
                : ReadinessSignal.connectionRequired(
                        "ARTIFACT_RETENTION_WORKERS_NOT_READY"));
        signals.put("chat", chatRetentionReady
                ? ReadinessSignal.ready()
                : ReadinessSignal.connectionRequired(
                        "RETENTION_WORKER_NOT_READY"));
        return signals;
    }

    private boolean recordRetentionReady() {
        try { return recordRetention.ready(); }
        catch (RuntimeException exception) { return false; }
    }

    private MeetingMediaProvider.Capability unavailableMedia() {
        return new MeetingMediaProvider.Capability(
                false, "disabled", "MEETING_PROVIDER_UNAVAILABLE",
                false, false, false, false, 0);
    }

    public record PolicyCapabilities(
            boolean recordingConfigured,
            boolean aiNotesConfigured) {

        public static PolicyCapabilities unavailable() {
            return new PolicyCapabilities(false, false);
        }
    }

    private record CapabilityReadiness(
            boolean mediaOperational,
            MeetingContentDependencies.Status dependencyStatus,
            MeetingIntelligenceProvider.Capability intelligenceCapability,
            boolean retentionReady,
            boolean databaseReady,
            boolean safeModel,
            boolean modelOperational,
            boolean recordingReady,
            boolean transcriptReady,
            boolean intelligenceReady) {
    }
}
