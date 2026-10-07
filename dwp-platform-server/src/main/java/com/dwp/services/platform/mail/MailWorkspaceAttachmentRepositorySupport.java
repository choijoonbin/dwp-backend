package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.springframework.jdbc.core.JdbcTemplate;

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

class MailWorkspaceAttachmentRepositorySupport extends MailWorkspaceDeliveryRepositorySupport {

    MailWorkspaceAttachmentRepositorySupport(JdbcTemplate jdbc, MailJsonCodec json) {
        super(jdbc, json);
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

}
