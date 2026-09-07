package com.dwp.services.messaging.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MessagingPrivacyRepository {
    private final JdbcTemplate jdbc;

    MessagingPrivacyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    MessagingPrivacyDtos.PrivacyPreference preference(long tenantId, long userId) {
        return jdbc.query("""
                SELECT read_receipts_enabled, version FROM msg_user_privacy_preferences
                 WHERE tenant_id = ? AND user_id = ?
                """, (row, ignored) -> new MessagingPrivacyDtos.PrivacyPreference(
                row.getBoolean("read_receipts_enabled"), row.getLong("version")), tenantId, userId)
                .stream().findFirst().orElse(new MessagingPrivacyDtos.PrivacyPreference(true, 0));
    }

    int save(long tenantId, long userId, boolean enabled, long version) {
        if (version == 0) {
            return jdbc.update("""
                    INSERT INTO msg_user_privacy_preferences (tenant_id, user_id, read_receipts_enabled)
                    VALUES (?, ?, ?) ON CONFLICT (tenant_id, user_id) DO NOTHING
                    """, tenantId, userId, enabled);
        }
        return jdbc.update("""
                UPDATE msg_user_privacy_preferences
                   SET read_receipts_enabled = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ? AND version = ?
                """, enabled, tenantId, userId, version);
    }

    void audit(long tenantId, long userId, long version) {
        jdbc.update("""
                INSERT INTO msg_audit_events (
                    tenant_id, actor_user_id, event_type, object_type, object_id, after_state)
                VALUES (?, ?, 'messaging.privacy-preferences.updated',
                        'MSG_USER_PRIVACY_PREFERENCE', ?, jsonb_build_object('version', ?))
                """, tenantId, userId, String.valueOf(userId), version);
    }
}
