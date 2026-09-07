package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
class MailAddressBookCommandReceiptRepository {

    enum CommandType {
        CONTACT_CREATE,
        GROUP_CREATE,
        GROUP_MEMBERS_REPLACE,
        GROUP_MESSAGE_SEND
    }

    record Receipt(
            String requestFingerprint,
            UUID targetId,
            Long appliedVersion,
            String status,
            boolean inserted) {

        boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    private final JdbcTemplate jdbc;

    MailAddressBookCommandReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Receipt reserve(
            Long tenantId,
            Long userId,
            CommandType commandType,
            UUID idempotencyKey,
            String requestFingerprint) {
        boolean inserted = jdbc.update("""
                INSERT INTO mail_address_book_command_receipts (
                    tenant_id, actor_user_id, command_type, idempotency_key,
                    request_fingerprint, command_status)
                VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS')
                ON CONFLICT (tenant_id, actor_user_id, command_type, idempotency_key)
                DO NOTHING
                """, tenantId, userId, commandType.name(), idempotencyKey,
                requestFingerprint) == 1;
        return jdbc.queryForObject("""
                SELECT request_fingerprint, target_id, applied_version, command_status
                  FROM mail_address_book_command_receipts
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND command_type = ? AND idempotency_key = ?
                   FOR UPDATE
                """, (result, ignored) -> new Receipt(
                result.getString("request_fingerprint"),
                result.getObject("target_id", UUID.class),
                result.getObject("applied_version", Long.class),
                result.getString("command_status"),
                inserted), tenantId, userId, commandType.name(), idempotencyKey);
    }

    void complete(
            Long tenantId,
            Long userId,
            CommandType commandType,
            UUID idempotencyKey,
            String requestFingerprint,
            UUID targetId,
            long appliedVersion) {
        int updated = jdbc.update("""
                UPDATE mail_address_book_command_receipts
                   SET target_id = ?, applied_version = ?, command_status = 'COMPLETED',
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND actor_user_id = ? AND command_type = ?
                   AND idempotency_key = ? AND request_fingerprint = ?
                   AND command_status = 'IN_PROGRESS'
                """, targetId, appliedVersion, tenantId, userId, commandType.name(),
                idempotencyKey, requestFingerprint);
        if (updated != 1) {
            throw new IllegalStateException("Address-book command receipt completion failed.");
        }
    }
}
