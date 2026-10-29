package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Atomic, item-level replay evidence for a reviewed client bulk intent. */
@Repository
class MailBulkMutationReceipts {

    private final JdbcTemplate jdbc;

    MailBulkMutationReceipts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Claim claim(
            long tenantId,
            long actorId,
            UUID threadId,
            String action,
            long expectedVersion,
            UUID intentId,
            UUID itemIdempotencyKey) {
        requireBulkIdentity(intentId, itemIdempotencyKey);
        String fingerprint = fingerprint(
                actorId, threadId, action, expectedVersion, intentId);
        UUID receiptId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO mail_bulk_mutation_receipts (
                    receipt_id, tenant_id, actor_user_id, intent_id,
                    item_idempotency_key, thread_id, action_kind,
                    expected_version, request_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, actor_user_id, item_idempotency_key) DO NOTHING
                """, receiptId, tenantId, actorId, intentId, itemIdempotencyKey,
                threadId, action, expectedVersion, fingerprint);
        if (inserted == 1) {
            return new Claim(true, new Receipt(
                    receiptId, threadId, action, expectedVersion, intentId,
                    itemIdempotencyKey, fingerprint, "PENDING", null, false));
        }
        Receipt existing = receipt(
                tenantId, actorId, itemIdempotencyKey).orElseThrow(() ->
                conflict("The bulk item receipt changed. Retry with the same key."));
        if (!existing.threadId().equals(threadId)
                || !existing.action().equals(action)
                || existing.expectedVersion() != expectedVersion
                || !existing.intentId().equals(intentId)
                || !constantTime(existing.fingerprint(), fingerprint)) {
            throw conflict(
                    "The bulk item key was already used for a different Mail action.");
        }
        if (!"SUCCEEDED".equals(existing.state())) {
            throw conflict("The idempotent Mail bulk item is still in progress.");
        }
        return new Claim(false, existing);
    }

    int complete(
            long tenantId,
            long actorId,
            UUID receiptId,
            long resultingVersion,
            boolean deleted) {
        return jdbc.update("""
                UPDATE mail_bulk_mutation_receipts
                   SET result_state = 'SUCCEEDED', resulting_version = ?, deleted = ?,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND actor_user_id = ? AND receipt_id = ?
                   AND result_state = 'PENDING'
                """, resultingVersion, deleted, tenantId, actorId, receiptId);
    }

    Optional<Receipt> receipt(long tenantId, long actorId, UUID itemIdempotencyKey) {
        return jdbc.query("""
                SELECT receipt_id, thread_id, action_kind, expected_version, intent_id,
                       item_idempotency_key, request_fingerprint, result_state,
                       resulting_version, deleted
                  FROM mail_bulk_mutation_receipts
                 WHERE tenant_id = ? AND actor_user_id = ? AND item_idempotency_key = ?
                """, (result, ignored) -> new Receipt(
                result.getObject("receipt_id", UUID.class),
                result.getObject("thread_id", UUID.class),
                result.getString("action_kind"),
                result.getLong("expected_version"),
                result.getObject("intent_id", UUID.class),
                result.getObject("item_idempotency_key", UUID.class),
                result.getString("request_fingerprint"),
                result.getString("result_state"),
                result.getObject("resulting_version", Long.class),
                result.getBoolean("deleted")),
                tenantId, actorId, itemIdempotencyKey).stream().findFirst();
    }

    static boolean bulk(UUID intentId, UUID itemIdempotencyKey) {
        if ((intentId == null) != (itemIdempotencyKey == null)) {
            throw new IllegalArgumentException(
                    "Bulk intent and item idempotency identities must be supplied together.");
        }
        return intentId != null;
    }

    private void requireBulkIdentity(UUID intentId, UUID itemIdempotencyKey) {
        if (!bulk(intentId, itemIdempotencyKey)) {
            throw new IllegalArgumentException("Bulk mutation identity is required.");
        }
    }

    private String fingerprint(
            long actorId,
            UUID threadId,
            String action,
            long expectedVersion,
            UUID intentId) {
        String canonical = String.join("\u001f",
                Long.toString(actorId), threadId.toString(), action,
                Long.toString(expectedVersion), intentId.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private boolean constantTime(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    record Claim(boolean created, Receipt receipt) { }

    record Receipt(
            UUID receiptId,
            UUID threadId,
            String action,
            long expectedVersion,
            UUID intentId,
            UUID itemIdempotencyKey,
            String fingerprint,
            String state,
            Long resultingVersion,
            boolean deleted) { }
}
