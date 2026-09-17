package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationModels.MaterializationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

final class NotificationRecipientContextWriter {

    private NotificationRecipientContextWriter() {
    }

    static void write(
            NamedParameterJdbcTemplate jdbc,
            long tenantId,
            long userId,
            UUID notificationId,
            List<MaterializationContext> contexts) {
        for (MaterializationContext context : contexts) {
            jdbc.update("""
                    INSERT INTO ntf_recipient_notification_contexts (
                        tenant_id, user_id, notification_id, kind,
                        context_key, context_key_hash, display_hint, matchable)
                    VALUES (
                        :tenantId, :userId, :notificationId, :kind,
                        :contextKey, :contextKeyHash, :displayHint, :matchable)
                    ON CONFLICT (
                        tenant_id, user_id, notification_id, kind, context_key_hash)
                    DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("userId", userId)
                    .addValue("notificationId", notificationId)
                    .addValue("kind", context.kind().name())
                    .addValue("contextKey", context.key())
                    .addValue("contextKeyHash",
                            NotificationStructuredContexts.keyHash(context))
                    .addValue("displayHint", context.displayHint())
                    .addValue("matchable", context.matchable()));
        }
    }
}
