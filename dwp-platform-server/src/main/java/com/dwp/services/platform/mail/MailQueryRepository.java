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

import static com.dwp.services.platform.mail.MailTypes.*;

@Repository
class MailQueryRepository {

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailQueryRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<MailDtos.AccountSummary> accounts(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT account.account_id, account.email_address, account.display_name,
                       account.account_kind, connection.provider_type,
                       account.connection_state, account.synchronization_state,
                       account.is_default
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE account.tenant_id = ?
                """ + MailAccessSql.ACCOUNT_ACCESS + """
                 ORDER BY account.is_default DESC, account.account_kind, account.display_name
                """, (result, ignored) -> new MailDtos.AccountSummary(
                result.getObject("account_id", UUID.class),
                result.getString("email_address"),
                result.getString("display_name"),
                result.getString("account_kind"),
                ProviderType.valueOf(result.getString("provider_type")),
                result.getString("connection_state"),
                result.getString("synchronization_state"),
                result.getBoolean("is_default")), tenantId, userId, userId);
    }

    List<MailDtos.ThreadSummary> threads(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            boolean sharedOnly,
            String search,
            int page,
            int pageSize) {
        return threads(
                tenantId, userId, lane, state, folder, null,
                sharedOnly, search, page, pageSize);
    }

    List<MailDtos.ThreadSummary> threads(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            UUID folderId,
            boolean sharedOnly,
            String search,
            int page,
            int pageSize) {
        return jdbc.query(threadSelect() + MailAccessSql.THREAD_ACCESS + """
                   AND (? = '' OR thread.triage_lane = ?)
                """ + MailAccessSql.WORKFLOW_FILTER + """
                   AND (? = '' OR EXISTS (
                       SELECT 1
                         FROM mail_thread_folders membership
                         JOIN mail_folders member_folder
                           ON member_folder.tenant_id = membership.tenant_id
                          AND member_folder.account_id = thread.account_id
                          AND member_folder.folder_id = membership.folder_id
                        WHERE membership.tenant_id = thread.tenant_id
                          AND membership.thread_id = thread.thread_id
                          AND member_folder.folder_type = ?
                   ))
                   AND (?::uuid IS NULL OR EXISTS (
                       SELECT 1
                         FROM mail_thread_folders selected_membership
                         JOIN mail_folders selected_folder
                           ON selected_folder.tenant_id = selected_membership.tenant_id
                          AND selected_folder.account_id = thread.account_id
                          AND selected_folder.folder_id = selected_membership.folder_id
                        WHERE selected_membership.tenant_id = thread.tenant_id
                          AND selected_membership.thread_id = thread.thread_id
                          AND selected_membership.folder_id = ?::uuid
                   ))
                   AND (? = FALSE OR thread.shared_inbox_id IS NOT NULL)
                   AND (? = '' OR LOWER(thread.subject) LIKE ? OR LOWER(thread.preview) LIKE ?
                        OR LOWER(thread.participants::text) LIKE ?)
                 ORDER BY
                       CASE thread.importance
                           WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1
                           WHEN 'NORMAL' THEN 2 ELSE 3 END,
                       thread.unread DESC, thread.latest_message_at DESC, thread.thread_id
                 LIMIT ? OFFSET ?
                """, (result, ignored) -> thread(result),
                tenantId, userId, userId,
                lane, lane,
                state, state, state,
                folder, folder,
                folderId, folderId,
                sharedOnly,
                search, pattern(search), pattern(search), pattern(search),
                pageSize, page * pageSize);
    }

    long threadCount(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            boolean sharedOnly,
            String search) {
        return threadCount(
                tenantId, userId, lane, state, folder, null, sharedOnly, search);
    }

    long threadCount(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            UUID folderId,
            boolean sharedOnly,
            String search) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_folders folder
                    ON folder.tenant_id = thread.tenant_id
                   AND folder.account_id = thread.account_id
                   AND folder.folder_id = thread.folder_id
                 WHERE thread.tenant_id = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                   AND (? = '' OR thread.triage_lane = ?)
                """ + MailAccessSql.WORKFLOW_FILTER + """
                   AND (? = '' OR EXISTS (
                       SELECT 1
                         FROM mail_thread_folders membership
                         JOIN mail_folders member_folder
                           ON member_folder.tenant_id = membership.tenant_id
                          AND member_folder.account_id = thread.account_id
                          AND member_folder.folder_id = membership.folder_id
                        WHERE membership.tenant_id = thread.tenant_id
                          AND membership.thread_id = thread.thread_id
                          AND member_folder.folder_type = ?
                   ))
                   AND (?::uuid IS NULL OR EXISTS (
                       SELECT 1
                         FROM mail_thread_folders selected_membership
                         JOIN mail_folders selected_folder
                           ON selected_folder.tenant_id = selected_membership.tenant_id
                          AND selected_folder.account_id = thread.account_id
                          AND selected_folder.folder_id = selected_membership.folder_id
                        WHERE selected_membership.tenant_id = thread.tenant_id
                          AND selected_membership.thread_id = thread.thread_id
                          AND selected_membership.folder_id = ?::uuid
                   ))
                   AND (? = FALSE OR thread.shared_inbox_id IS NOT NULL)
                   AND (? = '' OR LOWER(thread.subject) LIKE ? OR LOWER(thread.preview) LIKE ?
                        OR LOWER(thread.participants::text) LIKE ?)
                """, Long.class,
                tenantId, userId, userId,
                lane, lane,
                state, state, state,
                folder, folder,
                folderId, folderId,
                sharedOnly,
                search, pattern(search), pattern(search), pattern(search));
        return value == null ? 0 : value;
    }

    Optional<MailDtos.ThreadSummary> thread(Long tenantId, Long userId, UUID threadId) {
        return jdbc.query(threadSelect() + MailAccessSql.THREAD_ACCESS + """
                   AND thread.thread_id = ?
                """, (result, ignored) -> thread(result), tenantId, userId, userId, threadId)
                .stream().findFirst();
    }

    List<MailDtos.Message> messages(Long tenantId, Long userId, UUID threadId) {
        return jdbc.query("""
                SELECT message.message_id, message.sender_email, message.sender_name,
                       message.recipients::text, message.message_direction,
                       message.body_format, message.body_content,
                       message.attachments::text, message.sent_at,
                       CASE
                           WHEN message.message_direction = 'INBOUND' THEN 'RECEIVED'
                           WHEN message.message_direction = 'DRAFT' THEN 'DRAFT'
                           WHEN delivery.delivery_status = 'QUEUED' THEN 'QUEUED'
                           WHEN delivery.delivery_status = 'LEASED' THEN 'SENDING'
                           WHEN delivery.delivery_status = 'RETRY_WAIT' THEN 'RETRYING'
                           WHEN delivery.delivery_status = 'FAILED' THEN 'FAILED'
                           ELSE 'SENT'
                       END AS delivery_state,
                       delivery.accepted_at, delivery.last_error_code
                  FROM mail_messages message
                  JOIN mail_threads thread
                    ON thread.tenant_id = message.tenant_id
                   AND thread.thread_id = message.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  LEFT JOIN mail_delivery_outbox delivery
                    ON delivery.message_id = message.message_id
                   AND delivery.tenant_id = message.tenant_id
                 WHERE message.tenant_id = ? AND message.thread_id = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                 ORDER BY message.sent_at, message.message_id
                """, (result, ignored) -> new MailDtos.Message(
                result.getObject("message_id", UUID.class),
                result.getString("sender_email"),
                result.getString("sender_name"),
                json.mapList(result.getString("recipients")),
                result.getString("message_direction"),
                result.getString("body_format"),
                result.getString("body_content"),
                json.mapList(result.getString("attachments")),
                result.getObject("sent_at", OffsetDateTime.class),
                DeliveryState.valueOf(result.getString("delivery_state")),
                result.getObject("accepted_at", OffsetDateTime.class),
                result.getString("last_error_code")), tenantId, threadId, userId, userId);
    }

    List<MailDtos.InternalComment> comments(Long tenantId, Long userId, UUID threadId) {
        return jdbc.query("""
                SELECT comment.comment_id, comment.author_user_id, comment.author_name,
                       comment.body, comment.mentioned_user_ids::text, comment.created_at
                  FROM mail_internal_comments comment
                  JOIN mail_threads thread
                    ON thread.tenant_id = comment.tenant_id
                   AND thread.thread_id = comment.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE comment.tenant_id = ? AND comment.thread_id = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                 ORDER BY comment.created_at, comment.comment_id
                """, (result, ignored) -> new MailDtos.InternalComment(
                result.getObject("comment_id", UUID.class),
                result.getLong("author_user_id"),
                result.getString("author_name"),
                result.getString("body"),
                json.longList(result.getString("mentioned_user_ids")),
                result.getObject("created_at", OffsetDateTime.class)),
                tenantId, threadId, userId, userId);
    }

    boolean isActiveSharedInboxMember(Long tenantId, UUID sharedInboxId, Long userId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_tenant_policies policy
                  JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = policy.tenant_id
                   AND inbox.shared_inbox_id = ?
                   AND inbox.lifecycle_state = 'ACTIVE'
                  JOIN mail_shared_inbox_members membership
                    ON membership.tenant_id = inbox.tenant_id
                   AND membership.account_id = inbox.account_id
                   AND membership.shared_inbox_id = inbox.shared_inbox_id
                   AND membership.user_id = ?
                   AND membership.lifecycle_state = 'ACTIVE'
                 WHERE policy.tenant_id = ? AND policy.allow_shared_inboxes = TRUE
                """, Long.class, sharedInboxId, userId, tenantId);
        return count != null && count > 0;
    }

    List<MailDtos.SharedInboxMember> sharedInboxMembers(
            Long tenantId, UUID sharedInboxId) {
        return jdbc.query("""
                SELECT membership.user_id, account.display_name,
                       account.email_address, membership.member_role
                  FROM mail_shared_inbox_members membership
                  JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = membership.tenant_id
                   AND inbox.account_id = membership.account_id
                   AND inbox.shared_inbox_id = membership.shared_inbox_id
                   AND inbox.lifecycle_state = 'ACTIVE'
                  JOIN mail_tenant_policies policy
                    ON policy.tenant_id = inbox.tenant_id
                   AND policy.allow_shared_inboxes = TRUE
                  JOIN mail_accounts account
                    ON account.tenant_id = membership.tenant_id
                   AND account.owner_user_id = membership.user_id
                   AND account.account_kind = 'PERSONAL'
                   AND account.is_default = TRUE
                 WHERE membership.tenant_id = ?
                   AND membership.shared_inbox_id = ?
                   AND membership.lifecycle_state = 'ACTIVE'
                   AND account.connection_state = 'ACTIVE'
                 ORDER BY CASE membership.member_role WHEN 'MANAGER' THEN 0 ELSE 1 END,
                          account.display_name, membership.user_id
                """, (result, ignored) -> new MailDtos.SharedInboxMember(
                result.getLong("user_id"), result.getString("display_name"),
                result.getString("email_address"), result.getString("member_role")),
                tenantId, sharedInboxId);
    }

    List<MailDtos.ActionProposal> proposals(
            Long tenantId, Long userId, UUID threadId, int limit) {
        return jdbc.query("""
                SELECT proposal.proposal_id, proposal.thread_id, proposal.proposal_type,
                       proposal.action_contract_version,
                       proposal.proposal_status, proposal.title, proposal.summary,
                       proposal.evidence::text, proposal.proposed_payload::text,
                       proposal.confidence, proposal.risk_level,
                       proposal.required_resource_key, proposal.required_permission_code,
                       proposal.target_route, proposal.expires_at, proposal.version
                  FROM mail_action_proposals proposal
                  JOIN mail_threads thread
                    ON thread.tenant_id = proposal.tenant_id
                   AND thread.thread_id = proposal.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE proposal.tenant_id = ?
                   AND (CAST(? AS UUID) IS NULL OR proposal.thread_id = ?)
                   AND proposal.proposal_status = 'PROPOSED'
                   AND (proposal.expires_at IS NULL OR proposal.expires_at > CURRENT_TIMESTAMP)
                """ + MailAccessSql.THREAD_ACCESS + """
                 ORDER BY
                       CASE proposal.risk_level WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
                       proposal.confidence DESC, proposal.created_at DESC
                 LIMIT ?
                """, (result, ignored) -> proposal(result),
                tenantId, threadId, threadId, userId, userId, limit);
    }

    Optional<MailDtos.ActionProposal> proposal(
            Long tenantId, Long userId, UUID proposalId) {
        return jdbc.query("""
                SELECT proposal.proposal_id, proposal.thread_id, proposal.proposal_type,
                       proposal.action_contract_version,
                       proposal.proposal_status, proposal.title, proposal.summary,
                       proposal.evidence::text, proposal.proposed_payload::text,
                       proposal.confidence, proposal.risk_level,
                       proposal.required_resource_key, proposal.required_permission_code,
                       proposal.target_route, proposal.expires_at, proposal.version
                  FROM mail_action_proposals proposal
                  JOIN mail_threads thread
                    ON thread.tenant_id = proposal.tenant_id
                   AND thread.thread_id = proposal.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE proposal.tenant_id = ? AND proposal.proposal_id = ?
                """ + MailAccessSql.THREAD_ACCESS,
                (result, ignored) -> proposal(result), tenantId, proposalId, userId, userId)
                .stream().findFirst();
    }

    MailDtos.HomeMetrics metrics(Long tenantId, Long userId) {
        return jdbc.query("""
                WITH visible_threads AS (
                    SELECT thread.*
                      FROM mail_threads thread
                      JOIN mail_accounts account
                        ON account.tenant_id = thread.tenant_id
                       AND account.account_id = thread.account_id
                     WHERE thread.tenant_id = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                ), visible_proposals AS (
                    SELECT proposal.proposal_id
                      FROM mail_action_proposals proposal
                      JOIN visible_threads thread
                        ON thread.tenant_id = proposal.tenant_id
                       AND thread.thread_id = proposal.thread_id
                     WHERE proposal.tenant_id = ?
                       AND proposal.proposal_status = 'PROPOSED'
                       AND (proposal.expires_at IS NULL OR proposal.expires_at > CURRENT_TIMESTAMP)
                )
                SELECT COUNT(*) FILTER (
                           WHERE thread.unread
                             AND (%s) NOT IN ('ARCHIVED', 'TRASHED', 'SPAM', 'SNOOZED')) AS unread,
                       COUNT(*) FILTER (
                           WHERE thread.importance = 'URGENT'
                             AND (%s) = 'OPEN') AS urgent,
                       COUNT(*) FILTER (
                           WHERE thread.triage_lane = 'NEEDS_REPLY'
                             AND (%s) = 'OPEN') AS needs_reply,
                       COUNT(*) FILTER (
                           WHERE thread.assigned_user_id = ?
                             AND (%s) = 'OPEN') AS assigned,
                       COUNT(*) FILTER (
                           WHERE thread.workflow_state = 'SNOOZED'
                             AND thread.snoozed_until > CURRENT_TIMESTAMP) AS snoozed,
                       (SELECT COUNT(*) FROM visible_proposals) AS active_proposals
                  FROM visible_threads thread
                """.formatted(
                        MailAccessSql.EFFECTIVE_WORKFLOW_STATE,
                        MailAccessSql.EFFECTIVE_WORKFLOW_STATE,
                        MailAccessSql.EFFECTIVE_WORKFLOW_STATE,
                        MailAccessSql.EFFECTIVE_WORKFLOW_STATE),
                result -> {
                    if (!result.next()) return new MailDtos.HomeMetrics(0, 0, 0, 0, 0, 0);
                    return new MailDtos.HomeMetrics(
                            result.getInt("unread"), result.getInt("urgent"),
                            result.getInt("needs_reply"), result.getInt("assigned"),
                            result.getInt("snoozed"), result.getInt("active_proposals"));
                }, tenantId, userId, userId, tenantId, userId);
    }

    List<MailDtos.SharedInboxPulse> sharedInboxPulse(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT inbox.shared_inbox_id, inbox.display_name,
                       account.email_address, inbox.service_target_minutes,
                       COUNT(thread.thread_id) FILTER (
                           WHERE thread.workflow_state = 'OPEN') AS open_count,
                       COUNT(thread.thread_id) FILTER (
                           WHERE thread.workflow_state = 'OPEN'
                             AND thread.assigned_user_id IS NULL) AS unassigned_count,
                       COUNT(thread.thread_id) FILTER (
                           WHERE thread.workflow_state = 'OPEN'
                             AND thread.latest_message_at
                                 < CURRENT_TIMESTAMP
                                   - inbox.service_target_minutes * INTERVAL '1 minute') AS overdue_count
                  FROM mail_shared_inboxes inbox
                  JOIN mail_accounts account
                    ON account.tenant_id = inbox.tenant_id
                   AND account.account_id = inbox.account_id
                  LEFT JOIN mail_threads thread
                    ON thread.shared_inbox_id = inbox.shared_inbox_id
                   AND thread.tenant_id = inbox.tenant_id
                   AND thread.account_id = inbox.account_id
                 WHERE inbox.tenant_id = ? AND inbox.lifecycle_state = 'ACTIVE'
                """ + MailAccessSql.ACCOUNT_ACCESS + """
                 GROUP BY inbox.shared_inbox_id, inbox.display_name,
                          account.email_address, inbox.service_target_minutes
                 ORDER BY overdue_count DESC, open_count DESC, inbox.display_name
                """, (result, ignored) -> new MailDtos.SharedInboxPulse(
                result.getObject("shared_inbox_id", UUID.class),
                result.getString("display_name"),
                result.getString("email_address"),
                result.getInt("open_count"),
                result.getInt("unassigned_count"),
                result.getInt("overdue_count"),
                result.getInt("service_target_minutes")), tenantId, userId, userId);
    }

    MailDtos.TenantPolicy policy(Long tenantId) {
        return jdbc.query("""
                SELECT external_sender_banner, block_remote_images,
                       allow_shared_inboxes, ai_assistance_enabled,
                       ai_cross_app_actions_enabled, ai_auto_execute_enabled,
                       retention_days, maximum_attachment_mb, version
                  FROM mail_tenant_policies WHERE tenant_id = ?
                """, result -> result.next()
                ? new MailDtos.TenantPolicy(
                        result.getBoolean("external_sender_banner"),
                        result.getBoolean("block_remote_images"),
                        result.getBoolean("allow_shared_inboxes"),
                        result.getBoolean("ai_assistance_enabled"),
                        result.getBoolean("ai_cross_app_actions_enabled"),
                        result.getBoolean("ai_auto_execute_enabled"),
                        result.getInt("retention_days"),
                        result.getInt("maximum_attachment_mb"),
                        result.getLong("version"))
                : new MailDtos.TenantPolicy(
                        true, true, true, true, true, false, 365, 25, 0), tenantId);
    }

    List<MailDtos.ConnectionSummary> connections(Long tenantId) {
        return jdbc.query("""
                SELECT connection_id, connection_key, display_name, provider_type,
                       authentication_mode, mail_domain, connection_state,
                       capabilities::text, credential_ref IS NOT NULL AS credential_configured,
                       last_synchronized_at, last_error_code, version
                  FROM mail_provider_connections
                 WHERE tenant_id = ?
                 ORDER BY CASE connection_state WHEN 'ACTIVE' THEN 0 ELSE 1 END,
                          display_name
                """, (result, ignored) -> connection(result), tenantId);
    }

    Optional<MailDtos.ConnectionSummary> connection(Long tenantId, UUID connectionId) {
        return jdbc.query("""
                SELECT connection_id, connection_key, display_name, provider_type,
                       authentication_mode, mail_domain, connection_state,
                       capabilities::text, credential_ref IS NOT NULL AS credential_configured,
                       last_synchronized_at, last_error_code, version
                  FROM mail_provider_connections
                 WHERE tenant_id = ? AND connection_id = ?
                """, (result, ignored) -> connection(result), tenantId, connectionId)
                .stream().findFirst();
    }

    List<MailDtos.SharedInboxSummary> sharedInboxes(Long tenantId) {
        return jdbc.query("""
                SELECT inbox.shared_inbox_id, inbox.inbox_key, inbox.display_name,
                       account.email_address, inbox.purpose, inbox.service_target_minutes,
                       inbox.lifecycle_state, inbox.version,
                       COUNT(thread.thread_id) FILTER (
                           WHERE thread.workflow_state = 'OPEN') AS open_count,
                       COUNT(thread.thread_id) FILTER (
                           WHERE thread.workflow_state = 'OPEN'
                             AND thread.latest_message_at
                                 < CURRENT_TIMESTAMP
                                   - inbox.service_target_minutes * INTERVAL '1 minute') AS overdue_count
                  FROM mail_shared_inboxes inbox
                  JOIN mail_accounts account
                    ON account.tenant_id = inbox.tenant_id
                   AND account.account_id = inbox.account_id
                  LEFT JOIN mail_threads thread
                    ON thread.shared_inbox_id = inbox.shared_inbox_id
                   AND thread.tenant_id = inbox.tenant_id
                   AND thread.account_id = inbox.account_id
                 WHERE inbox.tenant_id = ?
                 GROUP BY inbox.shared_inbox_id, inbox.inbox_key, inbox.display_name,
                          account.email_address, inbox.purpose,
                          inbox.service_target_minutes, inbox.lifecycle_state, inbox.version
                 ORDER BY inbox.display_name
                """, (result, ignored) -> new MailDtos.SharedInboxSummary(
                result.getObject("shared_inbox_id", UUID.class),
                result.getString("inbox_key"), result.getString("display_name"),
                result.getString("email_address"), result.getString("purpose"),
                result.getInt("service_target_minutes"),
                result.getString("lifecycle_state"), result.getInt("open_count"),
                result.getInt("overdue_count"), result.getLong("version")), tenantId);
    }

    Optional<MailDtos.SharedInboxSummary> sharedInbox(Long tenantId, UUID sharedInboxId) {
        return sharedInboxes(tenantId).stream()
                .filter(inbox -> inbox.sharedInboxId().equals(sharedInboxId))
                .findFirst();
    }

    AdminCounts adminCounts(Long tenantId) {
        return new AdminCounts(
                count("SELECT COUNT(*) FROM mail_accounts WHERE tenant_id = ? AND account_kind = 'PERSONAL'", tenantId),
                count("SELECT COUNT(*) FROM mail_accounts WHERE tenant_id = ? AND account_kind = 'SHARED'", tenantId),
                count("SELECT COUNT(*) FROM mail_provider_connections WHERE tenant_id = ? AND connection_state = 'ACTIVE'", tenantId),
                count("SELECT COUNT(*) FROM mail_provider_connections WHERE tenant_id = ? AND connection_state = 'DEGRADED'", tenantId),
                count("SELECT COUNT(*) FROM mail_threads WHERE tenant_id = ? AND shared_inbox_id IS NOT NULL AND workflow_state = 'OPEN'", tenantId),
                count("""
                        SELECT COUNT(*)
                          FROM mail_action_proposals
                         WHERE tenant_id = ? AND proposal_status = 'PROPOSED'
                           AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                        """, tenantId),
                count("""
                        SELECT COUNT(*) FROM mail_delivery_outbox
                         WHERE tenant_id = ?
                           AND delivery_status IN ('QUEUED', 'LEASED', 'RETRY_WAIT')
                        """, tenantId),
                count("""
                        SELECT COUNT(*) FROM mail_delivery_outbox
                         WHERE tenant_id = ? AND delivery_status = 'FAILED'
                        """, tenantId));
    }

    private int count(String sql, Long tenantId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, tenantId);
        return value == null ? 0 : value;
    }

    private String threadSelect() {
        return """
                SELECT thread.thread_id, thread.account_id,
                       account.display_name AS account_name, folder.folder_type,
                       thread.shared_inbox_id, inbox.display_name AS shared_inbox_name,
                       thread.subject, thread.preview, thread.participants::text,
                       thread.latest_message_at, thread.unread, thread.starred,
                       thread.importance, thread.triage_lane,
                       %s AS workflow_state,
                       %s AS snoozed_until, thread.assigned_user_id,
                       thread.assigned_name, thread.has_attachments,
                       thread.external_sender, thread.classification,
                       thread.message_count, thread.version
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_folders folder
                    ON folder.tenant_id = thread.tenant_id
                   AND folder.account_id = thread.account_id
                   AND folder.folder_id = thread.folder_id
                  LEFT JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = thread.tenant_id
                   AND inbox.account_id = thread.account_id
                   AND inbox.shared_inbox_id = thread.shared_inbox_id
                 WHERE thread.tenant_id = ?
                """.formatted(
                        MailAccessSql.EFFECTIVE_WORKFLOW_STATE,
                        MailAccessSql.EFFECTIVE_SNOOZED_UNTIL);
    }

    private MailDtos.ThreadSummary thread(ResultSet result) throws SQLException {
        List<MailDtos.Participant> participants = json.mapList(
                        result.getString("participants")).stream()
                .map(value -> new MailDtos.Participant(
                        String.valueOf(value.getOrDefault("name", "")),
                        String.valueOf(value.getOrDefault("email", ""))))
                .toList();
        return new MailDtos.ThreadSummary(
                result.getObject("thread_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getString("account_name"),
                result.getString("folder_type"),
                result.getObject("shared_inbox_id", UUID.class),
                result.getString("shared_inbox_name"),
                result.getString("subject"), result.getString("preview"), participants,
                result.getObject("latest_message_at", OffsetDateTime.class),
                result.getBoolean("unread"), result.getBoolean("starred"),
                Importance.valueOf(result.getString("importance")),
                TriageLane.valueOf(result.getString("triage_lane")),
                WorkflowState.valueOf(result.getString("workflow_state")),
                result.getObject("snoozed_until", OffsetDateTime.class),
                nullableLong(result, "assigned_user_id"), result.getString("assigned_name"),
                result.getBoolean("has_attachments"),
                result.getBoolean("external_sender"),
                Classification.valueOf(result.getString("classification")),
                result.getInt("message_count"), result.getLong("version"));
    }

    private MailDtos.ActionProposal proposal(ResultSet result) throws SQLException {
        return new MailDtos.ActionProposal(
                result.getObject("proposal_id", UUID.class),
                result.getObject("thread_id", UUID.class),
                ProposalType.valueOf(result.getString("proposal_type")),
                result.getInt("action_contract_version"),
                ProposalStatus.valueOf(result.getString("proposal_status")),
                result.getString("title"), result.getString("summary"),
                json.mapList(result.getString("evidence")),
                json.map(result.getString("proposed_payload")),
                result.getBigDecimal("confidence"), result.getString("risk_level"),
                result.getString("required_resource_key"),
                result.getString("required_permission_code"),
                result.getString("target_route"),
                result.getObject("expires_at", OffsetDateTime.class),
                result.getLong("version"));
    }

    private MailDtos.ConnectionSummary connection(ResultSet result) throws SQLException {
        return new MailDtos.ConnectionSummary(
                result.getObject("connection_id", UUID.class),
                result.getString("connection_key"), result.getString("display_name"),
                ProviderType.valueOf(result.getString("provider_type")),
                result.getString("authentication_mode"), result.getString("mail_domain"),
                ConnectionState.valueOf(result.getString("connection_state")),
                json.stringList(result.getString("capabilities")),
                result.getBoolean("credential_configured"),
                result.getObject("last_synchronized_at", OffsetDateTime.class),
                result.getString("last_error_code"), result.getLong("version"));
    }

    private String pattern(String value) {
        return "%" + value.toLowerCase(java.util.Locale.ROOT) + "%";
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    record AdminCounts(
            int personalAccounts,
            int sharedAccounts,
            int activeConnections,
            int degradedConnections,
            int openSharedThreads,
            int pendingAiProposals,
            int queuedDeliveries,
            int failedDeliveries) {
    }
}
