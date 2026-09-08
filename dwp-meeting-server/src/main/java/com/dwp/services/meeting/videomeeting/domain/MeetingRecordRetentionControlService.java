package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingRecordRetentionDtos.ControlInput;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordRetentionDtos.ControlState;
import com.dwp.services.meeting.videomeeting.audit.MeetingRecordRetentionAuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
public class MeetingRecordRetentionControlService {
    private final MeetingRecordDispositionRepository records;
    private final MeetingRecordRetentionGuard guard;
    private final MeetingRecordRetentionProperties properties;
    private final MeetingWorkspaceCommands commands;
    private final MeetingRecordRetentionAuditRecorder audit;

    public MeetingRecordRetentionControlService(MeetingRecordDispositionRepository records,
            MeetingRecordRetentionGuard guard, MeetingRecordRetentionProperties properties,
            MeetingWorkspaceCommands commands, MeetingRecordRetentionAuditRecorder audit) {
        this.records = records; this.guard = guard; this.properties = properties;
        this.commands = commands; this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ControlState read(UUID meeting) {
        var actor = MeetingWorkspacePolicy.require("ADMIN.MEETINGS", "VIEW");
        return projection(actor.tenantId(), meeting);
    }

    @Transactional
    public ControlState update(UUID meeting, ControlInput input, String key) {
        var actor = MeetingWorkspacePolicy.require("ADMIN.MEETINGS", "MANAGE");
        if (meeting == null || input == null || input.hold() == null || input.purgeAuthorized() == null
                || input.expectedControlVersion() == null || input.expectedControlVersion() < 0
                || input.expectedMeetingVersion() == null || input.expectedMeetingVersion() < 0
                || input.expectedPolicyVersion() == null || input.expectedPolicyVersion() < 0
                || input.hold() && input.purgeAuthorized()) throw MeetingWorkspacePolicy.invalid();
        // Re-evaluate current tenant permissions before every replay, including tombstones.
        var attempt = commands.begin("RECORD_RETENTION_CONTROL", key, new Command(meeting, input));
        records.lockScope(actor.tenantId(), meeting);
        var control = records.control(actor.tenantId(), meeting, true);
        var snapshot = records.snapshot(actor.tenantId(), meeting, true);
        if (snapshot == null && (control == null || control.purgedAt() == null)) throw MeetingWorkspacePolicy.missing();
        if (attempt.replay() != null) return projection(actor.tenantId(), meeting);
        if (snapshot == null || control != null && control.purgedAt() != null) {
            throw MeetingWorkspacePolicy.invalid();
        }
        MeetingWorkspacePolicy.version(snapshot.meetingVersion(), input.expectedMeetingVersion());
        MeetingWorkspacePolicy.version(snapshot.policyVersion(), input.expectedPolicyVersion());
        MeetingWorkspacePolicy.version(control == null ? 0 : control.version(), input.expectedControlVersion());
        // Approval is only possible after the tenant's existing retention period, never for a live record.
        if (input.purgeAuthorized() && (!List.of("ENDED", "CANCELLED").contains(snapshot.lifecycle())
                || snapshot.deadline().isAfter(records.now()))) throw MeetingWorkspacePolicy.invalid();
        UUID event = audit.control(actor.tenantId(), meeting, actor.userId(),
                input.expectedControlVersion() + 1, input.hold(), input.purgeAuthorized());
        records.save(actor.tenantId(), meeting, snapshot, input.expectedControlVersion(),
                input.hold(), input.purgeAuthorized(), event, actor.userId());
        commands.complete(attempt, meeting, input.expectedControlVersion() + 1);
        return projection(actor.tenantId(), meeting);
    }

    private ControlState projection(long tenant, UUID meeting) {
        var control = records.control(tenant, meeting, false);
        var snapshot = records.snapshot(tenant, meeting, false);
        if (control != null && control.purgedAt() != null) {
            return new ControlState(meeting, control.meetingVersion(), control.policyVersion(), control.version(),
                    control.deadline(), control.hold(), control.authorized(), "PURGED", List.of(), true,
                    properties.isEnabled(), control.purgedAt());
        }
        if (snapshot == null) throw MeetingWorkspacePolicy.missing();
        boolean hold = control != null && control.hold();
        boolean authorized = control != null && control.authorized();
        var reasons = guard.reasons(tenant, meeting, snapshot, control, false);
        String state = hold ? "HELD" : !properties.isEnabled() ? "UNCONFIGURED"
                : !authorized ? "AWAITING_AUTHORIZATION" : reasons.isEmpty() ? "ELIGIBLE" : "BLOCKED";
        return new ControlState(meeting, snapshot.meetingVersion(), snapshot.policyVersion(),
                control == null ? 0 : control.version(), snapshot.deadline(), hold, authorized, state, reasons,
                control != null && records.published(tenant, meeting, control.auditId()), properties.isEnabled(), null);
    }

    private record Command(UUID meetingId, ControlInput input) { }
}
