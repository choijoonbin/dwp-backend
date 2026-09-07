package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.PersonSnapshot;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.TenantPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for tenant policy and trusted people projections used by meetings. */
final class VideoMeetingTenantDirectoryRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final VideoMeetingJdbcCodec codec;

    VideoMeetingTenantDirectoryRepository(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            VideoMeetingJdbcCodec codec) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.codec = codec;
    }

    TenantPolicy ensurePolicy(long tenantId, long actorUserId) {
        jdbc.update("""
                INSERT INTO vm_tenant_policies (tenant_id, created_by, updated_by)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenantId, actorUserId, actorUserId);
        return policy(tenantId).orElseThrow(() -> new BaseException(
                ErrorCode.ENTITY_NOT_FOUND, "The meeting tenant policy was not found."));
    }

    Optional<TenantPolicy> policy(long tenantId) {
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

    TenantPolicy updatePolicy(
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
                request.chatRetentionDays(), actorUserId, tenantId, request.expectedVersion())
                .stream().findFirst().orElseThrow(this::versionConflict);
    }

    Optional<PersonSnapshot> person(long tenantId, long userId) {
        return jdbc.query("""
                SELECT tenant_id, user_id, person_public_id, email_address, display_name,
                       job_title, organization_name
                  FROM vm_people_snapshot
                 WHERE tenant_id = ? AND user_id = ? AND lifecycle_state = 'ACTIVE'
                """, codec::person, tenantId, userId).stream().findFirst();
    }

    List<PersonSnapshot> people(long tenantId, List<Long> userIds) {
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

    List<PersonSnapshot> searchPeople(
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

    private BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The meeting or participant version changed. Refresh and retry.");
    }
}
