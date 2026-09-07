package com.dwp.services.messaging.receipt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
class MessagingReceiptRepository {
    private final NamedParameterJdbcTemplate jdbc;

    MessagingReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    Map<UUID, List<MessagingReceiptDtos.Recipient>> receipts(
            long tenantId, long userId, UUID conversationId, List<UUID> messageIds) {
        // One statement gives author authorization, eligibility and consent the same MVCC snapshot.
        return jdbc.query("""
                SELECT message.message_id, recipient.user_id, recipient.person_public_id,
                       recipient.display_name, recipient.receipt_status
                  FROM msg_messages message
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = message.tenant_id
                   AND conversation.conversation_id = message.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members author
                    ON author.tenant_id = message.tenant_id
                   AND author.conversation_id = message.conversation_id
                   AND author.user_id = :userId AND author.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= author.history_start_sequence
                  JOIN msg_people_snapshot author_person
                    ON author_person.tenant_id = author.tenant_id
                   AND author_person.user_id = author.user_id
                   AND author_person.lifecycle_state = 'ACTIVE'
                  LEFT JOIN LATERAL (
                    SELECT member.user_id, person.person_public_id, person.display_name,
                           CASE WHEN NOT COALESCE(preference.read_receipts_enabled, TRUE)
                                THEN 'UNAVAILABLE'
                                WHEN observation.message_id IS NOT NULL THEN 'READ'
                                ELSE 'UNREAD' END AS receipt_status
                      FROM msg_conversation_members member
                      JOIN msg_people_snapshot person
                        ON person.tenant_id = member.tenant_id AND person.user_id = member.user_id
                       AND person.lifecycle_state = 'ACTIVE'
                      LEFT JOIN msg_user_privacy_preferences preference
                        ON preference.tenant_id = member.tenant_id AND preference.user_id = member.user_id
                      LEFT JOIN msg_message_read_observations observation
                        ON observation.tenant_id = member.tenant_id AND observation.user_id = member.user_id
                       AND observation.message_id = message.message_id
                     WHERE member.tenant_id = message.tenant_id
                       AND member.conversation_id = message.conversation_id
                       AND member.user_id <> :userId AND member.lifecycle_state = 'ACTIVE'
                       AND member.history_start_sequence <= message.sequence
                       AND member.membership_started_at <= message.created_at
                  ) recipient ON TRUE
                 WHERE message.tenant_id = :tenantId AND message.conversation_id = :conversationId
                   AND message.message_id IN (:messageIds) AND message.sender_user_id = :userId
                   AND message.message_kind = 'USER' AND message.deleted_at IS NULL
                 ORDER BY message.sequence, recipient.display_name, recipient.user_id
                """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("userId", userId).addValue("conversationId", conversationId)
                .addValue("messageIds", messageIds), rows -> {
            Map<UUID, List<MessagingReceiptDtos.Recipient>> result = new LinkedHashMap<>();
            while (rows.next()) {
                UUID messageId = rows.getObject("message_id", UUID.class);
                var recipients = result.computeIfAbsent(messageId, ignored -> new ArrayList<>());
                Long recipientId = rows.getObject("user_id", Long.class);
                if (recipientId != null) {
                    recipients.add(new MessagingReceiptDtos.Recipient(recipientId,
                            rows.getObject("person_public_id", UUID.class), rows.getString("display_name"),
                            MessagingReceiptDtos.Status.valueOf(rows.getString("receipt_status"))));
                }
            }
            return result;
        });
    }

    List<UUID> observe(long tenantId, long userId, UUID conversationId, List<UUID> messageIds) {
        // The all-or-nothing gate prevents partial writes when even one ID is hidden or foreign.
        return jdbc.query("""
                WITH visible AS MATERIALIZED (
                    SELECT message.message_id
                      FROM msg_messages message
                      JOIN msg_conversations conversation
                        ON conversation.tenant_id = message.tenant_id
                       AND conversation.conversation_id = message.conversation_id
                       AND conversation.lifecycle_state = 'ACTIVE'
                      JOIN msg_conversation_members member
                        ON member.tenant_id = message.tenant_id
                       AND member.conversation_id = message.conversation_id
                       AND member.user_id = :userId AND member.lifecycle_state = 'ACTIVE'
                       AND member.history_start_sequence <= message.sequence
                      JOIN msg_people_snapshot person
                        ON person.tenant_id = member.tenant_id AND person.user_id = member.user_id
                       AND person.lifecycle_state = 'ACTIVE'
                     WHERE message.tenant_id = :tenantId AND message.conversation_id = :conversationId
                       AND message.message_id IN (:messageIds)
                       AND message.message_kind = 'USER' AND message.deleted_at IS NULL
                ), inserted AS (
                    INSERT INTO msg_message_read_observations (tenant_id, user_id, conversation_id, message_id)
                    SELECT :tenantId, :userId, :conversationId, message_id FROM visible
                     WHERE (SELECT COUNT(*) FROM visible) = :size
                    ON CONFLICT (tenant_id, user_id, message_id) DO NOTHING
                    RETURNING message_id
                )
                SELECT message_id FROM visible WHERE (SELECT COUNT(*) FROM visible) = :size
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("userId", userId)
                .addValue("conversationId", conversationId).addValue("messageIds", messageIds)
                .addValue("size", messageIds.size()), (row, ignored) -> row.getObject("message_id", UUID.class));
    }
}
