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

class MailQueryMappingSupport {

    final JdbcTemplate jdbc;
    final MailJsonCodec json;

    MailQueryMappingSupport(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    int count(String sql, Long tenantId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, tenantId);
        return value == null ? 0 : value;
    }

    String threadSelect() {
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

    MailDtos.ThreadSummary thread(ResultSet result) throws SQLException {
        List<MailDtos.Participant> participants = json.mapList(
                        result.getString("participants")).stream()
                .filter(value -> value.entrySet().stream().noneMatch(entry ->
                        "type".equalsIgnoreCase(entry.getKey())
                                && entry.getValue() != null
                                && "BCC".equalsIgnoreCase(
                                String.valueOf(entry.getValue()).trim())))
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

    MailDtos.ActionProposal proposal(ResultSet result) throws SQLException {
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

    MailDtos.ConnectionSummary connection(ResultSet result) throws SQLException {
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

    String pattern(String value) {
        return "%" + value.toLowerCase(java.util.Locale.ROOT) + "%";
    }

    Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

}
