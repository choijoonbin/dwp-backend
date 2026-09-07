package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.PreferencesInput;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.PreferencesResponse;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class MeetingPreferencesService {
    private final MeetingPreferencesRepository preferences;
    private final MeetingWorkspaceCommands commands;
    private final VideoMeetingAuditRecorder audit;

    public MeetingPreferencesService(MeetingPreferencesRepository preferences,
            MeetingWorkspaceCommands commands, VideoMeetingAuditRecorder audit) {
        this.preferences = preferences;
        this.commands = commands;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PreferencesResponse get() {
        var actor = MeetingWorkspacePolicy.require("APP.MEETINGS", "VIEW");
        return preferences.find(actor.tenantId(), actor.userId(), false)
                .orElse(new PreferencesResponse("", true, true, true, true, 10, true, 0, null));
    }

    @Transactional
    public PreferencesResponse update(PreferencesInput input, String key, String correlation) {
        var actor = MeetingWorkspacePolicy.require("APP.MEETINGS", "VIEW");
        MeetingWorkspacePolicy.text(input.displayName(), 100, false);
        if (input.reminderMinutes() < 0 || input.reminderMinutes() > 60) throw MeetingWorkspacePolicy.invalid();
        var attempt = commands.begin("PREFERENCES_UPDATE", key, input);
        var current = preferences.lock(actor.tenantId(), actor.userId());
        if (attempt.replay() != null) return current;
        MeetingWorkspacePolicy.version(current.version(), input.expectedVersion());
        var updated = preferences.update(actor.tenantId(), actor.userId(), input);
        audit.workspaceChanged(actor, "meeting.preferences.updated", "MEETING_USER_PREFERENCES",
                Long.toString(actor.userId()), correlation, Map.of("version", updated.version()));
        commands.complete(attempt, null, updated.version());
        return updated;
    }
}
