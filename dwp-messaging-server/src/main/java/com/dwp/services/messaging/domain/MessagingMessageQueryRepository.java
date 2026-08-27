package com.dwp.services.messaging.domain;

import com.dwp.services.messaging.attachment.AttachmentDtos;
import com.dwp.services.messaging.attachment.AttachmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class MessagingMessageQueryRepository {

    private final JdbcTemplate jdbc;
    private final AttachmentRepository attachments;

    MessagingMessageQueryRepository(JdbcTemplate jdbc) {
        this(jdbc, new AttachmentRepository(jdbc));
    }

    @Autowired
    MessagingMessageQueryRepository(JdbcTemplate jdbc, AttachmentRepository attachments) {
        this.jdbc = jdbc;
        this.attachments = attachments;
    }

    List<MessagingDtos.MessageSummary> timeline(
            long tenantId, UUID conversationId, long userId, int limit) {
        return messagePage(tenantId, conversationId, userId, null, limit).items();
    }

    MessagingDtos.MessagePage messagePage(
            long tenantId,
            UUID conversationId,
            long userId,
            Long beforeSequence,
            int limit) {
        String beforeClause = beforeSequence == null ? "" : " AND message.sequence < ?\n";
        List<Object> arguments = new ArrayList<>();
        arguments.add(userId);
        arguments.add(tenantId);
        arguments.add(conversationId);
        if (beforeSequence != null) arguments.add(beforeSequence);
        arguments.add(limit + 1);

        List<MessagingDtos.MessageSummary> descending = jdbc.query(messageSelect() + """
                 WHERE message.tenant_id = ?
                   AND message.conversation_id = ?
                   AND message.reply_to_message_id IS NULL
                """ + beforeClause + """
                 ORDER BY message.sequence DESC
                 LIMIT ?
                """, (row, ignored) -> message(row, List.of()),
                arguments.toArray());
        boolean hasMore = descending.size() > limit;
        List<MessagingDtos.MessageSummary> selected = hasMore
                ? descending.subList(0, limit)
                : descending;
        List<MessagingDtos.MessageSummary> items = attachReactions(
                tenantId, userId, selected.reversed());
        Long nextBeforeSequence = hasMore && !items.isEmpty()
                ? items.getFirst().sequence()
                : null;
        return new MessagingDtos.MessagePage(items, hasMore, nextBeforeSequence);
    }

    Optional<MessagingDtos.MessageSummary> message(
            long tenantId, UUID conversationId, long userId, UUID messageId) {
        return attachReactions(tenantId, userId, jdbc.query(messageSelect() + """
                 WHERE message.tenant_id = ?
                   AND message.conversation_id = ?
                   AND message.message_id = ?
                """, (row, ignored) -> message(row, List.of()),
                userId, tenantId, conversationId, messageId)).stream().findFirst();
    }

    List<MessagingDtos.MessageSummary> replies(
            long tenantId, UUID conversationId, long userId, UUID rootMessageId, int limit) {
        List<MessagingDtos.MessageSummary> result = jdbc.query(messageSelect() + """
                 WHERE message.tenant_id = ?
                   AND message.conversation_id = ?
                   AND message.reply_to_message_id = ?
                 ORDER BY message.sequence
                 LIMIT ?
                """, (row, ignored) -> message(row, List.of()),
                userId, tenantId, conversationId, rootMessageId, limit);
        return attachReactions(tenantId, userId, result);
    }

    long replyCount(long tenantId, UUID conversationId, UUID rootMessageId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_messages
                 WHERE tenant_id = ? AND conversation_id = ? AND reply_to_message_id = ?
                """, Long.class, tenantId, conversationId, rootMessageId);
        return count == null ? 0 : count;
    }

    Optional<MessagingMessageAccess> access(
            long tenantId, UUID conversationId, long userId, UUID messageId) {
        return jdbc.query("""
                SELECT message.message_id, message.conversation_id, message.sequence,
                       message.sender_user_id,
                       message.reply_to_message_id, message.deleted_at, message.version,
                       member.member_role
                  FROM msg_messages message
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                 WHERE message.tenant_id = ?
                   AND message.conversation_id = ?
                   AND message.message_id = ?
                """, (row, ignored) -> new MessagingMessageAccess(
                row.getObject("message_id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getLong("sequence"),
                row.getLong("sender_user_id"),
                row.getObject("reply_to_message_id", UUID.class),
                row.getObject("deleted_at", OffsetDateTime.class),
                row.getLong("version"),
                row.getString("member_role")),
                userId, tenantId, conversationId, messageId).stream().findFirst();
    }

    MessagingDtos.ConversationSettings settings(
            long tenantId, UUID conversationId, long userId) {
        return jdbc.queryForObject("""
                SELECT conversation_id, notification_level, favorite, pinned, version
                  FROM msg_conversation_members
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, (row, ignored) -> new MessagingDtos.ConversationSettings(
                row.getObject("conversation_id", UUID.class),
                row.getString("notification_level"),
                row.getBoolean("favorite"),
                row.getBoolean("pinned"),
                row.getLong("version")), tenantId, conversationId, userId);
    }

    MessagingDtos.SavedItemPage savedItems(long tenantId, long userId, int page, int pageSize) {
        List<SavedRow> rows = jdbc.query(messageSelect() + """
                 WHERE message.tenant_id = ?
                   AND saved.created_at IS NOT NULL
                   AND conversation.lifecycle_state = 'ACTIVE'
                 ORDER BY saved.created_at DESC, saved.message_id DESC
                 LIMIT ? OFFSET ?
                """, (row, ignored) -> new SavedRow(
                message(row, List.of()),
                row.getString("conversation_name"),
                row.getString("conversation_type"),
                row.getObject("saved_at", OffsetDateTime.class)),
                userId, tenantId, pageSize, page * pageSize);
        Map<UUID, MessagingDtos.MessageSummary> enriched = attachReactions(
                tenantId, userId, rows.stream().map(SavedRow::message).toList()).stream()
                .collect(LinkedHashMap::new, (map, item) -> map.put(item.messageId(), item), Map::putAll);
        List<MessagingDtos.SavedItemSummary> items = rows.stream()
                .map(row -> new MessagingDtos.SavedItemSummary(
                        enriched.get(row.message().messageId()),
                        row.conversationName(), row.conversationType(), row.savedAt()))
                .toList();
        return new MessagingDtos.SavedItemPage(items, savedItemCount(tenantId, userId), page, pageSize);
    }

    Optional<MessagingDtos.SavedItemSummary> savedItem(long tenantId, long userId, UUID messageId) {
        List<SavedRow> rows = jdbc.query(messageSelect() + """
                 WHERE message.tenant_id = ?
                   AND message.message_id = ?
                   AND saved.created_at IS NOT NULL
                   AND conversation.lifecycle_state = 'ACTIVE'
                """, (row, ignored) -> new SavedRow(
                message(row, List.of()),
                row.getString("conversation_name"),
                row.getString("conversation_type"),
                row.getObject("saved_at", OffsetDateTime.class)),
                userId, tenantId, messageId);
        if (rows.isEmpty()) return Optional.empty();
        SavedRow row = rows.getFirst();
        MessagingDtos.MessageSummary enriched = attachReactions(
                tenantId, userId, List.of(row.message())).getFirst();
        return Optional.of(new MessagingDtos.SavedItemSummary(
                enriched, row.conversationName(), row.conversationType(), row.savedAt()));
    }

    private long savedItemCount(long tenantId, long userId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_saved_items saved
                  JOIN msg_messages message
                    ON message.tenant_id = saved.tenant_id
                   AND message.message_id = saved.message_id
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.user_id = saved.user_id
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                 WHERE saved.tenant_id = ? AND saved.user_id = ?
                """, Long.class, tenantId, userId);
        return count == null ? 0 : count;
    }

    private String messageSelect() {
        return """
                SELECT message.message_id, message.conversation_id, message.sequence,
                       message.sender_user_id,
                       message.sender_person_public_id, message.sender_name,
                       CASE WHEN message.deleted_at IS NULL THEN message.body ELSE '' END AS body,
                       message.content_type, message.message_kind,
                       message.reply_to_message_id, message.edited_at, message.deleted_at,
                       message.created_at, message.version,
                       (SELECT COUNT(*) FROM msg_messages reply
                         WHERE reply.tenant_id = message.tenant_id
                           AND reply.conversation_id = message.conversation_id
                           AND reply.reply_to_message_id = message.message_id) AS reply_count,
                       root.message_id AS root_message_id,
                       root.sender_name AS root_sender_name,
                       CASE WHEN root.deleted_at IS NULL THEN root.body ELSE '' END AS root_body,
                       root.deleted_at AS root_deleted_at,
                       root.created_at AS root_created_at,
                       conversation.name AS conversation_name,
                       conversation.conversation_type AS conversation_type,
                       saved.created_at AS saved_at
                  FROM msg_messages message
                  JOIN msg_conversation_members member
                    ON member.tenant_id = message.tenant_id
                   AND member.conversation_id = message.conversation_id
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= member.history_start_sequence
                  LEFT JOIN msg_messages root
                    ON root.tenant_id = message.tenant_id
                   AND root.conversation_id = message.conversation_id
                   AND root.message_id = message.reply_to_message_id
                   AND root.sequence >= member.history_start_sequence
                  LEFT JOIN msg_conversations conversation
                    ON conversation.tenant_id = message.tenant_id
                   AND conversation.conversation_id = message.conversation_id
                  LEFT JOIN msg_saved_items saved
                    ON saved.tenant_id = message.tenant_id
                   AND saved.message_id = message.message_id
                   AND saved.user_id = member.user_id
                """;
    }

    private MessagingDtos.MessageSummary message(
            ResultSet row, List<MessagingDtos.ReactionSummary> reactions) throws SQLException {
        UUID rootMessageId = row.getObject("root_message_id", UUID.class);
        MessagingDtos.ThreadRootPreview root = rootMessageId == null ? null
                : new MessagingDtos.ThreadRootPreview(
                        rootMessageId,
                        row.getString("root_sender_name"),
                        row.getString("root_body"),
                        row.getObject("root_deleted_at", OffsetDateTime.class),
                        row.getObject("root_created_at", OffsetDateTime.class));
        return new MessagingDtos.MessageSummary(
                row.getObject("message_id", UUID.class),
                row.getObject("conversation_id", UUID.class),
                row.getLong("sequence"),
                row.getLong("sender_user_id"),
                row.getObject("sender_person_public_id", UUID.class),
                row.getString("sender_name"),
                row.getString("body"),
                row.getString("content_type"),
                row.getString("message_kind"),
                row.getObject("reply_to_message_id", UUID.class),
                row.getObject("edited_at", OffsetDateTime.class),
                row.getObject("deleted_at", OffsetDateTime.class),
                row.getObject("created_at", OffsetDateTime.class),
                row.getLong("version"),
                reactions,
                row.getInt("reply_count"),
                root);
    }

    private List<MessagingDtos.MessageSummary> attachReactions(
            long tenantId, long userId, List<MessagingDtos.MessageSummary> messages) {
        if (messages.isEmpty()) return messages;
        Map<UUID, List<MessagingDtos.ReactionSummary>> reactions = reactions(
                tenantId, userId, messages.stream().map(MessagingDtos.MessageSummary::messageId).toList());
        Map<UUID, List<AttachmentDtos.AttachmentSummary>> attachmentMap = attachments.cleanForMessages(
                tenantId, messages.stream()
                        .filter(message -> message.deletedAt() == null)
                        .map(MessagingDtos.MessageSummary::messageId)
                        .toList());
        Map<UUID, List<MessagingDtos.MentionSummary>> mentionMap = mentions(
                tenantId, messages.stream()
                        .filter(message -> message.deletedAt() == null)
                        .map(MessagingDtos.MessageSummary::messageId)
                        .toList());
        return messages.stream()
                .map(message -> new MessagingDtos.MessageSummary(
                        message.messageId(), message.conversationId(), message.sequence(),
                        message.senderUserId(),
                        message.senderPersonPublicId(), message.senderName(), message.body(),
                        message.contentType(), message.messageKind(), message.replyToMessageId(),
                        message.editedAt(), message.deletedAt(), message.createdAt(), message.version(),
                        reactions.getOrDefault(message.messageId(), List.of()),
                        message.replyCount(), message.rootPreview(),
                        attachmentMap.getOrDefault(message.messageId(), List.of()),
                        mentionMap.getOrDefault(message.messageId(), List.of())))
                .toList();
    }

    private Map<UUID, List<MessagingDtos.MentionSummary>> mentions(
            long tenantId, List<UUID> messageIds) {
        if (messageIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", messageIds.stream().map(ignored -> "?").toList());
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId);
        arguments.addAll(messageIds);
        return jdbc.query("""
                SELECT message_id, mentioned_user_id, display_name_snapshot, mention_kind
                  FROM msg_message_mentions
                 WHERE tenant_id = ? AND message_id IN (""" + placeholders + """
                 ) ORDER BY message_id, created_at, mentioned_user_id
                """, result -> {
            Map<UUID, List<MessagingDtos.MentionSummary>> grouped = new LinkedHashMap<>();
            while (result.next()) {
                grouped.computeIfAbsent(
                                result.getObject("message_id", UUID.class), ignored -> new ArrayList<>())
                        .add(new MessagingDtos.MentionSummary(
                                result.getLong("mentioned_user_id"),
                                result.getString("display_name_snapshot"),
                                result.getString("mention_kind")));
            }
            return grouped;
        }, arguments.toArray());
    }

    private Map<UUID, List<MessagingDtos.ReactionSummary>> reactions(
            long tenantId, long userId, List<UUID> messageIds) {
        String placeholders = String.join(",", messageIds.stream().map(ignored -> "?").toList());
        List<Object> arguments = new ArrayList<>();
        arguments.add(userId);
        arguments.add(tenantId);
        arguments.addAll(messageIds);
        return jdbc.query("""
                SELECT message_id, emoji, COUNT(*) AS reaction_count,
                       BOOL_OR(user_id = ?) AS mine
                  FROM msg_message_reactions
                 WHERE tenant_id = ? AND message_id IN (""" + placeholders + """
                 ) GROUP BY message_id, emoji
                 ORDER BY message_id, reaction_count DESC, emoji
                """, result -> {
            Map<UUID, List<MessagingDtos.ReactionSummary>> grouped = new LinkedHashMap<>();
            while (result.next()) {
                grouped.computeIfAbsent(result.getObject("message_id", UUID.class), ignored -> new ArrayList<>())
                        .add(new MessagingDtos.ReactionSummary(
                                result.getString("emoji"),
                                result.getInt("reaction_count"),
                                result.getBoolean("mine")));
            }
            return grouped;
        }, arguments.toArray());
    }

    private record SavedRow(
            MessagingDtos.MessageSummary message,
            String conversationName,
            String conversationType,
            OffsetDateTime savedAt) {
    }
}
