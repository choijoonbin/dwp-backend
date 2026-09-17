package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class MailGroupComposeRepository {

    record ComposeResult(
            UUID threadId,
            long version,
            String recipientSnapshotSha256,
            int recipientCount,
            UUID deliveryId,
            MailAddressBookDtos.GroupSendReceipt receipt) {

        ComposeResult(
                UUID threadId,
                long version,
                String recipientSnapshotSha256,
                int recipientCount) {
            this(threadId, version, recipientSnapshotSha256, recipientCount, null, null);
        }
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
            String correlationId,
            String requestFingerprint) {
        return compose(tenantId, userId, groupId, request, recipients,
                correlationId, requestFingerprint, null);
    }

    ComposeResult compose(
            Long tenantId,
            Long userId,
            UUID groupId,
            MailAddressBookDtos.GroupMessageRequest request,
            List<MailAddressBookRepository.Recipient> recipients,
            String correlationId,
            String requestFingerprint,
            UUID requiredAccountId) {
        UUID threadId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        String providerRef = "dwp:group:" + request.idempotencyKey();
        List<Map<String, Object>> participants = recipients.stream()
                .sorted(Comparator
                        .comparing(MailAddressBookRepository.Recipient::emailAddress)
                        .thenComparing(MailAddressBookRepository.Recipient::contactId))
                .map(recipient -> participant(recipient, request.recipientMode()))
                .toList();
        String recipientsJson = json.write(participants);
        String recipientSnapshotSha256 = MailRecipientSnapshot.fingerprint(participants);
        List<UUID> inserted = jdbc.query("""
                INSERT INTO mail_threads (
                    thread_id, tenant_id, account_id, folder_id, shared_inbox_id,
                    provider_thread_ref,
                    subject, preview, participants, latest_message_at,
                    unread, importance, triage_lane, workflow_state,
                    external_sender, classification, message_count,
                    created_by, updated_by)
                SELECT ?, account.tenant_id, account.account_id, folder.folder_id,
                       inbox.shared_inbox_id, ?,
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
                  LEFT JOIN mail_user_preferences preference
                    ON preference.tenant_id = account.tenant_id
                   AND preference.user_id = ?
                  LEFT JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = account.tenant_id
                   AND inbox.account_id = account.account_id
                   AND inbox.lifecycle_state = 'ACTIVE'
                 WHERE account.tenant_id = ?
                   AND ((?::uuid IS NULL
                         AND account.account_kind = 'PERSONAL'
                         AND account.owner_user_id = ?)
                        OR account.account_id = ?::uuid)
                   AND account.connection_state = 'ACTIVE'
                 ORDER BY CASE
                    WHEN account.account_id = preference.default_account_id THEN 0
                    WHEN account.is_default THEN 1 ELSE 2 END,
                    account.account_id
                 LIMIT 1
                RETURNING thread_id
                """, (result, ignored) -> result.getObject("thread_id", UUID.class),
                threadId, providerRef, request.subject().trim(), preview(request.body()),
                recipientsJson, recipientsJson, request.classification().name(),
                userId, userId, userId, tenantId,
                requiredAccountId, userId, requiredAccountId);
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
                    request_fingerprint, delivery_status, correlation_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                """, deliveryId, tenantId, threadId, messageId,
                request.idempotencyKey(), requestFingerprint, value(correlationId), userId);
        if (deliveryInserted != 1) {
            throw new IllegalStateException("Group mail delivery command was not enqueued.");
        }
        int snapshotInserted = jdbc.update("""
                INSERT INTO mail_group_recipient_snapshots (
                    delivery_id, tenant_id, owner_user_id, thread_id, message_id,
                    group_id, group_version, account_id, recipient_count, recipients,
                    recipients_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, deliveryId, tenantId, userId, threadId, messageId,
                groupId, request.groupVersion(), insertedAccountId(tenantId, threadId),
                participants.size(),
                recipientsJson, recipientSnapshotSha256);
        if (snapshotInserted != 1) {
            throw new IllegalStateException("Group mail recipient evidence was not persisted.");
        }
        MailAddressBookDtos.GroupSendReceipt receipt = jdbc.queryForObject("""
                INSERT INTO mail_group_send_history (
                    receipt_id, tenant_id, owner_user_id, group_id, group_version,
                    recipient_mode, account_id, recipient_count, thread_id, delivery_id,
                    receipt_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED')
                RETURNING receipt_id, group_id, group_version, recipient_mode, account_id,
                          recipient_count, thread_id, accepted_at, receipt_state
                """, (result, ignored) -> receipt(result), UUID.randomUUID(), tenantId, userId,
                groupId, request.groupVersion(), request.recipientMode().name(),
                insertedAccountId(tenantId, threadId), participants.size(), threadId, deliveryId);
        return new ComposeResult(
                threadId, 0L, recipientSnapshotSha256, participants.size(), deliveryId, receipt);
    }

    Optional<MailAddressBookDtos.GroupSendReceipt> receipt(
            Long tenantId, Long userId, UUID groupId, UUID threadId) {
        return jdbc.query("""
                SELECT receipt_id, group_id, group_version, recipient_mode, account_id,
                       recipient_count, thread_id, accepted_at, receipt_state
                  FROM mail_group_send_history
                 WHERE tenant_id = ? AND owner_user_id = ?
                   AND group_id = ? AND thread_id = ?
                 ORDER BY accepted_at DESC, receipt_id DESC
                 LIMIT 1
                """, (result, ignored) -> receipt(result),
                tenantId, userId, groupId, threadId).stream().findFirst();
    }

    List<MailAddressBookDtos.GroupSendReceipt> history(
            Long tenantId, Long userId, UUID groupId, int limit) {
        return jdbc.query("""
                SELECT receipt_id, group_id, group_version, recipient_mode, account_id,
                       recipient_count, thread_id, accepted_at, receipt_state
                  FROM mail_group_send_history
                 WHERE tenant_id = ? AND owner_user_id = ? AND group_id = ?
                 ORDER BY accepted_at DESC, receipt_id DESC
                 LIMIT ?
                """, (result, ignored) -> receipt(result), tenantId, userId, groupId, limit);
    }

    private MailAddressBookDtos.GroupSendReceipt receipt(java.sql.ResultSet result)
            throws java.sql.SQLException {
        return new MailAddressBookDtos.GroupSendReceipt(
                result.getObject("receipt_id", UUID.class),
                result.getObject("group_id", UUID.class),
                result.getLong("group_version"),
                MailAddressBookDtos.GroupRecipientMode.valueOf(
                        result.getString("recipient_mode")),
                result.getObject("account_id", UUID.class),
                result.getInt("recipient_count"),
                result.getObject("thread_id", UUID.class),
                result.getObject("accepted_at", java.time.OffsetDateTime.class),
                result.getString("receipt_state"));
    }

    private UUID insertedAccountId(Long tenantId, UUID threadId) {
        return jdbc.queryForObject(
                "SELECT account_id FROM mail_threads WHERE tenant_id = ? AND thread_id = ?",
                UUID.class, tenantId, threadId);
    }

    private Map<String, Object> participant(
            MailAddressBookRepository.Recipient recipient,
            MailAddressBookDtos.GroupRecipientMode mode) {
        Map<String, Object> participant = new LinkedHashMap<>();
        participant.put("name", recipient.displayName());
        participant.put("email", recipient.emailAddress());
        participant.put("type", mode.name());
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
