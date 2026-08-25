package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationCommandRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final NotificationIdempotencyRepository idempotencyRepository;

    public NotificationCommandRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            NotificationIdempotencyRepository idempotencyRepository) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotencyRepository = idempotencyRepository;
    }

    public MutationOutcome mutate(
            NotificationRequestContext.Actor actor,
            UUID notificationId,
            String action,
            long expectedVersion,
            Instant snoozedUntil,
            String idempotencyKey) {
        return mutate(
                actor,
                notificationId,
                action,
                expectedVersion,
                idempotencyKey,
                "NOTIFICATION_TRIAGE",
                new TriagePayload(notificationId, action, expectedVersion, snoozedUntil),
                (current, transitionNow) -> NotificationTriagePolicy.transition(
                        current, action, snoozedUntil, transitionNow));
    }

    public MutationOutcome restoreSnapshot(
            NotificationRequestContext.Actor actor,
            NotificationUndoSnapshot snapshot,
            String idempotencyKey) {
        return mutate(
                actor,
                snapshot.notificationId(),
                "UNDO",
                snapshot.expectedVersion(),
                idempotencyKey,
                "NOTIFICATION_BULK_UNDO",
                snapshot,
                (current, transitionNow) -> current.restoreSnapshot(snapshot));
    }

    private MutationOutcome mutate(
            NotificationRequestContext.Actor actor,
            UUID notificationId,
            String action,
            long expectedVersion,
            String idempotencyKey,
            String operation,
            Object payload,
            StateTransition transition) {
        Instant now = Instant.now();
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                operation,
                payload);
        MutationOutcome replay = idempotencyRepository.replay(receipt, MutationOutcome.class);
        if (replay != null) {
            return new MutationOutcome(
                    replay.notificationId(),
                    replay.action(),
                    replay.version(),
                    replay.changeVersion(),
                    replay.changed(),
                    true);
        }

        NotificationTriageState current = projectionForUpdate(actor, notificationId);
        if (current.version() != expectedVersion) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        NotificationTriageState next = transition.apply(current, now);
        if (next.equals(current)) {
            MutationOutcome noChange = new MutationOutcome(
                    notificationId, action, current.version(), current.changeVersion(), false, false);
            idempotencyRepository.complete(actor, receipt, noChange);
            return noChange;
        }

        ensureCounter(actor, current.userId());
        CounterState counter = counterForUpdate(actor, current.userId());
        long changeVersion = counter.version() + 1;
        int updated = jdbc.update("""
                UPDATE ntf_user_notifications
                   SET inbox_state = :inboxState,
                       read_at = :readAt,
                       saved_at = :savedAt,
                       completed_at = :completedAt,
                       snoozed_until = :snoozedUntil,
                       change_version = :changeVersion,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND notification_id = :notificationId
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("notificationId", notificationId)
                .addValue("inboxState", next.inboxState())
                .addValue("readAt", timestamp(next.readAt()))
                .addValue("savedAt", timestamp(next.savedAt()))
                .addValue("completedAt", timestamp(next.completedAt()))
                .addValue("snoozedUntil", timestamp(next.snoozedUntil()))
                .addValue("changeVersion", changeVersion)
                .addValue("expectedVersion", expectedVersion));
        if (updated != 1) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }

        CounterFlags before = flags(current, now);
        CounterFlags after = flags(next, now);
        updateCounter(
                actor,
                current.userId(),
                changeVersion,
                after.unread() - before.unread(),
                after.actionable() - before.actionable(),
                after.urgent() - before.urgent());
        appendOutbox(actor, notificationId, action, changeVersion, current.userId(), now);

        MutationOutcome outcome = new MutationOutcome(
                notificationId, action, expectedVersion + 1, changeVersion, true, false);
        idempotencyRepository.complete(actor, receipt, outcome);
        return outcome;
    }

    private NotificationTriageState projectionForUpdate(
            NotificationRequestContext.Actor actor,
            UUID notificationId) {
        List<NotificationTriageState> rows = jdbc.query("""
                SELECT user_id, inbox_state, read_at, saved_at, completed_at,
                       snoozed_until, action_required, effective_priority,
                       change_version, version
                  FROM ntf_user_notifications
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND notification_id = :notificationId
                 FOR UPDATE
                """, actorParams(actor).addValue("notificationId", notificationId),
                (resultSet, rowNumber) -> new NotificationTriageState(
                        resultSet.getLong("user_id"),
                        resultSet.getString("inbox_state"),
                        instant(resultSet.getTimestamp("read_at")),
                        instant(resultSet.getTimestamp("saved_at")),
                        instant(resultSet.getTimestamp("completed_at")),
                        instant(resultSet.getTimestamp("snoozed_until")),
                        resultSet.getBoolean("action_required"),
                        resultSet.getString("effective_priority"),
                        resultSet.getLong("change_version"),
                        resultSet.getLong("version")));
        if (rows.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }
        return rows.get(0);
    }

    private void ensureCounter(NotificationRequestContext.Actor actor, long userId) {
        jdbc.update("""
                INSERT INTO ntf_user_counters (tenant_id, user_id)
                VALUES (:tenantId, :userId)
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", userId));
    }

    private CounterState counterForUpdate(NotificationRequestContext.Actor actor, long userId) {
        return jdbc.queryForObject("""
                SELECT unread_count, actionable_unread_count, urgent_count, counter_version
                  FROM ntf_user_counters
                 WHERE tenant_id = :tenantId AND user_id = :userId
                 FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", userId), (resultSet, rowNumber) -> new CounterState(
                resultSet.getLong("unread_count"),
                resultSet.getLong("actionable_unread_count"),
                resultSet.getLong("urgent_count"),
                resultSet.getLong("counter_version")));
    }

    private void updateCounter(
            NotificationRequestContext.Actor actor,
            long userId,
            long changeVersion,
            int unreadDelta,
            int actionableDelta,
            int urgentDelta) {
        int updated = jdbc.update("""
                UPDATE ntf_user_counters
                   SET unread_count = GREATEST(0, unread_count + :unreadDelta),
                       actionable_unread_count =
                           GREATEST(0, actionable_unread_count + :actionableDelta),
                       urgent_count = GREATEST(0, urgent_count + :urgentDelta),
                       counter_version = :changeVersion,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", userId)
                .addValue("changeVersion", changeVersion)
                .addValue("unreadDelta", unreadDelta)
                .addValue("actionableDelta", actionableDelta)
                .addValue("urgentDelta", urgentDelta));
        if (updated != 1) throw new IllegalStateException("Notification counter update failed.");
    }

    private CounterFlags flags(NotificationTriageState state, Instant now) {
        boolean visibleUnread = "ACTIVE".equals(state.inboxState())
                && state.readAt() == null
                && (state.snoozedUntil() == null || !state.snoozedUntil().isAfter(now));
        return new CounterFlags(
                visibleUnread ? 1 : 0,
                visibleUnread && state.actionRequired() ? 1 : 0,
                visibleUnread && "URGENT".equals(state.priority()) ? 1 : 0);
    }

    private void appendOutbox(
            NotificationRequestContext.Actor actor,
            UUID notificationId,
            String action,
            long changeVersion,
            long userId,
            Instant occurredAt) {
        String eventKey = "triage:" + notificationId + ":" + changeVersion;
        jdbc.update("""
                INSERT INTO ntf_outbox_events (
                    outbox_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, event_key, payload, occurred_at)
                VALUES (
                    :outboxId, :tenantId, 'USER_NOTIFICATION', :aggregateId,
                    :eventType, :eventKey, CAST(:payload AS jsonb), :occurredAt)
                ON CONFLICT (tenant_id, event_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("tenantId", actor.tenantId())
                .addValue("aggregateId", notificationId.toString())
                .addValue("eventType", "notification." + action.toLowerCase())
                .addValue("eventKey", eventKey)
                .addValue("payload", json(Map.of(
                        "notificationId", notificationId.toString(),
                        "userId", userId,
                        "changeVersion", changeVersion)))
                .addValue("occurredAt", Timestamp.from(occurredAt)));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize notification data.", exception);
        }
    }

    private MapSqlParameterSource actorParams(NotificationRequestContext.Actor actor) {
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

    public record MutationOutcome(
            UUID notificationId,
            String action,
            long version,
            long changeVersion,
            boolean changed,
            boolean replayed) {
    }

    private record TriagePayload(
            UUID notificationId,
            String action,
            long expectedVersion,
            Instant snoozedUntil) {
    }

    private record CounterState(long unread, long actionable, long urgent, long version) {
    }

    private record CounterFlags(int unread, int actionable, int urgent) {
    }

    @FunctionalInterface
    private interface StateTransition {
        NotificationTriageState apply(NotificationTriageState current, Instant now);
    }

}
