package com.dwp.services.messaging.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class MessagingContinuityRepository {

    private static final RowMapper<MessagingContinuityDtos.DraftResponse> DRAFT_MAPPER =
            (row, ignored) -> new MessagingContinuityDtos.DraftResponse(
                    row.getObject("conversation_id", UUID.class),
                    row.getObject("thread_root_message_id", UUID.class),
                    row.getString("body"),
                    uuidArray(row, "mentioned_person_public_ids"),
                    row.getLong("version"),
                    row.getObject("updated_at", OffsetDateTime.class));

    private static final RowMapper<MessagingContinuityDtos.ScheduledMessageResponse>
            SCHEDULED_MAPPER = (row, ignored) -> scheduled(row);

    private final JdbcTemplate jdbc;

    MessagingContinuityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<MessagingContinuityDtos.DraftResponse> draft(
            long tenantId, long userId, UUID conversationId, UUID threadRootMessageId) {
        return jdbc.query("""
                SELECT draft.conversation_id, draft.thread_root_message_id, draft.body,
                       ARRAY(
                           SELECT person.person_public_id
                             FROM unnest(draft.mentioned_user_ids) WITH ORDINALITY
                                  AS mention(user_id, position)
                             JOIN msg_people_snapshot person
                               ON person.tenant_id = draft.tenant_id
                              AND person.user_id = mention.user_id
                            ORDER BY mention.position
                       ) AS mentioned_person_public_ids,
                       version, updated_at
                  FROM msg_message_drafts draft
                 WHERE draft.tenant_id = ? AND draft.user_id = ?
                   AND draft.conversation_id = ?
                   AND draft.thread_root_message_id IS NOT DISTINCT FROM ?
                """, DRAFT_MAPPER, tenantId, userId, conversationId, threadRootMessageId)
                .stream().findFirst();
    }

    boolean insertDraft(
            long tenantId,
            long userId,
            UUID conversationId,
            UUID threadRootMessageId,
            String body,
            List<Long> mentionedUserIds) {
        int inserted = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO msg_message_drafts (
                        tenant_id, user_id, conversation_id, thread_root_message_id,
                        body, mentioned_user_ids, version)
                    VALUES (?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT (tenant_id, user_id, conversation_id, thread_root_message_id)
                    DO NOTHING
                    """);
            statement.setLong(1, tenantId);
            statement.setLong(2, userId);
            statement.setObject(3, conversationId);
            statement.setObject(4, threadRootMessageId);
            statement.setString(5, body);
            statement.setArray(6, bigintArray(connection, mentionedUserIds));
            return statement;
        });
        return inserted == 1;
    }

    int updateDraft(
            long tenantId,
            long userId,
            UUID conversationId,
            UUID threadRootMessageId,
            String body,
            List<Long> mentionedUserIds,
            long expectedVersion) {
        return jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE msg_message_drafts
                       SET body = ?, mentioned_user_ids = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND user_id = ? AND conversation_id = ?
                       AND thread_root_message_id IS NOT DISTINCT FROM ?
                       AND version = ?
                    """);
            statement.setString(1, body);
            statement.setArray(2, bigintArray(connection, mentionedUserIds));
            statement.setLong(3, tenantId);
            statement.setLong(4, userId);
            statement.setObject(5, conversationId);
            statement.setObject(6, threadRootMessageId);
            statement.setLong(7, expectedVersion);
            return statement;
        });
    }

    int deleteDraft(
            long tenantId,
            long userId,
            UUID conversationId,
            UUID threadRootMessageId,
            long expectedVersion) {
        return jdbc.update("""
                DELETE FROM msg_message_drafts
                 WHERE tenant_id = ? AND user_id = ? AND conversation_id = ?
                   AND thread_root_message_id IS NOT DISTINCT FROM ?
                   AND version = ?
                """, tenantId, userId, conversationId, threadRootMessageId, expectedVersion);
    }

    boolean insertScheduled(
            ScheduledCommand command) {
        int inserted = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO msg_scheduled_messages (
                        scheduled_message_id, tenant_id, sender_user_id, conversation_id,
                        body, reply_to_message_id, mentioned_user_ids,
                        request_idempotency_key, send_idempotency_key, request_hash,
                        scheduled_for, display_time_zone, next_attempt_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, sender_user_id, request_idempotency_key)
                    DO NOTHING
                    """);
            statement.setObject(1, command.scheduledMessageId());
            statement.setLong(2, command.tenantId());
            statement.setLong(3, command.senderUserId());
            statement.setObject(4, command.conversationId());
            statement.setString(5, command.body());
            statement.setObject(6, command.replyToMessageId());
            statement.setArray(7, bigintArray(connection, command.mentionedUserIds()));
            statement.setObject(8, command.requestIdempotencyKey());
            statement.setObject(9, command.sendIdempotencyKey());
            statement.setString(10, command.requestHash());
            statement.setObject(11, command.scheduledFor());
            statement.setString(12, command.displayTimeZone());
            statement.setObject(13, command.scheduledFor());
            return statement;
        });
        return inserted == 1;
    }

    Optional<ScheduledRecord> scheduledByRequestKey(
            long tenantId, long userId, UUID requestIdempotencyKey) {
        return jdbc.query("""
                SELECT scheduled.*,
                       ARRAY(
                           SELECT person.person_public_id
                             FROM unnest(scheduled.mentioned_user_ids) WITH ORDINALITY
                                  AS mention(user_id, position)
                             JOIN msg_people_snapshot person
                               ON person.tenant_id = scheduled.tenant_id
                              AND person.user_id = mention.user_id
                            ORDER BY mention.position
                       ) AS mentioned_person_public_ids,
                       CASE WHEN member.user_id IS NOT NULL
                                  AND conversation.lifecycle_state = 'ACTIVE'
                            THEN conversation.name END AS conversation_name
                  FROM msg_scheduled_messages scheduled
                  LEFT JOIN msg_conversations conversation
                    ON conversation.tenant_id = scheduled.tenant_id
                   AND conversation.conversation_id = scheduled.conversation_id
                  LEFT JOIN msg_conversation_members member
                    ON member.tenant_id = scheduled.tenant_id
                   AND member.conversation_id = scheduled.conversation_id
                   AND member.user_id = scheduled.sender_user_id
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE scheduled.tenant_id = ? AND scheduled.sender_user_id = ?
                   AND scheduled.request_idempotency_key = ?
                """, (row, ignored) -> scheduledRecord(row),
                tenantId, userId, requestIdempotencyKey).stream().findFirst();
    }

    Optional<MessagingContinuityDtos.ScheduledMessageResponse> scheduled(
            long tenantId, long userId, UUID scheduledMessageId) {
        return jdbc.query("""
                SELECT scheduled.*,
                       ARRAY(
                           SELECT person.person_public_id
                             FROM unnest(scheduled.mentioned_user_ids) WITH ORDINALITY
                                  AS mention(user_id, position)
                             JOIN msg_people_snapshot person
                               ON person.tenant_id = scheduled.tenant_id
                              AND person.user_id = mention.user_id
                            ORDER BY mention.position
                       ) AS mentioned_person_public_ids,
                       CASE WHEN member.user_id IS NOT NULL
                                  AND conversation.lifecycle_state = 'ACTIVE'
                            THEN conversation.name END AS conversation_name
                  FROM msg_scheduled_messages scheduled
                  LEFT JOIN msg_conversations conversation
                    ON conversation.tenant_id = scheduled.tenant_id
                   AND conversation.conversation_id = scheduled.conversation_id
                  LEFT JOIN msg_conversation_members member
                    ON member.tenant_id = scheduled.tenant_id
                   AND member.conversation_id = scheduled.conversation_id
                   AND member.user_id = scheduled.sender_user_id
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE scheduled.tenant_id = ? AND scheduled.sender_user_id = ?
                   AND scheduled.scheduled_message_id = ?
                """, SCHEDULED_MAPPER, tenantId, userId, scheduledMessageId)
                .stream().findFirst();
    }

    List<MessagingContinuityDtos.ScheduledMessageResponse> scheduled(
            long tenantId,
            long userId,
            String status,
            MessagingScheduledCursor.Cursor cursor,
            int limit) {
        return jdbc.query("""
                SELECT scheduled.*,
                       ARRAY(
                           SELECT person.person_public_id
                             FROM unnest(scheduled.mentioned_user_ids) WITH ORDINALITY
                                  AS mention(user_id, position)
                             JOIN msg_people_snapshot person
                               ON person.tenant_id = scheduled.tenant_id
                              AND person.user_id = mention.user_id
                            ORDER BY mention.position
                       ) AS mentioned_person_public_ids,
                       CASE WHEN member.user_id IS NOT NULL
                                  AND conversation.lifecycle_state = 'ACTIVE'
                            THEN conversation.name END AS conversation_name
                  FROM msg_scheduled_messages scheduled
                  LEFT JOIN msg_conversations conversation
                    ON conversation.tenant_id = scheduled.tenant_id
                   AND conversation.conversation_id = scheduled.conversation_id
                  LEFT JOIN msg_conversation_members member
                    ON member.tenant_id = scheduled.tenant_id
                   AND member.conversation_id = scheduled.conversation_id
                   AND member.user_id = scheduled.sender_user_id
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE scheduled.tenant_id = ? AND scheduled.sender_user_id = ?
                   AND (?::VARCHAR IS NULL OR scheduled.status = ?)
                   AND (?::TIMESTAMPTZ IS NULL
                        OR (scheduled.created_at, scheduled.scheduled_message_id) < (?, ?))
                 ORDER BY scheduled.created_at DESC, scheduled.scheduled_message_id DESC
                 LIMIT ?
                """, SCHEDULED_MAPPER, tenantId, userId, status, status,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.scheduledMessageId(), limit);
    }

    Optional<MessagingContinuityDtos.ScheduledMessageResponse> cancel(
            long tenantId, long userId, UUID scheduledMessageId, long expectedVersion) {
        return jdbc.query("""
                WITH updated AS (
                UPDATE msg_scheduled_messages scheduled
                   SET status = 'CANCELLED', version = version + 1,
                       processing_started_at = NULL, processing_lease_until = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE scheduled.tenant_id = ? AND scheduled.sender_user_id = ?
                   AND scheduled.scheduled_message_id = ?
                   AND scheduled.status = 'PENDING' AND scheduled.version = ?
                RETURNING scheduled.*)
                SELECT updated.*,
                       ARRAY(
                           SELECT person.person_public_id
                             FROM unnest(updated.mentioned_user_ids) WITH ORDINALITY
                                  AS mention(user_id, position)
                             JOIN msg_people_snapshot person
                               ON person.tenant_id = updated.tenant_id
                              AND person.user_id = mention.user_id
                            ORDER BY mention.position
                       ) AS mentioned_person_public_ids,
                       CASE WHEN member.user_id IS NOT NULL
                                  AND conversation.lifecycle_state = 'ACTIVE'
                            THEN conversation.name END AS conversation_name
                  FROM updated
                  LEFT JOIN msg_conversations conversation
                    ON conversation.tenant_id = updated.tenant_id
                   AND conversation.conversation_id = updated.conversation_id
                  LEFT JOIN msg_conversation_members member
                    ON member.tenant_id = updated.tenant_id
                   AND member.conversation_id = updated.conversation_id
                   AND member.user_id = updated.sender_user_id
                   AND member.lifecycle_state = 'ACTIVE'
                """, SCHEDULED_MAPPER,
                tenantId, userId, scheduledMessageId, expectedVersion).stream().findFirst();
    }

    List<ScheduledRecord> claimDue(int limit) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT scheduled_message_id
                      FROM msg_scheduled_messages
                     WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
                        OR (status = 'PROCESSING'
                            AND processing_lease_until < CURRENT_TIMESTAMP)
                     ORDER BY next_attempt_at, scheduled_message_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                , updated AS (
                UPDATE msg_scheduled_messages scheduled
                   SET status = 'PROCESSING',
                       attempt_count = attempt_count + 1,
                       processing_started_at = CURRENT_TIMESTAMP,
                       processing_lease_until = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM candidates
                 WHERE scheduled.scheduled_message_id = candidates.scheduled_message_id
                RETURNING scheduled.*)
                SELECT updated.*,
                       ARRAY(
                           SELECT person.person_public_id
                             FROM unnest(updated.mentioned_user_ids) WITH ORDINALITY
                                  AS mention(user_id, position)
                             JOIN msg_people_snapshot person
                               ON person.tenant_id = updated.tenant_id
                              AND person.user_id = mention.user_id
                            ORDER BY mention.position
                       ) AS mentioned_person_public_ids,
                       CASE WHEN member.user_id IS NOT NULL
                                  AND conversation.lifecycle_state = 'ACTIVE'
                            THEN conversation.name END AS conversation_name
                  FROM updated
                  LEFT JOIN msg_conversations conversation
                    ON conversation.tenant_id = updated.tenant_id
                   AND conversation.conversation_id = updated.conversation_id
                  LEFT JOIN msg_conversation_members member
                    ON member.tenant_id = updated.tenant_id
                   AND member.conversation_id = updated.conversation_id
                   AND member.user_id = updated.sender_user_id
                   AND member.lifecycle_state = 'ACTIVE'
                """, (row, ignored) -> scheduledRecord(row), limit);
    }

    void recordWorkerStarted(UUID workerId) {
        jdbc.update("""
                INSERT INTO msg_scheduled_delivery_worker_heartbeats (
                    worker_id, last_started_at)
                VALUES (?, CURRENT_TIMESTAMP)
                ON CONFLICT (worker_id) DO UPDATE
                    SET last_started_at = CURRENT_TIMESTAMP,
                        claimed_count = 0, delivered_count = 0,
                        retry_count = 0, failed_count = 0,
                        updated_at = CURRENT_TIMESTAMP
                """, workerId);
    }

    void recordWorkerCompleted(
            UUID workerId,
            int claimedCount,
            int deliveredCount,
            int retryCount,
            int failedCount,
            String cycleFailureCode) {
        jdbc.update("""
                UPDATE msg_scheduled_delivery_worker_heartbeats
                   SET last_completed_at = CASE WHEN ?::VARCHAR IS NULL
                                                THEN CURRENT_TIMESTAMP
                                                ELSE last_completed_at END,
                       last_failure_at = CASE WHEN ?::VARCHAR IS NULL
                                              THEN last_failure_at
                                              ELSE CURRENT_TIMESTAMP END,
                       last_failure_code = CASE WHEN ?::VARCHAR IS NULL
                                                THEN last_failure_code
                                                ELSE ? END,
                       claimed_count = ?, delivered_count = ?,
                       retry_count = ?, failed_count = ?,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE worker_id = ?
                """, cycleFailureCode, cycleFailureCode, cycleFailureCode, cycleFailureCode,
                claimedCount, deliveredCount, retryCount, failedCount, workerId);
    }

    Optional<PersonIdentity> activePerson(long tenantId, long userId) {
        return jdbc.query("""
                SELECT person_public_id, display_name
                  FROM msg_people_snapshot
                 WHERE tenant_id = ? AND user_id = ? AND lifecycle_state = 'ACTIVE'
                """, (row, ignored) -> new PersonIdentity(
                        row.getObject("person_public_id", UUID.class),
                        row.getString("display_name")), tenantId, userId)
                .stream().findFirst();
    }

    List<MentionIdentity> activeMentionIdentities(
            long tenantId, List<UUID> personPublicIds) {
        if (personPublicIds.isEmpty()) return List.of();
        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT person_public_id, user_id
                      FROM msg_people_snapshot
                     WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                       AND person_public_id = ANY (?::UUID[])
                     ORDER BY person_public_id
                    """);
            statement.setLong(1, tenantId);
            statement.setArray(2, uuidArray(connection, personPublicIds));
            return statement;
        }, (row, ignored) -> new MentionIdentity(
                row.getObject("person_public_id", UUID.class),
                row.getLong("user_id")));
    }

    Optional<UUID> deliveredMessage(
            long tenantId, long userId, UUID conversationId, UUID sendIdempotencyKey) {
        return jdbc.query("""
                SELECT ledger.result_message_id
                  FROM msg_idempotency_keys ledger
                  JOIN msg_messages message
                    ON message.tenant_id = ledger.tenant_id
                   AND message.message_id = ledger.result_message_id
                   AND message.conversation_id = ?
                 WHERE ledger.tenant_id = ? AND ledger.user_id = ?
                   AND ledger.operation = 'SEND_MESSAGE'
                   AND ledger.idempotency_key = ?
                   AND ledger.result_message_id IS NOT NULL
                """, (row, ignored) -> row.getObject("result_message_id", UUID.class),
                conversationId, tenantId, userId, sendIdempotencyKey).stream().findFirst();
    }

    int markSent(UUID scheduledMessageId, UUID deliveredMessageId) {
        return jdbc.update("""
                UPDATE msg_scheduled_messages
                   SET status = 'SENT', delivered_message_id = ?,
                       delivered_at = CURRENT_TIMESTAMP,
                       processing_started_at = NULL, processing_lease_until = NULL,
                       failure_code = NULL, failure_detail = NULL,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE scheduled_message_id = ? AND status = 'PROCESSING'
                """, deliveredMessageId, scheduledMessageId);
    }

    int markRetry(UUID scheduledMessageId, String failureCode, String failureDetail, long delaySeconds) {
        return jdbc.update("""
                UPDATE msg_scheduled_messages
                   SET status = 'PENDING', next_attempt_at = CURRENT_TIMESTAMP
                           + (? * INTERVAL '1 second'),
                       processing_started_at = NULL, processing_lease_until = NULL,
                       failure_code = ?, failure_detail = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE scheduled_message_id = ? AND status = 'PROCESSING'
                """, delaySeconds, failureCode, failureDetail, scheduledMessageId);
    }

    int markFailed(UUID scheduledMessageId, String failureCode, String failureDetail) {
        return jdbc.update("""
                UPDATE msg_scheduled_messages
                   SET status = 'FAILED',
                       processing_started_at = NULL, processing_lease_until = NULL,
                       failure_code = ?, failure_detail = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE scheduled_message_id = ? AND status = 'PROCESSING'
                """, failureCode, failureDetail, scheduledMessageId);
    }

    private static ScheduledRecord scheduledRecord(ResultSet row) throws SQLException {
        return new ScheduledRecord(
                scheduled(row),
                row.getLong("tenant_id"),
                row.getLong("sender_user_id"),
                longArray(row, "mentioned_user_ids"),
                row.getObject("request_idempotency_key", UUID.class),
                row.getObject("send_idempotency_key", UUID.class),
                row.getString("request_hash"));
    }

    private static MessagingContinuityDtos.ScheduledMessageResponse scheduled(ResultSet row)
            throws SQLException {
        return new MessagingContinuityDtos.ScheduledMessageResponse(
                row.getObject("scheduled_message_id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getString("body"),
                row.getObject("reply_to_message_id", UUID.class),
                uuidArray(row, "mentioned_person_public_ids"),
                row.getObject("scheduled_for", OffsetDateTime.class),
                row.getString("display_time_zone"),
                row.getString("status"),
                row.getInt("attempt_count"),
                row.getString("failure_code"),
                row.getString("failure_detail"),
                row.getObject("delivered_message_id", UUID.class),
                row.getObject("delivered_at", OffsetDateTime.class),
                row.getLong("version"),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("updated_at", OffsetDateTime.class),
                row.getString("conversation_name"),
                row.getObject("payload_purged_at", OffsetDateTime.class));
    }

    private static Array bigintArray(java.sql.Connection connection, List<Long> values)
            throws SQLException {
        return connection.createArrayOf("bigint", values.toArray(Long[]::new));
    }

    private static Array uuidArray(java.sql.Connection connection, List<UUID> values)
            throws SQLException {
        return connection.createArrayOf("uuid", values.toArray(UUID[]::new));
    }

    private static List<UUID> uuidArray(ResultSet row, String column) throws SQLException {
        Array value = row.getArray(column);
        if (value == null) return List.of();
        Object array = value.getArray();
        if (array instanceof UUID[] uuids) return List.copyOf(Arrays.asList(uuids));
        return Arrays.stream((Object[]) array)
                .map(item -> item instanceof UUID uuid ? uuid : UUID.fromString(item.toString()))
                .toList();
    }

    private static List<Long> longArray(ResultSet row, String column) throws SQLException {
        Array value = row.getArray(column);
        if (value == null) return List.of();
        Object array = value.getArray();
        if (array instanceof Long[] longs) return List.copyOf(Arrays.asList(longs));
        return Arrays.stream((Object[]) array)
                .map(Number.class::cast)
                .map(Number::longValue)
                .toList();
    }

    record ScheduledCommand(
            UUID scheduledMessageId,
            long tenantId,
            long senderUserId,
            UUID conversationId,
            String body,
            UUID replyToMessageId,
            List<Long> mentionedUserIds,
            UUID requestIdempotencyKey,
            UUID sendIdempotencyKey,
            String requestHash,
            OffsetDateTime scheduledFor,
            String displayTimeZone) {
    }

    record ScheduledRecord(
            MessagingContinuityDtos.ScheduledMessageResponse response,
            long tenantId,
            long senderUserId,
            List<Long> mentionedUserIds,
            UUID requestIdempotencyKey,
            UUID sendIdempotencyKey,
            String requestHash) {
    }

    record PersonIdentity(UUID personPublicId, String displayName) {
    }

    record MentionIdentity(UUID personPublicId, long userId) {
    }
}
