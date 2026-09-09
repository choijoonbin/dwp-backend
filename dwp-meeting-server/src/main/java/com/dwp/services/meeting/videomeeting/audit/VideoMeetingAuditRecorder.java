package com.dwp.services.meeting.videomeeting.audit;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class VideoMeetingAuditRecorder {

    private static final String MODULE = "enterprise-video-meeting";

    private final AuditOutboxRecorder outbox;

    public VideoMeetingAuditRecorder(AuditOutboxRecorder outbox) {
        this.outbox = outbox;
    }

    public void meetingLifecycle(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            String action,
            String correlationId,
            Map<String, Object> afterState) {
        record(subject, "SYSTEM_EVENT", action, "VIDEO_MEETING",
                meeting.meetingId().toString(), correlationId, "INFO", "STANDARD", afterState);
    }

    public void participantAccess(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            Participant participant,
            String action,
            String correlationId,
            String outcome,
            Map<String, Object> afterState) {
        outbox.record(base(subject, "AUTHORIZATION", action, correlationId)
                .outcome(outcome)
                .severity("DENIED".equals(outcome) ? "LOW" : "INFO")
                .targetType("VIDEO_MEETING_PARTICIPANT")
                .targetId(participant.participantId().toString())
                .afterState(merge(afterState, Map.of(
                        "meetingId", meeting.meetingId().toString(),
                        "participantRole", participant.participantRole().name(),
                        "attendanceState", participant.attendanceState().name())))
                .retentionClass("EXTENDED")
                .build());
    }

    public void policyChanged(
            MeetingRequestContext.Subject subject,
            long policyVersion,
            String correlationId,
            Map<String, Object> afterState) {
        record(subject, "ADMIN_CHANGE", "meeting.policy.updated", "MEETING_TENANT_POLICY",
                Long.toString(subject.tenantId()), correlationId, "MEDIUM", "EXTENDED",
                merge(afterState, Map.of("policyVersion", policyVersion)));
    }

    /** Workspace audit carries identifiers and versions, never agenda, names or invitation aliases. */
    public void workspaceChanged(
            MeetingRequestContext.Subject subject, String action, String targetType,
            String targetId, String correlationId, Map<String, Object> metadata) {
        record(subject, "SYSTEM_EVENT", action, targetType, targetId, correlationId,
                "INFO", "STANDARD", metadata);
    }

    /** Export evidence is metadata-only and never contains meeting, participant, or report data. */
    public void adminOperationsExport(
            MeetingRequestContext.Subject subject,
            String correlationId,
            Map<String, Object> evidence) {
        record(subject, "DATA_EXPORT", "meeting.admin.operations.exported",
                "MEETING_ADMIN_OPERATIONS_EXPORT", Long.toString(subject.tenantId()),
                correlationId, "INFO", "EXTENDED", evidence);
    }

    /** Report export evidence is metadata-only and never contains meeting or report text. */
    public void intelligenceReportExport(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            UUID reportId,
            String correlationId,
            Map<String, Object> evidence) {
        record(subject, "DATA_EXPORT", "meeting.intelligence.report-exported",
                "MEETING_INTELLIGENCE_REPORT", reportId.toString(), correlationId,
                "INFO", "EXTENDED",
                merge(evidence, Map.of("meetingId", meeting.meetingId().toString())));
    }

    public void collaboration(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            String action,
            String targetType,
            String targetId,
            String correlationId,
            boolean moderation,
            Map<String, Object> afterState) {
        record(subject, moderation ? "ADMIN_CHANGE" : "SYSTEM_EVENT", action,
                targetType, targetId, correlationId,
                moderation ? "LOW" : "INFO",
                moderation ? "EXTENDED" : "STANDARD",
                merge(afterState, Map.of("meetingId", meeting.meetingId().toString())));
    }

    public void recordingAccess(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            UUID artifactId,
            String action,
            String correlationId,
            Map<String, Object> afterState) {
        record(subject, "AUTHORIZATION", action, "MEETING_RECORDING_ARTIFACT",
                artifactId.toString(), correlationId, "INFO", "EXTENDED",
                merge(afterState, Map.of("meetingId", meeting.meetingId().toString())));
    }

    /** Transcript access metadata never contains source text, search terms, or object locators. */
    public void transcriptAccess(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            UUID artifactId,
            String action,
            String correlationId,
            Map<String, Object> afterState) {
        record(subject, "AUTHORIZATION", action, "MEETING_TRANSCRIPT_ARTIFACT",
                artifactId.toString(), correlationId, "INFO", "EXTENDED",
                merge(afterState, Map.of("meetingId", meeting.meetingId().toString())));
    }

    public void recordingDeletion(
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            String action,
            String correlationId,
            String outcome,
            Map<String, Object> afterState) {
        outbox.record(AuditEvent.builder()
                .tenantId(tenantId)
                .category("SYSTEM_EVENT")
                .action(action)
                .actorType("SERVICE")
                .actorId("MEETING_RECORDING_RETENTION")
                .actorRoles(List.of("SYSTEM_RETENTION"))
                .sourceService("dwp-meeting-server")
                .sourceModule(MODULE)
                .correlationId(correlationId)
                .outcome(outcome)
                .severity("FAILED".equals(outcome) ? "MEDIUM" : "INFO")
                .targetType("MEETING_RECORDING_ARTIFACT")
                .targetId(artifactId.toString())
                .afterState(merge(afterState, Map.of("meetingId", meetingId.toString())))
                .retentionClass("EXTENDED")
                .build());
    }

    public void transcriptDeletion(
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            String action,
            String correlationId,
            String outcome,
            Map<String, Object> afterState) {
        outbox.record(AuditEvent.builder()
                .tenantId(tenantId)
                .category("SYSTEM_EVENT")
                .action(action)
                .actorType("SERVICE")
                .actorId("MEETING_TRANSCRIPT_RETENTION")
                .actorRoles(List.of("SYSTEM_RETENTION"))
                .sourceService("dwp-meeting-server")
                .sourceModule(MODULE)
                .correlationId(correlationId)
                .outcome(outcome)
                .severity("FAILED".equals(outcome) ? "MEDIUM" : "INFO")
                .targetType("MEETING_TRANSCRIPT_ARTIFACT")
                .targetId(artifactId.toString())
                .afterState(merge(afterState, Map.of("meetingId", meetingId.toString())))
                .retentionClass("EXTENDED")
                .build());
    }

    public void providerLifecycle(
            long tenantId,
            Meeting meeting,
            String action,
            String providerEventId,
            Map<String, Object> afterState) {
        providerEvent(
                tenantId, action, "VIDEO_MEETING", meeting.meetingId().toString(),
                providerEventId, merge(afterState,
                        Map.of("meetingId", meeting.meetingId().toString())));
    }

    public void providerParticipant(
            long tenantId,
            Meeting meeting,
            Participant participant,
            String action,
            String providerEventId,
            Map<String, Object> afterState) {
        providerEvent(
                tenantId, action, "VIDEO_MEETING_PARTICIPANT",
                participant.participantId().toString(), providerEventId,
                merge(afterState, Map.of(
                        "meetingId", meeting.meetingId().toString(),
                        "attendanceState", participant.attendanceState().name())));
    }

    private void providerEvent(
            long tenantId,
            String action,
            String targetType,
            String targetId,
            String providerEventId,
            Map<String, Object> afterState) {
        outbox.record(AuditEvent.builder()
                .tenantId(tenantId)
                .category("SYSTEM_EVENT")
                .action(action)
                .actorType("SERVICE")
                .actorId("LIVEKIT")
                .sourceService("dwp-meeting-server")
                .sourceModule(MODULE)
                .correlationId(providerEventId)
                .outcome("SUCCESS")
                .severity("INFO")
                .targetType(targetType)
                .targetId(targetId)
                .afterState(afterState)
                .retentionClass("EXTENDED")
                .build());
    }

    private void record(
            MeetingRequestContext.Subject subject,
            String category,
            String action,
            String targetType,
            String targetId,
            String correlationId,
            String severity,
            String retentionClass,
            Map<String, Object> afterState) {
        outbox.record(base(subject, category, action, correlationId)
                .outcome("SUCCESS")
                .severity(severity)
                .targetType(targetType)
                .targetId(targetId)
                .afterState(afterState)
                .retentionClass(retentionClass)
                .build());
    }

    private AuditEvent.Builder base(
            MeetingRequestContext.Subject subject,
            String category,
            String action,
            String correlationId) {
        return AuditEvent.builder()
                .tenantId(subject.tenantId())
                .category(category)
                .action(action)
                .actorType("USER")
                .actorId(Long.toString(subject.userId()))
                .actorDisplayName(subject.displayName())
                .actorRoles(List.copyOf(subject.roles()))
                .sourceService("dwp-meeting-server")
                .sourceModule(MODULE)
                .correlationId(correlationId);
    }

    private Map<String, Object> merge(
            Map<String, Object> left,
            Map<String, Object> right) {
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(left);
        merged.putAll(right);
        return Map.copyOf(merged);
    }
}
