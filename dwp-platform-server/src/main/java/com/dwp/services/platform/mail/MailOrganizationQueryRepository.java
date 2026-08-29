package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.*;
import static com.dwp.services.platform.mail.MailTypes.Importance;

@Repository
class MailOrganizationQueryRepository {

    record RuleCandidate(
            UUID threadId,
            long version,
            String sender,
            String recipient,
            String subject,
            String body,
            boolean attachments,
            Importance importance) {
    }

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailOrganizationQueryRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean ownsAccount(Long tenantId, Long userId, UUID accountId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_accounts
                 WHERE tenant_id = ? AND owner_user_id = ? AND account_id = ?
                   AND account_kind = 'PERSONAL' AND connection_state = 'ACTIVE'
                """, Integer.class, tenantId, userId, accountId);
        return count != null && count == 1;
    }

    boolean hasActiveChildren(Long tenantId, UUID folderId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mail_folders
                 WHERE tenant_id = ? AND parent_folder_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, tenantId, folderId);
        return count != null && count > 0;
    }

    boolean isReferencedByActiveRule(Long tenantId, UUID folderId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mail_rules rule
                 WHERE rule.tenant_id = ? AND rule.lifecycle_state = 'ACTIVE'
                   AND EXISTS (
                       SELECT 1 FROM jsonb_array_elements(rule.actions) action
                        WHERE action ->> 'type' = 'MOVE_TO_FOLDER'
                          AND action ->> 'folderId' = ?
                   )
                """, Integer.class, tenantId, folderId.toString());
        return count != null && count > 0;
    }

    List<MailOrganizationDtos.FolderSummary> folders(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT folder.folder_id, folder.account_id, folder.parent_folder_id,
                       folder.folder_key, folder.display_name, folder.folder_type,
                       folder.color_token, folder.provider_sync_state, folder.sort_order,
                       COUNT(thread.thread_id)::INTEGER AS total_count,
                       COUNT(thread.thread_id) FILTER (WHERE thread.unread)::INTEGER AS unread_count,
                       folder.version
                  FROM mail_folders folder
                  JOIN mail_accounts account
                    ON account.tenant_id = folder.tenant_id
                   AND account.account_id = folder.account_id
                  LEFT JOIN mail_thread_folders membership
                    ON membership.tenant_id = folder.tenant_id
                   AND membership.account_id = folder.account_id
                   AND membership.folder_id = folder.folder_id
                  LEFT JOIN mail_threads thread
                    ON thread.tenant_id = membership.tenant_id
                   AND thread.account_id = membership.account_id
                   AND thread.thread_id = membership.thread_id
                 WHERE folder.tenant_id = ? AND account.owner_user_id = ?
                   AND folder.lifecycle_state = 'ACTIVE'
                 GROUP BY folder.folder_id, account.is_default
                 ORDER BY account.is_default DESC, folder.sort_order, LOWER(folder.display_name)
                """, (result, ignored) -> folder(result), tenantId, userId);
    }

    Optional<MailOrganizationDtos.FolderSummary> folder(
            Long tenantId, Long userId, UUID folderId) {
        return jdbc.query("""
                SELECT folder.folder_id, folder.account_id, folder.parent_folder_id,
                       folder.folder_key, folder.display_name, folder.folder_type,
                       folder.color_token, folder.provider_sync_state, folder.sort_order,
                       COUNT(thread.thread_id)::INTEGER AS total_count,
                       COUNT(thread.thread_id) FILTER (WHERE thread.unread)::INTEGER AS unread_count,
                       folder.version
                  FROM mail_folders folder
                  JOIN mail_accounts account
                    ON account.tenant_id = folder.tenant_id
                   AND account.account_id = folder.account_id
                  LEFT JOIN mail_thread_folders membership
                    ON membership.tenant_id = folder.tenant_id
                   AND membership.account_id = folder.account_id
                   AND membership.folder_id = folder.folder_id
                  LEFT JOIN mail_threads thread
                    ON thread.tenant_id = membership.tenant_id
                   AND thread.account_id = membership.account_id
                   AND thread.thread_id = membership.thread_id
                 WHERE folder.tenant_id = ? AND account.owner_user_id = ?
                   AND folder.folder_id = ? AND folder.lifecycle_state = 'ACTIVE'
                 GROUP BY folder.folder_id
                """, (result, ignored) -> folder(result), tenantId, userId, folderId)
                .stream().findFirst();
    }

    List<MailOrganizationDtos.RuleSummary> rules(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT rule.rule_id, rule.account_id, rule.display_name, rule.priority,
                       rule.match_mode, rule.conditions::text, rule.actions::text,
                       rule.stop_processing, rule.enabled, rule.synchronization_state,
                       rule.last_run_at, rule.last_match_count, rule.version
                  FROM mail_rules rule
                  JOIN mail_accounts account
                    ON account.tenant_id = rule.tenant_id
                   AND account.account_id = rule.account_id
                 WHERE rule.tenant_id = ? AND rule.owner_user_id = ?
                   AND account.owner_user_id = ? AND rule.lifecycle_state = 'ACTIVE'
                 ORDER BY rule.priority, LOWER(rule.display_name), rule.rule_id
                """, (result, ignored) -> rule(result), tenantId, userId, userId);
    }

    Optional<MailOrganizationDtos.RuleSummary> rule(
            Long tenantId, Long userId, UUID ruleId) {
        return jdbc.query("""
                SELECT rule.rule_id, rule.account_id, rule.display_name, rule.priority,
                       rule.match_mode, rule.conditions::text, rule.actions::text,
                       rule.stop_processing, rule.enabled, rule.synchronization_state,
                       rule.last_run_at, rule.last_match_count, rule.version
                  FROM mail_rules rule
                  JOIN mail_accounts account
                    ON account.tenant_id = rule.tenant_id
                   AND account.account_id = rule.account_id
                 WHERE rule.tenant_id = ? AND rule.owner_user_id = ?
                   AND account.owner_user_id = ? AND rule.rule_id = ?
                   AND rule.lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> rule(result), tenantId, userId, userId, ruleId)
                .stream().findFirst();
    }

    List<MailOrganizationDtos.RuleRunSummary> recentRuns(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT run.run_id, run.rule_id, run.trigger_kind, run.run_status,
                       run.scanned_count, run.matched_count, run.changed_count,
                       run.started_at, run.completed_at
                  FROM mail_rule_runs run
                  JOIN mail_rules rule
                    ON rule.tenant_id = run.tenant_id
                   AND rule.rule_id = run.rule_id
                 WHERE run.tenant_id = ? AND rule.owner_user_id = ?
                 ORDER BY run.started_at DESC
                 LIMIT 20
                """, (result, ignored) -> new MailOrganizationDtos.RuleRunSummary(
                result.getObject("run_id", UUID.class),
                result.getObject("rule_id", UUID.class),
                result.getString("trigger_kind"),
                result.getString("run_status"),
                result.getInt("scanned_count"),
                result.getInt("matched_count"),
                result.getInt("changed_count"),
                result.getObject("started_at", OffsetDateTime.class),
                result.getObject("completed_at", OffsetDateTime.class)), tenantId, userId);
    }

    List<RuleCandidate> candidates(Long tenantId, Long userId, UUID accountId) {
        return jdbc.query("""
                SELECT thread.thread_id, thread.version,
                       COALESCE(latest.sender_email, '') AS sender,
                       account.email_address AS recipient,
                       thread.subject,
                       COALESCE(latest.body_content, thread.preview) AS body,
                       thread.has_attachments,
                       thread.importance
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  LEFT JOIN LATERAL (
                      SELECT message.sender_email, message.body_content
                        FROM mail_messages message
                       WHERE message.tenant_id = thread.tenant_id
                         AND message.thread_id = thread.thread_id
                         AND message.message_direction = 'INBOUND'
                       ORDER BY message.sent_at DESC, message.message_id DESC
                       LIMIT 1
                  ) latest ON TRUE
                 WHERE thread.tenant_id = ? AND account.owner_user_id = ?
                   AND thread.account_id = ?
                   AND thread.workflow_state NOT IN ('DRAFT', 'TRASHED', 'SPAM')
                 ORDER BY thread.latest_message_at DESC, thread.thread_id
                 LIMIT 501
                """, (result, ignored) -> new RuleCandidate(
                result.getObject("thread_id", UUID.class),
                result.getLong("version"),
                result.getString("sender"),
                result.getString("recipient"),
                result.getString("subject"),
                result.getString("body"),
                result.getBoolean("has_attachments"),
                Importance.valueOf(result.getString("importance"))), tenantId, userId, accountId);
    }

    private MailOrganizationDtos.FolderSummary folder(ResultSet result) throws SQLException {
        return new MailOrganizationDtos.FolderSummary(
                result.getObject("folder_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getObject("parent_folder_id", UUID.class),
                result.getString("folder_key"),
                result.getString("display_name"),
                result.getString("folder_type"),
                FolderColor.valueOf(result.getString("color_token")),
                ProviderSyncState.valueOf(result.getString("provider_sync_state")),
                result.getInt("sort_order"),
                result.getInt("total_count"),
                result.getInt("unread_count"),
                result.getLong("version"));
    }

    private MailOrganizationDtos.RuleSummary rule(ResultSet result) throws SQLException {
        return new MailOrganizationDtos.RuleSummary(
                result.getObject("rule_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getString("display_name"),
                result.getInt("priority"),
                RuleMatchMode.valueOf(result.getString("match_mode")),
                conditions(result.getString("conditions")),
                actions(result.getString("actions")),
                result.getBoolean("stop_processing"),
                result.getBoolean("enabled"),
                ProviderSyncState.valueOf(result.getString("synchronization_state")),
                result.getObject("last_run_at", OffsetDateTime.class),
                result.getInt("last_match_count"),
                result.getLong("version"));
    }

    private List<MailOrganizationDtos.RuleCondition> conditions(String value) {
        return json.mapList(value).stream().map(item -> new MailOrganizationDtos.RuleCondition(
                RuleField.valueOf(text(item, "field")),
                RuleOperator.valueOf(text(item, "operator")),
                text(item, "value"))).toList();
    }

    private List<MailOrganizationDtos.RuleAction> actions(String value) {
        return json.mapList(value).stream().map(item -> new MailOrganizationDtos.RuleAction(
                RuleActionType.valueOf(text(item, "type")),
                uuid(item.get("folderId")),
                importance(item.get("importance")))).toList();
    }

    private String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item == null ? "" : item.toString();
    }

    private UUID uuid(Object value) {
        return value == null || value.toString().isBlank() ? null : UUID.fromString(value.toString());
    }

    private Importance importance(Object value) {
        return value == null || value.toString().isBlank()
                ? null : Importance.valueOf(value.toString());
    }
}
