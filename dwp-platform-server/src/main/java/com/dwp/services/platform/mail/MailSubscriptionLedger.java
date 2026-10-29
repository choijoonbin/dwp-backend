package com.dwp.services.platform.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Durable, hash-only subscription lifecycle and renewal lease ledger. */
@Repository
class MailSubscriptionLedger {

    private static final String SYSTEM_PRINCIPAL = "MAIL_SUBSCRIPTION_RENEWAL";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    MailSubscriptionLedger(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    int scheduleMissingSubscriptions() {
        jdbc.update("""
                INSERT INTO mail_system_principals (
                    tenant_id, principal_key, principal_evidence)
                SELECT DISTINCT connection.tenant_id, ?,
                       jsonb_build_object(
                           'kind', 'SYSTEM', 'owner', 'dwp-platform-server')
                  FROM mail_provider_connections connection
                 WHERE connection.connection_state = 'ACTIVE'
                   AND connection.provider_type IN ('MICROSOFT_GRAPH', 'GOOGLE_GMAIL')
                ON CONFLICT (tenant_id, principal_key) DO NOTHING
                """, SYSTEM_PRINCIPAL);
        return jdbc.update("""
                INSERT INTO mail_provider_subscriptions (
                    tenant_id, connection_id, account_id, actor_user_id,
                    subscription_state, renewal_due_at, next_renewal_attempt_at)
                SELECT connection.tenant_id, connection.connection_id, account.account_id,
                       principal.actor_id, 'PENDING', CURRENT_TIMESTAMP,
                       CURRENT_TIMESTAMP
                  FROM mail_provider_connections connection
                  JOIN mail_accounts account
                    ON account.tenant_id = connection.tenant_id
                   AND account.connection_id = connection.connection_id
                   AND account.connection_state = 'ACTIVE'
                  JOIN mail_system_principals principal
                    ON principal.tenant_id = connection.tenant_id
                   AND principal.principal_key = ?
                 WHERE connection.connection_state = 'ACTIVE'
                   AND connection.provider_type IN ('MICROSOFT_GRAPH', 'GOOGLE_GMAIL')
                   AND NOT EXISTS (
                       SELECT 1 FROM mail_provider_subscriptions subscription
                        WHERE subscription.tenant_id = connection.tenant_id
                          AND subscription.connection_id = connection.connection_id
                          AND subscription.account_id = account.account_id
                          AND subscription.subscription_state NOT IN ('EXPIRED', 'REVOKED'))
                ON CONFLICT DO NOTHING
                """, SYSTEM_PRINCIPAL);
    }

    @Transactional
    UUID schedule(long tenantId, UUID connectionId, UUID accountId) {
        if (tenantId <= 0 || connectionId == null || accountId == null) {
            throw new IllegalArgumentException("SUBSCRIPTION_ACCOUNT_REQUIRED");
        }
        long actorId = ensureSystemPrincipal(tenantId);
        UUID created = jdbc.query("""
                INSERT INTO mail_provider_subscriptions (
                    tenant_id, connection_id, account_id, actor_user_id,
                    subscription_state, renewal_due_at, next_renewal_attempt_at)
                VALUES (?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
                RETURNING subscription_id
                """, (result, ignored) -> result.getObject("subscription_id", UUID.class),
                tenantId, connectionId, accountId, actorId)
                .stream().findFirst().orElse(null);
        if (created != null) return created;
        return jdbc.query("""
                SELECT subscription_id
                  FROM mail_provider_subscriptions
                 WHERE tenant_id = ? AND connection_id = ?
                   AND account_id IS NOT DISTINCT FROM ?
                   AND subscription_state NOT IN ('EXPIRED', 'REVOKED')
                 ORDER BY created_at DESC LIMIT 1
                """, (result, ignored) -> result.getObject("subscription_id", UUID.class),
                tenantId, connectionId, accountId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "MAIL_SUBSCRIPTION_SCHEDULE_UNAVAILABLE"));
    }

    @Transactional
    List<Renewal> claim(String workerId, int limit, int leaseSeconds) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT subscription_id
                      FROM mail_provider_subscriptions
                     WHERE subscription_state IN (
                               'PENDING', 'ACTIVE', 'RENEWAL_DUE', 'FAILED')
                       AND (subscription_state <> 'FAILED'
                            OR next_renewal_attempt_at IS NOT NULL)
                       AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                       AND COALESCE(next_renewal_attempt_at, renewal_due_at)
                           <= CURRENT_TIMESTAMP
                       AND (subscription_state <> 'ACTIVE'
                            OR renewal_due_at <= CURRENT_TIMESTAMP)
                     ORDER BY COALESCE(next_renewal_attempt_at, renewal_due_at),
                              created_at, subscription_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), leased AS (
                    UPDATE mail_provider_subscriptions subscription
                       SET subscription_state = 'RENEWING',
                           renewal_attempt_count = renewal_attempt_count + 1,
                           next_renewal_attempt_at = NULL,
                           lease_owner = ?,
                           lease_expires_at = CURRENT_TIMESTAMP
                               + (? * INTERVAL '1 second'),
                           worker_evidence = '{}'::jsonb,
                           updated_at = CURRENT_TIMESTAMP
                      FROM candidates
                     WHERE subscription.subscription_id = candidates.subscription_id
                    RETURNING subscription.*
                )
                SELECT subscription_id, tenant_id, connection_id, account_id,
                       actor_user_id, renewal_attempt_count
                  FROM leased
                 ORDER BY created_at, subscription_id
                """, (result, ignored) -> new Renewal(
                result.getObject("subscription_id", UUID.class),
                result.getLong("tenant_id"),
                result.getObject("connection_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getLong("actor_user_id"),
                result.getInt("renewal_attempt_count")),
                limit, workerId, leaseSeconds);
    }

    @Transactional
    LeaseRecovery releaseExpiredLeases(int maximumAttempts) {
        List<Boolean> terminal = jdbc.query("""
                UPDATE mail_provider_subscriptions
                   SET subscription_state = 'FAILED',
                       next_renewal_attempt_at = CASE
                           WHEN renewal_attempt_count >= ? THEN NULL
                           ELSE CURRENT_TIMESTAMP END,
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = 'SUBSCRIPTION_RENEWAL_LEASE_EXPIRED',
                       worker_evidence = jsonb_build_object(
                           'outcome', CASE WHEN renewal_attempt_count >= ?
                               THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                           'classification', 'LEASE_EXPIRED',
                           'attempt', renewal_attempt_count),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE subscription_state = 'RENEWING'
                   AND lease_expires_at <= CURRENT_TIMESTAMP
                RETURNING renewal_attempt_count >= ? terminal
                """, (result, ignored) -> result.getBoolean("terminal"),
                maximumAttempts, maximumAttempts, maximumAttempts);
        int failures = (int) terminal.stream().filter(Boolean::booleanValue).count();
        return new LeaseRecovery(terminal.size() - failures, failures);
    }

    @Transactional
    int expireDue() {
        List<Expired> expired = jdbc.query("""
                UPDATE mail_provider_subscriptions
                   SET subscription_state = 'EXPIRED',
                       next_renewal_attempt_at = NULL,
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = 'SUBSCRIPTION_EXPIRED',
                       worker_evidence = jsonb_build_object('outcome', 'EXPIRED'),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE expires_at <= CURRENT_TIMESTAMP
                   AND subscription_state NOT IN ('EXPIRED', 'REVOKED')
                RETURNING subscription_id, tenant_id, connection_id, actor_user_id
                """, (result, ignored) -> new Expired(
                result.getObject("subscription_id", UUID.class),
                result.getLong("tenant_id"),
                result.getObject("connection_id", UUID.class),
                result.getLong("actor_user_id")));
        for (Expired row : expired) {
            audit(row.tenantId(), row.actorId(), row.connectionId(),
                    row.subscriptionId(), "mail.subscription.expired",
                    Map.of("outcome", "EXPIRED"));
        }
        return expired.size();
    }

    @Transactional
    boolean completeSuccess(
            Renewal renewal,
            String workerId,
            String providerReferenceHash,
            String clientStateHash,
            OffsetDateTime expiresAt,
            OffsetDateTime renewalDueAt,
            Map<String, Object> evidence) {
        requireSha256(providerReferenceHash);
        requireOptionalSha256(clientStateHash);
        requireWindow(expiresAt, renewalDueAt);
        Map<String, Object> safe = safeEvidence(evidence);
        int updated = jdbc.update("""
                UPDATE mail_provider_subscriptions
                   SET provider_subscription_reference_hash = ?,
                       client_state_hash = ?, subscription_state = 'ACTIVE',
                       expires_at = ?, renewal_due_at = ?,
                       next_renewal_attempt_at = ?,
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_renewed_at = CURRENT_TIMESTAMP,
                       last_verified_at = CURRENT_TIMESTAMP,
                       renewal_attempt_count = 0, last_error_code = NULL,
                       verification_evidence = ?::jsonb,
                       worker_evidence = jsonb_build_object('outcome', 'RENEWED'),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND subscription_id = ?
                   AND subscription_state = 'RENEWING'
                   AND lease_owner = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, providerReferenceHash, clientStateHash, expiresAt, renewalDueAt,
                renewalDueAt, json(safe), renewal.tenantId(),
                renewal.subscriptionId(), workerId);
        if (updated == 1) {
            audit(renewal.tenantId(), renewal.actorId(), renewal.connectionId(),
                    renewal.subscriptionId(), "mail.subscription.renewed",
                    Map.of("outcome", "RENEWED", "attempt", renewal.attemptCount()));
        }
        return updated == 1;
    }

    @Transactional
    FailureDisposition completeFailure(
            Renewal renewal,
            String workerId,
            String errorCode,
            String classification,
            OffsetDateTime retryAt,
            boolean terminal) {
        if (errorCode == null || !errorCode.matches("[A-Z0-9_.-]{1,120}")
                || classification == null
                || !classification.matches("[A-Z0-9_]{1,40}")) {
            throw new IllegalArgumentException("SUBSCRIPTION_FAILURE_EVIDENCE_INVALID");
        }
        int backoffSeconds = Math.min(3600,
                1 << Math.min(12, Math.max(0, renewal.attemptCount() - 1)));
        Map<String, Object> evidence = Map.of(
                "outcome", terminal ? "FAILED" : "RETRY_WAIT",
                "classification", classification,
                "attempt", renewal.attemptCount(),
                "errorCode", errorCode);
        int updated = jdbc.update("""
                UPDATE mail_provider_subscriptions
                   SET subscription_state = 'FAILED',
                       next_renewal_attempt_at = CASE WHEN ? THEN NULL ELSE
                           GREATEST(COALESCE(?, CURRENT_TIMESTAMP
                               + (? * INTERVAL '1 second')), CURRENT_TIMESTAMP) END,
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = ?, worker_evidence = ?::jsonb,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND subscription_id = ?
                   AND subscription_state = 'RENEWING'
                   AND lease_owner = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, terminal, retryAt, backoffSeconds, errorCode, json(evidence),
                renewal.tenantId(), renewal.subscriptionId(), workerId);
        if (updated == 1 && terminal) {
            audit(renewal.tenantId(), renewal.actorId(), renewal.connectionId(),
                    renewal.subscriptionId(), "mail.subscription.failed", evidence);
        }
        return new FailureDisposition(updated == 1, terminal);
    }

    @Transactional
    UUID recordVerified(
            long tenantId,
            UUID connectionId,
            UUID accountId,
            String providerReferenceHash,
            String clientStateHash,
            OffsetDateTime expiresAt,
            OffsetDateTime renewalDueAt,
            Map<String, Object> safeEvidence) {
        requireSha256(providerReferenceHash);
        requireOptionalSha256(clientStateHash);
        requireWindow(expiresAt, renewalDueAt);
        Map<String, Object> safe = safeEvidence(safeEvidence);
        UUID id = schedule(tenantId, connectionId, accountId);
        int updated = jdbc.update("""
                UPDATE mail_provider_subscriptions
                   SET provider_subscription_reference_hash = ?,
                       client_state_hash = ?, subscription_state = 'ACTIVE',
                       expires_at = ?, renewal_due_at = ?,
                       next_renewal_attempt_at = ?, lease_owner = NULL,
                       lease_expires_at = NULL, last_renewed_at = CURRENT_TIMESTAMP,
                       last_verified_at = CURRENT_TIMESTAMP,
                       renewal_attempt_count = 0, last_error_code = NULL,
                       verification_evidence = ?::jsonb,
                       worker_evidence = jsonb_build_object('outcome', 'VERIFIED'),
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND subscription_id = ?
                """, providerReferenceHash, clientStateHash, expiresAt, renewalDueAt,
                renewalDueAt, json(safe), tenantId, id);
        if (updated != 1) {
            throw new IllegalStateException("MAIL_SUBSCRIPTION_RECORD_UNAVAILABLE");
        }
        return id;
    }

    private long ensureSystemPrincipal(long tenantId) {
        jdbc.update("""
                INSERT INTO mail_system_principals (
                    tenant_id, principal_key, principal_evidence)
                VALUES (?, ?, jsonb_build_object(
                    'kind', 'SYSTEM', 'owner', 'dwp-platform-server'))
                ON CONFLICT (tenant_id, principal_key) DO NOTHING
                """, tenantId, SYSTEM_PRINCIPAL);
        Long actor = jdbc.queryForObject("""
                SELECT actor_id FROM mail_system_principals
                 WHERE tenant_id = ? AND principal_key = ?
                """, Long.class, tenantId, SYSTEM_PRINCIPAL);
        if (actor == null || actor >= 0) {
            throw new IllegalStateException("MAIL_SUBSCRIPTION_PRINCIPAL_UNAVAILABLE");
        }
        return actor;
    }

    private void audit(
            long tenantId,
            long actorId,
            UUID connectionId,
            UUID subscriptionId,
            String action,
            Map<String, Object> evidence) {
        Map<String, Object> after = new LinkedHashMap<>(evidence);
        after.put("subscriptionId", subscriptionId);
        jdbc.update("""
                INSERT INTO mail_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action,
                    target_type, target_id, before_snapshot, after_snapshot)
                VALUES (?, ?, ?, ?, 'MAIL_CONNECTION', ?, '{}'::jsonb, ?::jsonb)
                """, UUID.randomUUID(), tenantId, actorId, action,
                connectionId.toString(), json(after));
    }

    private void requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SUBSCRIPTION_HASH_INVALID");
        }
    }

    private void requireOptionalSha256(String value) {
        if (value != null) requireSha256(value);
    }

    private void requireWindow(OffsetDateTime expiresAt, OffsetDateTime renewalDueAt) {
        if (expiresAt == null || renewalDueAt == null || renewalDueAt.isAfter(expiresAt)) {
            throw new IllegalArgumentException("SUBSCRIPTION_WINDOW_INVALID");
        }
    }

    private Map<String, Object> safeEvidence(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) return Map.of();
        if (evidence.size() > 20) {
            throw new IllegalArgumentException("SUBSCRIPTION_EVIDENCE_INVALID");
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : evidence.entrySet()) {
            String key = entry.getKey();
            String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")
                    || normalized.contains("token") || normalized.contains("secret")
                    || normalized.contains("credential") || normalized.contains("state")
                    || normalized.contains("reference") || normalized.contains("cursor")
                    || normalized.contains("authorization") || normalized.contains("url")) {
                throw new IllegalArgumentException("SUBSCRIPTION_EVIDENCE_INVALID");
            }
            Object value = entry.getValue();
            if (value instanceof String text) {
                if (!text.matches("[A-Za-z0-9._:-]{1,160}")) {
                    throw new IllegalArgumentException("SUBSCRIPTION_EVIDENCE_INVALID");
                }
            } else if (!(value instanceof Boolean) && !(value instanceof Number)) {
                throw new IllegalArgumentException("SUBSCRIPTION_EVIDENCE_INVALID");
            }
            safe.put(key, value);
        }
        return Map.copyOf(safe);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("SUBSCRIPTION_EVIDENCE_INVALID", failure);
        }
    }

    record Renewal(
            UUID subscriptionId,
            long tenantId,
            UUID connectionId,
            UUID accountId,
            long actorId,
            int attemptCount) { }

    record FailureDisposition(boolean persisted, boolean terminal) { }
    record LeaseRecovery(int retryWait, int failed) { }
    private record Expired(
            UUID subscriptionId,
            long tenantId,
            UUID connectionId,
            long actorId) { }
}
