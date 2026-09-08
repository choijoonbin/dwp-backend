package com.dwp.services.meeting.videomeeting.audit;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MeetingRecordRetentionAuditRecorder {
    private final AuditOutboxRecorder outbox;
    public MeetingRecordRetentionAuditRecorder(AuditOutboxRecorder outbox) { this.outbox = outbox; }

    public UUID control(long tenant, UUID meeting, long actor, long version, boolean hold, boolean authorize) {
        return record(tenant, meeting, "meeting.record.retention.controlled", "USER", Long.toString(actor),
                Map.of("controlVersion", version, "hold", hold, "purgeAuthorized", authorize));
    }

    public UUID purged(long tenant, UUID meeting, String worker, UUID fence, UUID deletion) {
        return record(tenant, meeting, "meeting.record.retention.purged", "SERVICE", worker,
                Map.of("deletionId", deletion.toString(), "fenceToken", fence.toString(),
                        "deletionReason", "RETENTION_EXPIRED"));
    }

    private UUID record(long tenant, UUID meeting, String action, String actorType, String actor,
            Map<String, Object> metadata) {
        return outbox.record(AuditEvent.builder().tenantId(tenant).category("SYSTEM_EVENT")
                .action(action).actorType(actorType).actorId(actor).actorRoles(List.of("RECORD_RETENTION"))
                .sourceService("dwp-meeting-server").sourceModule("enterprise-video-meeting")
                .outcome("SUCCESS").severity("INFO").targetType("MEETING_RECORD")
                .targetId(meeting.toString()).afterState(metadata).retentionClass("EXTENDED").build());
    }
}
