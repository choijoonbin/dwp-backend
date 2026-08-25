package com.dwp.services.notification.operations;

import com.dwp.services.notification.domain.NotificationModels.ChangeSignal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationRetentionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRetentionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void extendExpiry(
            long tenantId,
            UUID notificationId,
            Instant expiresAt) {
        jdbc.update("""
                UPDATE ntf_notifications
                   SET expires_at = CASE
                           WHEN expires_at IS NULL THEN :expiresAt
                           ELSE GREATEST(expires_at, :expiresAt)
                       END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND notification_id = :notificationId
                """, tenant(tenantId)
                .addValue("notificationId", notificationId)
                .addValue("expiresAt", Timestamp.from(expiresAt)));
    }

    public PurgeResult purgeExpired(
            long tenantId,
            Instant now,
            int batchSize) {
        List<Candidate> candidates = jdbc.query("""
                SELECT user_notification.user_id,
                       user_notification.notification_id
                  FROM ntf_user_notifications user_notification
                  JOIN ntf_notifications notification
                    ON notification.tenant_id = user_notification.tenant_id
                   AND notification.notification_id = user_notification.notification_id
                 WHERE user_notification.tenant_id = :tenantId
                   AND notification.expires_at IS NOT NULL
                   AND notification.expires_at <= :now
                   AND user_notification.saved_at IS NULL
                   AND NOT EXISTS (
                       SELECT 1
                         FROM ntf_notification_retention_holds hold
                        WHERE hold.tenant_id = user_notification.tenant_id
                          AND hold.notification_id = user_notification.notification_id
                          AND (hold.user_id IS NULL
                               OR hold.user_id = user_notification.user_id)
                          AND hold.released_at IS NULL
                          AND hold.starts_at <= :now
                          AND (hold.expires_at IS NULL OR hold.expires_at > :now)
                   )
                 ORDER BY notification.expires_at,
                          user_notification.user_id,
                          user_notification.notification_id
                 FOR UPDATE OF user_notification SKIP LOCKED
                 LIMIT :batchSize
                """, tenant(tenantId)
                .addValue("now", Timestamp.from(now))
                .addValue("batchSize", batchSize),
                (resultSet, rowNumber) -> new Candidate(
                        resultSet.getLong("user_id"),
                        resultSet.getObject("notification_id", UUID.class)));
        if (candidates.isEmpty()) return PurgeResult.empty();

        Map<Long, List<UUID>> byUser = new LinkedHashMap<>();
        candidates.forEach(candidate -> byUser
                .computeIfAbsent(candidate.userId(), ignored -> new ArrayList<>())
                .add(candidate.notificationId()));
        List<ChangeSignal> signals = new ArrayList<>();
        int deletedProjections = 0;
        for (Map.Entry<Long, List<UUID>> entry : byUser.entrySet()) {
            List<UUID> deleted = deleteProjections(
                    tenantId, entry.getKey(), entry.getValue());
            if (deleted.isEmpty()) continue;
            deletedProjections += deleted.size();
            long watermark = rebuildCounter(tenantId, entry.getKey(), now);
            deleted.forEach(notificationId -> signals.add(new ChangeSignal(
                    tenantId, entry.getKey(), watermark, notificationId)));
        }
        int deletedNotifications = deleteOrphans(tenantId, now, batchSize);
        return new PurgeResult(
                deletedProjections,
                deletedNotifications,
                List.copyOf(signals));
    }

    public int cleanupAdmissionHistory(
            long tenantId,
            Instant receiptCutoff,
            Instant windowCutoff,
            int batchSize) {
        int receipts = jdbc.update("""
                DELETE FROM ntf_delivery_admission_receipts
                 WHERE receipt_id IN (
                     SELECT receipt_id
                       FROM ntf_delivery_admission_receipts
                      WHERE tenant_id = :tenantId
                        AND created_at < :receiptCutoff
                      ORDER BY created_at
                      LIMIT :batchSize
                 )
                """, tenant(tenantId)
                .addValue("receiptCutoff", Timestamp.from(receiptCutoff))
                .addValue("batchSize", batchSize));
        int windows = jdbc.update("""
                DELETE FROM ntf_delivery_rate_windows
                 WHERE (tenant_id, user_id, type_version_id, channel,
                        window_started_at, window_seconds) IN (
                     SELECT tenant_id, user_id, type_version_id, channel,
                            window_started_at, window_seconds
                       FROM ntf_delivery_rate_windows
                      WHERE tenant_id = :tenantId
                        AND window_started_at < :windowCutoff
                      ORDER BY window_started_at
                      LIMIT :batchSize
                 )
                """, tenant(tenantId)
                .addValue("windowCutoff", Timestamp.from(windowCutoff))
                .addValue("batchSize", batchSize));
        return receipts + windows;
    }

    public int cleanupBulkUndoReceipts(
            long tenantId,
            Instant now,
            int batchSize) {
        return jdbc.update("""
                DELETE FROM ntf_bulk_undo_receipts
                 WHERE undo_token IN (
                     SELECT undo_token
                       FROM ntf_bulk_undo_receipts
                      WHERE tenant_id = :tenantId
                        AND (expires_at <= :now OR state = 'COMPLETED')
                      ORDER BY expires_at, undo_token
                      LIMIT :batchSize
                 )
                """, tenant(tenantId)
                .addValue("now", Timestamp.from(now))
                .addValue("batchSize", batchSize));
    }

    private List<UUID> deleteProjections(
            long tenantId,
            long userId,
            List<UUID> notificationIds) {
        return jdbc.query("""
                DELETE FROM ntf_user_notifications
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND notification_id IN (:notificationIds)
                   AND saved_at IS NULL
                RETURNING notification_id
                """, tenant(tenantId)
                .addValue("userId", userId)
                .addValue("notificationIds", notificationIds),
                (resultSet, rowNumber) ->
                        resultSet.getObject("notification_id", UUID.class));
    }

    private long rebuildCounter(long tenantId, long userId, Instant now) {
        return jdbc.queryForObject("""
                UPDATE ntf_user_counters counter
                   SET unread_count = (
                           SELECT COUNT(*)
                             FROM ntf_user_notifications item
                            WHERE item.tenant_id = counter.tenant_id
                              AND item.user_id = counter.user_id
                              AND item.inbox_state = 'ACTIVE'
                              AND item.read_at IS NULL
                              AND (item.snoozed_until IS NULL
                                   OR item.snoozed_until <= :now)
                       ),
                       actionable_unread_count = (
                           SELECT COUNT(*)
                             FROM ntf_user_notifications item
                            WHERE item.tenant_id = counter.tenant_id
                              AND item.user_id = counter.user_id
                              AND item.inbox_state = 'ACTIVE'
                              AND item.read_at IS NULL
                              AND item.action_required
                              AND (item.snoozed_until IS NULL
                                   OR item.snoozed_until <= :now)
                       ),
                       urgent_count = (
                           SELECT COUNT(*)
                             FROM ntf_user_notifications item
                            WHERE item.tenant_id = counter.tenant_id
                              AND item.user_id = counter.user_id
                              AND item.inbox_state = 'ACTIVE'
                              AND item.read_at IS NULL
                              AND item.effective_priority = 'URGENT'
                              AND (item.snoozed_until IS NULL
                                   OR item.snoozed_until <= :now)
                       ),
                       counter_version = counter.counter_version + 1,
                       min_available_change_version = counter.counter_version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE counter.tenant_id = :tenantId AND counter.user_id = :userId
                RETURNING counter_version
                """, tenant(tenantId)
                .addValue("userId", userId)
                .addValue("now", Timestamp.from(now)), Long.class);
    }

    private int deleteOrphans(long tenantId, Instant now, int batchSize) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT notification.notification_id
                  FROM ntf_notifications notification
                 WHERE notification.tenant_id = :tenantId
                   AND notification.expires_at IS NOT NULL
                   AND notification.expires_at <= :now
                   AND NOT EXISTS (
                       SELECT 1 FROM ntf_user_notifications item
                        WHERE item.tenant_id = notification.tenant_id
                          AND item.notification_id = notification.notification_id)
                   AND NOT EXISTS (
                       SELECT 1 FROM ntf_delivery_jobs job
                        WHERE job.tenant_id = notification.tenant_id
                          AND job.notification_id = notification.notification_id)
                   AND NOT EXISTS (
                       SELECT 1 FROM ntf_notification_retention_holds hold
                        WHERE hold.tenant_id = notification.tenant_id
                          AND hold.notification_id = notification.notification_id
                          AND hold.released_at IS NULL
                          AND hold.starts_at <= :now
                          AND (hold.expires_at IS NULL OR hold.expires_at > :now))
                 ORDER BY notification.expires_at, notification.notification_id
                 LIMIT :batchSize
                """, tenant(tenantId)
                .addValue("now", Timestamp.from(now))
                .addValue("batchSize", batchSize), UUID.class);
        if (ids.isEmpty()) return 0;
        jdbc.update("""
                DELETE FROM ntf_notification_intents
                 WHERE tenant_id = :tenantId AND notification_id IN (:notificationIds)
                """, tenant(tenantId).addValue("notificationIds", ids));
        return jdbc.update("""
                DELETE FROM ntf_notifications
                 WHERE tenant_id = :tenantId AND notification_id IN (:notificationIds)
                """, tenant(tenantId).addValue("notificationIds", ids));
    }

    private MapSqlParameterSource tenant(long tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId);
    }

    public record PurgeResult(
            int deletedProjections,
            int deletedNotifications,
            List<ChangeSignal> signals) {

        static PurgeResult empty() {
            return new PurgeResult(0, 0, List.of());
        }
    }

    private record Candidate(long userId, UUID notificationId) {
    }
}
