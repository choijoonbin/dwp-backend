package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Durable state machine for draining legacy rooms into incarnation-bound rooms. */
@Repository
class MeetingMediaUpgradeRepository {

    private final JdbcTemplate jdbc;

    MeetingMediaUpgradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UpgradeClaim> claimProvision(
            UUID fence,
            OffsetDateTime now,
            OffsetDateTime leaseUntil,
            int maximumAttempts) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT upgrade.tenant_id, upgrade.meeting_id
                      FROM vm_meeting_media_upgrades upgrade
                      JOIN vm_meetings meeting
                        ON meeting.tenant_id = upgrade.tenant_id
                       AND meeting.meeting_id = upgrade.meeting_id
                     WHERE meeting.lifecycle_state = 'LIVE'
                       AND meeting.media_access_state = 'MIGRATING'
                       AND upgrade.attempt_count < ?
                       AND (upgrade.upgrade_state = 'PENDING'
                            OR (upgrade.upgrade_state = 'FAILED_PROVISION'
                                AND upgrade.next_attempt_at <= ?)
                            OR (upgrade.upgrade_state = 'PROVISIONING'
                                AND upgrade.lease_expires_at <= ?))
                     ORDER BY COALESCE(upgrade.next_attempt_at,
                                       upgrade.lease_expires_at, upgrade.created_at)
                     FOR UPDATE OF upgrade SKIP LOCKED
                     LIMIT 1
                )
                UPDATE vm_meeting_media_upgrades upgrade
                   SET upgrade_state = 'PROVISIONING', execution_fence = ?,
                       lease_expires_at = ?, attempt_count = attempt_count + 1,
                       next_attempt_at = NULL, last_failure_code = NULL,
                       updated_at = ?
                  FROM candidate
                 WHERE upgrade.tenant_id = candidate.tenant_id
                   AND upgrade.meeting_id = candidate.meeting_id
                RETURNING upgrade.*
                """, this::claim, maximumAttempts, now, now, fence, leaseUntil, now)
                .stream().findFirst();
    }

    void switchToTarget(UpgradeClaim claim, OffsetDateTime switchedAt) {
        int updated = jdbc.update("""
                WITH switched AS (
                    UPDATE vm_meetings meeting
                       SET room_name = ?, provider_room_sid = NULL,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE meeting.tenant_id = ? AND meeting.meeting_id = ?
                       AND meeting.lifecycle_state = 'LIVE'
                       AND meeting.provider = 'LIVEKIT'
                       AND meeting.media_access_state = 'MIGRATING'
                       AND meeting.media_incarnation = ?
                       AND meeting.room_name = ?
                    RETURNING meeting.tenant_id, meeting.meeting_id
                )
                UPDATE vm_meeting_media_upgrades upgrade
                   SET upgrade_state = 'SWITCHED', execution_fence = NULL,
                       lease_expires_at = NULL, next_attempt_at = NULL,
                       last_failure_code = NULL, attempt_count = 0, updated_at = ?
                  FROM switched
                 WHERE upgrade.tenant_id = switched.tenant_id
                   AND upgrade.meeting_id = switched.meeting_id
                   AND upgrade.upgrade_state = 'PROVISIONING'
                   AND upgrade.execution_fence = ?
                   AND upgrade.lease_expires_at > ?
                """, claim.targetRoomName(), claim.tenantId(), claim.meetingId(),
                claim.roomIncarnation(), claim.legacyRoomName(), switchedAt,
                claim.executionFence(), switchedAt);
        if (updated != 1) throw staleFence();
    }

    void failProvision(
            UpgradeClaim claim, OffsetDateTime failedAt, OffsetDateTime retryAt) {
        transitionFailure(
                claim, "PROVISIONING", "FAILED_PROVISION",
                failedAt, retryAt, "TARGET_ROOM_PROVISION_FAILED");
    }

    Optional<UpgradeClaim> claimCleanup(
            UUID fence,
            OffsetDateTime now,
            OffsetDateTime leaseUntil,
            int maximumAttempts) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT upgrade.tenant_id, upgrade.meeting_id
                      FROM vm_meeting_media_upgrades upgrade
                      JOIN vm_meetings meeting
                        ON meeting.tenant_id = upgrade.tenant_id
                       AND meeting.meeting_id = upgrade.meeting_id
                     WHERE meeting.lifecycle_state = 'LIVE'
                       AND meeting.media_access_state = 'MIGRATING'
                       AND meeting.room_name = upgrade.target_room_name
                       AND upgrade.cleanup_not_before <= ?
                       AND upgrade.attempt_count < ?
                       AND (upgrade.upgrade_state = 'SWITCHED'
                            OR (upgrade.upgrade_state = 'FAILED_CLEANUP'
                                AND upgrade.next_attempt_at <= ?)
                            OR (upgrade.upgrade_state = 'CLEANING'
                                AND upgrade.lease_expires_at <= ?))
                     ORDER BY COALESCE(upgrade.next_attempt_at,
                                       upgrade.lease_expires_at, upgrade.updated_at)
                     FOR UPDATE OF upgrade SKIP LOCKED
                     LIMIT 1
                )
                UPDATE vm_meeting_media_upgrades upgrade
                   SET upgrade_state = 'CLEANING', execution_fence = ?,
                       lease_expires_at = ?, attempt_count = attempt_count + 1,
                       next_attempt_at = NULL, last_failure_code = NULL,
                       updated_at = ?
                  FROM candidate
                 WHERE upgrade.tenant_id = candidate.tenant_id
                   AND upgrade.meeting_id = candidate.meeting_id
                RETURNING upgrade.*
                """, this::claim, now, maximumAttempts, now, now,
                fence, leaseUntil, now)
                .stream().findFirst();
    }

    void finalizeActive(UpgradeClaim claim, OffsetDateTime completedAt) {
        int updated = jdbc.update("""
                WITH activated AS (
                    UPDATE vm_meetings meeting
                       SET media_access_state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                     WHERE meeting.tenant_id = ? AND meeting.meeting_id = ?
                       AND meeting.lifecycle_state = 'LIVE'
                       AND meeting.provider = 'LIVEKIT'
                       AND meeting.media_access_state = 'MIGRATING'
                       AND meeting.media_incarnation = ?
                       AND meeting.room_name = ?
                    RETURNING meeting.tenant_id, meeting.meeting_id
                )
                UPDATE vm_meeting_media_upgrades upgrade
                   SET upgrade_state = 'SUCCEEDED', execution_fence = NULL,
                       lease_expires_at = NULL, next_attempt_at = NULL,
                       last_failure_code = NULL, completed_at = ?, updated_at = ?
                  FROM activated
                 WHERE upgrade.tenant_id = activated.tenant_id
                   AND upgrade.meeting_id = activated.meeting_id
                   AND upgrade.upgrade_state = 'CLEANING'
                   AND upgrade.execution_fence = ?
                   AND upgrade.lease_expires_at > ?
                """, claim.tenantId(), claim.meetingId(), claim.roomIncarnation(),
                claim.targetRoomName(), completedAt, completedAt,
                claim.executionFence(), completedAt);
        if (updated != 1) throw staleFence();
    }

    void failCleanup(
            UpgradeClaim claim, OffsetDateTime failedAt, OffsetDateTime retryAt) {
        transitionFailure(
                claim, "CLEANING", "FAILED_CLEANUP",
                failedAt, retryAt, "LEGACY_ROOM_CLEANUP_FAILED");
    }

    private void transitionFailure(
            UpgradeClaim claim,
            String runningState,
            String failedState,
            OffsetDateTime failedAt,
            OffsetDateTime retryAt,
            String reason) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_media_upgrades
                   SET upgrade_state = ?, execution_fence = NULL,
                       lease_expires_at = NULL, next_attempt_at = ?,
                       last_failure_code = ?, updated_at = ?
                 WHERE tenant_id = ? AND meeting_id = ? AND upgrade_state = ?
                   AND execution_fence = ? AND lease_expires_at > ?
                """, failedState, retryAt, reason, failedAt,
                claim.tenantId(), claim.meetingId(), runningState,
                claim.executionFence(), failedAt);
        if (updated != 1) throw staleFence();
    }

    private UpgradeClaim claim(ResultSet row, int index) throws SQLException {
        return new UpgradeClaim(
                row.getLong("tenant_id"),
                row.getObject("meeting_id", UUID.class),
                row.getObject("room_incarnation", UUID.class),
                row.getString("legacy_room_name"),
                row.getString("target_room_name"),
                row.getObject("execution_fence", UUID.class),
                row.getObject("lease_expires_at", OffsetDateTime.class),
                row.getInt("attempt_count"));
    }

    private BaseException staleFence() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The legacy meeting room upgrade lease changed or expired.");
    }

    record UpgradeClaim(
            long tenantId,
            UUID meetingId,
            UUID roomIncarnation,
            String legacyRoomName,
            String targetRoomName,
            UUID executionFence,
            OffsetDateTime leaseExpiresAt,
            int attemptCount) {
    }
}
