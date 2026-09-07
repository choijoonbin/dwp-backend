package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.PreferencesInput;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.PreferencesResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class MeetingPreferencesRepository {
    private final JdbcTemplate jdbc;
    public MeetingPreferencesRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<PreferencesResponse> find(long tenant, long user, boolean lock) {
        return jdbc.query("SELECT * FROM vm_meeting_user_preferences WHERE tenant_id = ? AND user_id = ?"
                + (lock ? " FOR UPDATE" : ""), (row, index) -> new PreferencesResponse(
                        row.getString("display_name"), row.getBoolean("microphone_off"),
                        row.getBoolean("camera_off"), row.getBoolean("prejoin_enabled"),
                        row.getBoolean("reminder_enabled"), row.getInt("reminder_minutes"),
                        row.getBoolean("recap_notifications"), row.getLong("version"),
                        row.getObject("updated_at", OffsetDateTime.class)), tenant, user)
                .stream().findFirst();
    }

    public PreferencesResponse lock(long tenant, long user) {
        jdbc.update("INSERT INTO vm_meeting_user_preferences (tenant_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                tenant, user);
        return find(tenant, user, true).orElseThrow();
    }

    public PreferencesResponse update(long tenant, long user, PreferencesInput input) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_user_preferences
                   SET display_name = ?, microphone_off = ?, camera_off = ?, prejoin_enabled = ?,
                       reminder_enabled = ?, reminder_minutes = ?, recap_notifications = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ? AND version = ?
                """, input.displayName().trim(), input.microphoneOff(), input.cameraOff(),
                input.prejoinEnabled(), input.reminderEnabled(), input.reminderMinutes(),
                input.recapNotifications(), tenant, user, input.expectedVersion());
        if (updated != 1) throw MeetingWorkspacePolicy.missing();
        return find(tenant, user, false).orElseThrow();
    }
}
