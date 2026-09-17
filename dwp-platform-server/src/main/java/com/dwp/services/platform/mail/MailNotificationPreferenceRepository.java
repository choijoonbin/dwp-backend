package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
class MailNotificationPreferenceRepository {

    private final JdbcTemplate jdbc;

    MailNotificationPreferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<NewMailTarget> newMailTarget(long tenantId, UUID threadId, UUID messageId) {
        return jdbc.query("""
                SELECT account.owner_user_id, message.sent_at
                  FROM mail_messages message
                  JOIN mail_threads thread
                    ON thread.tenant_id = message.tenant_id
                   AND thread.thread_id = message.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE message.tenant_id = ? AND message.thread_id = ?
                   AND message.message_id = ?
                   AND message.message_direction = 'INBOUND'
                   AND account.account_kind = 'PERSONAL'
                   AND account.owner_user_id IS NOT NULL
                """, (result, ignored) -> new NewMailTarget(
                        result.getLong("owner_user_id"),
                        result.getObject("sent_at", OffsetDateTime.class)),
                tenantId, threadId, messageId).stream().findFirst();
    }

    Optional<AssignmentTarget> assignmentTarget(
            long tenantId, UUID threadId, long assignedUserId, long version) {
        return jdbc.query("""
                SELECT shared_inbox_id, updated_at
                  FROM mail_threads
                 WHERE tenant_id = ? AND thread_id = ?
                   AND shared_inbox_id IS NOT NULL
                   AND assigned_user_id = ? AND version = ?
                """, (result, ignored) -> new AssignmentTarget(
                        result.getObject("shared_inbox_id", UUID.class),
                        result.getObject("updated_at", OffsetDateTime.class)),
                tenantId, threadId, assignedUserId, version).stream().findFirst();
    }

    boolean notifyNewMail(long tenantId, long userId) {
        return enabled(tenantId, userId, "notify_new_mail");
    }

    boolean notifySharedAssignment(long tenantId, long userId) {
        return enabled(tenantId, userId, "notify_shared_assignment");
    }

    boolean notifyFollowUpDue(long tenantId, long userId) {
        return enabled(tenantId, userId, "notify_follow_up_due");
    }

    private boolean enabled(long tenantId, long userId, String column) {
        if (!column.equals("notify_new_mail")
                && !column.equals("notify_shared_assignment")
                && !column.equals("notify_follow_up_due")) {
            throw new IllegalArgumentException("Unknown mail notification preference.");
        }
        Boolean value = jdbc.queryForObject("""
                SELECT COALESCE((
                    SELECT %s
                      FROM mail_user_preferences
                     WHERE tenant_id = ? AND user_id = ?
                ), TRUE)
                """.formatted(column), Boolean.class, tenantId, userId);
        return Boolean.TRUE.equals(value);
    }

    record NewMailTarget(long userId, OffsetDateTime occurredAt) { }

    record AssignmentTarget(UUID sharedInboxId, OffsetDateTime occurredAt) { }
}
