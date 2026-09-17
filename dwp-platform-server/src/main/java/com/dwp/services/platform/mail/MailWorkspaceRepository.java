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
class MailWorkspaceRepository {

    record AdvancedComposeCreated(UUID threadId, UUID deliveryId, boolean replayed) {
    }

    record AdvancedComposeCommand(
            UUID threadId, UUID deliveryId, UUID accountId, String requestFingerprint) {
    }

    record AttachmentContentReference(
            String storageReference,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256) {
    }

    record ComposeProviderContext(
            UUID accountId,
            MailTypes.ProviderType providerType,
            UUID connectionId,
            URI credentialReference,
            String mailDomain,
            String providerAccountReference) {
    }

    private record AttachmentAggregate(long count, long totalBytes) {
    }

    private record DeletedAttachment(String storageReference, UUID threadId) {
    }

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailWorkspaceRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
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

    Attachment createAttachment(
            long tenantId, long userId, UUID attachmentId, String storageReference,
            String fileName, String contentType, long sizeBytes, String checksum,
            String evidence) {
        jdbc.update("""
                INSERT INTO mail_compose_attachments (
                    attachment_id, tenant_id, uploader_user_id, storage_reference,
                    file_name, content_type, size_bytes, checksum_sha256,
                    scan_state, scan_evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?)
                """, attachmentId, tenantId, userId, storageReference, fileName,
                contentType, sizeBytes, checksum, evidence);
        return attachment(tenantId, userId, attachmentId).orElseThrow();
    }

    Optional<Attachment> attachment(long tenantId, long userId, UUID attachmentId) {
        return jdbc.query("""
                SELECT attachment_id, file_name, content_type, size_bytes, scan_state,
                       version, created_at
                  FROM mail_compose_attachments
                 WHERE tenant_id = ? AND uploader_user_id = ? AND attachment_id = ?
                """, (result, ignored) -> attachment(result),
                tenantId, userId, attachmentId).stream().findFirst();
    }

    Optional<AttachmentContentReference> visibleAttachment(
            long tenantId,
            long userId,
            UUID threadId,
            UUID messageId,
            UUID attachmentId) {
        return jdbc.query("""
                SELECT attachment.storage_reference, attachment.file_name,
                       attachment.content_type, attachment.size_bytes,
                       attachment.checksum_sha256
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                  JOIN LATERAL jsonb_array_elements(message.attachments) projected
                    ON projected ->> 'attachmentId' = ?::text
                  JOIN mail_compose_attachments attachment
                    ON attachment.tenant_id = message.tenant_id
                   AND attachment.thread_id = message.thread_id
                   AND attachment.attachment_id = ?
                   AND attachment.scan_state = 'READY'
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND message.message_id = ?
                """ + MailAccessSql.THREAD_ACCESS,
                (result, ignored) -> new AttachmentContentReference(
                        result.getString("storage_reference"),
                        result.getString("file_name"),
                        result.getString("content_type"),
                        result.getLong("size_bytes"),
                        result.getString("checksum_sha256")),
                attachmentId, attachmentId, tenantId, threadId, messageId,
                userId, userId).stream().findFirst();
    }

    Optional<String> deleteOwnedDraftAttachment(
            long tenantId, long userId, UUID attachmentId) {
        List<DeletedAttachment> deleted = jdbc.query("""
                DELETE FROM mail_compose_attachments attachment
                 WHERE attachment.tenant_id = ?
                   AND attachment.uploader_user_id = ?
                   AND attachment.attachment_id = ?
                   AND (
                       attachment.thread_id IS NULL
                       OR EXISTS (
                           SELECT 1
                             FROM mail_threads thread
                             JOIN mail_accounts account
                               ON account.tenant_id = thread.tenant_id
                              AND account.account_id = thread.account_id
                            WHERE thread.tenant_id = attachment.tenant_id
                              AND thread.thread_id = attachment.thread_id
                              AND thread.workflow_state = 'DRAFT'
                              AND account.account_kind = 'PERSONAL'
                              AND account.owner_user_id = ?))
                RETURNING attachment.storage_reference, attachment.thread_id
                """, (result, ignored) -> new DeletedAttachment(
                        result.getString("storage_reference"),
                        result.getObject("thread_id", UUID.class)),
                tenantId, userId, attachmentId, userId);
        if (deleted.isEmpty()) return Optional.empty();
        UUID threadId = deleted.get(0).threadId();
        if (threadId != null) {
            jdbc.update("""
                    UPDATE mail_draft_options
                       SET attachment_ids = attachment_ids - ?,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND owner_user_id = ? AND thread_id = ?
                    """, attachmentId.toString(), tenantId, userId, threadId);
        }
        return Optional.of(deleted.get(0).storageReference());
    }

    boolean attachmentsReady(long tenantId, long userId, List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) return true;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_compose_attachments
                 WHERE tenant_id = ? AND uploader_user_id = ?
                   AND attachment_id = ANY (?::uuid[])
                   AND scan_state = 'READY'
                   AND thread_id IS NULL
                """, Integer.class, tenantId, userId, attachmentIds.toArray(UUID[]::new));
        return count != null && count == attachmentIds.size();
    }

    boolean attachmentsReadyForThread(
            long tenantId, long userId, List<UUID> attachmentIds, UUID threadId) {
        if (attachmentIds.isEmpty()) return true;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_compose_attachments
                 WHERE tenant_id = ? AND uploader_user_id = ?
                   AND attachment_id = ANY (?::uuid[])
                   AND scan_state = 'READY'
                   AND (thread_id IS NULL OR thread_id = ?)
                """, Integer.class, tenantId, userId,
                attachmentIds.toArray(UUID[]::new), threadId);
        return count != null && count == attachmentIds.size();
    }

    boolean attachmentsWithinTotalSize(
            long tenantId,
            long userId,
            List<UUID> attachmentIds,
            UUID threadId,
            long maximumBytes) {
        if (attachmentIds.isEmpty()) return true;
        AttachmentAggregate aggregate = jdbc.queryForObject("""
                SELECT COUNT(*) AS attachment_count,
                       COALESCE(SUM(selected.size_bytes), 0) AS total_bytes
                  FROM (
                        SELECT attachment.size_bytes
                          FROM mail_compose_attachments attachment
                         WHERE attachment.tenant_id = ?
                           AND attachment.uploader_user_id = ?
                           AND attachment.attachment_id = ANY (?::uuid[])
                           AND attachment.scan_state = 'READY'
                           AND ((?::uuid IS NULL AND attachment.thread_id IS NULL)
                             OR (?::uuid IS NOT NULL AND
                                 (attachment.thread_id IS NULL OR attachment.thread_id = ?)))
                           FOR SHARE
                       ) selected
                """, (result, ignored) -> new AttachmentAggregate(
                result.getLong("attachment_count"), result.getLong("total_bytes")),
                tenantId, userId, attachmentIds.toArray(UUID[]::new),
                threadId, threadId, threadId);
        return aggregate != null
                && aggregate.count() == attachmentIds.size()
                && aggregate.totalBytes() <= maximumBytes;
    }

    List<Map<String, Object>> attachmentProjection(
            long tenantId, long userId, List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT attachment_id, file_name, content_type, size_bytes, checksum_sha256
                  FROM mail_compose_attachments
                 WHERE tenant_id = ? AND uploader_user_id = ?
                   AND attachment_id = ANY (?::uuid[]) AND scan_state = 'READY'
                 ORDER BY created_at, attachment_id
                """, (result, ignored) -> Map.of(
                        "attachmentId", result.getObject("attachment_id", UUID.class),
                        "fileName", result.getString("file_name"),
                        "contentType", result.getString("content_type"),
                        "sizeBytes", result.getLong("size_bytes"),
                        "checksumSha256", result.getString("checksum_sha256")),
                tenantId, userId, attachmentIds.toArray(UUID[]::new));
    }

    Optional<UUID> composeAccount(long tenantId, long userId, UUID requestedAccountId) {
        return jdbc.query("""
                SELECT account.account_id
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                  LEFT JOIN mail_user_preferences preference
                    ON preference.tenant_id = account.tenant_id
                   AND preference.user_id = ?
                 WHERE account.tenant_id = ?
                   AND (?::uuid IS NULL OR account.account_id = ?::uuid)
                   AND (?::uuid IS NOT NULL
                        OR preference.default_account_id IS NULL
                        OR account.account_id = preference.default_account_id)
                   AND account.connection_state = 'ACTIVE'
                   AND connection.connection_state = 'ACTIVE'
                """ + MailAccessSql.ACCOUNT_SEND_ACCESS + """
                 ORDER BY CASE WHEN account.account_id = ?::uuid THEN 0
                               WHEN ?::uuid IS NULL
                                AND account.account_id = preference.default_account_id THEN 1
                               WHEN account.is_default THEN 2 ELSE 3 END,
                          account.account_kind, account.account_id
                 LIMIT 1
                """, (result, ignored) -> result.getObject(1, UUID.class),
                userId, tenantId, requestedAccountId, requestedAccountId,
                requestedAccountId,
                userId, userId, requestedAccountId, requestedAccountId).stream().findFirst();
    }

    Optional<ComposeProviderContext> composeProviderContext(
            long tenantId, long userId, UUID accountId) {
        return jdbc.query("""
                SELECT account.account_id, connection.provider_type,
                       connection.connection_id, connection.credential_ref,
                       connection.mail_domain, account.provider_account_ref
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE account.tenant_id = ? AND account.account_id = ?
                   AND account.connection_state = 'ACTIVE'
                   AND connection.connection_state = 'ACTIVE'
                """ + MailAccessSql.ACCOUNT_ACCESS,
                (result, ignored) -> new ComposeProviderContext(
                        result.getObject("account_id", UUID.class),
                        MailTypes.ProviderType.valueOf(result.getString("provider_type")),
                        result.getObject("connection_id", UUID.class),
                        uri(result.getString("credential_ref")),
                        result.getString("mail_domain"),
                        result.getString("provider_account_ref")),
                tenantId, accountId, userId, userId).stream().findFirst();
    }

    Optional<ComposeProviderContext> defaultPersonalComposeProviderContext(
            long tenantId, long userId) {
        return jdbc.query("""
                SELECT account.account_id, connection.provider_type,
                       connection.connection_id, connection.credential_ref,
                       connection.mail_domain, account.provider_account_ref
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                  LEFT JOIN mail_user_preferences preference
                    ON preference.tenant_id = account.tenant_id
                   AND preference.user_id = ?
                 WHERE account.tenant_id = ? AND account.owner_user_id = ?
                   AND account.account_kind = 'PERSONAL'
                   AND account.connection_state = 'ACTIVE'
                   AND connection.connection_state = 'ACTIVE'
                 ORDER BY CASE
                    WHEN account.account_id = preference.default_account_id THEN 0
                    WHEN account.is_default THEN 1 ELSE 2 END,
                    account.account_id
                 LIMIT 1
                """, (result, ignored) -> new ComposeProviderContext(
                        result.getObject("account_id", UUID.class),
                        MailTypes.ProviderType.valueOf(result.getString("provider_type")),
                        result.getObject("connection_id", UUID.class),
                        uri(result.getString("credential_ref")),
                        result.getString("mail_domain"),
                        result.getString("provider_account_ref")),
                userId, tenantId, userId).stream().findFirst();
    }

    Optional<String> composeAccountDomain(
            long tenantId, long userId, UUID accountId) {
        return jdbc.query("""
                SELECT LOWER(SPLIT_PART(account.email_address, '@', 2)) AS sender_domain
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE account.tenant_id = ? AND account.account_id = ?
                   AND account.connection_state = 'ACTIVE'
                   AND connection.connection_state = 'ACTIVE'
                """ + MailAccessSql.ACCOUNT_SEND_ACCESS,
                (result, ignored) -> result.getString("sender_domain"),
                tenantId, accountId, userId, userId).stream()
                .filter(domain -> domain != null && !domain.isBlank())
                .findFirst();
    }

    Optional<MailConnectorPort.SenderMode> composeSenderMode(
            long tenantId, long userId, UUID accountId) {
        return authorizedSenderMode(tenantId, accountId, userId);
    }

    private Optional<MailConnectorPort.SenderMode> authorizedSenderMode(
            long tenantId, UUID accountId, long userId) {
        return jdbc.query("""
                SELECT CASE
                           WHEN account.account_kind = 'PERSONAL' THEN 'ACCOUNT'
                           WHEN access_grant.can_send_as THEN 'SEND_AS'
                           ELSE 'SEND_ON_BEHALF'
                       END AS sender_mode
                  FROM mail_accounts account
                  LEFT JOIN mail_tenant_policies policy
                    ON policy.tenant_id = account.tenant_id
                  LEFT JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = account.tenant_id
                   AND inbox.account_id = account.account_id
                   AND inbox.lifecycle_state = 'ACTIVE'
                  LEFT JOIN mail_shared_inbox_members membership
                    ON membership.tenant_id = inbox.tenant_id
                   AND membership.account_id = inbox.account_id
                   AND membership.shared_inbox_id = inbox.shared_inbox_id
                   AND membership.user_id = ?
                   AND membership.lifecycle_state = 'ACTIVE'
                  LEFT JOIN mail_shared_inbox_access_grants access_grant
                    ON access_grant.tenant_id = membership.tenant_id
                   AND access_grant.shared_inbox_id = membership.shared_inbox_id
                   AND access_grant.user_id = membership.user_id
                   AND access_grant.member_state = 'ACTIVE'
                 WHERE account.tenant_id = ? AND account.account_id = ?
                   AND account.connection_state = 'ACTIVE'
                   AND (
                       (account.account_kind = 'PERSONAL' AND account.owner_user_id = ?)
                       OR (
                           account.account_kind = 'SHARED'
                           AND policy.allow_shared_inboxes = TRUE
                           AND membership.user_id IS NOT NULL
                           AND access_grant.can_read = TRUE
                           AND (access_grant.can_send_as = TRUE
                                OR access_grant.can_send_on_behalf = TRUE)
                           AND (access_grant.expires_at IS NULL
                                OR access_grant.expires_at > CURRENT_TIMESTAMP)
                       )
                   )
                """, (result, ignored) -> MailConnectorPort.SenderMode.valueOf(
                        result.getString("sender_mode")),
                userId, tenantId, accountId, userId).stream().findFirst();
    }

    private URI uri(String value) {
        return value == null || value.isBlank() ? null : URI.create(value);
    }

    void lockAdvancedComposeCommand(long tenantId, long userId, UUID idempotencyKey) {
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                tenantId + ":" + userId + ":" + idempotencyKey);
    }

    Optional<AdvancedComposeCreated> advancedComposeReplay(
            long tenantId, long userId, UUID idempotencyKey, String fingerprint) {
        return jdbc.query("""
                SELECT delivery.thread_id, delivery.delivery_id, delivery.request_fingerprint
                  FROM mail_delivery_outbox delivery
                 WHERE delivery.tenant_id = ? AND delivery.created_by = ?
                   AND delivery.idempotency_key = ?
                """, (result, ignored) -> {
                    if (!fingerprint.equals(result.getString("request_fingerprint"))) {
                        return new AdvancedComposeCreated(null, null, true);
                    }
                    return new AdvancedComposeCreated(
                            result.getObject("thread_id", UUID.class),
                            result.getObject("delivery_id", UUID.class), true);
                }, tenantId, userId, idempotencyKey).stream().findFirst();
    }

    Optional<AdvancedComposeCommand> advancedComposeCommand(
            long tenantId, long userId, UUID idempotencyKey) {
        return jdbc.query("""
                SELECT delivery.thread_id, delivery.delivery_id,
                       thread.account_id, delivery.request_fingerprint
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread
                    ON thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                 WHERE delivery.tenant_id = ? AND delivery.created_by = ?
                   AND delivery.idempotency_key = ?
                """, (result, ignored) -> new AdvancedComposeCommand(
                result.getObject("thread_id", UUID.class),
                result.getObject("delivery_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getString("request_fingerprint")),
                tenantId, userId, idempotencyKey).stream().findFirst();
    }

    AdvancedComposeCreated createAdvancedCompose(
            long tenantId,
            long userId,
            UUID accountId,
            List<Recipient> recipients,
            String subject,
            String body,
            BodyFormat bodyFormat,
            List<UUID> attachmentIds,
            OffsetDateTime scheduledAt,
            UUID idempotencyKey,
            String fingerprint,
            String correlationId) {
        return createAdvancedCompose(
                tenantId, userId, accountId, recipients, subject, body, bodyFormat,
                attachmentIds, scheduledAt, MailTypes.Classification.INTERNAL, false,
                idempotencyKey, fingerprint, correlationId);
    }

    AdvancedComposeCreated createAdvancedCompose(
            long tenantId,
            long userId,
            UUID accountId,
            List<Recipient> recipients,
            String subject,
            String body,
            BodyFormat bodyFormat,
            List<UUID> attachmentIds,
            OffsetDateTime scheduledAt,
            MailTypes.Classification classification,
            boolean externalRecipient,
            UUID idempotencyKey,
            String fingerprint,
            String correlationId) {
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String recipientJson = json.write(recipients.stream().map(recipient -> Map.of(
                "type", recipient.type().name(),
                "name", recipient.name() == null ? "" : recipient.name(),
                "email", recipient.email().toLowerCase(Locale.ROOT))).toList());
        String participantJson = json.write(recipients.stream().map(recipient -> Map.of(
                "type", recipient.type().name(),
                "name", recipient.name() == null ? recipient.email() : recipient.name(),
                "email", recipient.email().toLowerCase(Locale.ROOT))).toList());
        String attachmentJson = json.write(attachmentProjection(tenantId, userId, attachmentIds));
        String preview = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (preview.length() > 1200) preview = preview.substring(0, 1197) + "...";
        String providerReference = "dwp:advanced:" + idempotencyKey;
        int threadInserted = jdbc.update("""
                INSERT INTO mail_threads (
                    thread_id, tenant_id, account_id, folder_id, provider_thread_ref,
                    compose_request_fingerprint, subject, preview, participants,
                    latest_message_at, unread, importance, triage_lane, workflow_state,
                    has_attachments, external_sender, classification, message_count,
                    created_by, updated_by)
                SELECT ?, account.tenant_id, account.account_id, folder.folder_id, ?, ?,
                       ?, ?, ?::jsonb, CURRENT_TIMESTAMP, FALSE, 'NORMAL', 'UPDATES', 'OPEN',
                       ?, ?, ?, 1, ?, ?
                  FROM mail_accounts account
                  JOIN mail_folders folder
                    ON folder.tenant_id = account.tenant_id
                   AND folder.account_id = account.account_id
                   AND folder.folder_type = 'SENT'
                   AND folder.lifecycle_state = 'ACTIVE'
                 WHERE account.tenant_id = ? AND account.account_id = ?
                   AND account.connection_state = 'ACTIVE'
                """ + MailAccessSql.ACCOUNT_SEND_ACCESS,
                threadId, providerReference, fingerprint, subject.trim(), preview,
                participantJson, !attachmentIds.isEmpty(), externalRecipient,
                classification.name(), userId, userId,
                tenantId, accountId, userId, userId);
        if (threadInserted != 1) return null;
        jdbc.update("""
                INSERT INTO mail_messages (
                    message_id, tenant_id, thread_id, provider_message_ref,
                    sender_email, sender_name, recipients, message_direction,
                    body_format, body_content, attachments, sent_at, created_by)
                SELECT ?, thread.tenant_id, thread.thread_id, ?,
                       account.email_address, account.display_name, ?::jsonb, 'OUTBOUND',
                       ?, ?, ?::jsonb, CURRENT_TIMESTAMP, ?
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """, messageId, providerReference + ":message", recipientJson,
                bodyFormat.name(), body, attachmentJson, userId, tenantId, threadId);
        jdbc.update("""
                INSERT INTO mail_delivery_outbox (
                    delivery_id, tenant_id, thread_id, message_id, idempotency_key,
                    request_fingerprint, delivery_status, next_attempt_at,
                    correlation_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', COALESCE(?, CURRENT_TIMESTAMP), ?, ?)
                """, deliveryId, tenantId, threadId, messageId, idempotencyKey,
                fingerprint, scheduledAt, correlationId, userId);
        if (!attachmentIds.isEmpty()) {
            jdbc.update("""
                    UPDATE mail_compose_attachments
                       SET thread_id = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND uploader_user_id = ?
                       AND attachment_id = ANY (?::uuid[]) AND scan_state = 'READY'
                       AND thread_id IS NULL
                    """, threadId, tenantId, userId, attachmentIds.toArray(UUID[]::new));
        }
        return new AdvancedComposeCreated(threadId, deliveryId, false);
    }

    AdvancedComposeCreated sendAdvancedDraft(
            long tenantId,
            long userId,
            UUID threadId,
            long expectedVersion,
            UUID accountId,
            List<Recipient> recipients,
            String subject,
            String body,
            BodyFormat bodyFormat,
            List<UUID> attachmentIds,
            OffsetDateTime scheduledAt,
            MailTypes.Classification classification,
            boolean externalRecipient,
            UUID idempotencyKey,
            String fingerprint,
            String correlationId) {
        UUID deliveryId = UUID.randomUUID();
        String recipientJson = json.write(recipients.stream().map(recipient -> Map.of(
                "type", recipient.type().name(),
                "name", recipient.name() == null ? "" : recipient.name(),
                "email", recipient.email().toLowerCase(Locale.ROOT))).toList());
        String participantJson = json.write(recipients.stream().map(recipient -> Map.of(
                "type", recipient.type().name(),
                "name", recipient.name() == null ? recipient.email() : recipient.name(),
                "email", recipient.email().toLowerCase(Locale.ROOT))).toList());
        String attachmentJson = json.write(attachmentProjection(tenantId, userId, attachmentIds));
        String preview = body.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (preview.length() > 1200) preview = preview.substring(0, 1197) + "...";
        int updated = jdbc.update("""
                UPDATE mail_threads thread
                   SET account_id = account.account_id,
                       folder_id = sent.folder_id,
                       provider_thread_ref = ?, compose_request_fingerprint = ?,
                       subject = ?, preview = ?, participants = ?::jsonb,
                       latest_message_at = CURRENT_TIMESTAMP, workflow_state = 'OPEN',
                       has_attachments = ?, external_sender = ?, classification = ?,
                       message_count = 1,
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts current_account,
                       mail_accounts account,
                       mail_folders sent
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND thread.version = ? AND thread.workflow_state = 'DRAFT'
                   AND current_account.tenant_id = thread.tenant_id
                   AND current_account.account_id = thread.account_id
                   AND current_account.owner_user_id = ?
                   AND account.tenant_id = thread.tenant_id
                   AND account.account_id = ?
                   AND account.connection_state = 'ACTIVE'
                   AND sent.tenant_id = account.tenant_id
                   AND sent.account_id = account.account_id
                   AND sent.folder_type = 'SENT' AND sent.lifecycle_state = 'ACTIVE'
                """ + MailAccessSql.ACCOUNT_SEND_ACCESS,
                "dwp:advanced:" + idempotencyKey, fingerprint,
                subject.trim(), preview, participantJson, !attachmentIds.isEmpty(),
                externalRecipient, classification.name(), userId,
                tenantId, threadId, expectedVersion, userId, accountId, userId, userId);
        if (updated != 1) return null;
        List<UUID> messages = jdbc.query("""
                UPDATE mail_messages
                   SET provider_message_ref = ?, recipients = ?::jsonb,
                       message_direction = 'OUTBOUND', body_format = ?, body_content = ?,
                       attachments = ?::jsonb, sent_at = CURRENT_TIMESTAMP
                 WHERE message_id = (
                       SELECT message_id FROM mail_messages
                        WHERE tenant_id = ? AND thread_id = ? AND message_direction = 'DRAFT'
                        ORDER BY sent_at, message_id LIMIT 1)
                RETURNING message_id
                """, (result, ignored) -> result.getObject(1, UUID.class),
                "dwp:advanced:" + idempotencyKey + ":message", recipientJson,
                bodyFormat.name(), body, attachmentJson, tenantId, threadId);
        if (messages.size() != 1) {
            throw new IllegalStateException("The draft message projection is missing.");
        }
        jdbc.update("""
                INSERT INTO mail_delivery_outbox (
                    delivery_id, tenant_id, thread_id, message_id, idempotency_key,
                    request_fingerprint, delivery_status, next_attempt_at,
                    correlation_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', COALESCE(?, CURRENT_TIMESTAMP), ?, ?)
                """, deliveryId, tenantId, threadId, messages.get(0), idempotencyKey,
                fingerprint, scheduledAt, correlationId, userId);
        reconcileDraftAttachments(tenantId, userId, threadId, attachmentIds);
        return new AdvancedComposeCreated(threadId, deliveryId, false);
    }

    void saveDraftOptions(
            long tenantId, long userId, UUID threadId, ComposeOptions options) {
        String recipients = json.write(options.recipients());
        String attachments = json.write(options.attachmentIds());
        jdbc.update("""
                INSERT INTO mail_draft_options (
                    tenant_id, thread_id, owner_user_id, account_id, recipients, body_format,
                    attachment_ids, scheduled_at, time_zone, template_id, signature_id)
                SELECT thread.tenant_id, thread.thread_id, ?, ?, ?::jsonb, ?, ?::jsonb,
                       ?, ?, ?, ?
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND thread.created_by = ? AND thread.workflow_state = 'DRAFT'
                ON CONFLICT (thread_id) DO UPDATE SET
                    account_id = EXCLUDED.account_id,
                    recipients = EXCLUDED.recipients,
                    body_format = EXCLUDED.body_format,
                    attachment_ids = EXCLUDED.attachment_ids,
                    scheduled_at = EXCLUDED.scheduled_at,
                    time_zone = EXCLUDED.time_zone,
                    template_id = EXCLUDED.template_id,
                    signature_id = EXCLUDED.signature_id,
                    version = mail_draft_options.version + 1,
                    updated_at = CURRENT_TIMESTAMP
                """, userId, options.accountId(), recipients, options.bodyFormat().name(),
                attachments, options.scheduledAt(), options.timeZone(), options.templateId(),
                options.signatureId(), tenantId, threadId, userId);
        reconcileDraftAttachments(tenantId, userId, threadId, options.attachmentIds());
    }

    Optional<ComposeOptions> draftOptions(long tenantId, long userId, UUID threadId) {
        return jdbc.query("""
                SELECT account_id, recipients::text, body_format, attachment_ids::text,
                       scheduled_at, time_zone, template_id, signature_id
                  FROM mail_draft_options
                 WHERE tenant_id = ? AND owner_user_id = ? AND thread_id = ?
                """, (result, ignored) -> new ComposeOptions(
                        result.getObject("account_id", UUID.class),
                        recipients(result.getString("recipients")),
                        BodyFormat.valueOf(result.getString("body_format")),
                        json.stringList(result.getString("attachment_ids")).stream()
                                .map(UUID::fromString).toList(),
                        result.getObject("scheduled_at", OffsetDateTime.class),
                        result.getString("time_zone"),
                        result.getObject("template_id", UUID.class),
                        result.getObject("signature_id", UUID.class)),
                tenantId, userId, threadId).stream().findFirst();
    }

    List<Attachment> draftAttachments(long tenantId, long userId, UUID threadId) {
        return jdbc.query("""
                SELECT attachment_id, file_name, content_type, size_bytes, scan_state,
                       version, created_at
                  FROM mail_compose_attachments
                 WHERE tenant_id = ? AND uploader_user_id = ? AND thread_id = ?
                 ORDER BY created_at, attachment_id
                """, (result, ignored) -> attachment(result), tenantId, userId, threadId);
    }

    List<DeliverySummary> deliveries(
            long tenantId, long userId, String bucket, int page, int pageSize) {
        return jdbc.query(deliverySelect() + """
                   AND (%s)
                 ORDER BY delivery.created_at DESC, delivery.delivery_id
                 LIMIT ? OFFSET ?
                """.formatted(bucketPredicate(bucket)),
                (result, ignored) -> deliverySummary(result, userId),
                tenantId, userId, userId, pageSize, page * pageSize);
    }

    long deliveryCount(long tenantId, long userId, String bucket) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread
                    ON thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE delivery.tenant_id = ?
                """ + MailAccessSql.THREAD_ACCESS + " AND (" + bucketPredicate(bucket) + ")",
                Long.class, tenantId, userId, userId);
        return count == null ? 0 : count;
    }

    Optional<DeliveryReceipt> delivery(long tenantId, long userId, UUID deliveryId) {
        return jdbc.query(deliverySelect() + " AND delivery.delivery_id = ?",
                (result, ignored) -> deliveryReceipt(result, userId),
                tenantId, userId, userId, deliveryId).stream().findFirst();
    }

    boolean rescheduleDelivery(
            long tenantId, long userId, UUID deliveryId, OffsetDateTime scheduledAt, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox delivery
                   SET next_attempt_at = ?, version = delivery.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM mail_threads thread, mail_accounts account
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                   AND delivery.delivery_status IN ('QUEUED', 'RETRY_WAIT')
                   AND delivery.accepted_at IS NULL
                   AND delivery.provider_message_ref IS NULL
                   AND delivery.provider_thread_ref IS NULL
                   AND delivery.lease_owner IS NULL AND delivery.lease_expires_at IS NULL
                   AND delivery.version = ?
                   AND thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                """ + MailAccessSql.THREAD_SEND_ACCESS,
                scheduledAt, tenantId, deliveryId, version, userId, userId) == 1;
    }

    boolean cancelDelivery(long tenantId, long userId, UUID deliveryId, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox delivery
                   SET delivery_status = 'CANCELLED', version = delivery.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM mail_threads thread, mail_accounts account
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                   AND delivery.delivery_status IN ('QUEUED', 'RETRY_WAIT')
                   AND delivery.accepted_at IS NULL
                   AND delivery.provider_message_ref IS NULL
                   AND delivery.provider_thread_ref IS NULL
                   AND delivery.lease_owner IS NULL AND delivery.lease_expires_at IS NULL
                   AND delivery.version = ?
                   AND thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                """ + MailAccessSql.THREAD_SEND_ACCESS,
                tenantId, deliveryId, version, userId, userId) == 1;
    }

    boolean reconcileSandboxDelivery(
            long tenantId, long userId, UUID deliveryId, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox delivery
                   SET delivery_status = 'QUEUED', attempt_count = 0,
                       next_attempt_at = CURRENT_TIMESTAMP, last_error_code = NULL,
                       lease_owner = NULL, lease_expires_at = NULL,
                       version = delivery.version + 1, updated_at = CURRENT_TIMESTAMP
                  FROM mail_threads thread, mail_accounts account,
                       mail_provider_connections connection
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                   AND delivery.version = ?
                   AND delivery.accepted_at IS NULL
                   AND delivery.provider_message_ref IS NULL
                   AND delivery.provider_thread_ref IS NULL
                   AND (
                       (delivery.delivery_status = 'LEASED'
                        AND delivery.lease_expires_at < CURRENT_TIMESTAMP)
                       OR
                       (delivery.delivery_status = 'FAILED'
                        AND delivery.last_error_code = 'MAIL_PROVIDER_RESULT_UNKNOWN'
                        AND delivery.lease_owner IS NULL
                        AND delivery.lease_expires_at IS NULL)
                   )
                   AND thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                   AND connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                   AND connection.provider_type = 'DWP_SANDBOX'
                """ + MailAccessSql.THREAD_SEND_ACCESS,
                tenantId, deliveryId, version, userId, userId) == 1;
    }

    boolean retryDelivery(long tenantId, long userId, UUID deliveryId, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox delivery
                   SET delivery_status = 'RETRY_WAIT', next_attempt_at = CURRENT_TIMESTAMP,
                       last_error_code = NULL, version = delivery.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM mail_threads thread, mail_accounts account
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                   AND delivery.version = ? AND delivery.delivery_status = 'FAILED'
                   AND delivery.accepted_at IS NULL
                   AND delivery.provider_message_ref IS NULL
                   AND delivery.provider_thread_ref IS NULL
                   AND delivery.lease_owner IS NULL AND delivery.lease_expires_at IS NULL
                   AND delivery.request_fingerprint IS NOT NULL
                   AND COALESCE(delivery.last_error_code, '') <> 'MAIL_PROVIDER_RESULT_UNKNOWN'
                   AND thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                """ + MailAccessSql.THREAD_SEND_ACCESS,
                tenantId, deliveryId, version, userId, userId) == 1;
    }

    void audit(
            long tenantId, long userId, String action, String targetType, String targetId,
            String correlationId, Map<String, Object> before, Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO mail_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action, target_type,
                    target_id, correlation_id, before_snapshot, after_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, UUID.randomUUID(), tenantId, userId, action, targetType, targetId,
                correlationId, json.write(before), json.write(after));
    }

    private String deliverySelect() {
        return """
                SELECT delivery.delivery_id, delivery.delivery_id AS receipt_id,
                       delivery.thread_id, thread.subject,
                       account.display_name AS account_name, account.account_kind,
                       delivery.created_at AS requested_at, delivery.next_attempt_at AS scheduled_at,
                       delivery.delivery_status, delivery.attempt_count, delivery.accepted_at,
                       delivery.last_error_code, delivery.correlation_id,
                       delivery.lease_owner, delivery.lease_expires_at,
                       delivery.provider_message_ref, delivery.provider_thread_ref,
                       delivery.request_fingerprint,
                       connection.provider_type,
                       delivery.updated_at, delivery.version, delivery.created_by,
                       message.recipients::text AS recipients,
                       EXISTS (SELECT 1 FROM mail_group_recipient_snapshots snapshot
                                WHERE snapshot.tenant_id = delivery.tenant_id
                                  AND snapshot.delivery_id = delivery.delivery_id) AS group_delivery
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread
                    ON thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                  JOIN mail_messages message
                    ON message.tenant_id = delivery.tenant_id
                   AND message.message_id = delivery.message_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE delivery.tenant_id = ?
                """ + MailAccessSql.THREAD_ACCESS;
    }

    private String bucketPredicate(String bucket) {
        return switch (bucket) {
            case "SCHEDULED" -> "delivery.delivery_status IN ('QUEUED','RETRY_WAIT') "
                    + "AND delivery.next_attempt_at > CURRENT_TIMESTAMP";
            case "PROCESSING" -> "delivery.delivery_status IN ('QUEUED','RETRY_WAIT','LEASED') "
                    + "AND delivery.next_attempt_at <= CURRENT_TIMESTAMP";
            case "COMPLETED" -> "delivery.delivery_status = 'DELIVERED'";
            case "ATTENTION" -> "delivery.delivery_status IN ('FAILED','CANCELLED')";
            default -> "TRUE";
        };
    }

    private Template template(ResultSet result, long userId) throws SQLException {
        AssetScope scope = AssetScope.valueOf(result.getString("template_scope"));
        return new Template(
                result.getObject("template_id", UUID.class), result.getString("display_name"),
                result.getString("subject_template"), result.getString("body_content"),
                BodyFormat.valueOf(result.getString("body_format")), scope,
                result.getObject("account_id", UUID.class),
                scope != AssetScope.ORGANIZATION && result.getLong("owner_user_id") == userId,
                result.getString("mandatory_content"),
                result.getString("publication_state"),
                result.getInt("publication_version"),
                "ACTIVE".equals(result.getString("lifecycle_state")), result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private Signature signature(ResultSet result, long userId) throws SQLException {
        AssetScope scope = AssetScope.valueOf(result.getString("signature_scope"));
        return new Signature(
                result.getObject("signature_id", UUID.class), result.getString("display_name"),
                result.getString("body_content"),
                BodyFormat.valueOf(result.getString("body_format")), scope,
                result.getObject("account_id", UUID.class), result.getBoolean("default_for_new"),
                result.getBoolean("default_for_reply"),
                scope != AssetScope.ORGANIZATION && result.getLong("owner_user_id") == userId,
                result.getString("mandatory_content"),
                result.getString("publication_state"),
                result.getInt("publication_version"),
                "ACTIVE".equals(result.getString("lifecycle_state")), result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private Preferences preferences(ResultSet result) throws SQLException {
        return new Preferences(
                result.getString("density"), result.getString("remote_images"),
                result.getInt("send_delay_seconds"), result.getBoolean("keyboard_shortcuts"),
                result.getBoolean("notify_new_mail"),
                result.getBoolean("notify_shared_assignment"),
                result.getBoolean("notify_follow_up_due"),
                result.getObject("default_account_id", UUID.class),
                result.getObject("default_signature_id", UUID.class), Map.of(),
                result.getLong("version"));
    }

    private SavedView savedView(ResultSet result) throws SQLException {
        Map<String, Object> filters = json.map(result.getString("filters"));
        return new SavedView(
                result.getObject("saved_view_id", UUID.class), result.getString("display_name"),
                criteria(filters), result.getInt("sort_order"), result.getBoolean("is_default"),
                result.getLong("version"), result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private SearchCriteria criteria(Map<String, Object> value) {
        return new SearchCriteria(
                text(value.get("query")), uuid(value.get("accountId")), text(value.get("scope")),
                text(value.get("from")), text(value.get("to")), date(value.get("dateFrom")),
                date(value.get("dateTo")), bool(value.get("unread")),
                bool(value.get("needsReply")), bool(value.get("hasAttachment")),
                uuid(value.get("folderId")), text(value.get("state")), text(value.get("lane")));
    }

    private FollowUp followUp(ResultSet result) throws SQLException {
        return new FollowUp(
                result.getObject("follow_up_id", UUID.class),
                result.getObject("thread_id", UUID.class), result.getString("subject"),
                result.getString("participant_name"), result.getString("participant_email"),
                result.getObject("expected_reply_at", OffsetDateTime.class),
                result.getString("time_zone"), result.getString("note"),
                result.getString("tracker_status"),
                result.getObject("last_checked_at", OffsetDateTime.class), result.getLong("version"));
    }

    private Attachment attachment(ResultSet result) throws SQLException {
        return new Attachment(
                result.getObject("attachment_id", UUID.class), result.getString("file_name"),
                result.getString("content_type"), result.getLong("size_bytes"),
                result.getString("scan_state"), result.getLong("version"),
                result.getObject("created_at", OffsetDateTime.class));
    }

    private DeliverySummary deliverySummary(ResultSet result, long userId) throws SQLException {
        List<Recipient> recipients = visibleDeliveryRecipients(result, userId);
        return new DeliverySummary(
                result.getObject("delivery_id", UUID.class),
                result.getObject("receipt_id", UUID.class),
                result.getObject("thread_id", UUID.class), result.getString("subject"),
                recipientSummary(recipients), result.getString("account_name"),
                result.getBoolean("group_delivery") ? "GROUP" : "PERSONAL",
                result.getObject("requested_at", OffsetDateTime.class),
                result.getObject("scheduled_at", OffsetDateTime.class), deliveryState(result),
                reschedulable(result), cancellable(result), reconcilable(result),
                result.getLong("version"));
    }

    private DeliveryReceipt deliveryReceipt(ResultSet result, long userId) throws SQLException {
        DeliverySummary summary = deliverySummary(result, userId);
        List<DeliveryTimeline> timeline = new ArrayList<>();
        timeline.add(new DeliveryTimeline("QUEUED",
                result.getObject("requested_at", OffsetDateTime.class),
                "Delivery request accepted", "DWP_OUTBOX", "VERIFIED", null));
        if ("SENDING".equals(summary.state())) {
            timeline.add(new DeliveryTimeline("SENDING",
                    result.getObject("updated_at", OffsetDateTime.class),
                    "Provider submission in progress", "DWP_WORKER", "REPORTED", null));
        }
        OffsetDateTime accepted = result.getObject("accepted_at", OffsetDateTime.class);
        if (accepted != null) {
            timeline.add(new DeliveryTimeline("ACCEPTED", accepted,
                    "Provider accepted the message", "PROVIDER_RECEIPT", "VERIFIED", null));
        }
        String error = result.getString("last_error_code");
        if (error != null) {
            String attentionState = deliveryAttentionState(summary.state());
            timeline.add(new DeliveryTimeline(attentionState,
                    result.getObject("updated_at", OffsetDateTime.class),
                    "UNKNOWN".equals(attentionState)
                            ? "Provider outcome is unknown"
                            : "Delivery requires attention",
                    "DWP_OUTBOX", "VERIFIED", error));
        }
        String eligibility = retryEligible(result) ? "ELIGIBLE" : "INELIGIBLE";
        if ("UNKNOWN".equals(summary.state())) eligibility = "UNKNOWN";
        return new DeliveryReceipt(
                summary.deliveryId(), summary.receiptId(), summary.threadId(), summary.subject(),
                summary.recipientSummary(), summary.accountName(), summary.kind(),
                summary.requestedAt(), summary.scheduledAt(), summary.state(),
                summary.canReschedule(), summary.canCancel(), summary.canReconcile(),
                summary.version(), visibleDeliveryRecipients(result, userId), timeline,
                result.getObject("updated_at", OffsetDateTime.class),
                List.of(Map.of(
                        "source", "mail_delivery_outbox",
                        "correlationId", value(result.getString("correlation_id")),
                        "attemptCount", result.getInt("attempt_count"))), eligibility);
    }

    private String deliveryState(ResultSet result) throws SQLException {
        String status = result.getString("delivery_status");
        OffsetDateTime scheduledAt = result.getObject("scheduled_at", OffsetDateTime.class);
        OffsetDateTime leaseExpiresAt = result.getObject(
                "lease_expires_at", OffsetDateTime.class);
        if ("MAIL_PROVIDER_RESULT_UNKNOWN".equals(result.getString("last_error_code"))) {
            return "UNKNOWN";
        }
        if ("LEASED".equals(status) && leaseExpiresAt != null
                && leaseExpiresAt.isBefore(OffsetDateTime.now())) {
            return "UNKNOWN";
        }
        return switch (status) {
            case "QUEUED", "RETRY_WAIT" -> scheduledAt != null
                    && scheduledAt.isAfter(OffsetDateTime.now()) ? "SCHEDULED" : "QUEUED";
            case "LEASED" -> "SENDING";
            case "DELIVERED" -> "ACCEPTED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    static String deliveryAttentionState(String summaryState) {
        return "UNKNOWN".equals(summaryState) ? "UNKNOWN" : "FAILED";
    }

    private boolean reschedulable(ResultSet result) throws SQLException {
        return mutableQueuedDelivery(result);
    }

    private boolean cancellable(ResultSet result) throws SQLException {
        return mutableQueuedDelivery(result);
    }

    private boolean reconcilable(ResultSet result) throws SQLException {
        if (!"DWP_SANDBOX".equals(result.getString("provider_type"))
                || result.getObject("accepted_at") != null
                || result.getString("provider_message_ref") != null
                || result.getString("provider_thread_ref") != null) {
            return false;
        }
        String status = result.getString("delivery_status");
        OffsetDateTime leaseExpiresAt = result.getObject(
                "lease_expires_at", OffsetDateTime.class);
        return ("LEASED".equals(status) && leaseExpiresAt != null
                    && leaseExpiresAt.isBefore(OffsetDateTime.now()))
                || ("FAILED".equals(status)
                    && "MAIL_PROVIDER_RESULT_UNKNOWN".equals(
                            result.getString("last_error_code"))
                    && result.getString("lease_owner") == null
                    && leaseExpiresAt == null);
    }

    private boolean retryEligible(ResultSet result) throws SQLException {
        return "FAILED".equals(result.getString("delivery_status"))
                && result.getObject("accepted_at") == null
                && result.getString("provider_message_ref") == null
                && result.getString("provider_thread_ref") == null
                && result.getString("lease_owner") == null
                && result.getObject("lease_expires_at") == null
                && result.getString("request_fingerprint") != null
                && !"MAIL_PROVIDER_RESULT_UNKNOWN".equals(result.getString("last_error_code"));
    }

    private boolean mutableQueuedDelivery(ResultSet result) throws SQLException {
        return List.of("QUEUED", "RETRY_WAIT").contains(result.getString("delivery_status"))
                && result.getObject("accepted_at") == null
                && result.getString("provider_message_ref") == null
                && result.getString("provider_thread_ref") == null
                && result.getString("lease_owner") == null
                && result.getObject("lease_expires_at") == null;
    }

    private void reconcileDraftAttachments(
            long tenantId, long userId, UUID threadId, List<UUID> attachmentIds) {
        UUID[] selected = attachmentIds.toArray(UUID[]::new);
        jdbc.update("""
                UPDATE mail_compose_attachments
                   SET thread_id = NULL, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND uploader_user_id = ? AND thread_id = ?
                   AND NOT (attachment_id = ANY (?::uuid[]))
                """, tenantId, userId, threadId, selected);
        if (selected.length == 0) return;
        jdbc.update("""
                UPDATE mail_compose_attachments
                   SET thread_id = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND uploader_user_id = ?
                   AND attachment_id = ANY (?::uuid[]) AND scan_state = 'READY'
                   AND (thread_id IS NULL OR thread_id = ?)
                """, threadId, tenantId, userId, selected, threadId);
    }

    private List<Recipient> recipients(String raw) {
        return json.mapList(raw).stream().map(item -> new Recipient(
                recipientType(item.get("type")), text(item.get("name")), text(item.get("email"))))
                .filter(item -> item.email() != null && !item.email().isBlank()).toList();
    }

    private List<Recipient> visibleDeliveryRecipients(ResultSet result, long userId)
            throws SQLException {
        List<Recipient> recipients = recipients(result.getString("recipients"));
        if (result.getLong("created_by") == userId) return recipients;
        return recipients.stream()
                .filter(recipient -> recipient.type() != RecipientType.BCC)
                .toList();
    }

    private RecipientType recipientType(Object value) {
        try {
            return RecipientType.valueOf(text(value).toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return RecipientType.TO;
        }
    }

    private String recipientSummary(List<Recipient> recipients) {
        if (recipients.isEmpty()) return "No recipients";
        String first = recipients.get(0).name() == null || recipients.get(0).name().isBlank()
                ? recipients.get(0).email() : recipients.get(0).name();
        return recipients.size() == 1 ? first : first + " +" + (recipients.size() - 1);
    }

    private UUID uuid(Object value) {
        try {
            return value == null || String.valueOf(value).isBlank()
                    ? null : UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private OffsetDateTime instant(Object value) {
        try {
            return value == null || String.valueOf(value).isBlank()
                    ? null : OffsetDateTime.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private LocalDate date(Object value) {
        try {
            return value == null || String.valueOf(value).isBlank()
                    ? null : LocalDate.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Boolean bool(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue
                : value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }
}
