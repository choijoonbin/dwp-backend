package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Artifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingCard;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.PersonSnapshot;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

final class VideoMeetingJdbcCodec {

    private final ObjectMapper objectMapper;

    VideoMeetingJdbcCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    MeetingCard meetingCard(ResultSet resultSet, int rowNumber) throws SQLException {
        String viewerRole = resultSet.getString("viewer_role");
        return new MeetingCard(
                meeting(resultSet, rowNumber), resultSet.getInt("participant_count"),
                viewerRole == null ? null : ParticipantRole.valueOf(viewerRole));
    }

    Meeting meeting(ResultSet resultSet, int rowNumber) throws SQLException {
        long endedBy = resultSet.getLong("ended_by");
        boolean endedByNull = resultSet.wasNull();
        return new Meeting(
                resultSet.getObject("meeting_id", UUID.class),
                resultSet.getLong("tenant_id"), resultSet.getString("title"),
                resultSet.getString("description"), resultSet.getString("agenda"),
                VideoMeetingModels.LifecycleState.valueOf(resultSet.getString("lifecycle_state")),
                VideoMeetingModels.AccessScope.valueOf(resultSet.getString("access_scope")),
                resultSet.getString("join_code"),
                resultSet.getObject("scheduled_start_at", OffsetDateTime.class),
                resultSet.getObject("scheduled_end_at", OffsetDateTime.class),
                resultSet.getString("time_zone"), resultSet.getBoolean("waiting_room_enabled"),
                resultSet.getBoolean("guest_access_enabled"),
                resultSet.getBoolean("allow_join_before_host"),
                resultSet.getBoolean("default_microphone_enabled"),
                resultSet.getBoolean("default_camera_enabled"), resultSet.getString("provider"),
                resultSet.getString("room_name"), resultSet.getLong("organizer_user_id"),
                resultSet.getObject("organizer_person_public_id", UUID.class),
                resultSet.getString("organizer_name"),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.getObject("ended_at", OffsetDateTime.class),
                endedByNull ? null : endedBy,
                parse(resultSet.getString("decisions")),
                parse(resultSet.getString("follow_up_actions")),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    Participant participant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Participant(
                resultSet.getObject("participant_id", UUID.class),
                resultSet.getLong("tenant_id"), resultSet.getObject("meeting_id", UUID.class),
                nullableLong(resultSet, "user_id"),
                resultSet.getObject("person_public_id", UUID.class),
                resultSet.getString("email_address"), resultSet.getString("display_name"),
                resultSet.getString("job_title"), resultSet.getString("organization_name"),
                ParticipantRole.valueOf(resultSet.getString("participant_role")),
                AttendanceState.valueOf(resultSet.getString("attendance_state")),
                resultSet.getBoolean("can_self_unmute"),
                resultSet.getObject("join_requested_at", OffsetDateTime.class),
                resultSet.getObject("admitted_at", OffsetDateTime.class),
                nullableLong(resultSet, "admitted_by"),
                resultSet.getObject("joined_at", OffsetDateTime.class),
                resultSet.getObject("left_at", OffsetDateTime.class),
                resultSet.getObject("unmute_requested_at", OffsetDateTime.class),
                nullableLong(resultSet, "unmute_requested_by"), resultSet.getLong("version"));
    }

    Artifact artifact(ResultSet resultSet, int rowNumber) throws SQLException {
        long size = resultSet.getLong("size_bytes");
        boolean sizeNull = resultSet.wasNull();
        return new Artifact(
                resultSet.getObject("artifact_id", UUID.class), resultSet.getLong("tenant_id"),
                resultSet.getObject("meeting_id", UUID.class),
                resultSet.getString("artifact_type"), resultSet.getString("artifact_state"),
                resultSet.getString("content_type"), sizeNull ? null : size,
                resultSet.getObject("retention_until", OffsetDateTime.class),
                parse(resultSet.getString("metadata")), resultSet.getLong("version"));
    }

    TenantPolicy policy(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TenantPolicy(
                resultSet.getLong("tenant_id"), resultSet.getBoolean("meetings_enabled"),
                resultSet.getBoolean("waiting_room_required"),
                resultSet.getBoolean("guests_allowed"),
                resultSet.getBoolean("participant_chat_allowed"),
                resultSet.getBoolean("reactions_allowed"),
                resultSet.getBoolean("screen_share_allowed"),
                resultSet.getString("unmute_control"),
                resultSet.getString("recording_policy"),
                resultSet.getBoolean("allow_join_before_host"),
                resultSet.getBoolean("require_authenticated_internal_users"),
                resultSet.getInt("maximum_participants"), resultSet.getInt("retention_days"),
                resultSet.getInt("artifact_retention_days"),
                resultSet.getInt("chat_retention_days"), resultSet.getLong("version"));
    }

    PersonSnapshot person(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PersonSnapshot(
                resultSet.getLong("tenant_id"), resultSet.getLong("user_id"),
                resultSet.getObject("person_public_id", UUID.class),
                resultSet.getString("email_address"), resultSet.getString("display_name"),
                resultSet.getString("job_title"), resultSet.getString("organization_name"));
    }

    String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Meeting event payload is not serializable.", exception);
        }
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value == null ? "null" : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Meeting JSON state is invalid.", exception);
        }
    }
}
