package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.*;

@Repository
class MailOrganizationCommandRepository {

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailOrganizationCommandRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    UUID createFolder(
            Long tenantId,
            Long userId,
            MailOrganizationDtos.FolderCreateRequest request,
            int sortOrder) {
        UUID folderId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_folders (
                    folder_id, tenant_id, account_id, parent_folder_id,
                    folder_key, display_name, folder_type, sort_order,
                    color_token, provider_sync_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'CUSTOM', ?, ?, 'LOCAL_ONLY', ?, ?)
                """, folderId, tenantId, request.accountId(), request.parentFolderId(),
                "user-" + folderId, request.displayName().trim(), sortOrder,
                request.color().name(), userId, userId);
        return folderId;
    }

    int updateFolder(
            Long tenantId,
            Long userId,
            UUID folderId,
            MailOrganizationDtos.FolderUpdateRequest request) {
        return jdbc.update("""
                UPDATE mail_folders
                   SET parent_folder_id = ?, display_name = ?, color_token = ?,
                       provider_sync_state = 'LOCAL_ONLY', provider_sync_error = NULL,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND folder_id = ? AND folder_type = 'CUSTOM'
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, request.parentFolderId(), request.displayName().trim(),
                request.color().name(), userId, tenantId, folderId, request.version());
    }

    int archiveFolder(
            Long tenantId, Long userId, UUID folderId, UUID accountId, long version) {
        jdbc.update("""
                UPDATE mail_threads thread
                   SET previous_folder_id = thread.folder_id,
                       folder_id = inbox.folder_id,
                       workflow_state = 'OPEN', snoozed_until = NULL,
                       trashed_at = NULL, spam_reported_at = NULL,
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_folders inbox
                 WHERE thread.tenant_id = ? AND thread.folder_id = ?
                   AND inbox.account_id = ? AND inbox.folder_type = 'INBOX'
                   AND inbox.lifecycle_state = 'ACTIVE'
                """, userId, tenantId, folderId, accountId);
        return jdbc.update("""
                UPDATE mail_folders
                   SET lifecycle_state = 'ARCHIVED',
                       provider_sync_state = 'LOCAL_ONLY',
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND folder_id = ? AND folder_type = 'CUSTOM'
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, userId, tenantId, folderId, version);
    }

    UUID createRule(
            Long tenantId,
            Long userId,
            MailOrganizationDtos.RuleCreateRequest request) {
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_rules (
                    rule_id, tenant_id, account_id, owner_user_id,
                    display_name, priority, match_mode, conditions, actions,
                    stop_processing, enabled, synchronization_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, 'LOCAL_ONLY', ?, ?)
                """, ruleId, tenantId, request.accountId(), userId,
                request.displayName().trim(), request.priority(), request.matchMode().name(),
                json.write(request.conditions()), json.write(request.actions()),
                request.stopProcessing(), request.enabled(), userId, userId);
        return ruleId;
    }

    int updateRule(
            Long tenantId,
            Long userId,
            UUID ruleId,
            MailOrganizationDtos.RuleUpdateRequest request) {
        return jdbc.update("""
                UPDATE mail_rules
                   SET display_name = ?, priority = ?, match_mode = ?,
                       conditions = ?::jsonb, actions = ?::jsonb,
                       stop_processing = ?, enabled = ?,
                       synchronization_state = 'LOCAL_ONLY', last_error_code = NULL,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND rule_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, request.displayName().trim(), request.priority(), request.matchMode().name(),
                json.write(request.conditions()), json.write(request.actions()),
                request.stopProcessing(), request.enabled(), userId,
                tenantId, userId, ruleId, request.version());
    }

    int archiveRule(Long tenantId, Long userId, UUID ruleId, long version) {
        return jdbc.update("""
                UPDATE mail_rules
                   SET lifecycle_state = 'ARCHIVED', enabled = FALSE,
                       synchronization_state = 'LOCAL_ONLY',
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND rule_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, userId, tenantId, userId, ruleId, version);
    }

    UUID startRuleRun(Long tenantId, Long userId, UUID ruleId) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_rule_runs (
                    run_id, tenant_id, rule_id, trigger_kind,
                    run_status, initiated_by)
                VALUES (?, ?, ?, 'MANUAL', 'RUNNING', ?)
                """, runId, tenantId, ruleId, userId);
        return runId;
    }

    void completeRuleRun(
            Long tenantId,
            Long userId,
            UUID ruleId,
            UUID runId,
            int scanned,
            int matched,
            int changed) {
        jdbc.update("""
                UPDATE mail_rule_runs
                   SET run_status = 'SUCCEEDED', scanned_count = ?,
                       matched_count = ?, changed_count = ?,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND rule_id = ? AND run_id = ?
                   AND run_status = 'RUNNING'
                """, scanned, matched, changed, tenantId, ruleId, runId);
        jdbc.update("""
                UPDATE mail_rules
                   SET last_run_at = CURRENT_TIMESTAMP, last_match_count = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND rule_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, matched, userId, tenantId, userId, ruleId);
    }

    RuleApplication applyRuleActions(
            Long tenantId,
            Long userId,
            UUID accountId,
            UUID threadId,
            long expectedVersion,
            List<MailOrganizationDtos.RuleAction> actions) {
        UUID targetFolderId = actions.stream()
                .filter(action -> action.type() == RuleActionType.MOVE_TO_FOLDER)
                .map(MailOrganizationDtos.RuleAction::folderId)
                .findFirst().orElse(null);
        boolean markRead = actions.stream().anyMatch(action -> action.type() == RuleActionType.MARK_READ);
        boolean star = actions.stream().anyMatch(action -> action.type() == RuleActionType.STAR);
        String importance = actions.stream()
                .filter(action -> action.type() == RuleActionType.SET_IMPORTANCE)
                .map(MailOrganizationDtos.RuleAction::importance)
                .filter(value -> value != null)
                .map(Enum::name)
                .findFirst().orElse(null);
        Integer outcome = jdbc.queryForObject("""
                WITH eligible AS MATERIALIZED (
                    SELECT thread.tenant_id, thread.account_id, thread.thread_id
                      FROM mail_threads thread
                     WHERE thread.tenant_id = ? AND thread.account_id = ?
                       AND thread.thread_id = ? AND thread.version = ?
                       AND (?::uuid IS NULL OR EXISTS (
                           SELECT 1 FROM mail_folders target
                            WHERE target.tenant_id = thread.tenant_id
                              AND target.account_id = thread.account_id
                              AND target.folder_id = ?::uuid
                              AND target.lifecycle_state = 'ACTIVE'
                              AND target.folder_type IN ('INBOX', 'ARCHIVE', 'CUSTOM')
                       ))
                       AND thread.workflow_state NOT IN ('DRAFT', 'TRASHED', 'SPAM')
                     FOR UPDATE
                ), changed AS (
                UPDATE mail_threads thread
                   SET previous_folder_id = CASE WHEN ?::uuid IS NULL
                           THEN thread.previous_folder_id ELSE thread.folder_id END,
                       folder_id = COALESCE((
                           SELECT folder.folder_id FROM mail_folders folder
                            WHERE folder.tenant_id = thread.tenant_id
                              AND folder.account_id = thread.account_id
                              AND folder.folder_id = ?::uuid
                              AND folder.lifecycle_state = 'ACTIVE'
                       ), thread.folder_id),
                       unread = CASE WHEN ? THEN FALSE ELSE thread.unread END,
                       starred = CASE WHEN ? THEN TRUE ELSE thread.starred END,
                       importance = COALESCE(?, thread.importance),
                       workflow_state = CASE WHEN ?::uuid IS NULL THEN thread.workflow_state
                                             ELSE 'OPEN' END,
                       snoozed_until = CASE WHEN ?::uuid IS NULL THEN thread.snoozed_until
                                            ELSE NULL END,
                       trashed_at = NULL, spam_reported_at = NULL,
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM eligible
                 WHERE thread.tenant_id = eligible.tenant_id
                   AND thread.account_id = eligible.account_id
                   AND thread.thread_id = eligible.thread_id
                   AND (
                       (?::uuid IS NOT NULL AND (
                           thread.folder_id IS DISTINCT FROM ?::uuid
                           OR thread.workflow_state IS DISTINCT FROM 'OPEN'
                           OR thread.snoozed_until IS NOT NULL
                       ))
                       OR (? AND thread.unread)
                       OR (? AND NOT thread.starred)
                       OR (?::varchar IS NOT NULL
                           AND thread.importance IS DISTINCT FROM ?::varchar)
                       OR thread.trashed_at IS NOT NULL
                       OR thread.spam_reported_at IS NOT NULL
                   )
                 RETURNING 1
                )
                SELECT CASE
                    WHEN EXISTS (SELECT 1 FROM changed) THEN 1
                    WHEN EXISTS (SELECT 1 FROM eligible) THEN 0
                    ELSE -1
                END
                """, Integer.class,
                tenantId, accountId, threadId, expectedVersion, targetFolderId, targetFolderId,
                targetFolderId, targetFolderId, markRead, star, importance,
                targetFolderId, targetFolderId, userId,
                targetFolderId, targetFolderId, markRead, star, importance, importance);
        int resolved = outcome == null ? -1 : outcome;
        return new RuleApplication(resolved >= 0, resolved == 1);
    }

    record RuleApplication(boolean eligible, boolean changed) {
    }
}
