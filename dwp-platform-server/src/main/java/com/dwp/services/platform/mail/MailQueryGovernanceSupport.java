package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.*;

class MailQueryGovernanceSupport extends MailQueryMappingSupport {

    record ProposalHandoffRow(
            UUID proposalId,
            UUID commandId,
            String ownerRoute,
            String ownerState,
            String resultRef,
            OffsetDateTime updatedAt,
            long version) {
    }

    record OwnerProposalHandoffRow(
            UUID proposalId,
            UUID commandId,
            ProposalType proposalType,
            UUID sourceThreadId,
            Map<String, Object> proposedPayload,
            Long decidedBy,
            String ownerRoute,
            String ownerState,
            String resultRef,
            OffsetDateTime updatedAt,
            long version) {
    }

    MailQueryGovernanceSupport(JdbcTemplate jdbc, MailJsonCodec json) {
        super(jdbc, json);
    }

    Optional<MailDtos.SharedInboxReplyIdentity> sharedInboxReplyIdentity(
            Long tenantId, Long userId, UUID threadId) {
        return jdbc.query("""
                SELECT account.display_name, account.email_address,
                       CASE WHEN access_grant.can_send_as = TRUE
                            THEN 'SEND_AS' ELSE 'ON_BEHALF_OF' END AS sender_mode
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                   AND account.account_kind = 'SHARED'
                   AND account.connection_state = 'ACTIVE'
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                   AND connection.connection_state = 'ACTIVE'
                  JOIN mail_tenant_policies policy
                    ON policy.tenant_id = thread.tenant_id
                   AND policy.allow_shared_inboxes = TRUE
                  JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = thread.tenant_id
                   AND inbox.shared_inbox_id = thread.shared_inbox_id
                   AND inbox.account_id = thread.account_id
                   AND inbox.lifecycle_state = 'ACTIVE'
                  JOIN mail_shared_inbox_members membership
                    ON membership.tenant_id = inbox.tenant_id
                   AND membership.account_id = inbox.account_id
                   AND membership.shared_inbox_id = inbox.shared_inbox_id
                   AND membership.user_id = ?
                   AND membership.lifecycle_state = 'ACTIVE'
                  JOIN mail_shared_inbox_access_grants access_grant
                    ON access_grant.tenant_id = membership.tenant_id
                   AND access_grant.shared_inbox_id = membership.shared_inbox_id
                   AND access_grant.user_id = membership.user_id
                   AND access_grant.member_state = 'ACTIVE'
                   AND access_grant.can_read = TRUE
                   AND (access_grant.can_send_as = TRUE
                        OR access_grant.can_send_on_behalf = TRUE)
                   AND (access_grant.expires_at IS NULL
                        OR access_grant.expires_at > CURRENT_TIMESTAMP)
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """, (result, ignored) -> new MailDtos.SharedInboxReplyIdentity(
                        result.getString("display_name"),
                        result.getString("email_address"),
                        MailDtos.SharedInboxReplySenderMode.valueOf(
                                result.getString("sender_mode"))),
                userId, tenantId, threadId).stream().findFirst();
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

    List<MailDtos.ActionProposal> proposalsFiltered(
            Long tenantId,
            Long userId,
            UUID accountId,
            String status,
            String type,
            int limit) {
        return proposalsFiltered(
                tenantId, userId, accountId, status, type,
                null, null, 0, limit);
    }

    List<MailDtos.ActionProposal> proposalsFiltered(
            Long tenantId,
            Long userId,
            UUID accountId,
            String status,
            String type,
            LocalDate dateFrom,
            LocalDate dateTo,
            int page,
            int pageSize) {
        return jdbc.query("""
                SELECT proposal.proposal_id, proposal.thread_id, proposal.proposal_type,
                       proposal.action_contract_version,
                       CASE WHEN proposal.proposal_status = 'PROPOSED'
                                      AND proposal.expires_at IS NOT NULL
                                      AND proposal.expires_at <= CURRENT_TIMESTAMP
                            THEN 'EXPIRED' ELSE proposal.proposal_status END AS proposal_status,
                       proposal.title, proposal.summary,
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
                   AND (?::uuid IS NULL OR thread.account_id = ?::uuid)
                   AND (? = '' OR (CASE WHEN proposal.proposal_status = 'PROPOSED'
                                                   AND proposal.expires_at IS NOT NULL
                                                   AND proposal.expires_at <= CURRENT_TIMESTAMP
                                         THEN 'EXPIRED' ELSE proposal.proposal_status END) = ?)
                   AND (? = '' OR proposal.proposal_type = ?)
                   AND (?::date IS NULL OR proposal.created_at >= ?::date)
                   AND (?::date IS NULL OR proposal.created_at < (?::date + INTERVAL '1 day'))
                """ + MailAccessSql.THREAD_ACCESS + """
                 ORDER BY
                       CASE proposal.risk_level WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
                       proposal.created_at DESC, proposal.proposal_id
                 LIMIT ? OFFSET ?
                """, (result, ignored) -> proposal(result),
                tenantId, accountId, accountId, status, status, type, type,
                dateFrom, dateFrom, dateTo, dateTo,
                userId, userId, pageSize, page * pageSize);
    }

    long proposalCountFiltered(
            Long tenantId,
            Long userId,
            UUID accountId,
            String status,
            String type,
            LocalDate dateFrom,
            LocalDate dateTo) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_action_proposals proposal
                  JOIN mail_threads thread
                    ON thread.tenant_id = proposal.tenant_id
                   AND thread.thread_id = proposal.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE proposal.tenant_id = ?
                   AND (?::uuid IS NULL OR thread.account_id = ?::uuid)
                   AND (? = '' OR (CASE WHEN proposal.proposal_status = 'PROPOSED'
                                                   AND proposal.expires_at IS NOT NULL
                                                   AND proposal.expires_at <= CURRENT_TIMESTAMP
                                         THEN 'EXPIRED' ELSE proposal.proposal_status END) = ?)
                   AND (? = '' OR proposal.proposal_type = ?)
                   AND (?::date IS NULL OR proposal.created_at >= ?::date)
                   AND (?::date IS NULL OR proposal.created_at < (?::date + INTERVAL '1 day'))
                """ + MailAccessSql.THREAD_ACCESS,
                Long.class, tenantId, accountId, accountId, status, status, type, type,
                dateFrom, dateFrom, dateTo, dateTo, userId, userId);
        return count == null ? 0 : count;
    }

    int updateProposalPayload(
            Long tenantId,
            Long userId,
            UUID proposalId,
            Map<String, Object> payload,
            long version) {
        return jdbc.update("""
                UPDATE mail_action_proposals proposal
                   SET proposed_payload = ?::jsonb, version = proposal.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_threads thread, mail_accounts account
                 WHERE proposal.tenant_id = ? AND proposal.proposal_id = ?
                   AND proposal.proposal_status = 'PROPOSED' AND proposal.version = ?
                   AND thread.tenant_id = proposal.tenant_id
                   AND thread.thread_id = proposal.thread_id
                """ + MailAccessSql.THREAD_MANAGE_ACCESS,
                json.write(payload), userId, tenantId, proposalId, version, userId, userId);
    }

    Optional<MailDtos.ActionProposal> proposal(
            Long tenantId, Long userId, UUID proposalId) {
        return jdbc.query("""
                SELECT proposal.proposal_id, proposal.thread_id, proposal.proposal_type,
                       proposal.action_contract_version,
                       CASE WHEN proposal.proposal_status = 'PROPOSED'
                                      AND proposal.expires_at IS NOT NULL
                                      AND proposal.expires_at <= CURRENT_TIMESTAMP
                            THEN 'EXPIRED' ELSE proposal.proposal_status END AS proposal_status,
                       proposal.title, proposal.summary,
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

    Optional<ProposalHandoffRow> proposalHandoff(
            Long tenantId, Long userId, UUID proposalId) {
        return jdbc.query("""
                SELECT proposal.proposal_id, proposal.owner_command_id,
                       proposal.target_route, proposal.owner_state,
                       proposal.result_ref, proposal.owner_updated_at,
                       proposal.version
                  FROM mail_action_proposals proposal
                  JOIN mail_threads thread
                    ON thread.tenant_id = proposal.tenant_id
                   AND thread.thread_id = proposal.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE proposal.tenant_id = ? AND proposal.proposal_id = ?
                   AND proposal.owner_command_id IS NOT NULL
                   AND proposal.owner_state IS NOT NULL
                """ + MailAccessSql.THREAD_ACCESS,
                (result, ignored) -> new ProposalHandoffRow(
                        result.getObject("proposal_id", UUID.class),
                        result.getObject("owner_command_id", UUID.class),
                        result.getString("target_route"),
                        result.getString("owner_state"),
                        result.getString("result_ref"),
                        result.getObject("owner_updated_at", OffsetDateTime.class),
                        result.getLong("version")),
                tenantId, proposalId, userId, userId).stream().findFirst();
    }

    Optional<OwnerProposalHandoffRow> ownerProposalHandoff(
            long tenantId, UUID proposalId, boolean lock) {
        String sql = """
                SELECT proposal.proposal_id, proposal.owner_command_id,
                       proposal.proposal_type, proposal.thread_id,
                       proposal.proposed_payload::text AS proposed_payload,
                       proposal.decided_by,
                       proposal.target_route, proposal.owner_state,
                       proposal.result_ref, proposal.owner_updated_at,
                       proposal.version
                  FROM mail_action_proposals proposal
                 WHERE proposal.tenant_id = ? AND proposal.proposal_id = ?
                   AND proposal.owner_command_id IS NOT NULL
                   AND proposal.owner_state IS NOT NULL
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (result, ignored) -> new OwnerProposalHandoffRow(
                        result.getObject("proposal_id", UUID.class),
                        result.getObject("owner_command_id", UUID.class),
                        ProposalType.valueOf(result.getString("proposal_type")),
                        result.getObject("thread_id", UUID.class),
                        json.map(result.getString("proposed_payload")),
                        result.getObject("decided_by", Long.class),
                        result.getString("target_route"),
                        result.getString("owner_state"),
                        result.getString("result_ref"),
                        result.getObject("owner_updated_at", OffsetDateTime.class),
                        result.getLong("version")),
                tenantId, proposalId).stream().findFirst();
    }

    MailDtos.HomeMetrics metrics(Long tenantId, Long userId) {
        return metrics(tenantId, userId, null);
    }

    MailDtos.HomeMetrics metrics(Long tenantId, Long userId, UUID accountId) {
        return jdbc.query("""
                WITH visible_threads AS (
                    SELECT thread.*
                      FROM mail_threads thread
                      JOIN mail_accounts account
                        ON account.tenant_id = thread.tenant_id
                       AND account.account_id = thread.account_id
                     WHERE thread.tenant_id = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                       AND (?::uuid IS NULL OR thread.account_id = ?::uuid)
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
                }, tenantId, userId, userId, accountId, accountId, tenantId, userId);
    }

    List<MailDtos.SharedInboxPulse> sharedInboxPulse(Long tenantId, Long userId) {
        return sharedInboxPulse(tenantId, userId, null);
    }

    List<MailDtos.SharedInboxPulse> sharedInboxPulse(
            Long tenantId, Long userId, UUID accountId) {
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
                   AND (?::uuid IS NULL OR account.account_id = ?::uuid)
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
                result.getInt("service_target_minutes")),
                tenantId, accountId, accountId, userId, userId);
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
