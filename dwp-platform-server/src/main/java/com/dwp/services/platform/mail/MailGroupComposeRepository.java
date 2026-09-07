package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
class MailGroupComposeRepository {

    record ComposeResult(
            UUID threadId,
            long version,
            String recipientSnapshotSha256,
            int recipientCount) {
    }

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailGroupComposeRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    ComposeResult compose(
            Long tenantId,
            Long userId,
            UUID groupId,
            MailAddressBookDtos.GroupMessageRequest request,
            List<MailAddressBookRepository.Recipient> recipients,
            String correlationId) {
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String providerRef = "dwp:group:" + request.idempotencyKey();
        List<Map<String, Object>> participants = recipients.stream()
                .sorted(Comparator
                        .comparing(MailAddressBookRepository.Recipient::emailAddress)
                        .thenComparing(MailAddressBookRepository.Recipient::contactId))
                .map(this::participant)
                .toList();
        String recipientsJson = json.write(participants);
        String recipientSnapshotSha256 = MailRecipientSnapshot.fingerprint(participants);
        List<UUID> inserted = jdbc.query("""
                INSERT INTO mail_threads (
                    thread_id, tenant_id, account_id, folder_id, provider_thread_ref,
                    subject, preview, participants, latest_message_at,
                    unread, importance, triage_lane, workflow_state,
                    external_sender, classification, message_count,
                    created_by, updated_by)
                SELECT ?, account.tenant_id, account.account_id, folder.folder_id, ?,
                       ?, ?, ?::jsonb, CURRENT_TIMESTAMP,
                       FALSE, 'NORMAL', 'UPDATES', 'OPEN',
                       EXISTS (
                           SELECT 1 FROM jsonb_array_elements(?::jsonb) recipient
                            WHERE split_part(lower(recipient ->> 'email'), '@', 2)
                               <> split_part(lower(account.email_address), '@', 2)),
                       ?, 1, ?, ?
                  FROM mail_accounts account
                  JOIN mail_folders folder
                    ON folder.tenant_id = account.tenant_id
                   AND folder.account_id = account.account_id
                   AND folder.folder_type = 'SENT'
                   AND folder.lifecycle_state = 'ACTIVE'
                 WHERE account.tenant_id = ? AND account.owner_user_id = ?
                   AND account.is_default = TRUE AND account.account_kind = 'PERSONAL'
                   AND account.connection_state = 'ACTIVE'
                RETURNING thread_id
                """, (result, ignored) -> result.getObject("thread_id", UUID.class),
                threadId, providerRef, request.subject().trim(), preview(request.body()),
                recipientsJson, recipientsJson, request.classification().name(),
                userId, userId, tenantId, userId);
        if (inserted.isEmpty()) return null;
        int messageInserted = jdbc.update("""
                INSERT INTO mail_messages (
                    message_id, tenant_id, thread_id, provider_message_ref,
                    sender_email, sender_name, recipients, message_direction,
                    body_format, body_content, attachments, sent_at, created_by)
                SELECT ?, thread.tenant_id, thread.thread_id, ?,
                       account.email_address, account.display_name, ?::jsonb,
                       'OUTBOUND', 'TEXT', ?, '[]'::jsonb, CURRENT_TIMESTAMP, ?
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """, messageId, providerRef + ":message", recipientsJson,
                request.body().trim(), userId, tenantId, threadId);
        if (messageInserted != 1) {
            throw new IllegalStateException("Group mail message projection is missing.");
        }
        int deliveryInserted = jdbc.update("""
                INSERT INTO mail_delivery_outbox (
                    delivery_id, tenant_id, thread_id, message_id, idempotency_key,
                    delivery_status, correlation_id, created_by)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                """, deliveryId, tenantId, threadId, messageId,
                request.idempotencyKey(), value(correlationId), userId);
        if (deliveryInserted != 1) {
            throw new IllegalStateException("Group mail delivery command was not enqueued.");
        }
        int snapshotInserted = jdbc.update("""
                INSERT INTO mail_group_recipient_snapshots (
                    delivery_id, tenant_id, owner_user_id, thread_id, message_id,
                    group_id, group_version, recipient_count, recipients,
                    recipients_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, deliveryId, tenantId, userId, threadId, messageId,
                groupId, request.groupVersion(), participants.size(),
                recipientsJson, recipientSnapshotSha256);
        if (snapshotInserted != 1) {
            throw new IllegalStateException("Group mail recipient evidence was not persisted.");
        }
        return new ComposeResult(
                threadId, 0L, recipientSnapshotSha256, participants.size());
    }

    private Map<String, Object> participant(MailAddressBookRepository.Recipient recipient) {
        Map<String, Object> participant = new LinkedHashMap<>();
        participant.put("name", recipient.displayName());
        participant.put("email", recipient.emailAddress());
        participant.put("type", "TO");
        return participant;
    }

    private String preview(String body) {
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1200 ? normalized : normalized.substring(0, 1197) + "...";
    }

    private String value(String input) {
        return input == null || input.isBlank() ? null : input.trim();
    }
}
