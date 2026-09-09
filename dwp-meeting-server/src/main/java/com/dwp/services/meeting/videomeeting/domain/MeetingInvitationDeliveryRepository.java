package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Claim;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Event;
import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationNotificationGateway.Acceptance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class MeetingInvitationDeliveryRepository {

    private final JdbcTemplate jdbc;

    MeetingInvitationDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Event> claimEvent(
            OffsetDateTime now, OffsetDateTime leaseExpiresAt, UUID dispatchFence) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT candidate_event.event_id
                      FROM vm_meeting_invitation_outbox candidate_event
                     WHERE candidate_event.delivery_state = 'PENDING'
                       AND candidate_event.available_at <= ?
                       AND (candidate_event.dispatch_fence IS NULL
                            OR candidate_event.lease_expires_at <= ?)
                       AND NOT EXISTS (
                           SELECT 1
                             FROM vm_meeting_invitation_outbox predecessor
                            WHERE predecessor.tenant_id = candidate_event.tenant_id
                              AND predecessor.meeting_id = candidate_event.meeting_id
                              AND predecessor.delivery_state = 'PENDING'
                              AND (predecessor.invitation_revision,
                                   predecessor.created_at, predecessor.event_id)
                                  < (candidate_event.invitation_revision,
                                     candidate_event.created_at,
                                     candidate_event.event_id))
                     ORDER BY candidate_event.available_at,
                              candidate_event.created_at, candidate_event.event_id
                     LIMIT 1
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE vm_meeting_invitation_outbox event
                   SET dispatch_fence = ?, lease_expires_at = ?, updated_at = ?
                  FROM candidate
                 WHERE event.event_id = candidate.event_id
                RETURNING event.event_id, event.tenant_id, event.meeting_id,
                          event.event_type, event.created_at,
                          event.dispatch_fence, event.lease_expires_at
                """, this::mapEvent, now, now, dispatchFence, leaseExpiresAt, now)
                .stream().findFirst();
    }

    void lockMeeting(Event event) {
        List<UUID> locked = jdbc.query("""
                SELECT meeting_id
                  FROM vm_meetings
                 WHERE tenant_id = ? AND meeting_id = ?
                 FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class),
                event.tenantId(), event.meetingId());
        if (locked.size() != 1) {
            throw new IllegalStateException("Meeting invitation scope is unavailable.");
        }
    }

    void synchronizeCurrentRecipients(Event event, OffsetDateTime now) {
        List<Long> recipients = currentRecipientIds(event);
        for (Long recipient : recipients) {
            UUID sourceEventId = MeetingInvitationDeliveryModels.sourceEventId(
                    event.eventId(), event.tenantId(), recipient);
            jdbc.update("""
                    INSERT INTO vm_meeting_invitation_notification_deliveries (
                        event_id, tenant_id, meeting_id, recipient_user_id,
                        source_event_id, available_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id, recipient_user_id) DO UPDATE
                       SET delivery_state = 'PENDING', attempt_count = 0,
                           available_at = EXCLUDED.available_at,
                           delivery_fence = NULL, lease_expires_at = NULL,
                           notification_intent_id = NULL, notification_id = NULL,
                           recipient_count = NULL, owner_duplicate = NULL,
                           highest_change_version = NULL, accepted_at = NULL,
                           last_failure_code = NULL, updated_at = EXCLUDED.updated_at
                     WHERE vm_meeting_invitation_notification_deliveries.delivery_state
                           IN ('CANCELLED', 'SUPERSEDED')
                    """, event.eventId(), event.tenantId(), event.meetingId(), recipient,
                    sourceEventId, now, now, now);
        }
        jdbc.update("""
                UPDATE vm_meeting_invitation_notification_deliveries delivery
                   SET delivery_state = 'CANCELLED', delivery_fence = NULL,
                       lease_expires_at = NULL, last_failure_code = NULL,
                       updated_at = ?
                 WHERE delivery.event_id = ?
                   AND delivery.delivery_state IN ('PENDING', 'FAILED')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM vm_meeting_participants participant
                         JOIN vm_people_snapshot person
                           ON person.tenant_id = participant.tenant_id
                          AND person.user_id = participant.user_id
                          AND person.lifecycle_state = 'ACTIVE'
                        WHERE participant.tenant_id = delivery.tenant_id
                          AND participant.meeting_id = delivery.meeting_id
                          AND participant.user_id = delivery.recipient_user_id
                          AND participant.participant_role <> 'GUEST'
                          AND participant.attendance_state <> 'DENIED')
                """, now, event.eventId());
    }

    void exhaustExpiredTargets(Event event, OffsetDateTime now, int maximumAttempts) {
        jdbc.update("""
                UPDATE vm_meeting_invitation_notification_deliveries
                   SET delivery_state = 'FAILED', delivery_fence = NULL,
                       lease_expires_at = NULL, last_failure_code = 'RETRY_EXHAUSTED',
                       updated_at = ?
                 WHERE event_id = ? AND delivery_state = 'PENDING'
                   AND attempt_count >= ?
                   AND (delivery_fence IS NULL OR lease_expires_at <= ?)
                """, now, event.eventId(), maximumAttempts, now);
    }

    Optional<Claim> claimRecipient(
            Event event,
            OffsetDateTime now,
            OffsetDateTime leaseExpiresAt,
            UUID deliveryFence,
            int maximumAttempts) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT delivery.recipient_user_id
                      FROM vm_meeting_invitation_notification_deliveries delivery
                      JOIN vm_meeting_participants participant
                        ON participant.tenant_id = delivery.tenant_id
                       AND participant.meeting_id = delivery.meeting_id
                       AND participant.user_id = delivery.recipient_user_id
                       AND participant.participant_role <> 'GUEST'
                       AND participant.attendance_state <> 'DENIED'
                      JOIN vm_people_snapshot person
                        ON person.tenant_id = participant.tenant_id
                       AND person.user_id = participant.user_id
                       AND person.lifecycle_state = 'ACTIVE'
                     WHERE delivery.event_id = ?
                       AND delivery.delivery_state = 'PENDING'
                       AND delivery.available_at <= ?
                       AND delivery.attempt_count < ?
                       AND (delivery.delivery_fence IS NULL
                            OR delivery.lease_expires_at <= ?)
                     ORDER BY delivery.available_at, delivery.recipient_user_id
                     LIMIT 1
                     FOR UPDATE OF delivery SKIP LOCKED
                )
                UPDATE vm_meeting_invitation_notification_deliveries delivery
                   SET delivery_fence = ?, lease_expires_at = ?,
                       attempt_count = delivery.attempt_count + 1,
                       updated_at = ?
                  FROM candidate
                 WHERE delivery.event_id = ?
                   AND delivery.recipient_user_id = candidate.recipient_user_id
                RETURNING delivery.recipient_user_id, delivery.source_event_id,
                          delivery.attempt_count, delivery.delivery_fence,
                          delivery.lease_expires_at
                """, (rs, row) -> new Claim(
                        event, rs.getLong("recipient_user_id"),
                        rs.getObject("source_event_id", UUID.class),
                        rs.getInt("attempt_count"),
                        rs.getObject("delivery_fence", UUID.class),
                        rs.getObject("lease_expires_at", OffsetDateTime.class)),
                event.eventId(), now, maximumAttempts, now, deliveryFence,
                leaseExpiresAt, now, event.eventId()).stream().findFirst();
    }

    Event lockEvent(UUID eventId) {
        return jdbc.query("""
                SELECT event_id, tenant_id, meeting_id, event_type, created_at,
                       dispatch_fence, lease_expires_at
                  FROM vm_meeting_invitation_outbox
                 WHERE event_id = ? FOR UPDATE
                """, this::mapEvent, eventId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Meeting invitation event is unavailable."));
    }

    DeliveryLease lockRecipient(UUID eventId, long recipientUserId) {
        return jdbc.query("""
                SELECT delivery_state, source_event_id, attempt_count,
                       delivery_fence, lease_expires_at
                  FROM vm_meeting_invitation_notification_deliveries
                 WHERE event_id = ? AND recipient_user_id = ? FOR UPDATE
                """, (rs, row) -> new DeliveryLease(
                        rs.getString("delivery_state"),
                        rs.getObject("source_event_id", UUID.class),
                        rs.getInt("attempt_count"),
                        rs.getObject("delivery_fence", UUID.class),
                        rs.getObject("lease_expires_at", OffsetDateTime.class)),
                eventId, recipientUserId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Meeting invitation recipient is unavailable."));
    }

    boolean isCurrentRecipient(Event event, long recipientUserId) {
        List<Long> current = jdbc.query("""
                SELECT participant.user_id
                  FROM vm_meeting_participants participant
                  JOIN vm_people_snapshot person
                    ON person.tenant_id = participant.tenant_id
                   AND person.user_id = participant.user_id
                   AND person.lifecycle_state = 'ACTIVE'
                 WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                   AND participant.user_id = ?
                   AND participant.participant_role <> 'GUEST'
                   AND participant.attendance_state <> 'DENIED'
                 FOR SHARE OF participant, person
                """, (rs, row) -> rs.getLong(1),
                event.tenantId(), event.meetingId(), recipientUserId);
        return current.size() == 1;
    }

    void accept(Claim claim, Acceptance acceptance, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_invitation_notification_deliveries
                   SET delivery_state = 'ACCEPTED', delivery_fence = NULL,
                       lease_expires_at = NULL, notification_intent_id = ?,
                       notification_id = ?, recipient_count = ?, owner_duplicate = ?,
                       highest_change_version = ?, accepted_at = ?,
                       last_failure_code = NULL, updated_at = ?
                 WHERE event_id = ? AND recipient_user_id = ?
                   AND delivery_state = 'PENDING' AND delivery_fence = ?
                   AND lease_expires_at > ?
                """, acceptance.intentId(), acceptance.notificationId(),
                acceptance.recipientCount(), acceptance.duplicate(),
                acceptance.highestChangeVersion(), now, now, claim.event().eventId(),
                claim.recipientUserId(), claim.deliveryFence(), now);
        requireOne(updated);
    }

    void supersede(Claim claim, Acceptance acceptance, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_invitation_notification_deliveries
                   SET delivery_state = 'SUPERSEDED', delivery_fence = NULL,
                       lease_expires_at = NULL, notification_intent_id = ?,
                       notification_id = ?, recipient_count = ?, owner_duplicate = ?,
                       highest_change_version = ?, accepted_at = ?,
                       last_failure_code = NULL, updated_at = ?
                 WHERE event_id = ? AND recipient_user_id = ?
                   AND delivery_state = 'PENDING' AND delivery_fence = ?
                   AND lease_expires_at > ?
                """, acceptance.intentId(), acceptance.notificationId(),
                acceptance.recipientCount(), acceptance.duplicate(),
                acceptance.highestChangeVersion(), now, now, claim.event().eventId(),
                claim.recipientUserId(), claim.deliveryFence(), now);
        requireOne(updated);
    }

    void retry(Claim claim, String failureCode, OffsetDateTime now, OffsetDateTime availableAt) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_invitation_notification_deliveries
                   SET delivery_state = 'PENDING', delivery_fence = NULL,
                       lease_expires_at = NULL, available_at = ?,
                       last_failure_code = ?, updated_at = ?
                 WHERE event_id = ? AND recipient_user_id = ?
                   AND delivery_state = 'PENDING' AND delivery_fence = ?
                   AND lease_expires_at > ?
                """, availableAt, failureCode, now, claim.event().eventId(),
                claim.recipientUserId(), claim.deliveryFence(), now);
        requireOne(updated);
    }

    void fail(Claim claim, String failureCode, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_invitation_notification_deliveries
                   SET delivery_state = 'FAILED', delivery_fence = NULL,
                       lease_expires_at = NULL, last_failure_code = ?, updated_at = ?
                 WHERE event_id = ? AND recipient_user_id = ?
                   AND delivery_state = 'PENDING' AND delivery_fence = ?
                   AND lease_expires_at > ?
                """, failureCode, now, claim.event().eventId(),
                claim.recipientUserId(), claim.deliveryFence(), now);
        requireOne(updated);
    }

    void cancel(Claim claim, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_invitation_notification_deliveries
                   SET delivery_state = 'CANCELLED', delivery_fence = NULL,
                       lease_expires_at = NULL, last_failure_code = NULL, updated_at = ?
                 WHERE event_id = ? AND recipient_user_id = ?
                   AND delivery_state = 'PENDING' AND delivery_fence = ?
                   AND lease_expires_at > ?
                """, now, claim.event().eventId(), claim.recipientUserId(),
                claim.deliveryFence(), now);
        requireOne(updated);
    }

    ParentCounts parentCounts(Event event) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE delivery.delivery_state = 'ACCEPTED') accepted,
                       count(*) FILTER (WHERE delivery.delivery_state = 'FAILED') failed,
                       count(*) FILTER (WHERE delivery.delivery_state IS NULL
                           OR delivery.delivery_state NOT IN ('ACCEPTED', 'FAILED')) pending,
                       min(GREATEST(
                           delivery.available_at,
                           COALESCE(delivery.lease_expires_at, delivery.available_at)))
                           FILTER (WHERE delivery.delivery_state = 'PENDING') next_available
                  FROM vm_meeting_participants participant
                  JOIN vm_people_snapshot person
                    ON person.tenant_id = participant.tenant_id
                   AND person.user_id = participant.user_id
                   AND person.lifecycle_state = 'ACTIVE'
                  LEFT JOIN vm_meeting_invitation_notification_deliveries delivery
                    ON delivery.event_id = ?
                   AND delivery.tenant_id = participant.tenant_id
                   AND delivery.meeting_id = participant.meeting_id
                   AND delivery.recipient_user_id = participant.user_id
                 WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                   AND participant.user_id IS NOT NULL
                   AND participant.participant_role <> 'GUEST'
                   AND participant.attendance_state <> 'DENIED'
                """, (rs, row) -> new ParentCounts(
                        rs.getInt("accepted"), rs.getInt("failed"), rs.getInt("pending"),
                        rs.getObject("next_available", OffsetDateTime.class)),
                event.eventId(), event.tenantId(), event.meetingId());
    }

    void deliverParent(Event event, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_invitation_outbox
                   SET delivery_state = 'DELIVERED', delivered_at = ?,
                       dispatch_fence = NULL, lease_expires_at = NULL,
                       last_failure_code = NULL, updated_at = ?
                 WHERE event_id = ? AND delivery_state = 'PENDING'
                   AND dispatch_fence = ? AND lease_expires_at > ?
                """, now, now, event.eventId(), event.dispatchFence(), now);
        requireOne(updated);
    }

    void failParent(Event event, String failureCode, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_invitation_outbox
                   SET delivery_state = 'FAILED', delivered_at = NULL,
                       dispatch_fence = NULL, lease_expires_at = NULL,
                       last_failure_code = ?, updated_at = ?
                 WHERE event_id = ? AND delivery_state = 'PENDING'
                   AND dispatch_fence = ? AND lease_expires_at > ?
                """, failureCode, now, event.eventId(), event.dispatchFence(), now);
        requireOne(updated);
    }

    void releaseParent(
            Event event, OffsetDateTime now, OffsetDateTime availableAt, String failureCode) {
        int updated = jdbc.update("""
                UPDATE vm_meeting_invitation_outbox
                   SET delivery_state = 'PENDING', available_at = ?,
                       dispatch_fence = NULL, lease_expires_at = NULL,
                       last_failure_code = ?, updated_at = ?
                 WHERE event_id = ? AND delivery_state = 'PENDING'
                   AND dispatch_fence = ? AND lease_expires_at > ?
                """, availableAt, failureCode, now, event.eventId(),
                event.dispatchFence(), now);
        requireOne(updated);
    }

    private List<Long> currentRecipientIds(Event event) {
        return jdbc.query("""
                SELECT participant.user_id
                  FROM vm_meeting_participants participant
                  JOIN vm_people_snapshot person
                    ON person.tenant_id = participant.tenant_id
                   AND person.user_id = participant.user_id
                   AND person.lifecycle_state = 'ACTIVE'
                 WHERE participant.tenant_id = ? AND participant.meeting_id = ?
                   AND participant.user_id IS NOT NULL
                   AND participant.participant_role <> 'GUEST'
                   AND participant.attendance_state <> 'DENIED'
                 ORDER BY participant.user_id
                 FOR SHARE OF participant, person
                """, (rs, row) -> rs.getLong(1), event.tenantId(), event.meetingId());
    }

    private Event mapEvent(ResultSet rs, int rowNumber) throws SQLException {
        return new Event(
                rs.getObject("event_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("meeting_id", UUID.class), rs.getString("event_type"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("dispatch_fence", UUID.class),
                rs.getObject("lease_expires_at", OffsetDateTime.class));
    }

    private void requireOne(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Meeting invitation delivery fence changed or expired.");
        }
    }

    record DeliveryLease(
            String state,
            UUID sourceEventId,
            int attemptCount,
            UUID fence,
            OffsetDateTime leaseExpiresAt) {
    }

    record ParentCounts(
            int accepted,
            int failed,
            int pending,
            OffsetDateTime nextAvailable) {
        int total() { return accepted + failed + pending; }
    }
}
