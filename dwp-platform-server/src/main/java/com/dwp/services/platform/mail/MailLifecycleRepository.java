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
            boolean retentionElapsed,
            boolean activeLegalHold,
            boolean immutableDeliveryEvidence,
            boolean externalProvider) {

        LifecycleThread(
                UUID threadId,
                UUID accountId,
                UUID folderId,
                String folderType,
                UUID previousFolderId,
                String workflowState,
                long version,
                boolean retentionElapsed) {
            this(threadId, accountId, folderId, folderType, previousFolderId, workflowState,
                    version, retentionElapsed, false, false, false);
        }
    }

    record FolderTarget(UUID folderId, UUID accountId, String folderType, String displayName) {

        FolderTarget(UUID folderId, UUID accountId, String folderType) {
            this(folderId, accountId, folderType, folderType);
        }
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
                       COALESCE(
                           thread.trashed_at <= CURRENT_TIMESTAMP
                               - make_interval(days => COALESCE(policy.retention_days, 365)),
                           FALSE) AS retention_elapsed,
                       EXISTS (
                           SELECT 1 FROM mail_legal_holds hold
                            WHERE hold.tenant_id = thread.tenant_id
                              AND hold.hold_status = 'ACTIVE'
                              AND hold.starts_at <= CURRENT_TIMESTAMP
                              AND (hold.expires_at IS NULL OR hold.expires_at > CURRENT_TIMESTAMP)
                       ) AS active_legal_hold,
                       EXISTS (
                           SELECT 1 FROM mail_group_recipient_snapshots snapshot
                            WHERE snapshot.tenant_id = thread.tenant_id
                              AND snapshot.thread_id = thread.thread_id
                       ) OR EXISTS (
                           SELECT 1 FROM mail_group_send_history history
                            WHERE history.tenant_id = thread.tenant_id
                              AND history.thread_id = thread.thread_id
                       ) AS immutable_delivery_evidence,
                       COALESCE(connection.provider_type <> 'DWP_SANDBOX', TRUE)
                           AS external_provider
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_folders folder
                    ON folder.tenant_id = thread.tenant_id
                   AND folder.account_id = thread.account_id
                   AND folder.folder_id = thread.folder_id
                  LEFT JOIN mail_tenant_policies policy
                    ON policy.tenant_id = thread.tenant_id
                  LEFT JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
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
                result.getBoolean("retention_elapsed"),
                result.getBoolean("active_legal_hold"),
                result.getBoolean("immutable_delivery_evidence"),
                result.getBoolean("external_provider")),
                tenantId, threadId, userId, userId)
                .stream().findFirst();
    }

    Optional<FolderTarget> target(
            Long tenantId, Long userId, UUID accountId, UUID folderId) {
        return jdbc.query("""
                SELECT folder.folder_id, folder.account_id, folder.folder_type,
                       folder.display_name
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
                result.getString("folder_type"),
                result.getString("display_name")),
                tenantId, accountId, folderId, userId, userId)
                .stream().findFirst();
    }

    Optional<FolderTarget> systemTarget(
            Long tenantId, Long userId, UUID accountId, String folderType) {
        return jdbc.query("""
                SELECT folder.folder_id, folder.account_id, folder.folder_type,
                       folder.display_name
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
                result.getString("folder_type"),
                result.getString("display_name")),
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
                """ + MailAccessSql.THREAD_MANAGE_ACCESS + """
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
        return jdbc.update("""
                DELETE FROM mail_threads thread
                 USING mail_accounts account
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND thread.account_id = ? AND thread.version = ?
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.trashed_at <= CURRENT_TIMESTAMP - make_interval(days => COALESCE((
                       SELECT policy.retention_days FROM mail_tenant_policies policy
                        WHERE policy.tenant_id = thread.tenant_id
                   ), 365))
                   AND account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                """ + MailAccessSql.THREAD_MANAGE_ACCESS + """
                   AND NOT EXISTS (
                       SELECT 1 FROM mail_legal_holds hold
                        WHERE hold.tenant_id = thread.tenant_id
                          AND hold.hold_status = 'ACTIVE'
                          AND hold.starts_at <= CURRENT_TIMESTAMP
                          AND (hold.expires_at IS NULL OR hold.expires_at > CURRENT_TIMESTAMP)
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM mail_group_recipient_snapshots snapshot
                        WHERE snapshot.tenant_id = thread.tenant_id
                          AND snapshot.thread_id = thread.thread_id
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM mail_group_send_history history
                        WHERE history.tenant_id = thread.tenant_id
                          AND history.thread_id = thread.thread_id
                   )
                   AND EXISTS (
                       SELECT 1 FROM mail_provider_connections connection
                        WHERE connection.tenant_id = account.tenant_id
                          AND connection.connection_id = account.connection_id
                          AND connection.provider_type = 'DWP_SANDBOX'
                   )
                """, tenantId, before.threadId(), before.accountId(), version,
                userId, userId);
    }
}
