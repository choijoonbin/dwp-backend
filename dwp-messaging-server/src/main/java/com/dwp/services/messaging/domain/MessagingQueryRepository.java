package com.dwp.services.messaging.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class MessagingQueryRepository {

    private final JdbcTemplate jdbc;
    private final MessagingMessageQueryRepository messageQueries;

    MessagingQueryRepository(JdbcTemplate jdbc, MessagingMessageQueryRepository messageQueries) {
        this.jdbc = jdbc;
        this.messageQueries = messageQueries;
    }

    List<MessagingDtos.ConversationSummary> conversations(
            long tenantId,
            long userId,
            String scope,
            String query,
            int page,
            int pageSize) {
        return jdbc.query(conversationSelect() + """
                 WHERE conversation.tenant_id = ?
                   AND conversation.lifecycle_state = 'ACTIVE'
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND (? = 'ALL'
                        OR (? = 'FAVORITES' AND member.favorite = TRUE)
                        OR (? = 'SPACES' AND conversation.visibility = 'SPACE')
                        OR (? = 'DIRECT' AND conversation.conversation_type = 'DIRECT')
                        OR (? = 'CHANNELS' AND conversation.conversation_type <> 'DIRECT'))
                   AND (? = '' OR LOWER(COALESCE(conversation.name, '')) LIKE ?
                        OR LOWER(COALESCE(conversation.topic, '')) LIKE ?)
                 GROUP BY conversation.conversation_id, member.favorite, member.pinned,
                          member.last_read_sequence, last_message.message_id
                 ORDER BY member.pinned DESC, member.favorite DESC,
                          conversation.last_message_at DESC NULLS LAST,
                          conversation.conversation_id
                 LIMIT ? OFFSET ?
                """, (result, ignored) -> conversation(result),
                tenantId, userId,
                scope, scope, scope, scope, scope,
                query, pattern(query), pattern(query),
                pageSize, page * pageSize);
    }

    long conversationCount(long tenantId, long userId, String scope, String query) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_conversations conversation
                  JOIN msg_conversation_members member
                    ON member.tenant_id = conversation.tenant_id
                   AND member.conversation_id = conversation.conversation_id
                 WHERE conversation.tenant_id = ?
                   AND conversation.lifecycle_state = 'ACTIVE'
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND (? = 'ALL'
                        OR (? = 'FAVORITES' AND member.favorite = TRUE)
                        OR (? = 'SPACES' AND conversation.visibility = 'SPACE')
                        OR (? = 'DIRECT' AND conversation.conversation_type = 'DIRECT')
                        OR (? = 'CHANNELS' AND conversation.conversation_type <> 'DIRECT'))
                   AND (? = '' OR LOWER(COALESCE(conversation.name, '')) LIKE ?
                        OR LOWER(COALESCE(conversation.topic, '')) LIKE ?)
                """, Long.class,
                tenantId, userId, scope, scope, scope, scope, scope,
                query, pattern(query), pattern(query));
        return count == null ? 0 : count;
    }

    Optional<MessagingDtos.ConversationSummary> conversation(
            long tenantId, long userId, UUID conversationId) {
        return jdbc.query(conversationSelect() + """
                 WHERE conversation.tenant_id = ?
                   AND conversation.conversation_id = ?
                   AND conversation.lifecycle_state = 'ACTIVE'
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                 GROUP BY conversation.conversation_id, member.favorite, member.pinned,
                          member.last_read_sequence, last_message.message_id
                """, (result, ignored) -> conversation(result), tenantId, conversationId, userId)
                .stream()
                .findFirst();
    }

    List<MessagingDtos.MessageSummary> messages(
            long tenantId, UUID conversationId, long userId, int limit) {
        return messageQueries.timeline(tenantId, conversationId, userId, limit);
    }

    List<MessagingDtos.MemberSummary> members(long tenantId, UUID conversationId) {
        return jdbc.query("""
                SELECT member.user_id, member.person_public_id, person.display_name,
                       person.email_address, person.job_title, person.organization_name,
                       person.presence_state, member.member_role, member.membership_source,
                       member.notification_level, member.favorite, member.pinned,
                       member.last_read_message_id, member.last_read_sequence, member.last_read_at
                  FROM msg_conversation_members member
                  JOIN msg_people_snapshot person
                    ON person.tenant_id = member.tenant_id
                   AND person.user_id = member.user_id
                 WHERE member.tenant_id = ?
                   AND member.conversation_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                 ORDER BY CASE member.member_role
                              WHEN 'OWNER' THEN 0 WHEN 'MODERATOR' THEN 1
                              WHEN 'MEMBER' THEN 2 ELSE 3 END,
                          person.display_name, member.user_id
                """, (result, ignored) -> new MessagingDtos.MemberSummary(
                result.getLong("user_id"),
                result.getObject("person_public_id", UUID.class),
                result.getString("display_name"),
                result.getString("email_address"),
                result.getString("job_title"),
                result.getString("organization_name"),
                result.getString("presence_state"),
                result.getString("member_role"),
                result.getString("membership_source"),
                result.getString("notification_level"),
                result.getBoolean("favorite"),
                result.getBoolean("pinned"),
                result.getObject("last_read_message_id", UUID.class),
                result.getLong("last_read_sequence"),
                result.getObject("last_read_at", OffsetDateTime.class)),
                tenantId, conversationId);
    }

    List<MessagingDtos.PersonSummary> people(long tenantId, long userId, String query, int limit) {
        return jdbc.query("""
                SELECT user_id, person_public_id, email_address, display_name,
                       job_title, organization_name, presence_state
                  FROM msg_people_snapshot
                 WHERE tenant_id = ?
                   AND lifecycle_state = 'ACTIVE'
                   AND user_id <> ?
                   AND (? = '' OR LOWER(display_name) LIKE ?
                        OR LOWER(email_address) LIKE ?
                        OR LOWER(COALESCE(job_title, '')) LIKE ?)
                 ORDER BY CASE presence_state
                              WHEN 'AVAILABLE' THEN 0 WHEN 'FOCUS' THEN 1
                              WHEN 'BUSY' THEN 2 WHEN 'AWAY' THEN 3 ELSE 4 END,
                          display_name, user_id
                 LIMIT ?
                """, (result, ignored) -> person(result),
                tenantId, userId, query, pattern(query), pattern(query), pattern(query), limit);
    }

    MessagingDtos.HomeMetrics metrics(long tenantId, long userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(COUNT(DISTINCT conversation.conversation_id)
                       FILTER (WHERE unread.message_id IS NOT NULL), 0) AS unread_conversations,
                       COALESCE((SELECT COUNT(*)
                           FROM msg_message_mentions mention
                           JOIN msg_messages mentioned_message
                             ON mentioned_message.tenant_id = mention.tenant_id
                            AND mentioned_message.conversation_id = mention.conversation_id
                            AND mentioned_message.message_id = mention.message_id
                           JOIN msg_conversation_members mentioned_member
                             ON mentioned_member.tenant_id = mention.tenant_id
                            AND mentioned_member.conversation_id = mention.conversation_id
                            AND mentioned_member.user_id = mention.mentioned_user_id
                            AND mentioned_member.lifecycle_state = 'ACTIVE'
                          WHERE mention.tenant_id = ?
                            AND mention.mentioned_user_id = ?
                            AND mentioned_message.deleted_at IS NULL
                            AND mentioned_message.sequence > mentioned_member.last_read_sequence
                            AND mentioned_message.sequence >= mentioned_member.history_start_sequence),
                           0) AS mentions,
                       COALESCE(COUNT(DISTINCT conversation.conversation_id)
                       FILTER (WHERE conversation.visibility = 'SPACE'), 0) AS space_channels,
                       COALESCE(COUNT(DISTINCT conversation.conversation_id)
                       FILTER (WHERE conversation.conversation_type = 'DIRECT'), 0) AS direct_messages,
                       COALESCE((SELECT COUNT(*)
                           FROM msg_saved_items saved
                           JOIN msg_messages saved_message
                             ON saved_message.tenant_id = saved.tenant_id
                            AND saved_message.message_id = saved.message_id
                           JOIN msg_conversation_members saved_member
                             ON saved_member.tenant_id = saved_message.tenant_id
                            AND saved_member.conversation_id = saved_message.conversation_id
                            AND saved_member.user_id = saved.user_id
                            AND saved_member.lifecycle_state = 'ACTIVE'
                            AND saved_message.sequence >= saved_member.history_start_sequence
                          WHERE saved.tenant_id = ? AND saved.user_id = ?), 0) AS saved_items
                  FROM msg_conversations conversation
                  JOIN msg_conversation_members member
                    ON member.tenant_id = conversation.tenant_id
                   AND member.conversation_id = conversation.conversation_id
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                  LEFT JOIN msg_messages unread
                    ON unread.tenant_id = conversation.tenant_id
                   AND unread.conversation_id = conversation.conversation_id
                   AND unread.sender_user_id <> ?
                   AND unread.sequence > member.last_read_sequence
                   AND unread.sequence >= member.history_start_sequence
                 WHERE conversation.tenant_id = ?
                   AND conversation.lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> new MessagingDtos.HomeMetrics(
                result.getInt("unread_conversations"),
                result.getInt("mentions"),
                result.getInt("space_channels"),
                result.getInt("direct_messages"),
                result.getInt("saved_items")),
                tenantId, userId, tenantId, userId, userId, userId, tenantId);
    }

    MessagingDtos.TenantPolicy policy(long tenantId) {
        return jdbc.queryForObject("""
                SELECT direct_messages_enabled, space_messaging_enabled,
                       allow_message_edit, allow_message_delete,
                       ai_assistance_enabled, ai_auto_execute_enabled,
                       retention_days, maximum_attachment_mb, version
                  FROM msg_tenant_policies
                 WHERE tenant_id = ?
                """, (result, ignored) -> new MessagingDtos.TenantPolicy(
                result.getBoolean("direct_messages_enabled"),
                result.getBoolean("space_messaging_enabled"),
                result.getBoolean("allow_message_edit"),
                result.getBoolean("allow_message_delete"),
                result.getBoolean("ai_assistance_enabled"),
                result.getBoolean("ai_auto_execute_enabled"),
                result.getInt("retention_days"),
                result.getInt("maximum_attachment_mb"),
                result.getLong("version")), tenantId);
    }

    MessagingDtos.AdminMetrics adminMetrics(long tenantId) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM msg_conversations
                      WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE') AS active_conversations,
                    (SELECT COUNT(*) FROM msg_conversations
                      WHERE tenant_id = ? AND visibility = 'SPACE'
                        AND lifecycle_state = 'ACTIVE') AS space_linked_conversations,
                    (SELECT COUNT(*) FROM msg_conversation_members
                      WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE') AS active_members,
                    (SELECT COUNT(*) FROM msg_messages WHERE tenant_id = ?) AS retained_messages,
                    (SELECT COUNT(*) FROM msg_conversations
                      WHERE tenant_id = ? AND data_classification = 'RESTRICTED'
                        AND lifecycle_state = 'ACTIVE') AS restricted_conversations
                """, (result, ignored) -> new MessagingDtos.AdminMetrics(
                result.getInt("active_conversations"),
                result.getInt("space_linked_conversations"),
                result.getInt("active_members"),
                result.getInt("retained_messages"),
                result.getInt("restricted_conversations")),
                tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    boolean isMember(long tenantId, UUID conversationId, long userId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_conversation_members
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, Long.class, tenantId, conversationId, userId);
        return count != null && count > 0;
    }

    Optional<MessagingDtos.PersonSummary> person(long tenantId, long userId) {
        return jdbc.query("""
                SELECT user_id, person_public_id, email_address, display_name,
                       job_title, organization_name, presence_state
                  FROM msg_people_snapshot
                 WHERE tenant_id = ? AND user_id = ? AND lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> person(result), tenantId, userId).stream().findFirst();
    }

    private String conversationSelect() {
        return """
                SELECT conversation.conversation_id, conversation.conversation_key,
                       conversation.conversation_type, conversation.name, conversation.topic,
                       conversation.visibility, conversation.data_classification,
                       conversation.linked_space_key, conversation.linked_space_name,
                       conversation.lifecycle_state,
                       COUNT(DISTINCT active_member.user_id) AS member_count,
                       COUNT(DISTINCT unread.message_id) AS unread_count,
                       member.favorite, member.pinned,
                       last_message.message_id AS last_message_id,
                       last_message.sequence AS last_message_sequence,
                       last_message.sender_user_id AS last_sender_user_id,
                       last_message.sender_person_public_id AS last_sender_person_public_id,
                       last_message.sender_name AS last_sender_name,
                       CASE WHEN last_message.deleted_at IS NULL
                            THEN last_message.body ELSE '' END AS last_body,
                       last_message.content_type AS last_content_type,
                       last_message.message_kind AS last_message_kind,
                       last_message.reply_to_message_id AS last_reply_to_message_id,
                       last_message.edited_at AS last_edited_at,
                       last_message.deleted_at AS last_deleted_at,
                       last_message.created_at AS last_created_at,
                       last_message.version AS last_version,
                       CASE WHEN last_message.message_id IS NOT NULL
                            THEN conversation.last_message_at END AS last_message_at,
                       conversation.version
                  FROM msg_conversations conversation
                  JOIN msg_conversation_members member
                    ON member.tenant_id = conversation.tenant_id
                   AND member.conversation_id = conversation.conversation_id
                  LEFT JOIN msg_conversation_members active_member
                    ON active_member.tenant_id = conversation.tenant_id
                   AND active_member.conversation_id = conversation.conversation_id
                   AND active_member.lifecycle_state = 'ACTIVE'
                  LEFT JOIN msg_messages unread
                    ON unread.tenant_id = conversation.tenant_id
                   AND unread.conversation_id = conversation.conversation_id
                   AND unread.sender_user_id <> member.user_id
                   AND unread.deleted_at IS NULL
                   AND unread.sequence > member.last_read_sequence
                   AND unread.sequence >= member.history_start_sequence
                  LEFT JOIN msg_messages last_message
                    ON last_message.message_id = conversation.last_message_id
                   AND last_message.tenant_id = conversation.tenant_id
                   AND last_message.sequence >= member.history_start_sequence
                """;
    }

    private MessagingDtos.ConversationSummary conversation(ResultSet result) throws SQLException {
        UUID lastMessageId = result.getObject("last_message_id", UUID.class);
        MessagingDtos.MessageSummary lastMessage = lastMessageId == null ? null : new MessagingDtos.MessageSummary(
                lastMessageId,
                result.getObject("conversation_id", UUID.class),
                result.getLong("last_message_sequence"),
                result.getLong("last_sender_user_id"),
                result.getObject("last_sender_person_public_id", UUID.class),
                result.getString("last_sender_name"),
                result.getString("last_body"),
                result.getString("last_content_type"),
                result.getString("last_message_kind"),
                result.getObject("last_reply_to_message_id", UUID.class),
                result.getObject("last_edited_at", OffsetDateTime.class),
                result.getObject("last_deleted_at", OffsetDateTime.class),
                result.getObject("last_created_at", OffsetDateTime.class),
                result.getLong("last_version"),
                List.of(),
                0,
                null);
        return new MessagingDtos.ConversationSummary(
                result.getObject("conversation_id", UUID.class),
                result.getString("conversation_key"),
                result.getString("conversation_type"),
                result.getString("name"),
                result.getString("topic"),
                result.getString("visibility"),
                result.getString("data_classification"),
                result.getString("linked_space_key"),
                result.getString("linked_space_name"),
                result.getString("lifecycle_state"),
                result.getInt("member_count"),
                result.getInt("unread_count"),
                result.getBoolean("favorite"),
                result.getBoolean("pinned"),
                lastMessage,
                result.getObject("last_message_at", OffsetDateTime.class),
                result.getLong("version"));
    }

    private MessagingDtos.PersonSummary person(ResultSet result) throws SQLException {
        return new MessagingDtos.PersonSummary(
                result.getLong("user_id"),
                result.getObject("person_public_id", UUID.class),
                result.getString("email_address"),
                result.getString("display_name"),
                result.getString("job_title"),
                result.getString("organization_name"),
                result.getString("presence_state"));
    }

    private String pattern(String value) {
        return "%" + value.toLowerCase() + "%";
    }
}
