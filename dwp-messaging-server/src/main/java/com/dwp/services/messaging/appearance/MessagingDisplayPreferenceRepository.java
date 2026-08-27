package com.dwp.services.messaging.appearance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class MessagingDisplayPreferenceRepository {

    private static final List<String> DEFAULT_THEMES = List.of("DEFAULT", "MIST", "SAGE", "ROSE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    MessagingDisplayPreferenceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    Optional<GlobalRow> global(long tenantId, long userId) {
        return jdbc.query("""
                SELECT layout_mode, density, theme_key, show_avatars,
                       timestamp_mode, message_preview, version
                  FROM msg_user_display_preferences
                 WHERE tenant_id = ? AND user_id = ?
                """, (rs, rowNum) -> new GlobalRow(
                rs.getString("layout_mode"),
                rs.getString("density"),
                rs.getString("theme_key"),
                rs.getBoolean("show_avatars"),
                rs.getString("timestamp_mode"),
                rs.getBoolean("message_preview"),
                rs.getLong("version")), tenantId, userId).stream().findFirst();
    }

    int insertGlobal(long tenantId, long userId, GlobalRow row) {
        return jdbc.update("""
                INSERT INTO msg_user_display_preferences (
                    tenant_id, user_id, layout_mode, density, theme_key,
                    show_avatars, timestamp_mode, message_preview,
                    version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """, tenantId, userId, row.layoutMode(), row.density(), row.theme(),
                row.showAvatars(), row.timestampMode(), row.messagePreview(), userId, userId);
    }

    int updateGlobal(long tenantId, long userId, GlobalRow row, long expectedVersion) {
        return jdbc.update("""
                UPDATE msg_user_display_preferences
                   SET layout_mode = ?, density = ?, theme_key = ?, show_avatars = ?,
                       timestamp_mode = ?, message_preview = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND version = ?
                """, row.layoutMode(), row.density(), row.theme(), row.showAvatars(),
                row.timestampMode(), row.messagePreview(), userId,
                tenantId, userId, expectedVersion);
    }

    Optional<ConversationRow> conversation(long tenantId, long userId, UUID conversationId) {
        return jdbc.query("""
                SELECT layout_mode, density, theme_key, version
                  FROM msg_user_conversation_display_preferences
                 WHERE tenant_id = ? AND user_id = ? AND conversation_id = ?
                """, (rs, rowNum) -> new ConversationRow(
                rs.getString("layout_mode"),
                rs.getString("density"),
                rs.getString("theme_key"),
                rs.getLong("version")), tenantId, userId, conversationId).stream().findFirst();
    }

    int insertConversation(long tenantId, long userId, UUID conversationId, ConversationRow row) {
        return jdbc.update("""
                INSERT INTO msg_user_conversation_display_preferences (
                    tenant_id, user_id, conversation_id, layout_mode, density, theme_key,
                    version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (tenant_id, user_id, conversation_id) DO NOTHING
                """, tenantId, userId, conversationId, row.layoutMode(), row.density(), row.theme(),
                userId, userId);
    }

    int updateConversation(
            long tenantId,
            long userId,
            UUID conversationId,
            ConversationRow row,
            long expectedVersion) {
        return jdbc.update("""
                UPDATE msg_user_conversation_display_preferences
                   SET layout_mode = ?, density = ?, theme_key = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND conversation_id = ? AND version = ?
                """, row.layoutMode(), row.density(), row.theme(), userId,
                tenantId, userId, conversationId, expectedVersion);
    }

    int deleteConversation(long tenantId, long userId, UUID conversationId, long expectedVersion) {
        return jdbc.update("""
                DELETE FROM msg_user_conversation_display_preferences
                 WHERE tenant_id = ? AND user_id = ? AND conversation_id = ? AND version = ?
                """, tenantId, userId, conversationId, expectedVersion);
    }

    Optional<ConversationContext> conversationContext(
            long tenantId, long userId, UUID conversationId) {
        return jdbc.query("""
                SELECT conversation.conversation_type, conversation.data_classification
                  FROM msg_conversations conversation
                  JOIN msg_conversation_members member
                    ON member.tenant_id = conversation.tenant_id
                   AND member.conversation_id = conversation.conversation_id
                   AND member.user_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE conversation.tenant_id = ?
                   AND conversation.conversation_id = ?
                   AND conversation.lifecycle_state = 'ACTIVE'
                """, (rs, rowNum) -> new ConversationContext(
                rs.getString("conversation_type"),
                rs.getString("data_classification")), userId, tenantId, conversationId)
                .stream().findFirst();
    }

    MessagingDisplayDtos.AppearancePolicy policy(long tenantId) {
        return jdbc.query("""
                SELECT allowed_theme_keys, allow_personal_backgrounds,
                       allow_theme_sharing, version
                  FROM msg_tenant_appearance_policies
                 WHERE tenant_id = ?
                """, (rs, rowNum) -> new MessagingDisplayDtos.AppearancePolicy(
                parseThemes(rs.getString("allowed_theme_keys")),
                rs.getBoolean("allow_personal_backgrounds"),
                rs.getBoolean("allow_theme_sharing"),
                rs.getLong("version")), tenantId).stream().findFirst()
                .orElse(new MessagingDisplayDtos.AppearancePolicy(
                        DEFAULT_THEMES, false, false, 0));
    }

    void auditGlobal(long tenantId, long userId, long version) {
        audit(tenantId, userId, "messaging.display-preference.updated",
                "MSG_USER_DISPLAY_PREFERENCE", String.valueOf(userId), version);
    }

    void auditConversation(long tenantId, long userId, UUID conversationId, long version) {
        audit(tenantId, userId, "messaging.conversation-display-preference.updated",
                "MSG_USER_CONVERSATION_DISPLAY_PREFERENCE", conversationId.toString(), version);
    }

    private void audit(
            long tenantId,
            long userId,
            String eventType,
            String objectType,
            String objectId,
            long version) {
        jdbc.update("""
                INSERT INTO msg_audit_events (
                    tenant_id, actor_user_id, event_type, object_type, object_id, after_state)
                VALUES (?, ?, ?, ?, ?, jsonb_build_object('version', ?))
                """, tenantId, userId, eventType, objectType, objectId, version);
    }

    private List<String> parseThemes(String value) {
        try {
            List<String> parsed = objectMapper.readValue(value, new TypeReference<>() { });
            return parsed.isEmpty() ? DEFAULT_THEMES : List.copyOf(parsed);
        } catch (Exception ignored) {
            return DEFAULT_THEMES;
        }
    }

    record GlobalRow(
            String layoutMode,
            String density,
            String theme,
            boolean showAvatars,
            String timestampMode,
            boolean messagePreview,
            long version) {
    }

    record ConversationRow(String layoutMode, String density, String theme, long version) {
    }

    record ConversationContext(String conversationType, String classification) {
    }
}
