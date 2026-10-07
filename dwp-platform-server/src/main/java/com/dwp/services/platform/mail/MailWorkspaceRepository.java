package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

@Repository
class MailWorkspaceRepository extends MailWorkspaceAttachmentRepositorySupport {

    MailWorkspaceRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        super(jdbc, json);
    }

    boolean accountAccessible(long tenantId, long userId, UUID accountId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_accounts account
                 WHERE account.tenant_id = ? AND account.account_id = ?
                """ + MailAccessSql.ACCOUNT_ACCESS, Integer.class,
                tenantId, accountId, userId, userId);
        return count != null && count > 0;
    }

    boolean accountSendAccessible(long tenantId, long userId, UUID accountId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE account.tenant_id = ? AND account.account_id = ?
                   AND account.connection_state = 'ACTIVE'
                   AND connection.connection_state = 'ACTIVE'
                """ + MailAccessSql.ACCOUNT_SEND_ACCESS, Integer.class,
                tenantId, accountId, userId, userId);
        return count != null && count > 0;
    }

    int maximumAttachmentMb(long tenantId) {
        Integer size = jdbc.queryForObject("""
                SELECT maximum_attachment_mb
                  FROM mail_tenant_policies
                 WHERE tenant_id = ?
                """, Integer.class, tenantId);
        return size == null ? 25 : size;
    }

    boolean blockRemoteImages(long tenantId) {
        Boolean value = jdbc.queryForObject("""
                SELECT block_remote_images
                  FROM mail_tenant_policies
                 WHERE tenant_id = ?
                """, Boolean.class, tenantId);
        return value == null || value;
    }

    List<Template> templates(long tenantId, long userId, boolean includeArchived) {
        return jdbc.query("""
                SELECT template_id, display_name, subject_template, body_content, body_format,
                       template_scope, account_id, mandatory_content, publication_state,
                       publication_version, lifecycle_state, version, updated_at, owner_user_id
                 FROM mail_templates
                 WHERE tenant_id = ?
                   AND ((template_scope <> 'ORGANIZATION' AND owner_user_id = ?)
                     OR (template_scope = 'ORGANIZATION'
                         AND publication_state = 'PUBLISHED'))
                   AND (? OR lifecycle_state = 'ACTIVE')
                 ORDER BY lifecycle_state, template_scope, lower(display_name), template_id
                """, (result, ignored) -> template(result, userId),
                tenantId, userId, includeArchived);
    }

    Optional<Template> template(long tenantId, long userId, UUID templateId) {
        return jdbc.query("""
                SELECT template_id, display_name, subject_template, body_content, body_format,
                       template_scope, account_id, mandatory_content, publication_state,
                       publication_version, lifecycle_state, version, updated_at, owner_user_id
                 FROM mail_templates
                 WHERE tenant_id = ? AND template_id = ?
                   AND ((template_scope <> 'ORGANIZATION' AND owner_user_id = ?)
                     OR (template_scope = 'ORGANIZATION'
                         AND publication_state = 'PUBLISHED'))
                   AND lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> template(result, userId),
                tenantId, templateId, userId).stream().findFirst();
    }

    Optional<Template> templateForSend(
            long tenantId, long userId, UUID accountId, UUID templateId) {
        return jdbc.query("""
                SELECT template_id, display_name, subject_template, body_content, body_format,
                       template_scope, account_id, mandatory_content, publication_state,
                       publication_version, lifecycle_state, version, updated_at, owner_user_id
                  FROM mail_templates
                 WHERE tenant_id = ? AND template_id = ? AND lifecycle_state = 'ACTIVE'
                   AND ((template_scope = 'PERSONAL' AND owner_user_id = ?
                         AND account_id IS NULL)
                     OR (template_scope = 'ACCOUNT' AND owner_user_id = ?
                         AND account_id = ?)
                     OR (template_scope = 'ORGANIZATION' AND account_id IS NULL
                         AND publication_state = 'PUBLISHED'))
                   FOR SHARE
                """, (result, ignored) -> template(result, userId),
                tenantId, templateId, userId, userId, accountId).stream().findFirst();
    }

    Template createTemplate(long tenantId, long userId, TemplateRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_templates (
                    template_id, tenant_id, owner_user_id, account_id, template_scope,
                    display_name, subject_template, body_content, body_format,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id, tenantId, userId, request.accountId(), request.scope().name(),
                request.name().trim(), value(request.subject()), request.body().trim(),
                request.bodyFormat().name(), userId, userId);
        return template(tenantId, userId, id).orElseThrow();
    }

    Optional<Template> updateTemplate(
            long tenantId, long userId, UUID templateId, TemplateRequest request) {
        int updated = jdbc.update("""
                UPDATE mail_templates
                   SET account_id = ?, template_scope = ?, display_name = ?,
                       subject_template = ?, body_content = ?, body_format = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND template_id = ? AND owner_user_id = ?
                   AND template_scope <> 'ORGANIZATION' AND lifecycle_state = 'ACTIVE'
                   AND version = ?
                """, request.accountId(), request.scope().name(), request.name().trim(),
                value(request.subject()), request.body().trim(), request.bodyFormat().name(),
                userId, tenantId, templateId, userId, request.version());
        return updated == 1 ? template(tenantId, userId, templateId) : Optional.empty();
    }

    boolean archiveTemplate(long tenantId, long userId, UUID templateId, long version) {
        return jdbc.update("""
                UPDATE mail_templates
                   SET lifecycle_state = 'ARCHIVED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND template_id = ? AND owner_user_id = ?
                   AND template_scope <> 'ORGANIZATION' AND lifecycle_state = 'ACTIVE'
                   AND version = ?
                """, userId, tenantId, templateId, userId, version) == 1;
    }

    List<Signature> signatures(long tenantId, long userId, boolean includeArchived) {
        return jdbc.query("""
                SELECT signature_id, display_name, body_content, body_format, signature_scope,
                       account_id, default_for_new, default_for_reply, mandatory_content,
                       publication_state, publication_version, lifecycle_state, version,
                       updated_at, owner_user_id
                 FROM mail_signatures
                 WHERE tenant_id = ?
                   AND ((signature_scope <> 'ORGANIZATION' AND owner_user_id = ?)
                     OR (signature_scope = 'ORGANIZATION'
                         AND publication_state = 'PUBLISHED'))
                   AND (? OR lifecycle_state = 'ACTIVE')
                 ORDER BY lifecycle_state, default_for_new DESC, default_for_reply DESC,
                          signature_scope, lower(display_name), signature_id
                """, (result, ignored) -> signature(result, userId),
                tenantId, userId, includeArchived);
    }

    Optional<Signature> signature(long tenantId, long userId, UUID signatureId) {
        return jdbc.query("""
                SELECT signature_id, display_name, body_content, body_format, signature_scope,
                       account_id, default_for_new, default_for_reply, mandatory_content,
                       publication_state, publication_version, lifecycle_state, version,
                       updated_at, owner_user_id
                 FROM mail_signatures
                 WHERE tenant_id = ? AND signature_id = ?
                   AND ((signature_scope <> 'ORGANIZATION' AND owner_user_id = ?)
                     OR (signature_scope = 'ORGANIZATION'
                         AND publication_state = 'PUBLISHED'))
                   AND lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> signature(result, userId),
                tenantId, signatureId, userId).stream().findFirst();
    }

    Optional<Signature> signatureForSend(
            long tenantId, long userId, UUID accountId, UUID signatureId) {
        return jdbc.query("""
                SELECT signature_id, display_name, body_content, body_format, signature_scope,
                       account_id, default_for_new, default_for_reply, mandatory_content,
                       publication_state, publication_version, lifecycle_state, version,
                       updated_at, owner_user_id
                  FROM mail_signatures
                 WHERE tenant_id = ? AND signature_id = ? AND lifecycle_state = 'ACTIVE'
                   AND ((signature_scope = 'PERSONAL' AND owner_user_id = ?
                         AND account_id IS NULL)
                     OR (signature_scope = 'ACCOUNT' AND owner_user_id = ?
                         AND account_id = ?)
                     OR (signature_scope = 'ORGANIZATION' AND account_id IS NULL
                         AND publication_state = 'PUBLISHED'))
                   FOR SHARE
                """, (result, ignored) -> signature(result, userId),
                tenantId, signatureId, userId, userId, accountId).stream().findFirst();
    }

    Optional<UUID> preferredSignatureId(long tenantId, long userId) {
        return jdbc.query("""
                SELECT default_signature_id
                  FROM mail_user_preferences
                 WHERE tenant_id = ? AND user_id = ? AND default_signature_id IS NOT NULL
                """, (result, ignored) -> result.getObject("default_signature_id", UUID.class),
                tenantId, userId).stream().findFirst();
    }

    Optional<Signature> defaultSignatureForNew(
            long tenantId, long userId, UUID accountId) {
        return jdbc.query("""
                SELECT signature_id, display_name, body_content, body_format, signature_scope,
                       account_id, default_for_new, default_for_reply, mandatory_content,
                       publication_state, publication_version, lifecycle_state, version,
                       updated_at, owner_user_id
                  FROM mail_signatures
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                   AND default_for_new = TRUE
                   AND ((signature_scope = 'ACCOUNT' AND owner_user_id = ? AND account_id = ?)
                     OR (signature_scope = 'PERSONAL' AND owner_user_id = ?
                         AND account_id IS NULL)
                     OR (signature_scope = 'ORGANIZATION' AND account_id IS NULL
                         AND publication_state = 'PUBLISHED'))
                 ORDER BY CASE signature_scope
                              WHEN 'ACCOUNT' THEN 0
                              WHEN 'ORGANIZATION' THEN 1
                              ELSE 2
                          END,
                          publication_version DESC, version DESC, signature_id
                 LIMIT 1
                   FOR SHARE
                """, (result, ignored) -> signature(result, userId),
                tenantId, userId, accountId, userId).stream().findFirst();
    }

    Optional<UUID> replyAccount(
            long tenantId, long userId, UUID threadId) {
        return jdbc.query("""
                SELECT thread.account_id
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """ + MailAccessSql.THREAD_SEND_ACCESS + """
                 FOR SHARE
                """, (result, ignored) -> result.getObject("account_id", UUID.class),
                tenantId, threadId, userId, userId).stream().findFirst();
    }

    Optional<Signature> defaultSignatureForReply(
            long tenantId, long userId, UUID accountId) {
        return jdbc.query("""
                SELECT signature_id, display_name, body_content, body_format, signature_scope,
                       account_id, default_for_new, default_for_reply, mandatory_content,
                       publication_state, publication_version, lifecycle_state, version,
                       updated_at, owner_user_id
                  FROM mail_signatures
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                   AND default_for_reply = TRUE
                   AND ((signature_scope = 'ACCOUNT' AND owner_user_id = ? AND account_id = ?)
                     OR (signature_scope = 'PERSONAL' AND owner_user_id = ?
                         AND account_id IS NULL)
                     OR (signature_scope = 'ORGANIZATION' AND account_id IS NULL
                         AND publication_state = 'PUBLISHED'))
                 ORDER BY CASE signature_scope
                              WHEN 'ACCOUNT' THEN 0
                              WHEN 'ORGANIZATION' THEN 1
                              ELSE 2
                          END,
                          publication_version DESC, version DESC, signature_id
                 LIMIT 1
                   FOR SHARE
                """, (result, ignored) -> signature(result, userId),
                tenantId, userId, accountId, userId).stream().findFirst();
    }

    void clearSignatureDefaults(
            long tenantId, long userId, UUID accountId, boolean forNew, boolean forReply,
            UUID except) {
        if (!forNew && !forReply) return;
        List<String> assignments = new ArrayList<>();
        if (forNew) assignments.add("default_for_new = FALSE");
        if (forReply) assignments.add("default_for_reply = FALSE");
        jdbc.update("""
                UPDATE mail_signatures
                   SET %s, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ?
                   AND account_id IS NOT DISTINCT FROM ?::uuid
                   AND signature_id <> ? AND lifecycle_state = 'ACTIVE'
                """.formatted(String.join(", ", assignments)),
                userId, tenantId, userId, accountId, except);
    }

    Signature createSignature(long tenantId, long userId, SignatureRequest request) {
        UUID id = UUID.randomUUID();
        clearSignatureDefaults(tenantId, userId, request.accountId(),
                request.defaultForNew(), request.defaultForReply(), id);
        jdbc.update("""
                INSERT INTO mail_signatures (
                    signature_id, tenant_id, owner_user_id, account_id, signature_scope,
                    display_name, body_content, body_format, default_for_new, default_for_reply,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id, tenantId, userId, request.accountId(), request.scope().name(),
                request.name().trim(), request.body().trim(), request.bodyFormat().name(),
                request.defaultForNew(), request.defaultForReply(), userId, userId);
        return signature(tenantId, userId, id).orElseThrow();
    }

    Optional<Signature> updateSignature(
            long tenantId, long userId, UUID signatureId, SignatureRequest request) {
        clearSignatureDefaults(tenantId, userId, request.accountId(),
                request.defaultForNew(), request.defaultForReply(), signatureId);
        int updated = jdbc.update("""
                UPDATE mail_signatures
                   SET account_id = ?, signature_scope = ?, display_name = ?,
                       body_content = ?, body_format = ?, default_for_new = ?,
                       default_for_reply = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND signature_id = ? AND owner_user_id = ?
                   AND signature_scope <> 'ORGANIZATION' AND lifecycle_state = 'ACTIVE'
                   AND version = ?
                """, request.accountId(), request.scope().name(), request.name().trim(),
                request.body().trim(), request.bodyFormat().name(),
                request.defaultForNew(), request.defaultForReply(), userId,
                tenantId, signatureId, userId, request.version());
        return updated == 1 ? signature(tenantId, userId, signatureId) : Optional.empty();
    }

    boolean archiveSignature(long tenantId, long userId, UUID signatureId, long version) {
        boolean archived = jdbc.update("""
                UPDATE mail_signatures
                   SET lifecycle_state = 'ARCHIVED', default_for_new = FALSE,
                       default_for_reply = FALSE, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND signature_id = ? AND owner_user_id = ?
                   AND signature_scope <> 'ORGANIZATION' AND lifecycle_state = 'ACTIVE'
                   AND version = ?
                """, userId, tenantId, signatureId, userId, version) == 1;
        if (archived) {
            jdbc.update("""
                    UPDATE mail_user_preferences
                       SET default_signature_id = NULL, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND user_id = ? AND default_signature_id = ?
                    """, userId, tenantId, userId, signatureId);
        }
        return archived;
    }

    Preferences preferences(long tenantId, long userId) {
        jdbc.update("""
                INSERT INTO mail_user_preferences (
                    tenant_id, user_id, created_by, updated_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id) DO NOTHING
                """, tenantId, userId, userId, userId);
        return jdbc.query("""
                SELECT density, remote_images, send_delay_seconds, keyboard_shortcuts,
                       notify_new_mail, notify_shared_assignment, notify_follow_up_due,
                       default_account_id, default_signature_id, version
                  FROM mail_user_preferences
                 WHERE tenant_id = ? AND user_id = ?
                """, (result, ignored) -> preferences(result), tenantId, userId).get(0);
    }

    Optional<Preferences> updatePreferences(
            long tenantId, long userId, PreferencesRequest request) {
        int updated = jdbc.update("""
                UPDATE mail_user_preferences
                   SET density = ?, remote_images = ?, send_delay_seconds = ?,
                       keyboard_shortcuts = ?, notify_new_mail = ?,
                       notify_shared_assignment = ?, notify_follow_up_due = ?,
                       default_account_id = ?, default_signature_id = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND version = ?
                """, request.density(), request.remoteImages(), request.sendDelaySeconds(),
                request.keyboardShortcuts(), request.notifyNewMail(),
                request.notifySharedAssignment(), request.notifyFollowUpDue(),
                request.defaultAccountId(), request.defaultSignatureId(), userId,
                tenantId, userId, request.version());
        return updated == 1 ? Optional.of(preferences(tenantId, userId)) : Optional.empty();
    }

    List<SavedView> savedViews(long tenantId, long userId) {
        return jdbc.query("""
                SELECT saved_view_id, display_name, filters::text, sort_order, is_default,
                       version, created_at, updated_at
                  FROM mail_saved_views
                 WHERE tenant_id = ? AND owner_user_id = ?
                 ORDER BY is_default DESC, sort_order, lower(display_name), saved_view_id
                """, (result, ignored) -> savedView(result), tenantId, userId);
    }

    Optional<SavedView> savedView(long tenantId, long userId, UUID savedViewId) {
        return jdbc.query("""
                SELECT saved_view_id, display_name, filters::text, sort_order, is_default,
                       version, created_at, updated_at
                  FROM mail_saved_views
                 WHERE tenant_id = ? AND owner_user_id = ? AND saved_view_id = ?
                """, (result, ignored) -> savedView(result),
                tenantId, userId, savedViewId).stream().findFirst();
    }

    void clearDefaultSavedView(long tenantId, long userId, UUID except) {
        jdbc.update("""
                UPDATE mail_saved_views
                   SET is_default = FALSE, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ?
                   AND saved_view_id <> ? AND is_default = TRUE
                """, userId, tenantId, userId, except);
    }

    SavedView createSavedView(long tenantId, long userId, SavedViewRequest request) {
        UUID id = UUID.randomUUID();
        boolean defaultView = Boolean.TRUE.equals(request.defaultView());
        if (defaultView) clearDefaultSavedView(tenantId, userId, id);
        jdbc.update("""
                INSERT INTO mail_saved_views (
                    saved_view_id, tenant_id, owner_user_id, display_name, filters,
                    sort_order, is_default, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, id, tenantId, userId, request.name().trim(),
                json.write(request.criteria()), request.sortOrder() == null ? 0 : request.sortOrder(),
                defaultView, userId, userId);
        return savedView(tenantId, userId, id).orElseThrow();
    }

    Optional<SavedView> updateSavedView(
            long tenantId, long userId, UUID savedViewId, SavedViewRequest request) {
        boolean defaultView = Boolean.TRUE.equals(request.defaultView());
        if (defaultView) clearDefaultSavedView(tenantId, userId, savedViewId);
        int updated = jdbc.update("""
                UPDATE mail_saved_views
                   SET display_name = ?, filters = ?::jsonb, sort_order = ?,
                       is_default = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND saved_view_id = ?
                   AND version = ?
                """, request.name().trim(), json.write(request.criteria()),
                request.sortOrder() == null ? 0 : request.sortOrder(), defaultView, userId,
                tenantId, userId, savedViewId, request.version());
        return updated == 1 ? savedView(tenantId, userId, savedViewId) : Optional.empty();
    }

    boolean deleteSavedView(long tenantId, long userId, UUID savedViewId, long version) {
        return jdbc.update("""
                DELETE FROM mail_saved_views
                 WHERE tenant_id = ? AND owner_user_id = ? AND saved_view_id = ? AND version = ?
                """, tenantId, userId, savedViewId, version) == 1;
    }

    List<FollowUp> followUps(long tenantId, long userId, String status) {
        return jdbc.query("""
                SELECT tracker.follow_up_id, tracker.thread_id, thread.subject,
                       thread.participants->0->>'name' AS participant_name,
                       thread.participants->0->>'email' AS participant_email,
                       tracker.expected_reply_at, tracker.time_zone, tracker.note,
                       CASE WHEN tracker.tracker_status = 'WAITING'
                                  AND tracker.expected_reply_at < CURRENT_TIMESTAMP
                            THEN 'OVERDUE' ELSE tracker.tracker_status END AS tracker_status,
                       tracker.last_checked_at, tracker.version
                  FROM mail_follow_up_trackers tracker
                  JOIN mail_threads thread
                    ON thread.tenant_id = tracker.tenant_id
                   AND thread.thread_id = tracker.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE tracker.tenant_id = ? AND tracker.owner_user_id = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                   AND (? = '' OR (CASE WHEN tracker.tracker_status = 'WAITING'
                                             AND tracker.expected_reply_at < CURRENT_TIMESTAMP
                                       THEN 'OVERDUE' ELSE tracker.tracker_status END) = ?)
                 ORDER BY tracker.expected_reply_at, tracker.follow_up_id
                """, (result, ignored) -> followUp(result),
                tenantId, userId, userId, userId, status, status);
    }

    Optional<FollowUp> followUp(long tenantId, long userId, UUID followUpId) {
        return jdbc.query("""
                SELECT tracker.follow_up_id, tracker.thread_id, thread.subject,
                       thread.participants->0->>'name' AS participant_name,
                       thread.participants->0->>'email' AS participant_email,
                       tracker.expected_reply_at, tracker.time_zone, tracker.note,
                       CASE WHEN tracker.tracker_status = 'WAITING'
                                  AND tracker.expected_reply_at < CURRENT_TIMESTAMP
                            THEN 'OVERDUE' ELSE tracker.tracker_status END AS tracker_status,
                       tracker.last_checked_at, tracker.version
                  FROM mail_follow_up_trackers tracker
                  JOIN mail_threads thread
                    ON thread.tenant_id = tracker.tenant_id
                   AND thread.thread_id = tracker.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE tracker.tenant_id = ? AND tracker.owner_user_id = ?
                   AND tracker.follow_up_id = ?
                """ + MailAccessSql.THREAD_ACCESS,
                (result, ignored) -> followUp(result),
                tenantId, userId, followUpId, userId, userId).stream().findFirst();
    }

    Optional<FollowUp> createFollowUp(
            long tenantId, long userId, UUID threadId, FollowUpRequest request) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO mail_follow_up_trackers (
                    follow_up_id, tenant_id, owner_user_id, thread_id,
                    expected_reply_at, time_zone, note, tracker_status,
                    created_by, updated_by)
                SELECT ?, thread.tenant_id, ?, thread.thread_id, ?, ?, ?, 'WAITING', ?, ?
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                ON CONFLICT (tenant_id, owner_user_id, thread_id) DO NOTHING
                """, id, userId, request.expectedReplyAt(), request.timeZone().trim(),
                nullable(request.note()), userId, userId, tenantId, threadId, userId, userId);
        return inserted == 1 ? followUp(tenantId, userId, id) : Optional.empty();
    }

    Optional<FollowUp> updateFollowUp(
            long tenantId, long userId, UUID followUpId, FollowUpRequest request) {
        int updated = jdbc.update("""
                UPDATE mail_follow_up_trackers
                   SET expected_reply_at = ?, time_zone = ?, note = ?,
                       tracker_status = 'WAITING', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND follow_up_id = ?
                   AND version = ? AND tracker_status <> 'CANCELLED'
                """, request.expectedReplyAt(), request.timeZone().trim(), nullable(request.note()),
                userId, tenantId, userId, followUpId, request.version());
        return updated == 1 ? followUp(tenantId, userId, followUpId) : Optional.empty();
    }

    boolean deleteFollowUp(long tenantId, long userId, UUID followUpId, long version) {
        return jdbc.update("""
                UPDATE mail_follow_up_trackers
                   SET tracker_status = 'CANCELLED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND follow_up_id = ?
                   AND version = ? AND tracker_status <> 'CANCELLED'
                """, userId, tenantId, userId, followUpId, version) == 1;
    }

}
