package com.dwp.services.platform.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class AdminMailCompletionRepositoryDeliverySupport extends AdminMailCompletionRepositoryBase {

    AdminMailCompletionRepositoryDeliverySupport(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    List<DeliveryRow> deliveries(
            long tenantId, String state, String query, int limit, int offset) {
        return deliveries(tenantId, state, query, null, "", "", null, null, limit, offset);
    }

    List<DeliveryRow> deliveries(
            long tenantId, String state, String query, UUID accountId,
            String provider, String command, LocalDate dateFrom, LocalDate dateTo,
            int limit, int offset) {
        String normalizedState = state == null ? "" : state.trim().toUpperCase();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String normalizedProvider = provider == null ? "" : provider.trim().toUpperCase();
        String normalizedCommand = command == null ? "" : command.trim().toUpperCase();
        return jdbc.query("""
                SELECT delivery.delivery_id, delivery.thread_id, delivery.message_id,
                       delivery.delivery_status, delivery.attempt_count,
                       delivery.next_attempt_at, delivery.lease_owner,
                       delivery.lease_expires_at, delivery.provider_message_ref,
                       delivery.provider_thread_ref, delivery.last_error_code,
                       delivery.correlation_id, delivery.accepted_at,
                       delivery.created_at, delivery.created_by, delivery.updated_at,
                       delivery.version, account.display_name account_name,
                       connection.provider_type
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread ON thread.tenant_id = delivery.tenant_id
                                          AND thread.thread_id = delivery.thread_id
                  JOIN mail_accounts account ON account.tenant_id = thread.tenant_id
                                            AND account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE delivery.tenant_id = ?
                   AND (? = '' OR delivery.delivery_status = ?)
                   AND (? = '' OR lower(delivery.delivery_id::text) LIKE '%' || ? || '%'
                        OR lower(delivery.correlation_id) LIKE '%' || ? || '%')
                   AND (?::uuid IS NULL OR account.account_id = ?::uuid)
                   AND (? = '' OR connection.provider_type = ?)
                   AND (? = '' OR ? = 'SEND')
                   AND (?::date IS NULL OR delivery.created_at >= ?::date)
                   AND (?::date IS NULL OR delivery.created_at < (?::date + INTERVAL '1 day'))
                 ORDER BY delivery.updated_at DESC
                 LIMIT ? OFFSET ?
                """, DELIVERY, tenantId, normalizedState, normalizedState,
                normalizedQuery, normalizedQuery, normalizedQuery,
                accountId, accountId, normalizedProvider, normalizedProvider,
                normalizedCommand, normalizedCommand,
                dateFrom, dateFrom, dateTo, dateTo, limit, offset);
    }

    long deliveryCount(long tenantId, String state, String query) {
        return deliveryCount(tenantId, state, query, null, "", "", null, null);
    }

    long deliveryCount(
            long tenantId, String state, String query, UUID accountId,
            String provider, String command, LocalDate dateFrom, LocalDate dateTo) {
        String normalizedState = state == null ? "" : state.trim().toUpperCase();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String normalizedProvider = provider == null ? "" : provider.trim().toUpperCase();
        String normalizedCommand = command == null ? "" : command.trim().toUpperCase();
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread ON thread.tenant_id = delivery.tenant_id
                                          AND thread.thread_id = delivery.thread_id
                  JOIN mail_accounts account ON account.tenant_id = thread.tenant_id
                                            AND account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE delivery.tenant_id = ?
                   AND (? = '' OR delivery.delivery_status = ?)
                   AND (? = '' OR lower(delivery.delivery_id::text) LIKE '%' || ? || '%'
                        OR lower(delivery.correlation_id) LIKE '%' || ? || '%')
                   AND (?::uuid IS NULL OR account.account_id = ?::uuid)
                   AND (? = '' OR connection.provider_type = ?)
                   AND (? = '' OR ? = 'SEND')
                   AND (?::date IS NULL OR delivery.created_at >= ?::date)
                   AND (?::date IS NULL OR delivery.created_at < (?::date + INTERVAL '1 day'))
                """, Long.class, tenantId, normalizedState, normalizedState,
                normalizedQuery, normalizedQuery, normalizedQuery,
                accountId, accountId, normalizedProvider, normalizedProvider,
                normalizedCommand, normalizedCommand,
                dateFrom, dateFrom, dateTo, dateTo);
        return count == null ? 0 : count;
    }

    List<DeliveryEvidenceRow> deliveryEvidence(long tenantId, DeliveryRow delivery) {
        List<DeliveryEvidenceRow> evidence = new ArrayList<>();
        jdbc.query("""
                SELECT event_type, occurred_at, published_at, publish_attempts
                  FROM mail_domain_events
                 WHERE tenant_id = ?
                   AND (aggregate_id IN (?, ?, ?)
                        OR (? <> '' AND correlation_id = ?))
                 ORDER BY occurred_at, domain_event_id
                """, rs -> {
                    OffsetDateTime occurredAt = offset(rs, "occurred_at");
                    OffsetDateTime publishedAt = offset(rs, "published_at");
                    String eventType = rs.getString("event_type");
                    evidence.add(new DeliveryEvidenceRow(
                            "DOMAIN_EVENT_RECORDED", "SUCCEEDED", eventType, occurredAt,
                            "mail_domain_events", "VERIFIED"));
                    evidence.add(new DeliveryEvidenceRow(
                            "DOMAIN_EVENT_PUBLISHED",
                            publishedAt == null ? "UNKNOWN" : "SUCCEEDED",
                            publishedAt == null
                                    ? "UNPUBLISHED_ATTEMPTS_" + rs.getInt("publish_attempts")
                                    : eventType,
                            publishedAt == null ? occurredAt : publishedAt,
                            "mail_domain_events.published_at",
                            publishedAt == null ? "UNAVAILABLE" : "VERIFIED"));
                    evidence.add(new DeliveryEvidenceRow(
                            "DOMAIN_EVENT_CONSUMED", "UNKNOWN",
                            "CONSUMER_RECEIPT_NOT_AVAILABLE",
                            publishedAt == null ? occurredAt : publishedAt,
                            "NO_CONSUMER_RECEIPT_SOURCE", "UNAVAILABLE"));
                },
                tenantId, delivery.id(), delivery.threadId(), delivery.messageId(),
                delivery.correlationId() == null ? "" : delivery.correlationId(),
                delivery.correlationId() == null ? "" : delivery.correlationId());
        evidence.addAll(jdbc.query("""
                SELECT action, occurred_at
                  FROM mail_audit_events
                 WHERE tenant_id = ?
                   AND (target_id IN (?, ?, ?)
                        OR (? <> '' AND correlation_id = ?))
                 ORDER BY occurred_at, audit_event_id
                """, (rs, ignored) -> new DeliveryEvidenceRow(
                "AUDIT_RECORDED", "SUCCEEDED", rs.getString("action"),
                offset(rs, "occurred_at"), "mail_audit_events", "VERIFIED"),
                tenantId, delivery.id().toString(), delivery.threadId().toString(),
                delivery.messageId().toString(),
                delivery.correlationId() == null ? "" : delivery.correlationId(),
                delivery.correlationId() == null ? "" : delivery.correlationId()));
        return evidence.stream().sorted(java.util.Comparator.comparing(DeliveryEvidenceRow::at))
                .toList();
    }

    Optional<DeliveryRow> delivery(long tenantId, UUID deliveryId) {
        return one("""
                SELECT delivery.delivery_id, delivery.thread_id, delivery.message_id,
                       delivery.delivery_status, delivery.attempt_count,
                       delivery.next_attempt_at, delivery.lease_owner,
                       delivery.lease_expires_at, delivery.provider_message_ref,
                       delivery.provider_thread_ref, delivery.last_error_code,
                       delivery.correlation_id, delivery.accepted_at,
                       delivery.created_at, delivery.created_by, delivery.updated_at,
                       delivery.version, account.display_name account_name,
                       connection.provider_type
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread ON thread.thread_id = delivery.thread_id
                  JOIN mail_accounts account ON account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.connection_id = account.connection_id
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                """, DELIVERY, tenantId, deliveryId);
    }

    Optional<DeliveryAuthorizationRow> deliveryAuthorization(
            long tenantId, UUID deliveryId) {
        return jdbc.query("""
                SELECT account.account_id, connection.connection_id,
                       connection.provider_type, connection.credential_ref,
                       connection.mail_domain, delivery.created_by,
                       CASE
                           WHEN account.account_kind = 'PERSONAL' THEN 'ACCOUNT'
                           WHEN access_grant.can_send_as THEN 'SEND_AS'
                           ELSE 'SEND_ON_BEHALF'
                       END sender_mode,
                       message.body_format,
                       EXISTS (
                           SELECT 1
                             FROM jsonb_array_elements(
                                      COALESCE(snapshot.recipients, message.recipients,
                                               '[]'::jsonb)) recipient
                            WHERE UPPER(TRIM(COALESCE(recipient ->> 'type', 'TO'))) = 'BCC'
                       ) has_bcc,
                       jsonb_array_length(COALESCE(message.attachments, '[]'::jsonb))
                           attachment_count
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread
                    ON thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                  JOIN mail_messages message
                    ON message.tenant_id = delivery.tenant_id
                   AND message.thread_id = delivery.thread_id
                   AND message.message_id = delivery.message_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                  LEFT JOIN mail_group_recipient_snapshots snapshot
                    ON snapshot.tenant_id = delivery.tenant_id
                   AND snapshot.delivery_id = delivery.delivery_id
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
                   AND membership.user_id = delivery.created_by
                   AND membership.lifecycle_state = 'ACTIVE'
                  LEFT JOIN mail_shared_inbox_access_grants access_grant
                    ON access_grant.tenant_id = membership.tenant_id
                   AND access_grant.shared_inbox_id = membership.shared_inbox_id
                   AND access_grant.user_id = membership.user_id
                   AND access_grant.member_state = 'ACTIVE'
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                   AND connection.connection_state = 'ACTIVE'
                   AND account.connection_state = 'ACTIVE'
                   AND (
                       (account.account_kind = 'PERSONAL'
                            AND account.owner_user_id = delivery.created_by)
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
                """, (rs, ignored) -> new DeliveryAuthorizationRow(
                uuid(rs, "account_id"), uuid(rs, "connection_id"),
                rs.getString("provider_type"), rs.getString("credential_ref"),
                rs.getString("mail_domain"), rs.getLong("created_by"),
                rs.getString("sender_mode"), rs.getString("body_format"),
                rs.getBoolean("has_bcc"), rs.getInt("attachment_count")),
                tenantId, deliveryId).stream().findFirst();
    }

    List<RecoveryRow> recoveryEvents(long tenantId, UUID deliveryId) {
        return jdbc.query("""
                SELECT recovery_event_id, action_kind, result_state, evidence,
                       correlation_id, occurred_at
                  FROM mail_delivery_recovery_events
                 WHERE tenant_id = ? AND delivery_id = ?
                 ORDER BY occurred_at
                """, (rs, ignored) -> new RecoveryRow(
                uuid(rs, "recovery_event_id"), rs.getString("action_kind"),
                rs.getString("result_state"), map(rs.getString("evidence")),
                rs.getString("correlation_id"), offset(rs, "occurred_at")),
                tenantId, deliveryId);
    }

    Optional<RecoveryCommandRow> recoveryCommand(long tenantId, long actorId, UUID key) {
        return one("""
                SELECT recovery_event_id, delivery_id, action_kind, result_state,
                       request_fingerprint
                  FROM mail_delivery_recovery_events
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, (rs, ignored) -> new RecoveryCommandRow(
                uuid(rs, "recovery_event_id"), uuid(rs, "delivery_id"),
                rs.getString("action_kind"), rs.getString("result_state"),
                rs.getString("request_fingerprint")), tenantId, actorId, key);
    }

    UUID insertRecoveryEvent(
            long tenantId, UUID deliveryId, long actorId, String action,
            String result, Map<String, Object> evidence, UUID key,
            String fingerprint, String correlationId) {
        return jdbc.queryForObject("""
                INSERT INTO mail_delivery_recovery_events (
                    tenant_id, delivery_id, actor_user_id, action_kind, result_state,
                    evidence, idempotency_key, request_fingerprint, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                RETURNING recovery_event_id
                """, UUID.class, tenantId, deliveryId, actorId, action, result,
                json(evidence), key, fingerprint, correlationId);
    }

    int retryDelivery(long tenantId, UUID deliveryId, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = 'RETRY_WAIT', next_attempt_at = CURRENT_TIMESTAMP,
                       last_error_code = NULL, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND delivery_id = ? AND version = ?
                   AND delivery_status = 'FAILED'
                   AND accepted_at IS NULL AND provider_message_ref IS NULL
                   AND provider_thread_ref IS NULL
                   AND lease_owner IS NULL AND lease_expires_at IS NULL
                   AND request_fingerprint IS NOT NULL
                   AND COALESCE(last_error_code, '') <> 'MAIL_PROVIDER_RESULT_UNKNOWN'
                """, tenantId, deliveryId, version);
    }

    int blockDeliveryAccess(
            long tenantId, UUID deliveryId, long version, String errorCode) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = 'FAILED', next_attempt_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND delivery_id = ? AND version = ?
                   AND delivery_status IN ('QUEUED', 'RETRY_WAIT', 'LEASED', 'FAILED')
                   AND accepted_at IS NULL AND provider_message_ref IS NULL
                   AND provider_thread_ref IS NULL
                """, errorCode, tenantId, deliveryId, version);
    }

    int cancelDelivery(long tenantId, UUID deliveryId, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = 'CANCELLED', next_attempt_at = CURRENT_TIMESTAMP,
                       last_error_code = 'ADMIN_CANCELLED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND delivery_id = ? AND version = ?
                   AND delivery_status IN ('QUEUED', 'RETRY_WAIT')
                   AND accepted_at IS NULL AND provider_message_ref IS NULL
                   AND lease_owner IS NULL AND lease_expires_at IS NULL
                """, tenantId, deliveryId, version);
    }

    int failExpiredSandboxLease(long tenantId, UUID deliveryId, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET delivery_status = 'FAILED', lease_owner = NULL, lease_expires_at = NULL,
                       last_error_code = 'ADMIN_RECONCILED_EXPIRED_LEASE',
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND delivery_id = ? AND version = ?
                   AND delivery_status = 'LEASED' AND lease_expires_at < CURRENT_TIMESTAMP
                   AND accepted_at IS NULL AND provider_message_ref IS NULL
                   AND request_fingerprint IS NOT NULL
                """, tenantId, deliveryId, version);
    }

    Optional<ExportRow> exportByCommand(long tenantId, long actorId, UUID key) {
        return one(EXPORT_BY + " AND actor_user_id = ? AND idempotency_key = ?",
                EXPORT, tenantId, actorId, key);
    }

    Optional<ExportRow> export(long tenantId, long actorId, UUID exportId) {
        return one(EXPORT_BY + " AND actor_user_id = ? AND export_id = ?",
                EXPORT, tenantId, actorId, exportId);
    }

    Optional<ExportRow> export(long tenantId, UUID exportId) {
        return one(EXPORT_BY + " AND export_id = ?", EXPORT, tenantId, exportId);
    }

    Optional<ExportRow> exportForUpdate(long tenantId, UUID exportId) {
        return one(EXPORT_BY + " AND export_id = ? FOR UPDATE", EXPORT, tenantId, exportId);
    }

    Optional<UUID> insertExport(
            UUID exportId,
            long tenantId,
            long actorId,
            Map<String, Object> filters,
            String purpose,
            String watermark,
            UUID key,
            OffsetDateTime expiresAt,
            String snapshotPayload,
            String payloadSha256,
            int itemCount,
            boolean truncated,
            OffsetDateTime snapshotCutoff,
            String exportKind,
            Map<String, Object> exportScope,
            Long policyVersion,
            String requestFingerprint) {
        return jdbc.query("""
                INSERT INTO mail_delivery_audit_exports (
                    export_id, tenant_id, actor_user_id, filters, purpose, export_state,
                    storage_reference, watermark, idempotency_key, expires_at,
                    snapshot_payload, payload_sha256, item_count, truncated, snapshot_cutoff,
                    export_kind, export_scope, policy_version, required_approvals,
                    request_fingerprint)
                VALUES (?, ?, ?, ?::jsonb, ?, 'PENDING_APPROVAL', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?::jsonb, ?, 1, ?)
                ON CONFLICT (tenant_id, actor_user_id, idempotency_key) DO NOTHING
                RETURNING export_id
                """, (result, ignored) -> uuid(result, "export_id"),
                exportId, tenantId, actorId, json(filters), purpose,
                "DATABASE_SNAPSHOT:" + payloadSha256, watermark, key, expiresAt,
                snapshotPayload, payloadSha256, itemCount, truncated, snapshotCutoff,
                exportKind, json(exportScope), policyVersion, requestFingerprint)
                .stream().findFirst();
    }

    Optional<ExportApprovalRow> exportApprovalByCommand(
            long tenantId, long actorId, UUID idempotencyKey) {
        return one("""
                SELECT approval_id, export_id, approver_user_id, decision,
                       request_fingerprint, decided_at
                  FROM mail_evidence_export_approvals
                 WHERE tenant_id = ? AND approver_user_id = ? AND idempotency_key = ?
                """, EXPORT_APPROVAL, tenantId, actorId, idempotencyKey);
    }

    Optional<UUID> insertExportApproval(
            long tenantId, UUID exportId, long actorId, UUID idempotencyKey,
            String requestFingerprint) {
        return jdbc.query("""
                INSERT INTO mail_evidence_export_approvals (
                    tenant_id, export_id, approver_user_id, decision,
                    idempotency_key, request_fingerprint)
                VALUES (?, ?, ?, 'APPROVED', ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING approval_id
                """, (result, ignored) -> uuid(result, "approval_id"),
                tenantId, exportId, actorId, idempotencyKey, requestFingerprint)
                .stream().findFirst();
    }

    List<ExportApprovalRow> exportApprovals(long tenantId, UUID exportId) {
        return jdbc.query("""
                SELECT approval_id, export_id, approver_user_id, decision,
                       request_fingerprint, decided_at
                  FROM mail_evidence_export_approvals
                 WHERE tenant_id = ? AND export_id = ?
                 ORDER BY decided_at, approver_user_id
                """, EXPORT_APPROVAL, tenantId, exportId);
    }

    int markExportReady(long tenantId, UUID exportId) {
        return jdbc.update("""
                UPDATE mail_delivery_audit_exports target
                   SET export_state = 'READY'
                 WHERE tenant_id = ? AND export_id = ?
                   AND export_state = 'PENDING_APPROVAL'
                   AND expires_at > CURRENT_TIMESTAMP
                   AND (SELECT count(DISTINCT approval.approver_user_id)
                          FROM mail_evidence_export_approvals approval
                         WHERE approval.tenant_id = target.tenant_id
                           AND approval.export_id = target.export_id
                           AND approval.decision = 'APPROVED') >= target.required_approvals
                """, tenantId, exportId);
    }

    void audit(
            long tenantId, long actorId, String action, String targetType,
            String targetId, String correlationId, Map<String, Object> before,
            Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO mail_audit_events (
                    tenant_id, actor_user_id, action, target_type, target_id,
                    correlation_id, before_snapshot, after_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, tenantId, actorId, action, targetType, targetId,
                correlationId, json(before), json(after));
    }

}
