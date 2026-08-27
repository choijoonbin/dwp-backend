package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Artifact;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.HomeProjection;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingCard;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingDetail;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.ParticipantRole;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.PersonSnapshot;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class VideoMeetingRepository {

    static final String ACCESS_PREDICATE = """
            (meeting.organizer_user_id = :userId
             OR meeting.access_scope = 'INTERNAL'
             OR EXISTS (
                 SELECT 1 FROM vm_meeting_participants access
                  WHERE access.tenant_id = meeting.tenant_id
                    AND access.meeting_id = meeting.meeting_id
                    AND access.user_id = :userId))
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final VideoMeetingJdbcCodec codec;
    private final VideoMeetingQueryRepository queries;
    private final RowMapper<Meeting> meetingMapper;
    private final RowMapper<Participant> participantMapper;

    public VideoMeetingRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.codec = new VideoMeetingJdbcCodec(objectMapper);
        this.queries = new VideoMeetingQueryRepository(jdbc, namedJdbc, codec);
        this.meetingMapper = codec::meeting;
        this.participantMapper = codec::participant;
    }

    public TenantPolicy ensurePolicy(long tenantId, long actorUserId) {
        jdbc.update("""
                INSERT INTO vm_tenant_policies (tenant_id, created_by, updated_by)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenantId, actorUserId, actorUserId);
        return policy(tenantId).orElseThrow(() -> new BaseException(
                ErrorCode.ENTITY_NOT_FOUND, "The meeting tenant policy was not found."));
    }

    public Optional<TenantPolicy> policy(long tenantId) {
        return jdbc.query("""
                SELECT tenant_id, meetings_enabled, waiting_room_required, guests_allowed,
                       participant_chat_allowed, reactions_allowed, screen_share_allowed,
                       unmute_control, recording_policy, allow_join_before_host,
                       require_authenticated_internal_users, maximum_participants, retention_days,
                       artifact_retention_days, chat_retention_days, version
                  FROM vm_tenant_policies
                 WHERE tenant_id = ?
                """, codec::policy, tenantId).stream().findFirst();
    }

    public TenantPolicy updatePolicy(
            long tenantId,
            VideoMeetingDtos.TenantPolicyUpdateRequest request,
            long actorUserId) {
        return jdbc.query("""
                UPDATE vm_tenant_policies
                   SET meetings_enabled = ?, waiting_room_required = ?, guests_allowed = ?,
                       participant_chat_allowed = ?, reactions_allowed = ?,
                       screen_share_allowed = ?,
                       allow_join_before_host = ?, require_authenticated_internal_users = ?,
                       maximum_participants = ?, recording_policy = ?,
                       retention_days = ?, artifact_retention_days = ?,
                       chat_retention_days = COALESCE(?, chat_retention_days),
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND version = ?
                RETURNING tenant_id, meetings_enabled, waiting_room_required, guests_allowed,
                          participant_chat_allowed, reactions_allowed, screen_share_allowed,
                          unmute_control, recording_policy, allow_join_before_host,
                          require_authenticated_internal_users, maximum_participants,
                          retention_days,
                          artifact_retention_days, chat_retention_days, version
                """, codec::policy,
                request.meetingsEnabled(), request.waitingRoomRequired(), request.guestsAllowed(),
                request.participantChatAllowed(), request.reactionsAllowed(),
                request.screenShareAllowed(),
                request.allowJoinBeforeHost(), request.requireAuthenticatedInternalUsers(),
                request.maximumParticipants(), request.recordingPolicy(),
                request.retentionDays(), request.artifactRetentionDays(),
                request.chatRetentionDays(),
                actorUserId, tenantId, request.expectedVersion())
                .stream().findFirst().orElseThrow(this::versionConflict);
    }

    public Optional<PersonSnapshot> person(long tenantId, long userId) {
        return jdbc.query("""
                SELECT tenant_id, user_id, person_public_id, email_address, display_name,
                       job_title, organization_name
                  FROM vm_people_snapshot
                 WHERE tenant_id = ? AND user_id = ? AND lifecycle_state = 'ACTIVE'
                """, codec::person, tenantId, userId).stream().findFirst();
    }

    public List<PersonSnapshot> people(long tenantId, List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return List.of();
        return namedJdbc.query("""
                SELECT tenant_id, user_id, person_public_id, email_address, display_name,
                       job_title, organization_name
                  FROM vm_people_snapshot
                 WHERE tenant_id = :tenantId
                   AND user_id IN (:userIds)
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY user_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userIds", userIds), codec::person);
    }

    public List<PersonSnapshot> searchPeople(
            long tenantId, long requestingUserId, String query, int limit) {
        String pattern = "%" + query.toLowerCase(java.util.Locale.ROOT) + "%";
        return jdbc.query("""
                SELECT tenant_id, user_id, person_public_id, email_address, display_name,
                       job_title, organization_name
                  FROM vm_people_snapshot
                 WHERE tenant_id = ?
                   AND lifecycle_state = 'ACTIVE'
                   AND user_id <> ?
                   AND (? = '' OR LOWER(display_name) LIKE ?
                        OR LOWER(email_address) LIKE ?
                        OR LOWER(COALESCE(job_title, '')) LIKE ?
                        OR LOWER(COALESCE(organization_name, '')) LIKE ?)
                 ORDER BY display_name, user_id
                 LIMIT ?
                """, codec::person,
                tenantId, requestingUserId, query, pattern, pattern, pattern, pattern, limit);
    }

    public int activeParticipantCount(long tenantId, UUID meetingId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM vm_meeting_participants
                 WHERE tenant_id = ? AND meeting_id = ?
                   AND attendance_state <> 'DENIED'
                """, Integer.class, tenantId, meetingId);
        return count == null ? 0 : count;
    }

    public boolean joinCodeExists(long tenantId, String joinCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM vm_meetings
                 WHERE tenant_id = ? AND join_code = ?
                """, Integer.class, tenantId, joinCode);
        return count != null && count > 0;
    }

    public Optional<IdempotentMeeting> byIdempotency(
            long tenantId, long organizerUserId, String idempotencyKey) {
        return jdbc.query("""
                SELECT meeting.*, meeting.request_hash AS stored_request_hash
                  FROM vm_meetings meeting
                 WHERE tenant_id = ? AND organizer_user_id = ? AND idempotency_key = ?
                """, (resultSet, rowNumber) -> new IdempotentMeeting(
                        codec.meeting(resultSet, rowNumber),
                        resultSet.getString("stored_request_hash")),
                tenantId, organizerUserId, idempotencyKey)
                .stream().findFirst();
    }

    public Meeting create(CreateMeeting command) {
        try {
            return jdbc.query("""
                    INSERT INTO vm_meetings (
                        meeting_id, tenant_id, title, description, agenda, lifecycle_state,
                        access_scope, join_code, scheduled_start_at, scheduled_end_at,
                        time_zone, waiting_room_enabled, guest_access_enabled,
                        allow_join_before_host, default_microphone_enabled,
                        default_camera_enabled,
                        organizer_user_id, organizer_person_public_id, organizer_name,
                        idempotency_key, request_hash, correlation_id, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING *
                    """, meetingMapper,
                    command.meetingId(), command.tenantId(), command.title(),
                    command.description(), command.agenda(), command.lifecycleState(),
                    command.accessScope(), command.joinCode(), command.scheduledStartAt(),
                    command.scheduledEndAt(), command.timeZone(), command.waitingRoomEnabled(),
                    command.guestAccessEnabled(), command.allowJoinBeforeHost(),
                    command.defaultMicrophoneEnabled(), command.defaultCameraEnabled(),
                    command.organizer().userId(),
                    command.organizer().personPublicId(), command.organizer().displayName(),
                    command.idempotencyKey(), command.requestHash(), command.correlationId(),
                    command.organizer().userId(), command.organizer().userId())
                    .stream().findFirst().orElseThrow(() -> new BaseException(
                            ErrorCode.RESOURCE_CONFLICT, "The meeting could not be created."));
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The meeting conflicts with an existing command or join code.", exception);
        }
    }

    public Participant addInternalParticipant(
            Meeting meeting,
            PersonSnapshot person,
            ParticipantRole role,
            AttendanceState state,
            long actorUserId) {
        OffsetDateTime admittedAt = state == AttendanceState.ADMITTED
                ? OffsetDateTime.now() : null;
        return jdbc.query("""
                INSERT INTO vm_meeting_participants (
                    participant_id, tenant_id, meeting_id, user_id, person_public_id,
                    email_address, display_name, job_title, organization_name,
                    participant_role, attendance_state, admitted_at, admitted_by,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """, participantMapper,
                UUID.randomUUID(), meeting.tenantId(), meeting.meetingId(), person.userId(),
                person.personPublicId(), person.emailAddress(), person.displayName(),
                person.jobTitle(), person.organizationName(), role.name(), state.name(),
                admittedAt, admittedAt == null ? null : actorUserId, actorUserId, actorUserId)
                .stream().findFirst().orElseThrow();
    }

    public Participant addGuestParticipant(
            Meeting meeting,
            VideoMeetingDtos.GuestInvitee guest,
            long actorUserId) {
        return jdbc.query("""
                INSERT INTO vm_meeting_participants (
                    participant_id, tenant_id, meeting_id, email_address, display_name,
                    participant_role, attendance_state, created_by, updated_by)
                VALUES (?, ?, ?, lower(?), ?, 'GUEST', 'INVITED', ?, ?)
                RETURNING *
                """, participantMapper,
                UUID.randomUUID(), meeting.tenantId(), meeting.meetingId(),
                guest.emailAddress(), guest.displayName(), actorUserId, actorUserId)
                .stream().findFirst().orElseThrow();
    }

    public Optional<Meeting> accessibleMeeting(long tenantId, UUID meetingId, long userId) {
        MapSqlParameterSource parameters = accessParameters(tenantId, meetingId, userId);
        return namedJdbc.query("""
                SELECT meeting.* FROM vm_meetings meeting
                 WHERE meeting.tenant_id = :tenantId
                   AND meeting.meeting_id = :meetingId
                   AND
                """ + ACCESS_PREDICATE,
                parameters, meetingMapper).stream().findFirst();
    }

    public Optional<Meeting> resolveCode(long tenantId, String joinCode) {
        return jdbc.query("""
                SELECT * FROM vm_meetings
                 WHERE tenant_id = ? AND join_code = ?
                   AND lifecycle_state IN ('SCHEDULED', 'LOBBY', 'LIVE')
                """, meetingMapper, tenantId, joinCode).stream().findFirst();
    }

    public Meeting lockMeeting(long tenantId, UUID meetingId) {
        return jdbc.query("""
                SELECT * FROM vm_meetings
                 WHERE tenant_id = ? AND meeting_id = ?
                 FOR UPDATE
                """, meetingMapper, tenantId, meetingId).stream().findFirst()
                .orElseThrow(() -> notFound("The meeting was not found."));
    }

    public Optional<Participant> participant(long tenantId, UUID meetingId, long userId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_participants
                 WHERE tenant_id = ? AND meeting_id = ? AND user_id = ?
                """, participantMapper, tenantId, meetingId, userId)
                .stream().findFirst();
    }

    public Optional<Participant> participant(
            long tenantId, UUID meetingId, UUID participantId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_participants
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                """, participantMapper, tenantId, meetingId, participantId)
                .stream().findFirst();
    }

    public Participant requestJoin(
            long tenantId,
            UUID meetingId,
            UUID participantId,
            boolean autoAdmit,
            long actorUserId) {
        String state = autoAdmit ? "ADMITTED" : "REQUESTED";
        return jdbc.query("""
                UPDATE vm_meeting_participants
                   SET attendance_state = ?, join_requested_at = CURRENT_TIMESTAMP,
                       admitted_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE admitted_at END,
                       admitted_by = CASE WHEN ? THEN ? ELSE admitted_by END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                   AND attendance_state IN ('INVITED', 'REQUESTED', 'DENIED')
                RETURNING *
                """, participantMapper,
                state, autoAdmit, autoAdmit, autoAdmit ? actorUserId : null, actorUserId,
                tenantId, meetingId, participantId).stream().findFirst()
                .orElseGet(() -> participant(tenantId, meetingId, participantId)
                        .orElseThrow(() -> notFound("The meeting participant was not found.")));
    }

    public Participant decideAdmission(
            long tenantId,
            UUID meetingId,
            UUID participantId,
            boolean admit,
            long actorUserId,
            long expectedVersion) {
        return jdbc.query("""
                UPDATE vm_meeting_participants
                   SET attendance_state = ?,
                       admitted_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                       admitted_by = CASE WHEN ? THEN ? ELSE NULL END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                   AND attendance_state = 'REQUESTED' AND version = ?
                RETURNING *
                """, participantMapper,
                admit ? "ADMITTED" : "DENIED", admit, admit,
                admit ? actorUserId : null, actorUserId, tenantId, meetingId,
                participantId, expectedVersion).stream().findFirst()
                .orElseThrow(this::versionConflict);
    }

    public Meeting start(
            Meeting meeting,
            String provider,
            String roomName,
            long actorUserId,
            long expectedVersion) {
        return jdbc.query("""
                UPDATE vm_meetings
                   SET lifecycle_state = 'LIVE', provider = ?, room_name = ?,
                       started_at = CURRENT_TIMESTAMP, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND version = ?
                   AND lifecycle_state IN ('DRAFT', 'SCHEDULED', 'LOBBY')
                RETURNING *
                """, meetingMapper, provider, roomName, actorUserId,
                meeting.tenantId(), meeting.meetingId(), expectedVersion)
                .stream().findFirst().orElseThrow(this::versionConflict);
    }

    public Participant markJoined(
            long tenantId, UUID meetingId, UUID participantId, long actorUserId) {
        return jdbc.query("""
                UPDATE vm_meeting_participants
                   SET attendance_state = 'JOINED',
                       joined_at = COALESCE(joined_at, CURRENT_TIMESTAMP),
                       left_at = NULL,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                   AND attendance_state IN ('ADMITTED', 'JOINED', 'LEFT')
                RETURNING *
                """, participantMapper, actorUserId, tenantId, meetingId, participantId)
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN, "Admission is required before joining the meeting."));
    }

    public Participant markLeft(
            long tenantId, UUID meetingId, UUID participantId, long actorUserId) {
        return jdbc.query("""
                UPDATE vm_meeting_participants
                   SET attendance_state = 'LEFT', left_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND participant_id = ?
                   AND attendance_state = 'JOINED'
                RETURNING *
                """, participantMapper, actorUserId, tenantId, meetingId, participantId)
                .stream().findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.OBJECT_VERSION_CONFLICT,
                        "The participant is no longer connected to this meeting."));
    }

    public Meeting end(Meeting meeting, long actorUserId, long expectedVersion) {
        return jdbc.query("""
                UPDATE vm_meetings
                   SET lifecycle_state = 'ENDED', ended_at = CURRENT_TIMESTAMP, ended_by = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND lifecycle_state = 'LIVE'
                   AND version = ?
                RETURNING *
                """, meetingMapper, actorUserId, actorUserId, meeting.tenantId(),
                meeting.meetingId(), expectedVersion).stream().findFirst()
                .orElseThrow(this::versionConflict);
    }

    public MeetingDetail detail(Meeting meeting) {
        List<Participant> participants = jdbc.query("""
                SELECT * FROM vm_meeting_participants
                 WHERE tenant_id = ? AND meeting_id = ?
                 ORDER BY CASE participant_role
                    WHEN 'ORGANIZER' THEN 1 WHEN 'CO_HOST' THEN 2
                    WHEN 'PRESENTER' THEN 3 WHEN 'ATTENDEE' THEN 4 ELSE 5 END,
                    display_name, participant_id
                """, participantMapper, meeting.tenantId(), meeting.meetingId());
        List<Artifact> artifacts = jdbc.query("""
                SELECT artifact_id, tenant_id, meeting_id, artifact_type, artifact_state,
                       content_type, size_bytes, retention_until, metadata, version
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ?
                 ORDER BY artifact_type
                """, codec::artifact, meeting.tenantId(), meeting.meetingId());
        return new MeetingDetail(meeting, participants, artifacts);
    }

    public HomeProjection home(long tenantId, long userId, OffsetDateTime now) {
        return queries.home(tenantId, userId, now);
    }

    public PagedMeetings meetings(
            long tenantId, long userId, int page, int pageSize) {
        return queries.meetings(tenantId, userId, page, pageSize);
    }

    public PagedMeetings history(
            long tenantId, long userId, int page, int pageSize) {
        return queries.history(tenantId, userId, page, pageSize);
    }

    public List<Participant> waitingParticipants(long tenantId, UUID meetingId) {
        return jdbc.query("""
                SELECT * FROM vm_meeting_participants
                 WHERE tenant_id = ? AND meeting_id = ?
                   AND attendance_state = 'REQUESTED'
                 ORDER BY join_requested_at, participant_id
                """, participantMapper, tenantId, meetingId);
    }

    public AdminOverviewData adminOverview(
            long tenantId, OffsetDateTime dayStart, OffsetDateTime dayEnd,
            OffsetDateTime sevenDaysAgo) {
        return queries.adminOverview(tenantId, dayStart, dayEnd, sevenDaysAgo);
    }

    public void recordEvent(
            Meeting meeting,
            Participant participant,
            Long actorUserId,
            String eventType,
            String correlationId,
            String idempotencyKey,
            Map<String, Object> payload) {
        try {
            jdbc.update("""
                    INSERT INTO vm_meeting_events (
                        event_id, tenant_id, meeting_id, participant_id, actor_user_id,
                        event_type, correlation_id, idempotency_key, event_payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), meeting.tenantId(), meeting.meetingId(),
                    participant == null ? null : participant.participantId(), actorUserId,
                    eventType, correlationId, idempotencyKey, codec.json(payload));
        } catch (DataIntegrityViolationException exception) {
            if (idempotencyKey == null) throw exception;
        }
    }

    public void recordPolicyEvent(
            long tenantId,
            long actorUserId,
            long policyVersion,
            String correlationId,
            String idempotencyKey,
            String recordingPolicy) {
        try {
            jdbc.update("""
                    INSERT INTO vm_meeting_events (
                        event_id, tenant_id, actor_user_id, event_type,
                        correlation_id, idempotency_key, event_payload)
                    VALUES (?, ?, ?, 'POLICY_UPDATED', ?, ?, ?::jsonb)
                    """, UUID.randomUUID(), tenantId, actorUserId, correlationId,
                    idempotencyKey, codec.json(Map.of(
                            "policyVersion", policyVersion,
                            "recordingPolicy", recordingPolicy)));
        } catch (DataIntegrityViolationException exception) {
            if (idempotencyKey == null) throw exception;
        }
    }

    private MapSqlParameterSource accessParameters(long tenantId, UUID meetingId, long userId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("meetingId", meetingId)
                .addValue("userId", userId);
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The meeting or participant version changed. Refresh and retry.");
    }

    public record CreateMeeting(
            UUID meetingId,
            long tenantId,
            String title,
            String description,
            String agenda,
            String lifecycleState,
            String accessScope,
            String joinCode,
            OffsetDateTime scheduledStartAt,
            OffsetDateTime scheduledEndAt,
            String timeZone,
            boolean waitingRoomEnabled,
            boolean guestAccessEnabled,
            boolean allowJoinBeforeHost,
            boolean defaultMicrophoneEnabled,
            boolean defaultCameraEnabled,
            PersonSnapshot organizer,
            String idempotencyKey,
            String requestHash,
            String correlationId) {
    }

    public record IdempotentMeeting(Meeting meeting, String requestHash) {
    }

    public record PagedMeetings(List<MeetingCard> items, long total) {
    }

    public record AdminOverviewData(
            int liveMeetings,
            int scheduledToday,
            int waitingParticipants,
            int meetingsLastSevenDays,
            int failedJoinAttempts) {
    }
}
