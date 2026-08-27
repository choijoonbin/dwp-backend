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
                       (account.owner_user_id = ? OR EXISTS (
                           SELECT 1
                             FROM mail_shared_inbox_members membership
                            WHERE membership.tenant_id = thread.tenant_id
                              AND membership.shared_inbox_id = thread.shared_inbox_id
                              AND membership.user_id = ?
                              AND membership.member_role = 'MANAGER'
                              AND membership.lifecycle_state = 'ACTIVE'
                       )) AS permanent_delete_allowed
                  FROM mail_threads thread
                  JOIN mail_accounts account ON account.account_id = thread.account_id
                  JOIN mail_folders folder ON folder.folder_id = thread.folder_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND (account.owner_user_id = ? OR EXISTS (
                       SELECT 1
                         FROM mail_shared_inbox_members membership
                        WHERE membership.tenant_id = thread.tenant_id
                          AND membership.shared_inbox_id = thread.shared_inbox_id
                          AND membership.user_id = ?
                          AND membership.lifecycle_state = 'ACTIVE'
                   ))
                """, (result, ignored) -> new LifecycleThread(
                result.getObject("thread_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getObject("folder_id", UUID.class),
                result.getString("folder_type"),
                result.getObject("previous_folder_id", UUID.class),
                result.getString("workflow_state"),
                result.getLong("version"),
                result.getBoolean("permanent_delete_allowed")),
                userId, userId, tenantId, threadId, userId, userId)
                .stream().findFirst();
    }

    Optional<FolderTarget> target(
            Long tenantId, Long userId, UUID accountId, UUID folderId) {
        return jdbc.query("""
                SELECT folder.folder_id, folder.account_id, folder.folder_type
                  FROM mail_folders folder
                  JOIN mail_accounts account ON account.account_id = folder.account_id
                 WHERE folder.tenant_id = ? AND folder.account_id = ?
                   AND folder.folder_id = ? AND folder.lifecycle_state = 'ACTIVE'
                   AND (account.owner_user_id = ? OR EXISTS (
                       SELECT 1
                         FROM mail_shared_inboxes inbox
                         JOIN mail_shared_inbox_members membership
                           ON membership.tenant_id = inbox.tenant_id
                          AND membership.shared_inbox_id = inbox.shared_inbox_id
                        WHERE inbox.account_id = account.account_id
                          AND membership.user_id = ?
                          AND membership.lifecycle_state = 'ACTIVE'
                   ))
                """, (result, ignored) -> new FolderTarget(
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
                  JOIN mail_accounts account ON account.account_id = folder.account_id
                 WHERE folder.tenant_id = ? AND folder.account_id = ?
                   AND folder.folder_type = ? AND folder.lifecycle_state = 'ACTIVE'
                   AND (account.owner_user_id = ? OR EXISTS (
                       SELECT 1
                         FROM mail_shared_inboxes inbox
                         JOIN mail_shared_inbox_members membership
                           ON membership.tenant_id = inbox.tenant_id
                          AND membership.shared_inbox_id = inbox.shared_inbox_id
                        WHERE inbox.account_id = account.account_id
                          AND membership.user_id = ?
                          AND membership.lifecycle_state = 'ACTIVE'
                   ))
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
                  FROM mail_accounts account
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND thread.account_id = ? AND thread.version = ?
                   AND account.account_id = thread.account_id
                   AND (account.owner_user_id = ? OR EXISTS (
                       SELECT 1
                         FROM mail_shared_inbox_members membership
                        WHERE membership.tenant_id = thread.tenant_id
                          AND membership.shared_inbox_id = thread.shared_inbox_id
                          AND membership.user_id = ?
                          AND membership.lifecycle_state = 'ACTIVE'
                   ))
                """, previousFolderId, target.folderId(), workflowState,
                workflowState, workflowState, workflowState, userId,
                tenantId, before.threadId(), before.accountId(), version, userId, userId);
    }

    int deleteForever(Long tenantId, Long userId, LifecycleThread before, long version) {
        return jdbc.update("""
                DELETE FROM mail_threads thread
                 USING mail_accounts account, mail_folders folder
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND thread.account_id = account.account_id
                   AND thread.folder_id = folder.folder_id
                   AND folder.folder_type = 'TRASH'
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.version = ?
                   AND (account.owner_user_id = ? OR EXISTS (
                       SELECT 1
                         FROM mail_shared_inbox_members membership
                        WHERE membership.tenant_id = thread.tenant_id
                          AND membership.shared_inbox_id = thread.shared_inbox_id
                          AND membership.user_id = ?
                          AND membership.member_role = 'MANAGER'
                          AND membership.lifecycle_state = 'ACTIVE'
                   ))
                """, tenantId, before.threadId(), version, userId, userId);
    }
}
