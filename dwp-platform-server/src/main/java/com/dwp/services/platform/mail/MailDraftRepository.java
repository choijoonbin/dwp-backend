package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
class MailDraftRepository {

    record CreateResult(UUID threadId, boolean created) {
    }

    private final JdbcTemplate jdbc;

    MailDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    CreateResult create(Long tenantId, Long userId, MailDtos.DraftSaveRequest request) {
        String email = email(request.toEmail());
        String name = recipientName(request.toName(), email);
        String subject = value(request.subject());
        String body = value(request.body());
        String providerRef = createReference(request.idempotencyKey());
        UUID existing = threadByProviderReference(tenantId, userId, providerRef);
        if (existing != null) return new CreateResult(existing, false);

        List<UUID> threadIds = jdbc.query("""
                INSERT INTO mail_threads (
                    thread_id, tenant_id, account_id, folder_id, provider_thread_ref,
                    subject, preview, participants, latest_message_at,
                    unread, importance, triage_lane, workflow_state,
                    external_sender, classification, message_count,
                    created_by, updated_by)
                SELECT ?, account.tenant_id, account.account_id, folder.folder_id, ?,
                       ?, ?, CASE WHEN ? = '' THEN '[]'::jsonb
                           ELSE jsonb_build_array(jsonb_build_object(
                               'name', ?, 'email', LOWER(?))) END,
                       CURRENT_TIMESTAMP, FALSE, 'NORMAL', 'UPDATES', 'DRAFT',
                       CASE WHEN ? = '' THEN FALSE
                           ELSE SPLIT_PART(LOWER(?), '@', 2)
                               <> SPLIT_PART(account.email_address, '@', 2) END,
                       'INTERNAL', 1, ?, ?
                  FROM mail_accounts account
                  JOIN mail_folders folder
                    ON folder.tenant_id = account.tenant_id
                   AND folder.account_id = account.account_id
                   AND folder.folder_type = 'DRAFTS'
                   AND folder.lifecycle_state = 'ACTIVE'
                 WHERE account.tenant_id = ? AND account.owner_user_id = ?
                   AND account.account_kind = 'PERSONAL'
                   AND account.is_default = TRUE
                   AND account.connection_state = 'ACTIVE'
                ON CONFLICT (account_id, provider_thread_ref) DO NOTHING
                RETURNING thread_id
                """, (result, ignored) -> result.getObject("thread_id", UUID.class),
                UUID.randomUUID(), providerRef, subject, preview(body),
                email, name, email, email, email, userId, userId, tenantId, userId);
        if (threadIds.isEmpty()) {
            UUID concurrent = threadByProviderReference(tenantId, userId, providerRef);
            if (concurrent == null) return null;
            return new CreateResult(concurrent, false);
        }

        UUID threadId = threadIds.get(0);
        int inserted = jdbc.update("""
                INSERT INTO mail_messages (
                    message_id, tenant_id, thread_id, provider_message_ref,
                    sender_email, sender_name, recipients, message_direction,
                    body_format, body_content, attachments, sent_at, created_by)
                SELECT ?, thread.tenant_id, thread.thread_id, ?,
                       account.email_address, account.display_name,
                       CASE WHEN ? = '' THEN '[]'::jsonb
                           ELSE jsonb_build_array(jsonb_build_object(
                               'name', ?, 'email', LOWER(?), 'type', 'TO')) END,
                       'DRAFT', 'TEXT', ?, '[]'::jsonb, CURRENT_TIMESTAMP, ?
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND account.owner_user_id = ?
                ON CONFLICT (thread_id, provider_message_ref) DO NOTHING
                """, UUID.randomUUID(), providerRef + ":message",
                email, name, email, body, userId, tenantId, threadId, userId);
        if (inserted != 1) {
            throw new IllegalStateException("Draft message projection is missing.");
        }
        return new CreateResult(threadId, true);
    }

    int save(
            Long tenantId,
            Long userId,
            UUID threadId,
            MailDtos.DraftSaveRequest request) {
        String email = email(request.toEmail());
        String name = recipientName(request.toName(), email);
        String subject = value(request.subject());
        String body = value(request.body());
        int updated = jdbc.update("""
                UPDATE mail_threads thread
                   SET subject = ?, preview = ?,
                       participants = CASE WHEN ? = '' THEN '[]'::jsonb
                           ELSE jsonb_build_array(jsonb_build_object(
                               'name', ?, 'email', LOWER(?))) END,
                       latest_message_at = CURRENT_TIMESTAMP,
                       external_sender = CASE WHEN ? = '' THEN FALSE
                           ELSE SPLIT_PART(LOWER(?), '@', 2)
                               <> SPLIT_PART(account.email_address, '@', 2) END,
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts account, mail_folders folder
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                   AND account.account_kind = 'PERSONAL'
                   AND account.owner_user_id = ?
                   AND folder.tenant_id = thread.tenant_id
                   AND folder.account_id = thread.account_id
                   AND folder.folder_id = thread.folder_id
                   AND folder.folder_type = 'DRAFTS'
                   AND folder.lifecycle_state = 'ACTIVE'
                   AND thread.workflow_state = 'DRAFT'
                   AND thread.version = ?
                """, subject, preview(body), email, name, email,
                email, email, userId, tenantId, threadId, userId, request.version());
        if (updated == 0) return 0;

        int messageUpdated = jdbc.update("""
                UPDATE mail_messages
                   SET provider_message_ref = ?,
                       recipients = CASE WHEN ? = '' THEN '[]'::jsonb
                           ELSE jsonb_build_array(jsonb_build_object(
                               'name', ?, 'email', LOWER(?), 'type', 'TO')) END,
                       body_content = ?, sent_at = CURRENT_TIMESTAMP
                 WHERE message_id = (
                       SELECT message_id
                         FROM mail_messages
                        WHERE tenant_id = ? AND thread_id = ?
                          AND message_direction = 'DRAFT'
                        ORDER BY sent_at, message_id
                        LIMIT 1)
                """, saveReference(request.idempotencyKey()), email, name, email,
                body, tenantId, threadId);
        if (messageUpdated != 1) {
            throw new IllegalStateException("Draft message projection is missing.");
        }
        return updated;
    }

    private UUID threadByProviderReference(
            Long tenantId, Long userId, String providerReference) {
        List<UUID> threadIds = jdbc.query("""
                SELECT thread.thread_id
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.provider_thread_ref = ?
                   AND account.account_kind = 'PERSONAL'
                   AND account.owner_user_id = ?
                 ORDER BY thread.created_at DESC
                 LIMIT 1
                """, (result, ignored) -> result.getObject("thread_id", UUID.class),
                tenantId, providerReference, userId);
        return threadIds.isEmpty() ? null : threadIds.get(0);
    }

    private String createReference(UUID idempotencyKey) {
        return "dwp:draft:create:" + idempotencyKey;
    }

    private String saveReference(UUID idempotencyKey) {
        return "dwp:draft:save:" + idempotencyKey;
    }

    private String recipientName(String name, String email) {
        return name != null && !name.isBlank() ? name.trim() : email;
    }

    private String preview(String body) {
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1200 ? normalized : normalized.substring(0, 1197) + "...";
    }

    private String value(String input) {
        return input == null ? "" : input.trim();
    }

    private String email(String input) {
        return value(input).toLowerCase(Locale.ROOT);
    }
}
