package com.dwp.services.meeting.videomeeting.audit;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MeetingChatRetentionAuditRecorder {

    private static final String MODULE = "enterprise-video-meeting";
    private final AuditOutboxRecorder outbox;

    public MeetingChatRetentionAuditRecorder(AuditOutboxRecorder outbox) {
        this.outbox = outbox;
    }

    public void purged(
            long tenantId,
            UUID meetingId,
            UUID messageId,
            UUID executionId,
            UUID fence,
            String workerId) {
        outbox.record(AuditEvent.builder()
                .tenantId(tenantId)
                .category("SYSTEM_EVENT")
                .action("meeting.chat.retention.purged")
                .actorType("SERVICE")
                .actorId(workerId)
                .actorRoles(List.of("SYSTEM_RETENTION"))
                .sourceService("dwp-meeting-server")
                .sourceModule(MODULE)
                .correlationId(executionId.toString())
                .outcome("SUCCESS")
                .severity("INFO")
                .targetType("MEETING_CHAT_MESSAGE")
                .targetId(messageId.toString())
                .afterState(Map.of(
                        "meetingId", meetingId.toString(),
                        "deletionReason", "RETENTION_EXPIRED",
                        "fenceToken", fence.toString()))
                .retentionClass("EXTENDED")
                .build());
    }
}
