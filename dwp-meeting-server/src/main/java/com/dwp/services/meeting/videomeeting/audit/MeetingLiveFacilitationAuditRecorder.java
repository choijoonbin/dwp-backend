package com.dwp.services.meeting.videomeeting.audit;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MeetingLiveFacilitationAuditRecorder {

    private final AuditOutboxRecorder outbox;

    public MeetingLiveFacilitationAuditRecorder(AuditOutboxRecorder outbox) {
        this.outbox = outbox;
    }

    /** Content is deliberately excluded: audit stores only identity, state and version evidence. */
    public void command(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            String action,
            String targetType,
            UUID targetId,
            String correlationId,
            Map<String, Object> evidence) {
        outbox.record(AuditEvent.builder()
                .tenantId(subject.tenantId())
                .category(subject.userId() == meeting.organizerUserId()
                        ? "ADMIN_CHANGE" : "SYSTEM_EVENT")
                .action(action)
                .actorType("USER")
                .actorId(Long.toString(subject.userId()))
                .actorDisplayName(subject.displayName())
                .actorRoles(List.copyOf(subject.roles()))
                .sourceService("dwp-meeting-server")
                .sourceModule("enterprise-video-meeting")
                .correlationId(correlationId)
                .outcome("SUCCESS")
                .severity("INFO")
                .targetType(targetType)
                .targetId(targetId == null ? meeting.meetingId().toString() : targetId.toString())
                .afterState(merge(evidence, Map.of("meetingId", meeting.meetingId().toString())))
                .retentionClass("EXTENDED")
                .build());
    }

    public void retention(long tenantId, UUID meetingId, UUID executionId) {
        outbox.record(AuditEvent.builder()
                .tenantId(tenantId)
                .category("SYSTEM_EVENT")
                .action("meeting.facilitation.retention.purged")
                .actorType("SERVICE")
                .actorId("MEETING_FACILITATION_RETENTION")
                .actorRoles(List.of("SYSTEM_RETENTION"))
                .sourceService("dwp-meeting-server")
                .sourceModule("enterprise-video-meeting")
                .correlationId(executionId.toString())
                .outcome("SUCCESS")
                .severity("INFO")
                .targetType("VIDEO_MEETING_FACILITATION")
                .targetId(meetingId.toString())
                .afterState(Map.of("meetingId", meetingId.toString(), "purged", true))
                .retentionClass("EXTENDED")
                .build());
    }

    private Map<String, Object> merge(
            Map<String, Object> left, Map<String, Object> right) {
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(left);
        merged.putAll(right);
        return Map.copyOf(merged);
    }
}
