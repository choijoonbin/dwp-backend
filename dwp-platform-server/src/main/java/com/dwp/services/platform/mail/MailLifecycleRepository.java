package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class MailLifecycleRepository {

    record LifecycleThread(
            UUID threadId,
            UUID accountId,
            UUID folderId,
            String folderType,
            UUID previousFolderId,
            String workflowState,
            long version,
            boolean permanentDeleteAllowed) {
    }

    record FolderTarget(UUID folderId, UUID accountId, String folderType) {
    }

    private final JdbcTemplate jdbc;

    MailLifecycleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<LifecycleThread> visibleThread(Long tenantId, Long userId, UUID threadId) {
        return jdbc.query("""
                SELECT thread.thread_id, thread.account_id, thread.folder_id,
                       folder.folder_type, thread.previous_folder_id,
                       thread.workflow_state, thread.version,
                       FALSE AS permanent_delete_allowed
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_folders folder
                    ON folder.tenant_id = thread.tenant_id
                   AND folder.account_id = thread.account_id
                   AND folder.folder_id = thread.folder_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """ + MailAccessSql.THREAD_ACCESS,
                (result, ignored) -> new LifecycleThread(
                result.getObject("thread_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getObject("folder_id", UUID.class),
                result.getString("folder_type"),
                result.getObject("previous_folder_id", UUID.class),
                result.getString("workflow_state"),
                result.getLong("version"),
                result.getBoolean("permanent_delete_allowed")),
                tenantId, threadId, userId, userId)
                .stream().findFirst();
    }

    Optional<FolderTarget> target(
            Long tenantId, Long userId, UUID accountId, UUID folderId) {
        return jdbc.query("""
                SELECT folder.folder_id, folder.account_id, folder.folder_type
                  FROM mail_folders folder
                  JOIN mail_accounts account
                    ON account.tenant_id = folder.tenant_id
                   AND account.account_id = folder.account_id
                 WHERE folder.tenant_id = ? AND folder.account_id = ?
                   AND folder.folder_id = ? AND folder.lifecycle_state = 'ACTIVE'
                """ + MailAccessSql.ACCOUNT_ACCESS,
                (result, ignored) -> new FolderTarget(
                result.getObject("folder_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getString("folder_type")),
                tenantId, accountId, folderId, userId, userId)
                .stream().findFirst();
    }

    Optional<FolderTarget> systemTarget(
            Long tenantId, Long userId, UUID accountId, String folderType) {
        return jdbc.query("""
                SELECT folder.folder_id, folder.account_id, folder.folder_type
                  FROM mail_folders folder
                  JOIN mail_accounts account
                    ON account.tenant_id = folder.tenant_id
                   AND account.account_id = folder.account_id
                 WHERE folder.tenant_id = ? AND folder.account_id = ?
                   AND folder.folder_type = ? AND folder.lifecycle_state = 'ACTIVE'
                """ + MailAccessSql.ACCOUNT_ACCESS + """
                 ORDER BY folder.sort_order, folder.folder_id
                 LIMIT 1
                """, (result, ignored) -> new FolderTarget(
                result.getObject("folder_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getString("folder_type")),
                tenantId, accountId, folderType, userId, userId)
                .stream().findFirst();
    }

    int move(
            Long tenantId,
            Long userId,
            LifecycleThread before,
            FolderTarget target,
            String workflowState,
            UUID previousFolderId,
            long version) {
        return jdbc.update("""
                UPDATE mail_threads thread
                   SET previous_folder_id = ?, folder_id = ?, workflow_state = ?,
                       snoozed_until = NULL,
                       trashed_at = CASE WHEN ? = 'TRASHED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                       spam_reported_at = CASE WHEN ? = 'SPAM' THEN CURRENT_TIMESTAMP ELSE NULL END,
                       unread = CASE WHEN ? IN ('TRASHED', 'SPAM') THEN FALSE ELSE thread.unread END,
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts account, mail_folders target_folder
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND thread.account_id = ? AND thread.version = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                   AND target_folder.tenant_id = ?
                   AND target_folder.account_id = ?
                   AND target_folder.folder_id = ?
                   AND target_folder.lifecycle_state = 'ACTIVE'
                """, previousFolderId, target.folderId(), workflowState,
                workflowState, workflowState, workflowState, userId,
                tenantId, before.threadId(), before.accountId(), version,
                userId, userId, tenantId, before.accountId(), target.folderId());
    }

    int deleteForever(Long tenantId, Long userId, LifecycleThread before, long version) {
        throw new IllegalStateException(
                "Permanent mail deletion is disabled until retention and legal-hold policy is governed.");
    }
}
