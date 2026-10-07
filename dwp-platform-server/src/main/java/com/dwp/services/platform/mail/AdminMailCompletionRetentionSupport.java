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

class AdminMailCompletionRetentionSupport extends AdminMailCompletionPurgeSupport {

    AdminMailCompletionRetentionSupport(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    Optional<AdminReceiptRow> adminReceipt(long tenantId, long actorId, UUID key) {
        return one("""
                SELECT command_kind, request_fingerprint, aggregate_type, aggregate_id,
                       completed_at, correlation_id
                  FROM mail_admin_command_receipts
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, (rs, ignored) -> new AdminReceiptRow(
                rs.getString("command_kind"), rs.getString("request_fingerprint"),
                rs.getString("aggregate_type"), uuid(rs, "aggregate_id"),
                offset(rs, "completed_at"), rs.getString("correlation_id")),
                tenantId, actorId, key);
    }

    boolean claimAdminReceipt(
            long tenantId, long actorId, String commandKind, UUID key,
            String fingerprint, String correlationId) {
        return jdbc.update("""
                INSERT INTO mail_admin_command_receipts (
                    tenant_id, actor_user_id, command_kind, idempotency_key,
                    request_fingerprint, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, actor_user_id, idempotency_key) DO NOTHING
                """, tenantId, actorId, commandKind, key, fingerprint, correlationId) == 1;
    }

    void completeAdminReceipt(
            long tenantId, long actorId, UUID key, String aggregateType, UUID aggregateId) {
        jdbc.update("""
                UPDATE mail_admin_command_receipts
                   SET aggregate_type = ?, aggregate_id = ?, completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, aggregateType, aggregateId, tenantId, actorId, key);
    }

    PolicyRow policy(long tenantId) {
        return jdbc.queryForObject("""
                SELECT external_sender_banner, block_remote_images, allow_shared_inboxes,
                       ai_assistance_enabled, ai_cross_app_actions_enabled,
                       ai_auto_execute_enabled, retention_days, maximum_attachment_mb,
                       version, updated_at
                  FROM mail_tenant_policies WHERE tenant_id = ?
                """, POLICY, tenantId);
    }

    void lockRetentionLifecycle(long tenantId) {
        jdbc.queryForObject("""
                SELECT tenant_id
                  FROM mail_tenant_policies
                 WHERE tenant_id = ?
                   FOR UPDATE
                """, Long.class, tenantId);
    }

    List<PolicyHistoryRow> policyHistory(long tenantId, int limit) {
        return jdbc.query("""
                SELECT history_id, policy_version, changed_by, diff_summary,
                       apply_result, correlation_id, changed_at
                  FROM mail_policy_history
                 WHERE tenant_id = ? ORDER BY changed_at DESC LIMIT ?
                """, (rs, ignored) -> new PolicyHistoryRow(
                uuid(rs, "history_id"), rs.getLong("policy_version"),
                rs.getLong("changed_by"), map(rs.getString("diff_summary")),
                rs.getString("apply_result"), rs.getString("correlation_id"),
                offset(rs, "changed_at")), tenantId, limit);
    }

    List<LegalHoldRow> legalHolds(long tenantId) {
        return jdbc.query("""
                SELECT hold_id, display_name, safe_case_reference, hold_scope,
                       CASE WHEN hold_status = 'ACTIVE' AND expires_at <= CURRENT_TIMESTAMP
                            THEN 'EXPIRED' ELSE hold_status END hold_status,
                       starts_at, expires_at, version
                  FROM mail_legal_holds
                 WHERE tenant_id = ? ORDER BY created_at DESC
                """, LEGAL_HOLD, tenantId);
    }

    Optional<LegalHoldRow> legalHold(long tenantId, UUID holdId) {
        return one("""
                SELECT hold_id, display_name, safe_case_reference, hold_scope,
                       CASE WHEN hold_status = 'ACTIVE' AND expires_at <= CURRENT_TIMESTAMP
                            THEN 'EXPIRED' ELSE hold_status END hold_status,
                       starts_at, expires_at, version
                  FROM mail_legal_holds
                 WHERE tenant_id = ? AND hold_id = ?
                """, LEGAL_HOLD, tenantId, holdId);
    }

    UUID insertLegalHold(
            long tenantId, String name, String caseRef, Map<String, Object> scope,
            OffsetDateTime startsAt, OffsetDateTime expiresAt, long actorId) {
        return jdbc.queryForObject("""
                INSERT INTO mail_legal_holds (
                    tenant_id, display_name, safe_case_reference, hold_scope,
                    starts_at, expires_at, created_by, updated_by)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                RETURNING hold_id
                """, UUID.class, tenantId, name, caseRef, json(scope), startsAt,
                expiresAt, actorId, actorId);
    }

    int updateLegalHold(
            long tenantId, UUID holdId, String name, String caseRef,
            Map<String, Object> scope, OffsetDateTime startsAt,
            OffsetDateTime expiresAt, long version, long actorId) {
        return jdbc.update("""
                UPDATE mail_legal_holds
                   SET display_name = ?, safe_case_reference = ?, hold_scope = ?::jsonb,
                       starts_at = ?, expires_at = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND hold_id = ? AND version = ? AND hold_status = 'ACTIVE'
                """, name, caseRef, json(scope), startsAt, expiresAt, actorId,
                tenantId, holdId, version);
    }

    int releaseLegalHold(long tenantId, UUID holdId, long version, long actorId) {
        return jdbc.update("""
                UPDATE mail_legal_holds
                   SET hold_status = 'RELEASED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND hold_id = ? AND version = ? AND hold_status = 'ACTIVE'
                """, actorId, tenantId, holdId, version);
    }

    Optional<LegalHoldReleasePreviewRow> legalHoldReleasePreviewByCommand(
            long tenantId, long requesterId, UUID key) {
        return one(LEGAL_HOLD_RELEASE_PREVIEW_BY
                        + " AND requester_user_id = ? AND idempotency_key = ?",
                LEGAL_HOLD_RELEASE_PREVIEW, tenantId, requesterId, key);
    }

    Optional<LegalHoldReleasePreviewRow> legalHoldReleasePreview(
            long tenantId, UUID previewId) {
        return one(LEGAL_HOLD_RELEASE_PREVIEW_BY + " AND release_preview_id = ?",
                LEGAL_HOLD_RELEASE_PREVIEW, tenantId, previewId);
    }

    UUID insertLegalHoldReleasePreview(
            long tenantId, UUID holdId, long requesterId, long holdVersion,
            long policyVersion, Map<String, Object> holdScope,
            OffsetDateTime retentionBoundary, String snapshotFingerprint,
            String requestFingerprint, Map<String, Long> affectedCounts,
            Map<String, Long> currentlyHeldCounts, Map<String, Long> purgeSafeCounts,
            Map<String, Long> protectedCounts, Map<String, Long> providerRequiredCounts,
            UUID key, OffsetDateTime expiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO mail_legal_hold_release_previews (
                    tenant_id, hold_id, requester_user_id, hold_version, policy_version,
                    hold_scope, retention_boundary, snapshot_fingerprint,
                    request_fingerprint, affected_resource_counts,
                    currently_held_resource_counts,
                    purge_safe_after_release_resource_counts,
                    still_protected_after_release_resource_counts,
                    provider_capability_required_resource_counts,
                    idempotency_key, expires_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb,
                        ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                RETURNING release_preview_id
                """, UUID.class, tenantId, holdId, requesterId, holdVersion,
                policyVersion, json(holdScope), retentionBoundary, snapshotFingerprint,
                requestFingerprint, json(affectedCounts), json(currentlyHeldCounts),
                json(purgeSafeCounts), json(protectedCounts), json(providerRequiredCounts),
                key, expiresAt);
    }

    List<LegalHoldReleaseApprovalRow> legalHoldReleaseApprovals(
            long tenantId, UUID previewId) {
        return jdbc.query("""
                SELECT approval_id, release_preview_id, approver_user_id, decision,
                       hold_version, policy_version, preview_fingerprint,
                       request_fingerprint, idempotency_key, decided_at
                  FROM mail_legal_hold_release_approvals
                 WHERE tenant_id = ? AND release_preview_id = ?
                 ORDER BY decided_at, approval_id
                """, LEGAL_HOLD_RELEASE_APPROVAL, tenantId, previewId);
    }

    Optional<LegalHoldReleaseApprovalRow> legalHoldReleaseApprovalByCommand(
            long tenantId, long approverId, UUID key) {
        return one("""
                SELECT approval_id, release_preview_id, approver_user_id, decision,
                       hold_version, policy_version, preview_fingerprint,
                       request_fingerprint, idempotency_key, decided_at
                  FROM mail_legal_hold_release_approvals
                 WHERE tenant_id = ? AND approver_user_id = ? AND idempotency_key = ?
                """, LEGAL_HOLD_RELEASE_APPROVAL, tenantId, approverId, key);
    }

    UUID insertLegalHoldReleaseApproval(
            long tenantId, UUID previewId, long approverId, String decision,
            long holdVersion, long policyVersion, String previewFingerprint,
            String requestFingerprint, UUID key) {
        return jdbc.queryForObject("""
                INSERT INTO mail_legal_hold_release_approvals (
                    tenant_id, release_preview_id, approver_user_id, decision,
                    hold_version, policy_version, preview_fingerprint,
                    request_fingerprint, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING approval_id
                """, UUID.class, tenantId, previewId, approverId, decision,
                holdVersion, policyVersion, previewFingerprint, requestFingerprint, key);
    }

    Optional<LegalHoldReleaseExecutionRow> legalHoldReleaseExecutionByCommand(
            long tenantId, long executorId, UUID key) {
        return one(LEGAL_HOLD_RELEASE_EXECUTION_BY
                        + " AND executed_by_user_id = ? AND idempotency_key = ?",
                LEGAL_HOLD_RELEASE_EXECUTION, tenantId, executorId, key);
    }

    Optional<LegalHoldReleaseExecutionRow> legalHoldReleaseExecutionByPreview(
            long tenantId, UUID previewId) {
        return one(LEGAL_HOLD_RELEASE_EXECUTION_BY + " AND release_preview_id = ?",
                LEGAL_HOLD_RELEASE_EXECUTION, tenantId, previewId);
    }

    UUID insertLegalHoldReleaseExecution(
            long tenantId, UUID previewId, UUID holdId, long requesterId,
            long approvedById, long executorId, long holdVersion, long policyVersion,
            String previewFingerprint, String requestFingerprint, UUID key) {
        return jdbc.queryForObject("""
                INSERT INTO mail_legal_hold_release_executions (
                    tenant_id, release_preview_id, hold_id, requester_user_id,
                    approved_by_user_id, executed_by_user_id, hold_version_before,
                    hold_version_after, policy_version, preview_fingerprint,
                    request_fingerprint, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING execution_id
                """, UUID.class, tenantId, previewId, holdId, requesterId,
                approvedById, executorId, holdVersion, holdVersion + 1,
                policyVersion, previewFingerprint, requestFingerprint, key);
    }

    int activeHoldCount(long tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM mail_legal_holds
                 WHERE tenant_id = ? AND hold_status = 'ACTIVE'
                   AND starts_at <= CURRENT_TIMESTAMP
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                """, Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    List<LegalHoldRow> activeLegalHolds(long tenantId) {
        return jdbc.query("""
                SELECT hold_id, display_name, safe_case_reference, hold_scope,
                       hold_status, starts_at, expires_at, version
                  FROM mail_legal_holds
                 WHERE tenant_id = ? AND hold_status = 'ACTIVE'
                   AND starts_at <= CURRENT_TIMESTAMP
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                 ORDER BY hold_id
                """, LEGAL_HOLD, tenantId);
    }

}
