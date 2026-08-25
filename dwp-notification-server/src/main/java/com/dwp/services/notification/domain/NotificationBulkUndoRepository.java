package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationBulkUndoRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationBulkUndoRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UndoReceipt create(
            NotificationRequestContext.Actor actor,
            String action,
            List<NotificationUndoSnapshot> snapshots,
            Instant now,
            Duration window) {
        if (snapshots.isEmpty()) return null;
        UUID token = UUID.randomUUID();
        Instant expiresAt = now.plus(window);
        jdbc.update("""
                INSERT INTO ntf_bulk_undo_receipts (
                    undo_token, tenant_id, user_id, action, expires_at, created_at)
                VALUES (:undoToken, :tenantId, :userId, :action, :expiresAt, :createdAt)
                """, actor(actor)
                .addValue("undoToken", token)
                .addValue("action", action)
                .addValue("expiresAt", Timestamp.from(expiresAt))
                .addValue("createdAt", Timestamp.from(now)));
        MapSqlParameterSource[] items = snapshots.stream()
                .map(snapshot -> actor(actor)
                        .addValue("undoToken", token)
                        .addValue("notificationId", snapshot.notificationId())
                        .addValue("inboxState", snapshot.inboxState())
                        .addValue("readAt", timestamp(snapshot.readAt()))
                        .addValue("savedAt", timestamp(snapshot.savedAt()))
                        .addValue("completedAt", timestamp(snapshot.completedAt()))
                        .addValue("snoozedUntil", timestamp(snapshot.snoozedUntil()))
                        .addValue("expectedVersion", snapshot.expectedVersion()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO ntf_bulk_undo_items (
                    undo_token, tenant_id, user_id, notification_id,
                    before_inbox_state, before_read_at, before_saved_at,
                    before_completed_at, before_snoozed_until, expected_version)
                VALUES (
                    :undoToken, :tenantId, :userId, :notificationId,
                    :inboxState, :readAt, :savedAt,
                    :completedAt, :snoozedUntil, :expectedVersion)
                """, items);
        return new UndoReceipt(token, expiresAt, snapshots);
    }

    public UndoReceipt lockPending(
            NotificationRequestContext.Actor actor,
            UUID undoToken,
            Instant now) {
        List<ReceiptRow> receipts = jdbc.query("""
                SELECT action, state, expires_at
                  FROM ntf_bulk_undo_receipts
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND undo_token = :undoToken
                 FOR UPDATE
                """, actor(actor).addValue("undoToken", undoToken),
                (resultSet, rowNumber) -> new ReceiptRow(
                        resultSet.getString("action"),
                        resultSet.getString("state"),
                        resultSet.getTimestamp("expires_at").toInstant()));
        if (receipts.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }
        ReceiptRow receipt = receipts.get(0);
        if (!"AVAILABLE".equals(receipt.state()) || !receipt.expiresAt().isAfter(now)) {
            throw new NotificationException(
                    NotificationErrorCode.NOTIFICATION_STALE_VERSION,
                    "The bulk undo window has expired or was already completed.");
        }
        List<NotificationUndoSnapshot> snapshots = jdbc.query("""
                SELECT notification_id, before_inbox_state, before_read_at,
                       before_saved_at, before_completed_at, before_snoozed_until,
                       expected_version
                  FROM ntf_bulk_undo_items
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND undo_token = :undoToken
                   AND undone_at IS NULL
                 ORDER BY notification_id
                 FOR UPDATE
                """, actor(actor).addValue("undoToken", undoToken),
                (resultSet, rowNumber) -> new NotificationUndoSnapshot(
                        resultSet.getObject("notification_id", UUID.class),
                        resultSet.getString("before_inbox_state"),
                        instant(resultSet.getTimestamp("before_read_at")),
                        instant(resultSet.getTimestamp("before_saved_at")),
                        instant(resultSet.getTimestamp("before_completed_at")),
                        instant(resultSet.getTimestamp("before_snoozed_until")),
                        resultSet.getLong("expected_version")));
        return new UndoReceipt(undoToken, receipt.expiresAt(), snapshots);
    }

    public void markUndone(
            NotificationRequestContext.Actor actor,
            UUID undoToken,
            UUID notificationId,
            Instant now) {
        jdbc.update("""
                UPDATE ntf_bulk_undo_items
                   SET undone_at = :undoneAt
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND undo_token = :undoToken
                   AND notification_id = :notificationId
                   AND undone_at IS NULL
                """, actor(actor)
                .addValue("undoToken", undoToken)
                .addValue("notificationId", notificationId)
                .addValue("undoneAt", Timestamp.from(now)));
    }

    public void completeIfEmpty(
            NotificationRequestContext.Actor actor,
            UUID undoToken,
            Instant now) {
        jdbc.update("""
                UPDATE ntf_bulk_undo_receipts receipt
                   SET state = 'COMPLETED', completed_at = :completedAt
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND undo_token = :undoToken
                   AND state = 'AVAILABLE'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM ntf_bulk_undo_items item
                        WHERE item.tenant_id = receipt.tenant_id
                          AND item.user_id = receipt.user_id
                          AND item.undo_token = receipt.undo_token
                          AND item.undone_at IS NULL)
                """, actor(actor)
                .addValue("undoToken", undoToken)
                .addValue("completedAt", Timestamp.from(now)));
    }

    private MapSqlParameterSource actor(NotificationRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record UndoReceipt(
            UUID undoToken,
            Instant expiresAt,
            List<NotificationUndoSnapshot> snapshots) {
    }

    private record ReceiptRow(String action, String state, Instant expiresAt) {
    }
}
