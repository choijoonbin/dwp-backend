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
                      JOIN mail_messages message ON message.message_id = delivery.message_id
                      JOIN mail_threads thread ON thread.thread_id = delivery.thread_id
                      JOIN mail_accounts account ON account.account_id = thread.account_id
                      JOIN mail_provider_connections connection
                        ON connection.connection_id = account.connection_id
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
                       thread.subject, message.body_content, message.recipients::text,
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
                  JOIN mail_messages message ON message.message_id = leased.message_id
                  JOIN mail_threads thread ON thread.thread_id = leased.thread_id
                  JOIN mail_accounts account ON account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.connection_id = account.connection_id
                 ORDER BY leased.created_at, leased.delivery_id
                """, (result, ignored) -> new DeliveryJob(
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
                result.getString("body_content"),
                recipients(result.getString("recipients")),
                result.getString("reply_to_provider_message_ref")),
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

    int retry(Long tenantId, UUID threadId, UUID messageId) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = 'QUEUED', attempt_count = 0,
                       next_attempt_at = CURRENT_TIMESTAMP,
                       last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND thread_id = ? AND message_id = ?
                   AND delivery_status = 'FAILED'
                """, tenantId, threadId, messageId);
    }

    void releaseExpiredLeases() {
        jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = 'RETRY_WAIT',
                       next_attempt_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = COALESCE(last_error_code, 'DELIVERY_LEASE_EXPIRED'),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE delivery_status = 'LEASED' AND lease_expires_at < CURRENT_TIMESTAMP
                """);
    }

    void mirrorSandboxDelivery(
            DeliveryJob job,
            MailConnectorPort.DeliveryReceipt receipt) {
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
                        ON connection.connection_id = account.connection_id
                       AND connection.provider_type = 'DWP_SANDBOX'
                       AND connection.connection_state = 'ACTIVE'
                      JOIN mail_folders folder
                        ON folder.account_id = account.account_id
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
                    job.subject(), preview(job.body()), job.senderName(), job.senderEmail(),
                    OffsetDateTime.ofInstant(receipt.acceptedAt(), ZoneOffset.UTC),
                    job.senderEmail(), job.createdBy(), job.createdBy(),
                    job.tenantId(), recipient, job.accountId());
            for (UUID targetThread : targetThreads) {
                jdbc.update("""
                        INSERT INTO mail_messages (
                            message_id, tenant_id, thread_id, provider_message_ref,
                            sender_email, sender_name, recipients, message_direction,
                            body_format, body_content, attachments, sent_at, created_by)
                        VALUES (?, ?, ?, ?, ?, ?,
                                jsonb_build_array(jsonb_build_object(
                                    'name', ?, 'email', LOWER(?), 'type', 'TO')),
                                'INBOUND', 'TEXT', ?, '[]'::jsonb, ?, ?)
                        ON CONFLICT (thread_id, provider_message_ref) DO NOTHING
                        """, UUID.randomUUID(), job.tenantId(), targetThread,
                        receipt.providerMessageReference(), job.senderEmail(), job.senderName(),
                        recipient, recipient, job.body(),
                        OffsetDateTime.ofInstant(receipt.acceptedAt(), ZoneOffset.UTC),
                        job.createdBy());
            }
        }
    }

    private List<String> recipients(String rawJson) {
        return json.mapList(rawJson).stream()
                .map(value -> String.valueOf(value.getOrDefault("email", "")).trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
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
            String body,
            List<String> recipients,
            String replyToProviderMessageReference) {
    }
}
