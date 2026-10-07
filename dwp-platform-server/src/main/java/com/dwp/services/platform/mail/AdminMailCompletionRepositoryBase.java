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

class AdminMailCompletionRepositoryBase {

    /**
     * Evidence rows below are intentionally retained by RESTRICT foreign keys. Keep this
     * predicate shared by preview, deletion, and post-delete verification so an approved
     * candidate set can never expand at execution time.
     */
    static final String PURGE_IMMUTABLE_EVIDENCE_EXISTS = """
            EXISTS (
                SELECT 1
                  FROM mail_group_recipient_snapshots snapshot
                 WHERE snapshot.tenant_id = thread.tenant_id
                   AND (snapshot.thread_id = thread.thread_id
                        OR EXISTS (
                            SELECT 1
                              FROM mail_messages evidence_message
                             WHERE evidence_message.tenant_id = thread.tenant_id
                               AND evidence_message.thread_id = thread.thread_id
                               AND evidence_message.message_id = snapshot.message_id)))
            OR EXISTS (
                SELECT 1
                  FROM mail_group_send_history history
                 WHERE history.tenant_id = thread.tenant_id
                   AND history.thread_id = thread.thread_id)
            OR EXISTS (
                SELECT 1
                  FROM mail_draft_command_receipts receipt
                 WHERE receipt.tenant_id = thread.tenant_id
                   AND receipt.thread_id = thread.thread_id)
            OR EXISTS (
                SELECT 1
                  FROM mail_rule_backfill_applications application
                 WHERE application.tenant_id = thread.tenant_id
                   AND application.thread_id = thread.thread_id)
            """;

    static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() { };

    final JdbcTemplate jdbc;
    final ObjectMapper objectMapper;

    AdminMailCompletionRepositoryBase(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbc.query(sql, mapper, args);
        return rows.stream().findFirst();
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize mail administrator evidence", exception);
        }
    }

    Map<String, Object> map(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid mail administrator object evidence", exception);
        }
    }

    List<String> strings(String value) {
        try {
            return value == null ? List.of() : objectMapper.readValue(value, STRINGS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid mail administrator list evidence", exception);
        }
    }

    List<Map<String, Object>> maps(String value) {
        try {
            return value == null ? List.of() : objectMapper.readValue(value, MAPS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid mail administrator step evidence", exception);
        }
    }

    static UUID uuid(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    static OffsetDateTime offset(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, OffsetDateTime.class);
    }

    static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    static final RowMapper<ConnectionRow> CONNECTION = (rs, ignored) -> new ConnectionRow(
            uuid(rs, "connection_id"), rs.getString("provider_type"),
            rs.getString("connection_state"), rs.getString("credential_ref"),
            rs.getString("mail_domain"), rs.getLong("version"),
            offset(rs, "last_synchronized_at"), rs.getString("last_error_code"),
            offset(rs, "updated_at"));

    static final RowMapper<ConnectionOperationRow> CONNECTION_OPERATION = (rs, ignored) ->
            new ConnectionOperationRow(
                    uuid(rs, "operation_id"), uuid(rs, "connection_id"),
                    rs.getString("operation_kind"), rs.getString("operation_state"),
                    uuid(rs, "idempotency_key"), rs.getString("request_fingerprint"),
                    rs.getString("correlation_id"), offset(rs, "evidence_generated_at"),
                    rs.getString("error_code"), offset(rs, "accepted_at"),
                    offset(rs, "completed_at"));

    static final RowMapper<AccessGrantRow> ACCESS_GRANT = (rs, ignored) -> new AccessGrantRow(
            uuid(rs, "member_id"), rs.getLong("user_id"), rs.getString("display_name"),
            rs.getString("department"), rs.getString("member_state"),
            offset(rs, "expires_at"), rs.getBoolean("can_read"),
            rs.getBoolean("can_send_as"), rs.getBoolean("can_send_on_behalf"),
            rs.getBoolean("can_assign"), rs.getBoolean("can_manage"),
            rs.getString("provider_state"), rs.getLong("version"));

    static final RowMapper<PolicyRow> POLICY = (rs, ignored) -> new PolicyRow(
            rs.getBoolean("external_sender_banner"), rs.getBoolean("block_remote_images"),
            rs.getBoolean("allow_shared_inboxes"), rs.getBoolean("ai_assistance_enabled"),
            rs.getBoolean("ai_cross_app_actions_enabled"), rs.getBoolean("ai_auto_execute_enabled"),
            rs.getInt("retention_days"), rs.getInt("maximum_attachment_mb"),
            rs.getLong("version"), offset(rs, "updated_at"));

    final RowMapper<LegalHoldRow> LEGAL_HOLD = (rs, ignored) -> new LegalHoldRow(
            uuid(rs, "hold_id"), rs.getString("display_name"),
            rs.getString("safe_case_reference"), map(rs.getString("hold_scope")),
            rs.getString("hold_status"), offset(rs, "starts_at"),
            offset(rs, "expires_at"), rs.getLong("version"));

    static final String LEGAL_HOLD_RELEASE_PREVIEW_BY = """
            SELECT release_preview_id, hold_id, requester_user_id, hold_version,
                   policy_version, hold_scope, retention_boundary, snapshot_fingerprint,
                   request_fingerprint, affected_resource_counts,
                   currently_held_resource_counts,
                   purge_safe_after_release_resource_counts,
                   still_protected_after_release_resource_counts,
                   provider_capability_required_resource_counts,
                   idempotency_key, generated_at, expires_at
              FROM mail_legal_hold_release_previews WHERE tenant_id = ?
            """;

    final RowMapper<LegalHoldReleasePreviewRow> LEGAL_HOLD_RELEASE_PREVIEW =
            (rs, ignored) -> new LegalHoldReleasePreviewRow(
                    uuid(rs, "release_preview_id"), uuid(rs, "hold_id"),
                    rs.getLong("requester_user_id"), rs.getLong("hold_version"),
                    rs.getLong("policy_version"), map(rs.getString("hold_scope")),
                    offset(rs, "retention_boundary"),
                    rs.getString("snapshot_fingerprint"),
                    rs.getString("request_fingerprint"),
                    map(rs.getString("affected_resource_counts")),
                    map(rs.getString("currently_held_resource_counts")),
                    map(rs.getString("purge_safe_after_release_resource_counts")),
                    map(rs.getString("still_protected_after_release_resource_counts")),
                    map(rs.getString("provider_capability_required_resource_counts")),
                    uuid(rs, "idempotency_key"), offset(rs, "generated_at"),
                    offset(rs, "expires_at"));

    static final RowMapper<LegalHoldReleaseApprovalRow> LEGAL_HOLD_RELEASE_APPROVAL =
            (rs, ignored) -> new LegalHoldReleaseApprovalRow(
                    uuid(rs, "approval_id"), uuid(rs, "release_preview_id"),
                    rs.getLong("approver_user_id"), rs.getString("decision"),
                    rs.getLong("hold_version"), rs.getLong("policy_version"),
                    rs.getString("preview_fingerprint"),
                    rs.getString("request_fingerprint"), uuid(rs, "idempotency_key"),
                    offset(rs, "decided_at"));

    static final String LEGAL_HOLD_RELEASE_EXECUTION_BY = """
            SELECT execution_id, release_preview_id, hold_id, requester_user_id,
                   approved_by_user_id, executed_by_user_id, hold_version_before,
                   hold_version_after, policy_version, preview_fingerprint,
                   request_fingerprint, idempotency_key, executed_at
              FROM mail_legal_hold_release_executions WHERE tenant_id = ?
            """;

    static final RowMapper<LegalHoldReleaseExecutionRow> LEGAL_HOLD_RELEASE_EXECUTION =
            (rs, ignored) -> new LegalHoldReleaseExecutionRow(
                    uuid(rs, "execution_id"), uuid(rs, "release_preview_id"),
                    uuid(rs, "hold_id"), rs.getLong("requester_user_id"),
                    rs.getLong("approved_by_user_id"), rs.getLong("executed_by_user_id"),
                    rs.getLong("hold_version_before"), rs.getLong("hold_version_after"),
                    rs.getLong("policy_version"), rs.getString("preview_fingerprint"),
                    rs.getString("request_fingerprint"), uuid(rs, "idempotency_key"),
                    offset(rs, "executed_at"));

    static final String PURGE_PREVIEW_BY = """
            SELECT candidate_snapshot_id, actor_user_id, purge_scope, resource_types,
                   before_at, snapshot_fingerprint, total_candidates, held_count,
                   eligible_count, partial_sources, policy_version, idempotency_key,
                   expires_at, created_at
              FROM mail_purge_previews WHERE tenant_id = ?
            """;

    final RowMapper<PurgePreviewRow> PURGE_PREVIEW = (rs, ignored) -> new PurgePreviewRow(
            uuid(rs, "candidate_snapshot_id"), rs.getLong("actor_user_id"),
            map(rs.getString("purge_scope")), strings(rs.getString("resource_types")),
            offset(rs, "before_at"), rs.getString("snapshot_fingerprint"),
            rs.getInt("total_candidates"), rs.getInt("held_count"),
            rs.getInt("eligible_count"), strings(rs.getString("partial_sources")),
            rs.getLong("policy_version"), uuid(rs, "idempotency_key"),
            offset(rs, "expires_at"), offset(rs, "created_at"));

    static final RowMapper<PurgeApprovalRow> PURGE_APPROVAL = (rs, ignored) ->
            new PurgeApprovalRow(
                    uuid(rs, "approval_id"), uuid(rs, "candidate_snapshot_id"),
                    rs.getLong("approver_user_id"), rs.getLong("policy_version"),
                    offset(rs, "approved_at"));

    static final String PURGE_JOB_BY = """
            SELECT job_id, candidate_snapshot_id, actor_user_id, job_state,
                   deleted_threads, deleted_messages, step_results,
                   verification_state, error_code, idempotency_key,
                   started_at, completed_at
              FROM mail_purge_jobs WHERE tenant_id = ?
            """;

    final RowMapper<PurgeJobRow> PURGE_JOB = (rs, ignored) -> new PurgeJobRow(
            uuid(rs, "job_id"), uuid(rs, "candidate_snapshot_id"),
            rs.getLong("actor_user_id"), rs.getString("job_state"),
            rs.getInt("deleted_threads"), rs.getInt("deleted_messages"),
            maps(rs.getString("step_results")), rs.getString("verification_state"),
            rs.getString("error_code"), uuid(rs, "idempotency_key"),
            offset(rs, "started_at"), offset(rs, "completed_at"));

    static final RowMapper<DeliveryRow> DELIVERY = (rs, ignored) -> new DeliveryRow(
            uuid(rs, "delivery_id"), uuid(rs, "thread_id"), uuid(rs, "message_id"),
            rs.getString("delivery_status"), rs.getInt("attempt_count"),
            offset(rs, "next_attempt_at"), rs.getString("lease_owner"),
            offset(rs, "lease_expires_at"), rs.getString("provider_message_ref"),
            rs.getString("provider_thread_ref"), rs.getString("last_error_code"),
            rs.getString("correlation_id"), offset(rs, "accepted_at"),
            offset(rs, "created_at"), rs.getLong("created_by"),
            offset(rs, "updated_at"), rs.getLong("version"),
            rs.getString("account_name"), rs.getString("provider_type"));

    static final String EXPORT_BY = """
            SELECT export_id, actor_user_id, filters, purpose, export_state,
                   storage_reference, watermark, idempotency_key, created_at, expires_at,
                   snapshot_payload, payload_sha256, item_count, truncated, snapshot_cutoff,
                   export_kind, export_scope, policy_version, required_approvals,
                   request_fingerprint
              FROM mail_delivery_audit_exports WHERE tenant_id = ?
            """;

    final RowMapper<ExportRow> EXPORT = (rs, ignored) -> new ExportRow(
            uuid(rs, "export_id"), rs.getLong("actor_user_id"),
            map(rs.getString("filters")), rs.getString("purpose"),
            rs.getString("export_state"), rs.getString("storage_reference"),
            rs.getString("watermark"), uuid(rs, "idempotency_key"),
            offset(rs, "created_at"), offset(rs, "expires_at"),
            rs.getString("snapshot_payload"), rs.getString("payload_sha256"),
            rs.getObject("item_count", Integer.class),
            rs.getObject("truncated", Boolean.class),
            offset(rs, "snapshot_cutoff"), rs.getString("export_kind"),
            map(rs.getString("export_scope")),
            rs.getObject("policy_version", Long.class),
            rs.getInt("required_approvals"), rs.getString("request_fingerprint"));

    static final RowMapper<ExportApprovalRow> EXPORT_APPROVAL = (rs, ignored) ->
            new ExportApprovalRow(
                    uuid(rs, "approval_id"), uuid(rs, "export_id"),
                    rs.getLong("approver_user_id"), rs.getString("decision"),
                    rs.getString("request_fingerprint"), offset(rs, "decided_at"));

    record SourceStamp(String sourceId, OffsetDateTime observedAt) { }
    record ExceptionRow(String kind, String severity, String resourceRef, int impactCount,
                        OffsetDateTime observedAt, String correlationId, String nextAction) { }
    record AuditRow(UUID auditId, String commandType, String resourceRef, long actorId,
                    String result, OffsetDateTime occurredAt, String correlationId) { }
    record ConnectionRow(UUID id, String providerType, String state, String credentialRef,
                         String mailDomain, long version, OffsetDateTime lastSynchronizedAt,
                         String errorCode, OffsetDateTime updatedAt) {
        URI secretReference() {
            return credentialRef == null || credentialRef.isBlank() ? null : URI.create(credentialRef);
        }
    }
    record AccountRow(UUID id, String email, String providerAccountRef, String cursor) { }
    record SyncAccountRow(
            UUID id,
            String email,
            Long ownerUserId,
            String cursor,
            UUID sharedInboxId) { }
    record InboundIdentity(UUID threadId, UUID messageId) { }
    record InboundMessageRow(
            String providerMessageReference,
            String providerThreadReference,
            OffsetDateTime occurredAt,
            String senderEmail,
            String senderName,
            List<Map<String, Object>> recipients,
            String subject,
            String body,
            String bodyFormat,
            String preview,
            List<Map<String, Object>> participants,
            boolean externalSender,
            String classification,
            List<Map<String, Object>> attachmentProjection) { }
    record InboundAttachmentRow(
            UUID id,
            String storageReference,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256,
            String scanState,
            String scanEvidence) { }
    record InboundMaterialized(UUID threadId, UUID messageId, boolean inserted) { }
    record ConnectionOperationRow(UUID id, UUID connectionId, String kind, String state,
                                  UUID idempotencyKey, String fingerprint, String correlationId,
                                  OffsetDateTime evidenceAt, String errorCode,
                                  OffsetDateTime acceptedAt, OffsetDateTime completedAt) { }
    record SharedInboxRow(UUID id, UUID accountId, long version, String providerType) { }
    record AccessGrantRow(UUID id, long userId, String displayName, String department,
                          String state, OffsetDateTime expiresAt, boolean read,
                          boolean sendAs, boolean sendOnBehalf, boolean assign,
                          boolean manage, String providerState, long version) { }
    record ImpactRow(int activeAssignments, int openDrafts, int pendingCommands) {
        boolean hasImpact() { return activeAssignments + openDrafts + pendingCommands > 0; }
    }
    record MemberRevokePreviewRow(
            UUID id, UUID inboxId, UUID memberId, long actorId, long memberVersion,
            int activeAssignments, int openDrafts, int pendingCommands,
            boolean providerRevocationRequired, String fingerprint,
            OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime consumedAt) { }
    record AdminReceiptRow(String commandKind, String fingerprint, String aggregateType,
                           UUID aggregateId, OffsetDateTime completedAt, String correlationId) { }
    record PolicyRow(boolean externalBanner, boolean blockRemoteImages,
                     boolean allowSharedInboxes, boolean aiAssistance,
                     boolean aiCrossAppActions, boolean aiAutoExecute,
                     int retentionDays, int maximumAttachmentMb,
                     long version, OffsetDateTime updatedAt) { }
    record PolicyHistoryRow(UUID id, long version, long actorId, Map<String, Object> diff,
                            String result, String correlationId, OffsetDateTime changedAt) { }
    record LegalHoldRow(UUID id, String name, String caseRef, Map<String, Object> scope,
                        String status, OffsetDateTime startsAt, OffsetDateTime expiresAt,
                        long version) { }
    record LegalHoldReleasePreviewRow(
            UUID id, UUID holdId, long requesterId, long holdVersion, long policyVersion,
            Map<String, Object> holdScope, OffsetDateTime retentionBoundary,
            String fingerprint, String requestFingerprint,
            Map<String, Object> affectedCounts, Map<String, Object> currentlyHeldCounts,
            Map<String, Object> purgeSafeCounts, Map<String, Object> protectedCounts,
            Map<String, Object> providerRequiredCounts, UUID idempotencyKey,
            OffsetDateTime generatedAt, OffsetDateTime expiresAt) { }
    record LegalHoldReleaseApprovalRow(
            UUID id, UUID previewId, long approverId, String decision,
            long holdVersion, long policyVersion, String previewFingerprint,
            String requestFingerprint, UUID idempotencyKey, OffsetDateTime decidedAt) { }
    record LegalHoldReleaseExecutionRow(
            UUID id, UUID previewId, UUID holdId, long requesterId, long approvedById,
            long executorId, long holdVersionBefore, long holdVersionAfter,
            long policyVersion, String previewFingerprint, String requestFingerprint,
            UUID idempotencyKey, OffsetDateTime executedAt) { }
    record CandidateRow(
            UUID threadId,
            UUID accountId,
            int messageCount,
            int attachmentCount,
            int draftCount,
            String providerType,
            boolean immutableEvidenceBlocked) { }
    record CandidateSet(List<CandidateRow> rows) {
        CandidateSet { rows = List.copyOf(rows); }
        int totalThreadCount() { return rows.size(); }
        int totalMessageCount() { return rows.stream().mapToInt(CandidateRow::messageCount).sum(); }
        int eligibleThreadCount() {
            return (int) rows.stream().filter(row -> !row.immutableEvidenceBlocked()).count();
        }
        int eligibleMessageCount() {
            return rows.stream().filter(row -> !row.immutableEvidenceBlocked())
                    .mapToInt(CandidateRow::messageCount).sum();
        }
        int blockedThreadCount() {
            return (int) rows.stream().filter(CandidateRow::immutableEvidenceBlocked).count();
        }
        int blockedMessageCount() {
            return rows.stream().filter(CandidateRow::immutableEvidenceBlocked)
                    .mapToInt(CandidateRow::messageCount).sum();
        }
        boolean hasExternalProvider() {
            return rows.stream().filter(row -> !row.immutableEvidenceBlocked())
                    .anyMatch(row -> !"DWP_SANDBOX".equals(row.providerType()));
        }
        List<UUID> eligibleThreadIds() {
            return rows.stream().filter(row -> !row.immutableEvidenceBlocked())
                    .map(CandidateRow::threadId).toList();
        }
        List<UUID> blockedThreadIds() {
            return rows.stream().filter(CandidateRow::immutableEvidenceBlocked)
                    .map(CandidateRow::threadId).toList();
        }
    }
    record PurgeCandidateSnapshotRow(
            UUID threadId, UUID accountId, int messageCount, int attachmentCount,
            int draftCount, String providerType, boolean held, List<UUID> holdIds,
            Map<String, Object> evidence) { }
    record PurgePreviewRow(UUID id, long actorId, Map<String, Object> scope,
                           List<String> resourceTypes, OffsetDateTime before,
                           String fingerprint, int total, int held, int eligible,
                           List<String> partialSources, long policyVersion,
                           UUID idempotencyKey, OffsetDateTime expiresAt,
                           OffsetDateTime createdAt) { }
    record PurgeApprovalRow(UUID id, UUID snapshotId, long actorId,
                            long policyVersion, OffsetDateTime approvedAt) { }
    record PurgeJobRow(UUID id, UUID snapshotId, long actorId, String state,
                       int deletedThreads, int deletedMessages,
                       List<Map<String, Object>> steps, String verification,
                       String errorCode, UUID idempotencyKey,
                       OffsetDateTime startedAt, OffsetDateTime completedAt) { }
    record PurgeLeaseRow(
            UUID id, long tenantId, UUID snapshotId, long actorId,
            List<UUID> candidateThreadIds, List<Map<String, Object>> steps,
            int attemptCount, String previousStep,
            int previousDeletedThreads, int previousDeletedMessages,
            OffsetDateTime startedAt) {
        PurgeLeaseRow(
                UUID id, long tenantId, UUID snapshotId, long actorId,
                List<UUID> candidateThreadIds, List<Map<String, Object>> steps,
                int attemptCount) {
            this(id, tenantId, snapshotId, actorId, candidateThreadIds, steps,
                    attemptCount, "ACCEPTED", 0, 0, OffsetDateTime.now());
        }
    }
    record PurgeEventEvidence(
            UUID eventId, OffsetDateTime occurredAt, OffsetDateTime publishedAt,
            int publishAttempts, String deliveryState, String lastError) {
        PurgeEventEvidence(
                UUID eventId, OffsetDateTime occurredAt, OffsetDateTime publishedAt,
                int publishAttempts) {
            this(eventId, occurredAt, publishedAt, publishAttempts,
                    publishedAt == null ? "PENDING" : "PUBLISHED", null);
        }
    }
    record DeleteCounts(int threads, int messages, int attachmentCleanupCommands) {
        DeleteCounts(int threads, int messages) {
            this(threads, messages, 0);
        }
    }
    record DeliveryRow(UUID id, UUID threadId, UUID messageId, String status,
                       int attemptCount, OffsetDateTime nextAttemptAt, String leaseOwner,
                       OffsetDateTime leaseExpiresAt, String providerMessageRef,
                       String providerThreadRef, String errorCode, String correlationId,
                       OffsetDateTime acceptedAt, OffsetDateTime createdAt, long actorId,
                       OffsetDateTime updatedAt, long version, String accountName,
                       String providerType) { }
    record DeliveryAuthorizationRow(
            UUID accountId, UUID connectionId, String providerType,
            String credentialReference, String mailDomain, long senderId,
            String senderMode, String bodyFormat, boolean hasBcc,
            int attachmentCount) {
        java.net.URI secretReference() {
            return credentialReference == null || credentialReference.isBlank()
                    ? null : java.net.URI.create(credentialReference);
        }
    }
    record RecoveryRow(UUID id, String action, String result, Map<String, Object> evidence,
                       String correlationId, OffsetDateTime occurredAt) { }
    record DeliveryEvidenceRow(String stage, String state, String code, OffsetDateTime at,
                               String source, String evidenceState) { }
    record RecoveryCommandRow(UUID id, UUID deliveryId, String action,
                              String result, String fingerprint) { }
    record ExportRow(UUID id, long actorId, Map<String, Object> filters, String purpose,
                     String state, String storageReference, String watermark,
                     UUID idempotencyKey, OffsetDateTime createdAt,
                     OffsetDateTime expiresAt, String snapshotPayload,
                     String payloadSha256, Integer itemCount, Boolean truncated,
                     OffsetDateTime snapshotCutoff, String exportKind,
                     Map<String, Object> exportScope, Long policyVersion,
                     int requiredApprovals, String requestFingerprint) { }
    record ExportApprovalRow(UUID id, UUID exportId, long approverUserId,
                             String decision, String requestFingerprint,
                             OffsetDateTime decidedAt) { }
}
