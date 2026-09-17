package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
final class MailAdminMutationReceipts {

    private final JdbcTemplate jdbc;
    private final AdminMailCommandFingerprint fingerprints;

    MailAdminMutationReceipts(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.fingerprints = new AdminMailCommandFingerprint(objectMapper);
    }

    String fingerprint(Object... values) {
        return fingerprints.digest(values);
    }

    Receipt claimOrReplay(
            long tenantId,
            long actorId,
            String commandKind,
            UUID idempotencyKey,
            String fingerprint,
            String correlationId) {
        if (idempotencyKey == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "An Idempotency-Key is required for a Mail administrator mutation.");
        }
        Optional<Receipt> existing = receipt(tenantId, actorId, idempotencyKey);
        if (existing.isPresent()) return requireReplay(existing.get(), commandKind, fingerprint);
        int inserted = jdbc.update("""
                INSERT INTO mail_admin_command_receipts (
                    tenant_id, actor_user_id, command_kind, idempotency_key,
                    request_fingerprint, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, actor_user_id, idempotency_key) DO NOTHING
                """, tenantId, actorId, commandKind, idempotencyKey,
                fingerprint, correlationId);
        if (inserted == 1) return null;
        Receipt winner = receipt(tenantId, actorId, idempotencyKey)
                .orElseThrow(MailAdminMutationReceipts::conflict);
        return requireReplay(winner, commandKind, fingerprint);
    }

    void complete(
            long tenantId,
            long actorId,
            UUID idempotencyKey,
            String aggregateType,
            UUID aggregateId) {
        if (jdbc.update("""
                UPDATE mail_admin_command_receipts
                   SET aggregate_type = ?, aggregate_id = ?,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND idempotency_key = ? AND completed_at IS NULL
                """, aggregateType, aggregateId, tenantId, actorId, idempotencyKey) != 1) {
            throw conflict();
        }
    }

    static UUID tenantPolicyId(long tenantId) {
        return UUID.nameUUIDFromBytes(
                ("MAIL_TENANT_POLICY:" + tenantId).getBytes(StandardCharsets.UTF_8));
    }

    private Optional<Receipt> receipt(long tenantId, long actorId, UUID key) {
        return jdbc.query("""
                SELECT command_kind, request_fingerprint, aggregate_type,
                       aggregate_id, completed_at
                  FROM mail_admin_command_receipts
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, (result, ignored) -> new Receipt(
                        result.getString("command_kind"),
                        result.getString("request_fingerprint"),
                        result.getString("aggregate_type"),
                        result.getObject("aggregate_id", UUID.class),
                        result.getObject("completed_at", OffsetDateTime.class)),
                tenantId, actorId, key).stream().findFirst();
    }

    private Receipt requireReplay(
            Receipt receipt, String commandKind, String fingerprint) {
        if (!receipt.commandKind().equals(commandKind)
                || !receipt.fingerprint().equals(fingerprint)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for another Mail administrator command.");
        }
        if (receipt.completedAt() == null || receipt.aggregateId() == null
                || receipt.aggregateType() == null) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotent Mail administrator command is still in progress.");
        }
        return receipt;
    }

    private static BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The Mail administrator command receipt changed. Retry with the same key.");
    }

    record Receipt(
            String commandKind,
            String fingerprint,
            String aggregateType,
            UUID aggregateId,
            OffsetDateTime completedAt) {
    }
}
