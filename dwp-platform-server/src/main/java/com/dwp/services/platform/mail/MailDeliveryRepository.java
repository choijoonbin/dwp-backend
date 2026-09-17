package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.ProviderType;

@Repository
class MailDeliveryRepository {

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailDeliveryRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    List<DeliveryJob> claim(String workerId, int batchSize, int leaseSeconds) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT delivery.delivery_id
                      FROM mail_delivery_outbox delivery
                      JOIN mail_messages message
                        ON message.tenant_id = delivery.tenant_id
                       AND message.thread_id = delivery.thread_id
                       AND message.message_id = delivery.message_id
                      JOIN mail_threads thread
                        ON thread.tenant_id = delivery.tenant_id
                       AND thread.thread_id = delivery.thread_id
                      JOIN mail_accounts account
                        ON account.tenant_id = thread.tenant_id
                       AND account.account_id = thread.account_id
                      JOIN mail_provider_connections connection
                        ON connection.tenant_id = account.tenant_id
                       AND connection.connection_id = account.connection_id
                     WHERE delivery.delivery_status IN ('QUEUED', 'RETRY_WAIT')
                       AND delivery.next_attempt_at <= CURRENT_TIMESTAMP
                       AND connection.connection_state = 'ACTIVE'
                       AND account.connection_state = 'ACTIVE'
                     ORDER BY delivery.next_attempt_at, delivery.created_at, delivery.delivery_id
                     FOR UPDATE OF delivery SKIP LOCKED
                     LIMIT ?
                ), leased AS (
                    UPDATE mail_delivery_outbox delivery
                       SET delivery_status = 'LEASED',
                           attempt_count = attempt_count + 1,
                           lease_owner = ?,
                           lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                           updated_at = CURRENT_TIMESTAMP
                      FROM candidates
                     WHERE delivery.delivery_id = candidates.delivery_id
                    RETURNING delivery.*
                )
                SELECT leased.delivery_id, leased.tenant_id, leased.thread_id,
                       leased.message_id, leased.idempotency_key, leased.attempt_count,
                       leased.correlation_id, leased.created_by,
                       connection.connection_id, connection.provider_type,
                       connection.credential_ref, connection.mail_domain,
                       account.account_id, account.provider_account_ref,
                       account.email_address AS sender_email,
                       account.display_name AS sender_name,
                       thread.subject, message.body_format, message.body_content,
                       COALESCE(snapshot.recipients, message.recipients)::text AS recipients,
                       snapshot.recipients_sha256,
                       jsonb_array_length(message.attachments) AS expected_attachment_count,
                       COALESCE((
                           SELECT jsonb_agg(jsonb_build_object(
                                      'attachmentId', attachment.attachment_id,
                                      'storageReference', attachment.storage_reference,
                                      'fileName', attachment.file_name,
                                      'contentType', attachment.content_type,
                                      'sizeBytes', attachment.size_bytes,
                                      'checksumSha256', attachment.checksum_sha256)
                                      ORDER BY attachment.created_at, attachment.attachment_id)
                             FROM mail_compose_attachments attachment
                            WHERE attachment.tenant_id = leased.tenant_id
                              AND attachment.thread_id = leased.thread_id
                              AND attachment.scan_state = 'READY'
                              AND EXISTS (
                                  SELECT 1
                                    FROM jsonb_array_elements(message.attachments) projected
                                   WHERE projected ->> 'attachmentId'
                                         = attachment.attachment_id::text)
                       ), '[]'::jsonb)::text AS delivery_attachments,
                       (
                           SELECT previous.provider_message_ref
                             FROM mail_messages previous
                            WHERE previous.tenant_id = leased.tenant_id
                              AND previous.thread_id = leased.thread_id
                              AND previous.message_id <> leased.message_id
                              AND previous.message_direction = 'INBOUND'
                              AND previous.provider_message_ref IS NOT NULL
                            ORDER BY previous.sent_at DESC, previous.message_id DESC
                            LIMIT 1
                       ) AS reply_to_provider_message_ref
                  FROM leased
                  JOIN mail_messages message
                    ON message.tenant_id = leased.tenant_id
                   AND message.thread_id = leased.thread_id
                   AND message.message_id = leased.message_id
                  JOIN mail_threads thread
                    ON thread.tenant_id = leased.tenant_id
                   AND thread.thread_id = leased.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                  LEFT JOIN mail_group_recipient_snapshots snapshot
                    ON snapshot.tenant_id = leased.tenant_id
                   AND snapshot.delivery_id = leased.delivery_id
                 ORDER BY leased.created_at, leased.delivery_id
                """, (result, ignored) -> {
                    RecipientLists recipients = recipients(
                            result.getString("recipients"),
                            result.getString("recipients_sha256"));
                    return new DeliveryJob(
                            result.getObject("delivery_id", UUID.class),
                            result.getLong("tenant_id"),
                            result.getObject("thread_id", UUID.class),
                            result.getObject("message_id", UUID.class),
                            result.getObject("idempotency_key", UUID.class),
                            result.getInt("attempt_count"),
                            result.getString("correlation_id"),
                            result.getLong("created_by"),
                            result.getObject("connection_id", UUID.class),
                            ProviderType.valueOf(result.getString("provider_type")),
                            uri(result.getString("credential_ref")),
                            result.getString("mail_domain"),
                            result.getObject("account_id", UUID.class),
                            result.getString("provider_account_ref"),
                            result.getString("sender_email"),
                            result.getString("sender_name"),
                            result.getString("subject"),
                            result.getString("body_format"),
                            result.getString("body_content"),
                            recipients.to(), recipients.cc(), recipients.bcc(),
                            result.getInt("expected_attachment_count"),
                            deliveryAttachments(result.getString("delivery_attachments")),
                            result.getString("reply_to_provider_message_ref"));
                },
                batchSize, workerId, leaseSeconds);
    }

    int markDelivered(
            DeliveryJob job,
            String workerId,
            MailConnectorPort.DeliveryReceipt receipt) {
        int updated = jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = 'DELIVERED',
                       provider_message_ref = ?, provider_thread_ref = ?,
                       accepted_at = ?, last_error_code = NULL,
                       lease_owner = NULL, lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE delivery_id = ? AND delivery_status = 'LEASED' AND lease_owner = ?
                """, receipt.providerMessageReference(), receipt.providerThreadReference(),
                OffsetDateTime.ofInstant(receipt.acceptedAt(), ZoneOffset.UTC),
                job.deliveryId(), workerId);
        if (updated == 0) return 0;
        jdbc.update("""
                UPDATE mail_messages
                   SET provider_message_ref = ?, sent_at = ?
                 WHERE tenant_id = ? AND message_id = ?
                """, receipt.providerMessageReference(),
                OffsetDateTime.ofInstant(receipt.acceptedAt(), ZoneOffset.UTC),
                job.tenantId(), job.messageId());
        jdbc.update("""
                UPDATE mail_threads
                   SET provider_thread_ref = CASE
                           WHEN provider_thread_ref IS NULL OR provider_thread_ref LIKE 'dwp:%'
                           THEN ? ELSE provider_thread_ref END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND thread_id = ?
                """, receipt.providerThreadReference(), job.tenantId(), job.threadId());
        return updated;
    }

    int markFailed(
            DeliveryJob job,
            String workerId,
            String status,
            String errorCode,
            OffsetDateTime nextAttemptAt) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = ?, last_error_code = ?,
                       next_attempt_at = COALESCE(?, next_attempt_at),
                       lease_owner = NULL, lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE delivery_id = ? AND delivery_status = 'LEASED' AND lease_owner = ?
                """, status, errorCode, nextAttemptAt, job.deliveryId(), workerId);
    }

    int retry(Long tenantId, Long userId, UUID threadId, UUID messageId) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox delivery
                   SET delivery_status = 'QUEUED', attempt_count = 0,
                       next_attempt_at = CURRENT_TIMESTAMP,
                       last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
                  FROM mail_threads thread, mail_accounts account
                 WHERE delivery.tenant_id = ? AND delivery.thread_id = ?
                   AND delivery.message_id = ? AND delivery.delivery_status = 'FAILED'
                   AND delivery.accepted_at IS NULL
                   AND delivery.provider_message_ref IS NULL
                   AND delivery.provider_thread_ref IS NULL
                   AND delivery.lease_owner IS NULL AND delivery.lease_expires_at IS NULL
                   AND delivery.request_fingerprint IS NOT NULL
                   AND COALESCE(delivery.last_error_code, '') <> 'MAIL_PROVIDER_RESULT_UNKNOWN'
                   AND thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                """ + MailAccessSql.THREAD_SEND_ACCESS,
                tenantId, threadId, messageId, userId, userId);
    }

    void releaseExpiredLeases() {
        jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = 'FAILED',
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = 'MAIL_PROVIDER_RESULT_UNKNOWN',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE delivery_status = 'LEASED' AND lease_expires_at < CURRENT_TIMESTAMP
                """);
    }

    Optional<MailConnectorPort.SenderMode> authorizedSenderMode(DeliveryJob job) {
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
                job.createdBy(), job.tenantId(), job.accountId(), job.createdBy())
                .stream().findFirst();
    }

    List<InboundMessage> mirrorSandboxDelivery(
            DeliveryJob job,
            MailConnectorPort.DeliveryReceipt receipt) {
        return mirrorSandboxDelivery(job, receipt, MailConnectorPort.SenderMode.ACCOUNT);
    }

    List<InboundMessage> mirrorSandboxDelivery(
            DeliveryJob job,
            MailConnectorPort.DeliveryReceipt receipt,
            MailConnectorPort.SenderMode senderMode) {
        List<InboundMessage> inboundMessages = new java.util.ArrayList<>();
        String senderName = senderMode == MailConnectorPort.SenderMode.SEND_ON_BEHALF
                ? job.senderName() + " (sent on behalf)"
                : job.senderName();
        for (String recipient : job.recipients()) {
            List<UUID> targetThreads = jdbc.query("""
                    INSERT INTO mail_threads (
                        thread_id, tenant_id, account_id, folder_id, provider_thread_ref,
                        subject, preview, participants, latest_message_at,
                        unread, importance, triage_lane, workflow_state,
                        external_sender, classification, message_count,
                        created_by, updated_by)
                    SELECT ?, account.tenant_id, account.account_id, folder.folder_id, ?,
                           ?, ?, jsonb_build_array(jsonb_build_object(
                               'name', ?, 'email', LOWER(?))), ?,
                           TRUE, 'NORMAL', 'PRIORITY', 'OPEN',
                           SPLIT_PART(LOWER(?), '@', 2)
                               <> SPLIT_PART(account.email_address, '@', 2),
                           'INTERNAL', 1, ?, ?
                      FROM mail_accounts account
                      JOIN mail_provider_connections connection
                        ON connection.tenant_id = account.tenant_id
                       AND connection.connection_id = account.connection_id
                       AND connection.provider_type = 'DWP_SANDBOX'
                       AND connection.connection_state = 'ACTIVE'
                      JOIN mail_folders folder
                        ON folder.tenant_id = account.tenant_id
                       AND folder.account_id = account.account_id
                       AND folder.folder_type = 'INBOX'
                       AND folder.lifecycle_state = 'ACTIVE'
                     WHERE account.tenant_id = ?
                       AND LOWER(account.email_address) = LOWER(?)
                       AND account.account_id <> ?
                       AND account.connection_state = 'ACTIVE'
                    ON CONFLICT (account_id, provider_thread_ref) DO UPDATE SET
                        folder_id = EXCLUDED.folder_id,
                        preview = EXCLUDED.preview,
                        latest_message_at = EXCLUDED.latest_message_at,
                        unread = TRUE,
                        workflow_state = 'OPEN',
                        message_count = mail_threads.message_count + 1,
                        version = mail_threads.version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    RETURNING thread_id
                    """, (result, ignored) -> result.getObject("thread_id", UUID.class),
                    UUID.randomUUID(), receipt.providerThreadReference(),
                    job.subject(), preview(job.body()), senderName, job.senderEmail(),
                    OffsetDateTime.ofInstant(receipt.acceptedAt(), ZoneOffset.UTC),
                    job.senderEmail(), job.createdBy(), job.createdBy(),
                    job.tenantId(), recipient, job.accountId());
            for (UUID targetThread : targetThreads) {
                Long recipientOwner = jdbc.queryForObject("""
                        SELECT account.owner_user_id
                          FROM mail_threads thread
                          JOIN mail_accounts account
                            ON account.tenant_id = thread.tenant_id
                           AND account.account_id = thread.account_id
                         WHERE thread.tenant_id = ? AND thread.thread_id = ?
                        """, Long.class, job.tenantId(), targetThread);
                String mirroredAttachments = mirrorAttachments(
                        job, targetThread,
                        recipientOwner == null ? job.createdBy() : recipientOwner);
                List<UUID> insertedMessages = jdbc.query("""
                        INSERT INTO mail_messages (
                            message_id, tenant_id, thread_id, provider_message_ref,
                            sender_email, sender_name, recipients, message_direction,
                            body_format, body_content, attachments, sent_at, created_by)
                        VALUES (?, ?, ?, ?, ?, ?,
                                jsonb_build_array(jsonb_build_object(
                                    'name', ?, 'email', LOWER(?), 'type', 'TO')),
                                'INBOUND', ?, ?, ?::jsonb, ?, ?)
                        ON CONFLICT (thread_id, provider_message_ref) DO NOTHING
                        RETURNING message_id
                        """, (result, ignored) -> result.getObject("message_id", UUID.class),
                        UUID.randomUUID(), job.tenantId(), targetThread,
                        receipt.providerMessageReference(), job.senderEmail(), senderName,
                        recipient, recipient, job.bodyFormat(), job.body(), mirroredAttachments,
                        OffsetDateTime.ofInstant(receipt.acceptedAt(), ZoneOffset.UTC),
                        job.createdBy());
                if (!insertedMessages.isEmpty()) {
                    inboundMessages.add(new InboundMessage(
                            targetThread, insertedMessages.getFirst(),
                            OffsetDateTime.ofInstant(receipt.acceptedAt(), ZoneOffset.UTC)));
                }
            }
        }
        return List.copyOf(inboundMessages);
    }

    int markFollowUpsReplied(
            Long tenantId, UUID threadId, UUID messageId, OffsetDateTime receivedAt) {
        return jdbc.update("""
                UPDATE mail_follow_up_trackers tracker
                   SET tracker_status = 'REPLIED', last_checked_at = message.sent_at,
                       version = tracker.version + 1, updated_at = CURRENT_TIMESTAMP
                  FROM mail_messages message
                 WHERE tracker.tenant_id = ? AND tracker.thread_id = ?
                   AND tracker.tracker_status IN ('WAITING', 'OVERDUE')
                   AND tracker.created_at <= message.sent_at
                   AND message.tenant_id = tracker.tenant_id
                   AND message.thread_id = tracker.thread_id
                   AND message.message_id = ?
                   AND message.message_direction = 'INBOUND'
                   AND message.sent_at <= ?
                """, tenantId, threadId, messageId, receivedAt);
    }

    private String mirrorAttachments(DeliveryJob job, UUID targetThread, Long recipientOwner) {
        if (job.attachments().isEmpty()) return "[]";
        List<Map<String, Object>> projection = new java.util.ArrayList<>();
        for (DeliveryAttachment source : job.attachments()) {
            UUID attachmentId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO mail_compose_attachments (
                        attachment_id, tenant_id, uploader_user_id, thread_id,
                        storage_reference, file_name, content_type, size_bytes,
                        checksum_sha256, scan_state, scan_evidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'READY',
                            'DWP_SANDBOX_DELIVERY')
                    """, attachmentId, job.tenantId(), recipientOwner, targetThread,
                    source.storageReference(), source.fileName(), source.contentType(),
                    source.sizeBytes(), source.checksumSha256());
            projection.add(Map.of(
                    "attachmentId", attachmentId,
                    "fileName", source.fileName(),
                    "contentType", source.contentType(),
                    "sizeBytes", source.sizeBytes(),
                    "checksumSha256", source.checksumSha256()));
        }
        return json.write(projection);
    }

    private RecipientLists recipients(String rawJson, String expectedSha256) {
        List<Map<String, Object>> recipientSnapshot = json.mapList(rawJson);
        if (expectedSha256 != null
                && !expectedSha256.equals(MailRecipientSnapshot.fingerprint(recipientSnapshot))) {
            throw new IllegalStateException("Group recipient snapshot integrity check failed.");
        }
        return new RecipientLists(
                recipientAddresses(recipientSnapshot, "TO"),
                recipientAddresses(recipientSnapshot, "CC"),
                recipientAddresses(recipientSnapshot, "BCC"));
    }

    private List<String> recipientAddresses(
            List<Map<String, Object>> recipients, String expectedType) {
        return recipients.stream()
                .filter(value -> expectedType.equals(recipientType(value)))
                .map(value -> String.valueOf(value.getOrDefault("email", "")).trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String recipientType(Map<String, Object> recipient) {
        Object rawType = recipient.entrySet().stream()
                .filter(entry -> "type".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("TO");
        String type = String.valueOf(rawType).trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("TO", "CC", "BCC").contains(type)) {
            throw new IllegalStateException("Mail recipient type is invalid.");
        }
        return type;
    }

    private List<DeliveryAttachment> deliveryAttachments(String rawJson) {
        return json.mapList(rawJson).stream().map(value -> new DeliveryAttachment(
                UUID.fromString(String.valueOf(value.get("attachmentId"))),
                String.valueOf(value.get("storageReference")),
                String.valueOf(value.get("fileName")),
                String.valueOf(value.get("contentType")),
                ((Number) value.get("sizeBytes")).longValue(),
                String.valueOf(value.get("checksumSha256")))).toList();
    }

    private URI uri(String value) {
        return value == null || value.isBlank() ? null : URI.create(value);
    }

    private String preview(String body) {
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1200 ? normalized : normalized.substring(0, 1197) + "...";
    }

    record DeliveryJob(
            UUID deliveryId,
            Long tenantId,
            UUID threadId,
            UUID messageId,
            UUID idempotencyKey,
            int attemptCount,
            String correlationId,
            Long createdBy,
            UUID connectionId,
            ProviderType providerType,
            URI credentialReference,
            String mailDomain,
            UUID accountId,
            String providerAccountReference,
            String senderEmail,
            String senderName,
            String subject,
            String bodyFormat,
            String body,
            List<String> toRecipients,
            List<String> ccRecipients,
            List<String> bccRecipients,
            int expectedAttachmentCount,
            List<DeliveryAttachment> attachments,
            String replyToProviderMessageReference) {

        DeliveryJob {
            toRecipients = List.copyOf(toRecipients);
            ccRecipients = List.copyOf(ccRecipients);
            bccRecipients = List.copyOf(bccRecipients);
            attachments = List.copyOf(attachments);
        }

        DeliveryJob(
                UUID deliveryId,
                Long tenantId,
                UUID threadId,
                UUID messageId,
                UUID idempotencyKey,
                int attemptCount,
                String correlationId,
                Long createdBy,
                UUID connectionId,
                ProviderType providerType,
                URI credentialReference,
                String mailDomain,
                UUID accountId,
                String providerAccountReference,
                String senderEmail,
                String senderName,
                String subject,
                String body,
                List<String> recipients,
                String replyToProviderMessageReference) {
            this(deliveryId, tenantId, threadId, messageId, idempotencyKey, attemptCount,
                    correlationId, createdBy, connectionId, providerType, credentialReference,
                    mailDomain, accountId, providerAccountReference, senderEmail, senderName,
                    subject, "TEXT", body, recipients, List.of(), List.of(), 0, List.of(),
                    replyToProviderMessageReference);
        }

        DeliveryJob(
                UUID deliveryId,
                Long tenantId,
                UUID threadId,
                UUID messageId,
                UUID idempotencyKey,
                int attemptCount,
                String correlationId,
                Long createdBy,
                UUID connectionId,
                ProviderType providerType,
                URI credentialReference,
                String mailDomain,
                UUID accountId,
                String providerAccountReference,
                String senderEmail,
                String senderName,
                String subject,
                String body,
                List<String> toRecipients,
                List<String> ccRecipients,
                List<String> bccRecipients,
                String replyToProviderMessageReference) {
            this(deliveryId, tenantId, threadId, messageId, idempotencyKey, attemptCount,
                    correlationId, createdBy, connectionId, providerType, credentialReference,
                    mailDomain, accountId, providerAccountReference, senderEmail, senderName,
                    subject, "TEXT", body, toRecipients, ccRecipients, bccRecipients,
                    0, List.of(), replyToProviderMessageReference);
        }

        List<String> recipients() {
            return java.util.stream.Stream.of(toRecipients, ccRecipients, bccRecipients)
                    .flatMap(List::stream)
                    .distinct()
                    .toList();
        }
    }

    record DeliveryAttachment(
            UUID attachmentId,
            String storageReference,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256) {
    }

    record InboundMessage(UUID threadId, UUID messageId, OffsetDateTime receivedAt) {
    }

    private record RecipientLists(List<String> to, List<String> cc, List<String> bcc) {
    }
}
