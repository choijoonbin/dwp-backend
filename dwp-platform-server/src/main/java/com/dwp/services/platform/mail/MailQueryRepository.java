package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.*;

@Repository
class MailQueryRepository extends MailQueryGovernanceSupport {

    enum SharedInboxPermission { READ, SEND, ASSIGN, MANAGE }

    MailQueryRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        super(jdbc, json);
    }

    List<MailDtos.AccountSummary> accounts(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT account.account_id, account.email_address, account.display_name,
                       account.account_kind, connection.provider_type,
                       account.connection_state, account.synchronization_state,
                       connection.connection_state AS provider_connection_state,
                       (connection.provider_type = 'DWP_SANDBOX'
                           OR connection.credential_ref IS NOT NULL) AS credential_configured,
                       connection.last_synchronized_at,
                       connection.last_error_code,
                       connection.updated_at AS readiness_observed_at,
                       CASE WHEN preference.default_account_id IS NOT NULL
                            THEN account.account_id = preference.default_account_id
                            ELSE account.is_default END AS is_default
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                  LEFT JOIN mail_user_preferences preference
                    ON preference.tenant_id = account.tenant_id
                   AND preference.user_id = ?
                 WHERE account.tenant_id = ?
                """ + MailAccessSql.ACCOUNT_ACCESS + """
                 ORDER BY is_default DESC, account.account_kind, account.display_name
                """, (result, ignored) -> new MailDtos.AccountSummary(
                result.getObject("account_id", UUID.class),
                result.getString("email_address"),
                result.getString("display_name"),
                result.getString("account_kind"),
                ProviderType.valueOf(result.getString("provider_type")),
                result.getString("connection_state"),
                result.getString("synchronization_state"),
                result.getBoolean("is_default"),
                persistedAccountReadiness(result)), userId, tenantId, userId, userId);
    }

    private MailDtos.AccountReadiness persistedAccountReadiness(ResultSet result)
            throws SQLException {
        boolean credentialConfigured = result.getBoolean("credential_configured");
        String accountState = result.getString("connection_state");
        String providerState = result.getString("provider_connection_state");
        String errorCode = result.getString("last_error_code");
        String action;
        if (!credentialConfigured || "CONFIGURATION_REQUIRED".equals(providerState)
                || "REAUTHENTICATION_REQUIRED".equals(accountState)) {
            action = "ACTIVATE_EXTERNALLY";
        } else if ("SUSPENDED".equals(accountState) || "SUSPENDED".equals(providerState)) {
            action = "CONTACT_ADMIN";
        } else {
            action = "RETRY";
        }
        OffsetDateTime lastSync = result.getObject(
                "last_synchronized_at", OffsetDateTime.class);
        OffsetDateTime observedAt = result.getObject(
                "readiness_observed_at", OffsetDateTime.class);
        boolean sandbox = "DWP_SANDBOX".equals(result.getString("provider_type"));
        MailDtos.AuthorizationEvidence consent = persistedAuthorizationEvidence(
                sandbox, "CONSENT", observedAt);
        MailDtos.AuthorizationEvidence token = persistedAuthorizationEvidence(
                sandbox, "TOKEN", observedAt);
        String readinessError = errorCode == null || errorCode.isBlank()
                ? "RUNTIME_READINESS_NOT_CHECKED" : errorCode;
        return new MailDtos.AccountReadiness(
                "UNAVAILABLE", "NO_RUNTIME_ATTESTATION",
                observedAt, readinessError, credentialConfigured, lastSync,
                lastSync == null ? null : "ACCOUNT", action, consent, token,
                unavailableFeatureEvidence(
                        observedAt, readinessError, action));
    }

    private MailDtos.AuthorizationEvidence persistedAuthorizationEvidence(
            boolean sandbox, String kind, OffsetDateTime observedAt) {
        return sandbox
                ? new MailDtos.AuthorizationEvidence(
                        "NOT_REQUIRED", "PERSISTED_CONNECTION", observedAt,
                        null, null, "NONE")
                : new MailDtos.AuthorizationEvidence(
                        "UNKNOWN", "NO_OAUTH_ATTESTATION", observedAt,
                        null, "MAIL_" + kind + "_EVIDENCE_UNAVAILABLE",
                        "ACTIVATE_EXTERNALLY");
    }

    private Map<String, MailDtos.FeatureReadiness> unavailableFeatureEvidence(
            OffsetDateTime observedAt,
            String errorCode,
            String action) {
        MailDtos.FeatureReadiness unavailable = new MailDtos.FeatureReadiness(
                "UNAVAILABLE", "NO_RUNTIME_ATTESTATION", observedAt,
                errorCode, null, "UNAVAILABLE", action);
        return Map.of(
                "SEND", unavailable,
                "BCC", unavailable,
                "HTML_BODY", unavailable,
                "ATTACHMENTS", unavailable,
                "SCHEDULING", unavailable);
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
                        OR EXISTS (
                            SELECT 1
                              FROM jsonb_array_elements(thread.participants) participant(value)
                             WHERE UPPER(TRIM(COALESCE(participant.value ->> 'type', 'TO')))
                                   <> 'BCC'
                               AND LOWER(participant.value::text) LIKE ?))
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

    List<MailDtos.ThreadSummary> threadsAdvanced(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            UUID folderId,
            boolean sharedOnly,
            String search,
            UUID accountId,
            String scope,
            UUID sharedInboxId,
            String assignment,
            String sender,
            String recipient,
            LocalDate dateFrom,
            LocalDate dateTo,
            Boolean unread,
            Boolean needsReply,
            Boolean hasAttachment,
            String importance,
            int page,
            int pageSize) {
        return jdbc.query(threadSelect() + MailAccessSql.THREAD_ACCESS
                        + advancedThreadFilters() + """
                 ORDER BY
                       CASE thread.importance
                           WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1
                           WHEN 'NORMAL' THEN 2 ELSE 3 END,
                       thread.unread DESC, thread.latest_message_at DESC, thread.thread_id
                 LIMIT ? OFFSET ?
                """, (result, ignored) -> thread(result),
                advancedThreadParameters(
                        tenantId, userId, lane, state, folder, folderId, sharedOnly, search,
                        accountId, scope, sharedInboxId, assignment, sender, recipient,
                        dateFrom, dateTo, unread, needsReply, hasAttachment, importance,
                        pageSize, page * pageSize));
    }

    long threadCountAdvanced(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            UUID folderId,
            boolean sharedOnly,
            String search,
            UUID accountId,
            String scope,
            UUID sharedInboxId,
            String assignment,
            String sender,
            String recipient,
            LocalDate dateFrom,
            LocalDate dateTo,
            Boolean unread,
            Boolean needsReply,
            Boolean hasAttachment,
            String importance) {
        String sql = "SELECT COUNT(*) FROM (" + threadSelect()
                + MailAccessSql.THREAD_ACCESS + advancedThreadFilters() + ") visible_threads";
        Long count = jdbc.queryForObject(sql, Long.class,
                advancedThreadParameters(
                        tenantId, userId, lane, state, folder, folderId, sharedOnly, search,
                        accountId, scope, sharedInboxId, assignment, sender, recipient,
                        dateFrom, dateTo, unread, needsReply, hasAttachment, importance));
        return count == null ? 0 : count;
    }

    private String advancedThreadFilters() {
        return """
                   AND (? = '' OR thread.triage_lane = ?)
                """ + MailAccessSql.WORKFLOW_FILTER + """
                   AND (? = '' OR folder.folder_type = ?)
                   AND (?::uuid IS NULL OR EXISTS (
                       SELECT 1 FROM mail_thread_folders selected_membership
                        WHERE selected_membership.tenant_id = thread.tenant_id
                          AND selected_membership.thread_id = thread.thread_id
                          AND selected_membership.folder_id = ?::uuid))
                   AND (? = FALSE OR thread.shared_inbox_id IS NOT NULL)
                   AND (? = '' OR LOWER(thread.subject) LIKE ? OR LOWER(thread.preview) LIKE ?
                        OR EXISTS (
                            SELECT 1
                              FROM jsonb_array_elements(thread.participants) participant(value)
                             WHERE UPPER(TRIM(COALESCE(participant.value ->> 'type', 'TO')))
                                   <> 'BCC'
                               AND LOWER(participant.value::text) LIKE ?))
                   AND (?::uuid IS NULL OR thread.account_id = ?::uuid)
                   AND (? = '' OR account.account_kind = ?)
                   AND (?::uuid IS NULL OR thread.shared_inbox_id = ?::uuid)
                   AND (? = ''
                        OR (? = 'MINE' AND thread.assigned_user_id = ?)
                        OR (? = 'UNASSIGNED' AND thread.assigned_user_id IS NULL)
                        OR (? = 'OVERDUE'
                            AND thread.shared_inbox_id IS NOT NULL
                            AND inbox.lifecycle_state = 'ACTIVE'
                            AND thread.workflow_state = 'OPEN'
                            AND thread.latest_message_at
                                < CURRENT_TIMESTAMP
                                  - inbox.service_target_minutes * INTERVAL '1 minute'))
                   AND (? = '' OR EXISTS (
                       SELECT 1 FROM mail_messages searched_sender
                        WHERE searched_sender.tenant_id = thread.tenant_id
                          AND searched_sender.thread_id = thread.thread_id
                          AND LOWER(searched_sender.sender_email) LIKE ?))
                   AND (? = '' OR EXISTS (
                       SELECT 1
                         FROM mail_messages searched_recipient
                         CROSS JOIN LATERAL jsonb_array_elements(
                             searched_recipient.recipients) recipient(value)
                        WHERE searched_recipient.tenant_id = thread.tenant_id
                          AND searched_recipient.thread_id = thread.thread_id
                          AND UPPER(TRIM(COALESCE(recipient.value ->> 'type', 'TO'))) <> 'BCC'
                          AND LOWER(recipient.value::text) LIKE ?))
                   AND (?::date IS NULL OR thread.latest_message_at >= ?::date)
                   AND (?::date IS NULL OR thread.latest_message_at < (?::date + INTERVAL '1 day'))
                   AND (?::boolean IS NULL OR thread.unread = ?::boolean)
                   AND (?::boolean IS NULL OR ?::boolean = FALSE OR thread.triage_lane = 'NEEDS_REPLY')
                   AND (?::boolean IS NULL OR thread.has_attachments = ?::boolean)
                   AND (? = '' OR thread.importance = ?)
                """;
    }

    private Object[] advancedThreadParameters(
            Long tenantId,
            Long userId,
            String lane,
            String state,
            String folder,
            UUID folderId,
            boolean sharedOnly,
            String search,
            UUID accountId,
            String scope,
            UUID sharedInboxId,
            String assignment,
            String sender,
            String recipient,
            LocalDate dateFrom,
            LocalDate dateTo,
            Boolean unread,
            Boolean needsReply,
            Boolean hasAttachment,
            String importance,
            Object... tail) {
        String accountKind = switch (scope) {
            case "PERSONAL" -> "PERSONAL";
            case "SHARED" -> "SHARED";
            default -> "";
        };
        List<Object> values = new java.util.ArrayList<>();
        java.util.Collections.addAll(values,
                tenantId, userId, userId,
                lane, lane,
                state, state, state,
                folder, folder,
                folderId, folderId,
                sharedOnly,
                search, pattern(search), pattern(search), pattern(search),
                accountId, accountId,
                accountKind, accountKind,
                sharedInboxId, sharedInboxId,
                assignment, assignment, userId, assignment, assignment,
                sender, pattern(sender),
                recipient, pattern(recipient));
        values.add(dateFrom);
        values.add(dateFrom);
        values.add(dateTo);
        values.add(dateTo);
        values.add(unread);
        values.add(unread);
        values.add(needsReply);
        values.add(needsReply);
        values.add(hasAttachment);
        values.add(hasAttachment);
        values.add(importance);
        values.add(importance);
        java.util.Collections.addAll(values, tail);
        return values.toArray();
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
                        OR EXISTS (
                            SELECT 1
                              FROM jsonb_array_elements(thread.participants) participant(value)
                             WHERE UPPER(TRIM(COALESCE(participant.value ->> 'type', 'TO')))
                                   <> 'BCC'
                               AND LOWER(participant.value::text) LIKE ?))
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
                       message.created_by,
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
                visibleRecipients(
                        result.getString("message_direction"),
                        result.getString("recipients"),
                        result.getLong("created_by") == userId),
                result.getString("message_direction"),
                result.getString("body_format"),
                result.getString("body_content"),
                json.mapList(result.getString("attachments")),
                result.getObject("sent_at", OffsetDateTime.class),
                DeliveryState.valueOf(result.getString("delivery_state")),
                result.getObject("accepted_at", OffsetDateTime.class),
                result.getString("last_error_code")), tenantId, threadId, userId, userId);
    }

    List<Map<String, Object>> visibleRecipients(String direction, String recipientsJson) {
        return visibleRecipients(
                direction, recipientsJson, !"INBOUND".equalsIgnoreCase(direction));
    }

    List<Map<String, Object>> visibleRecipients(
            String direction, String recipientsJson, boolean senderView) {
        List<Map<String, Object>> recipients = json.mapList(recipientsJson);
        if (!"INBOUND".equalsIgnoreCase(direction) && senderView) return recipients;
        return recipients.stream()
                .filter(recipient -> recipient.entrySet().stream().noneMatch(entry ->
                        "type".equalsIgnoreCase(entry.getKey())
                                && entry.getValue() != null
                                && "BCC".equalsIgnoreCase(
                                String.valueOf(entry.getValue()).trim())))
                .toList();
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
                  JOIN mail_shared_inbox_access_grants access_grant
                    ON access_grant.tenant_id = membership.tenant_id
                   AND access_grant.shared_inbox_id = membership.shared_inbox_id
                   AND access_grant.user_id = membership.user_id
                   AND access_grant.member_state = 'ACTIVE'
                   AND access_grant.can_read = TRUE
                   AND (access_grant.expires_at IS NULL
                        OR access_grant.expires_at > CURRENT_TIMESTAMP)
                 WHERE policy.tenant_id = ? AND policy.allow_shared_inboxes = TRUE
                """, Long.class, sharedInboxId, userId, tenantId);
        return count != null && count > 0;
    }

    boolean hasSharedInboxPermission(
            Long tenantId, UUID sharedInboxId, Long userId,
            SharedInboxPermission permission) {
        String permissionPredicate = switch (permission) {
            case READ -> "access_grant.can_read = TRUE";
            case SEND -> "(access_grant.can_send_as = TRUE "
                    + "OR access_grant.can_send_on_behalf = TRUE)";
            case ASSIGN -> "access_grant.can_assign = TRUE";
            case MANAGE -> "access_grant.can_manage = TRUE";
        };
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
                  JOIN mail_shared_inbox_access_grants access_grant
                    ON access_grant.tenant_id = membership.tenant_id
                   AND access_grant.shared_inbox_id = membership.shared_inbox_id
                   AND access_grant.user_id = membership.user_id
                   AND access_grant.member_state = 'ACTIVE'
                   AND (access_grant.expires_at IS NULL
                        OR access_grant.expires_at > CURRENT_TIMESTAMP)
                 WHERE policy.tenant_id = ? AND policy.allow_shared_inboxes = TRUE
                   AND %s
                """.formatted(permissionPredicate), Long.class,
                sharedInboxId, userId, tenantId);
        return count != null && count > 0;
    }

}
