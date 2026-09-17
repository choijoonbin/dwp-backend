package com.dwp.services.platform.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class AdminMailCompletionRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> MAPS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    AdminMailCompletionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<SourceStamp> sourceStamps(long tenantId) {
        return jdbc.query("""
                SELECT source_id, observed_at
                  FROM (
                    SELECT 'OVERVIEW' source_id, max(updated_at) observed_at
                      FROM mail_tenant_policies WHERE tenant_id = ?
                    UNION ALL
                    SELECT 'COMMAND', max(observed_at)
                      FROM (
                        SELECT completed_at observed_at FROM mail_admin_command_receipts
                         WHERE tenant_id = ?
                        UNION ALL
                        SELECT completed_at FROM mail_connection_operations
                         WHERE tenant_id = ?
                        UNION ALL
                        SELECT occurred_at FROM mail_delivery_recovery_events
                         WHERE tenant_id = ?
                      ) commands
                    UNION ALL
                    SELECT 'OUTBOX', max(updated_at)
                      FROM mail_delivery_outbox WHERE tenant_id = ?
                    UNION ALL
                    SELECT 'PROVIDER', max(updated_at)
                      FROM mail_provider_connections WHERE tenant_id = ?
                    UNION ALL
                    SELECT 'AUDIT', max(occurred_at)
                      FROM mail_audit_events WHERE tenant_id = ?
                    UNION ALL
                    SELECT 'EVENT', max(occurred_at)
                      FROM mail_domain_events WHERE tenant_id = ?
                  ) evidence
                """, (rs, ignored) -> new SourceStamp(
                rs.getString("source_id"), offset(rs, "observed_at")),
                tenantId, tenantId, tenantId, tenantId, tenantId,
                tenantId, tenantId, tenantId);
    }

    List<ExceptionRow> exceptions(long tenantId, int limit) {
        return jdbc.query("""
                SELECT kind, severity, resource_ref, impact_count, observed_at,
                       correlation_id, next_action
                  FROM (
                    SELECT 'CONNECTION' kind,
                           CASE connection_state WHEN 'SUSPENDED' THEN 'CRITICAL' ELSE 'WARNING' END severity,
                           connection_id::text resource_ref, 1 impact_count,
                           updated_at observed_at, NULL::varchar correlation_id,
                           'OPEN_CONNECTION' next_action
                      FROM mail_provider_connections
                     WHERE tenant_id = ? AND connection_state IN ('DEGRADED', 'SUSPENDED', 'CONFIGURATION_REQUIRED')
                    UNION ALL
                    SELECT 'DELIVERY',
                           CASE delivery_status WHEN 'FAILED' THEN 'CRITICAL' ELSE 'WARNING' END,
                           delivery_id::text, 1, updated_at, correlation_id,
                           'OPEN_DELIVERY'
                      FROM mail_delivery_outbox
                     WHERE tenant_id = ?
                       AND (delivery_status = 'FAILED'
                            OR (delivery_status = 'LEASED' AND lease_expires_at < CURRENT_TIMESTAMP))
                    UNION ALL
                    SELECT 'OBSERVABILITY', 'CRITICAL', job_id::text, 1,
                           COALESCE(completed_at, started_at), NULL::varchar, 'ESCALATE'
                      FROM mail_purge_jobs
                     WHERE tenant_id = ? AND job_state IN ('FAILED', 'UNKNOWN', 'PARTIAL')
                  ) exceptions
                 ORDER BY observed_at DESC
                 LIMIT ?
                """, (rs, ignored) -> new ExceptionRow(
                rs.getString("kind"), rs.getString("severity"),
                rs.getString("resource_ref"), rs.getInt("impact_count"),
                offset(rs, "observed_at"), rs.getString("correlation_id"),
                rs.getString("next_action")), tenantId, tenantId, tenantId, limit);
    }

    List<AuditRow> commandAudit(long tenantId, int limit) {
        return jdbc.query("""
                SELECT audit_id, command_type, resource_ref, actor_user_id,
                       result, occurred_at, correlation_id
                  FROM (
                    SELECT audit_event_id audit_id, action command_type,
                           target_id resource_ref, actor_user_id,
                           CASE COALESCE(after_snapshot->>'result', 'SUCCEEDED')
                             WHEN 'FAILED' THEN 'FAILED'
                             WHEN 'UNKNOWN' THEN 'UNKNOWN'
                             WHEN 'BLOCKED' THEN 'BLOCKED'
                             ELSE 'SUCCEEDED'
                           END result,
                           occurred_at, correlation_id
                      FROM mail_audit_events WHERE tenant_id = ?
                    UNION ALL
                    SELECT recovery_event_id, 'mail.delivery.' || lower(action_kind),
                           delivery_id::text, actor_user_id, result_state,
                           occurred_at, correlation_id
                      FROM mail_delivery_recovery_events WHERE tenant_id = ?
                  ) audit
                 ORDER BY occurred_at DESC
                 LIMIT ?
                """, (rs, ignored) -> new AuditRow(
                uuid(rs, "audit_id"), rs.getString("command_type"),
                rs.getString("resource_ref"), rs.getLong("actor_user_id"),
                rs.getString("result"), offset(rs, "occurred_at"),
                rs.getString("correlation_id")), tenantId, tenantId, limit);
    }

    Optional<ConnectionRow> connection(long tenantId, UUID connectionId) {
        return one("""
                SELECT connection_id, provider_type, connection_state, credential_ref,
                       mail_domain, version, last_synchronized_at, last_error_code, updated_at
                  FROM mail_provider_connections
                 WHERE tenant_id = ? AND connection_id = ?
                """, CONNECTION, tenantId, connectionId);
    }

    List<AccountRow> connectionAccounts(long tenantId, UUID connectionId) {
        return jdbc.query("""
                SELECT account_id, email_address,
                       COALESCE(provider_account_ref, email_address) provider_account_ref,
                       synchronization_cursor
                  FROM mail_accounts
                 WHERE tenant_id = ? AND connection_id = ? AND connection_state = 'ACTIVE'
                 ORDER BY account_id
                """, (rs, ignored) -> new AccountRow(
                uuid(rs, "account_id"), rs.getString("email_address"),
                rs.getString("provider_account_ref"), rs.getString("synchronization_cursor")),
                tenantId, connectionId);
    }

    Optional<ConnectionOperationRow> connectionOperation(long tenantId, long actorId, UUID key) {
        return one("""
                SELECT operation_id, connection_id, operation_kind, operation_state,
                       idempotency_key, request_fingerprint, correlation_id,
                       evidence_generated_at, error_code, accepted_at, completed_at
                  FROM mail_connection_operations
                 WHERE tenant_id = ? AND actor_user_id = ? AND idempotency_key = ?
                """, CONNECTION_OPERATION, tenantId, actorId, key);
    }

    UUID insertConnectionOperation(
            long tenantId, long actorId, UUID connectionId, String kind, String scope,
            Map<String, Object> payload, UUID key, String fingerprint, String correlationId) {
        return jdbc.queryForObject("""
                INSERT INTO mail_connection_operations (
                    tenant_id, connection_id, actor_user_id, operation_kind,
                    operation_scope, request_payload, idempotency_key,
                    request_fingerprint, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                RETURNING operation_id
                """, UUID.class, tenantId, connectionId, actorId, kind, scope,
                json(payload), key, fingerprint, correlationId);
    }

    void completeConnectionOperation(
            long tenantId, UUID operationId, String state, String errorCode,
            OffsetDateTime evidenceAt) {
        jdbc.update("""
                UPDATE mail_connection_operations
                   SET operation_state = ?, error_code = ?, evidence_generated_at = ?,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND operation_id = ?
                """, state, errorCode, evidenceAt, tenantId, operationId);
    }

    int markConnectionSynchronized(long tenantId, UUID connectionId, long version, long actorId) {
        return jdbc.update("""
                UPDATE mail_provider_connections
                   SET connection_state = 'ACTIVE', last_synchronized_at = CURRENT_TIMESTAMP,
                       last_error_code = NULL, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND connection_id = ? AND version = ?
                """, actorId, tenantId, connectionId, version);
    }

    Optional<SharedInboxRow> sharedInbox(long tenantId, UUID inboxId) {
        return one("""
                SELECT inbox.shared_inbox_id, inbox.account_id, inbox.version,
                       connection.provider_type
                  FROM mail_shared_inboxes inbox
                  JOIN mail_accounts account ON account.account_id = inbox.account_id
                  JOIN mail_provider_connections connection
                    ON connection.connection_id = account.connection_id
                 WHERE inbox.tenant_id = ? AND inbox.shared_inbox_id = ?
                """, (rs, ignored) -> new SharedInboxRow(
                uuid(rs, "shared_inbox_id"), uuid(rs, "account_id"),
                rs.getLong("version"), rs.getString("provider_type")), tenantId, inboxId);
    }

    List<AccessGrantRow> accessGrants(long tenantId, UUID inboxId) {
        return jdbc.query("""
                SELECT member_id, user_id, display_name, department, member_state,
                       expires_at, can_read, can_send_as, can_send_on_behalf,
                       can_assign, can_manage, provider_state, version
                  FROM mail_shared_inbox_access_grants
                 WHERE tenant_id = ? AND shared_inbox_id = ?
                 ORDER BY CASE member_state WHEN 'ACTIVE' THEN 0 WHEN 'PENDING' THEN 1 ELSE 2 END,
                          lower(display_name), member_id
                """, ACCESS_GRANT, tenantId, inboxId);
    }

    Optional<AccessGrantRow> accessGrant(long tenantId, UUID inboxId, UUID memberId) {
        return one("""
                SELECT member_id, user_id, display_name, department, member_state,
                       expires_at, can_read, can_send_as, can_send_on_behalf,
                       can_assign, can_manage, provider_state, version
                  FROM mail_shared_inbox_access_grants
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND member_id = ?
                """, ACCESS_GRANT, tenantId, inboxId, memberId);
    }

    Optional<AccessGrantRow> accessGrantByUser(long tenantId, UUID inboxId, long userId) {
        return one("""
                SELECT member_id, user_id, display_name, department, member_state,
                       expires_at, can_read, can_send_as, can_send_on_behalf,
                       can_assign, can_manage, provider_state, version
                  FROM mail_shared_inbox_access_grants
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, ACCESS_GRANT, tenantId, inboxId, userId);
    }

    ImpactRow accessImpact(long tenantId, UUID inboxId, Long userId) {
        Long assignee = userId == null ? -1L : userId;
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM mail_threads
                    WHERE tenant_id = ? AND shared_inbox_id = ?
                      AND (? < 0 OR assigned_user_id = ?)
                      AND workflow_state IN ('OPEN', 'SNOOZED')) active_assignments,
                  (SELECT count(*) FROM mail_threads
                    WHERE tenant_id = ? AND shared_inbox_id = ?
                      AND (? < 0 OR created_by = ?)
                      AND workflow_state = 'DRAFT') open_drafts,
                  (SELECT count(*) FROM mail_delivery_outbox delivery
                     JOIN mail_threads thread ON thread.thread_id = delivery.thread_id
                    WHERE delivery.tenant_id = ? AND thread.shared_inbox_id = ?
                      AND (? < 0 OR delivery.created_by = ?)
                      AND delivery.delivery_status IN ('QUEUED', 'LEASED', 'RETRY_WAIT')) pending_commands
                """, (rs, ignored) -> new ImpactRow(
                rs.getInt("active_assignments"), rs.getInt("open_drafts"),
                rs.getInt("pending_commands")),
                tenantId, inboxId, assignee, assignee,
                tenantId, inboxId, assignee, assignee,
                tenantId, inboxId, assignee, assignee);
    }

    UUID insertAccessGrant(
            long tenantId, UUID inboxId, long userId, String displayName, String department,
            OffsetDateTime expiresAt, boolean read, boolean sendAs, boolean sendOnBehalf,
            boolean assign, boolean manage, String providerState, long actorId) {
        return jdbc.queryForObject("""
                INSERT INTO mail_shared_inbox_access_grants (
                    tenant_id, shared_inbox_id, user_id, display_name, department,
                    member_state, expires_at, can_read, can_send_as, can_send_on_behalf,
                    can_assign, can_manage, provider_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING member_id
                """, UUID.class, tenantId, inboxId, userId, displayName, department,
                expiresAt, read, sendAs, sendOnBehalf, assign, manage, providerState,
                actorId, actorId);
    }

    int updateAccessGrant(
            long tenantId, UUID inboxId, UUID memberId, String displayName, String department,
            OffsetDateTime expiresAt, boolean read, boolean sendAs, boolean sendOnBehalf,
            boolean assign, boolean manage, String providerState, long version, long actorId) {
        return jdbc.update("""
                UPDATE mail_shared_inbox_access_grants
                   SET display_name = ?, department = ?, expires_at = ?,
                       can_read = ?, can_send_as = ?, can_send_on_behalf = ?,
                       can_assign = ?, can_manage = ?, provider_state = ?,
                       member_state = 'ACTIVE', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND member_id = ?
                   AND version = ? AND member_state <> 'REVOKED'
                """, displayName, department, expiresAt, read, sendAs, sendOnBehalf,
                assign, manage, providerState, actorId, tenantId, inboxId, memberId, version);
    }

    int revokeAccessGrant(
            long tenantId, UUID inboxId, UUID memberId, long version,
            String providerState, long actorId) {
        return jdbc.update("""
                UPDATE mail_shared_inbox_access_grants
                   SET member_state = 'REVOKED', provider_state = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND member_id = ?
                   AND version = ? AND member_state <> 'REVOKED'
                """, providerState, actorId, tenantId, inboxId, memberId, version);
    }

    int bumpSharedInbox(long tenantId, UUID inboxId, long expectedVersion, long actorId) {
        return jdbc.update("""
                UPDATE mail_shared_inboxes
                   SET version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND version = ?
                """, actorId, tenantId, inboxId, expectedVersion);
    }

    void upsertLegacyMember(
            long tenantId, UUID inboxId, UUID accountId, long userId,
            boolean manager, long actorId) {
        jdbc.update("""
                INSERT INTO mail_shared_inbox_members (
                    tenant_id, shared_inbox_id, account_id, user_id, member_role,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                ON CONFLICT (tenant_id, shared_inbox_id, user_id) DO UPDATE
                    SET account_id = EXCLUDED.account_id,
                        member_role = EXCLUDED.member_role,
                        lifecycle_state = 'ACTIVE',
                        updated_at = CURRENT_TIMESTAMP,
                        updated_by = EXCLUDED.updated_by
                """, tenantId, inboxId, accountId, userId,
                manager ? "MANAGER" : "MEMBER", actorId, actorId);
    }

    void retireLegacyMember(long tenantId, UUID inboxId, long userId, long actorId) {
        jdbc.update("""
                UPDATE mail_shared_inbox_members
                   SET lifecycle_state = 'RETIRED', updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, actorId, tenantId, inboxId, userId);
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

    int activeHoldCount(long tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM mail_legal_holds
                 WHERE tenant_id = ? AND hold_status = 'ACTIVE'
                   AND starts_at <= CURRENT_TIMESTAMP
                   AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                """, Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    CandidateSet purgeCandidates(long tenantId, OffsetDateTime before) {
        List<CandidateRow> rows = jdbc.query("""
                SELECT thread.thread_id,
                       (SELECT count(*) FROM mail_messages message
                         WHERE message.tenant_id = thread.tenant_id
                           AND message.thread_id = thread.thread_id) message_count,
                       connection.provider_type
                  FROM mail_threads thread
                  JOIN mail_accounts account ON account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.connection_id = account.connection_id
                 WHERE thread.tenant_id = ? AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                 ORDER BY thread.thread_id
                """, (rs, ignored) -> new CandidateRow(
                uuid(rs, "thread_id"), rs.getInt("message_count"),
                rs.getString("provider_type")), tenantId, before);
        return new CandidateSet(rows);
    }

    Optional<PurgePreviewRow> purgePreviewByCommand(long tenantId, long actorId, UUID key) {
        return one(PURGE_PREVIEW_BY + " AND actor_user_id = ? AND idempotency_key = ?",
                PURGE_PREVIEW, tenantId, actorId, key);
    }

    Optional<PurgePreviewRow> purgePreview(long tenantId, UUID snapshotId) {
        return one(PURGE_PREVIEW_BY + " AND candidate_snapshot_id = ?",
                PURGE_PREVIEW, tenantId, snapshotId);
    }

    UUID insertPurgePreview(
            long tenantId, long actorId, Map<String, Object> scope,
            List<String> resourceTypes, OffsetDateTime before, String fingerprint,
            int total, int held, int eligible, List<String> partialSources,
            long policyVersion, UUID key, OffsetDateTime expiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO mail_purge_previews (
                    tenant_id, actor_user_id, purge_scope, resource_types, before_at,
                    snapshot_fingerprint, total_candidates, held_count, eligible_count,
                    partial_sources, policy_version, idempotency_key, expires_at)
                VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                RETURNING candidate_snapshot_id
                """, UUID.class, tenantId, actorId, json(scope), json(resourceTypes),
                before, fingerprint, total, held, eligible, json(partialSources),
                policyVersion, key, expiresAt);
    }

    Optional<PurgeApprovalRow> purgeApprovalByCommand(long tenantId, long actorId, UUID key) {
        return one("""
                SELECT approval_id, candidate_snapshot_id, approver_user_id,
                       policy_version, approved_at
                  FROM mail_purge_approvals
                 WHERE tenant_id = ? AND approver_user_id = ? AND idempotency_key = ?
                """, PURGE_APPROVAL, tenantId, actorId, key);
    }

    UUID insertPurgeApproval(
            long tenantId, UUID snapshotId, long actorId, long policyVersion, UUID key) {
        return jdbc.queryForObject("""
                INSERT INTO mail_purge_approvals (
                    tenant_id, candidate_snapshot_id, approver_user_id,
                    policy_version, idempotency_key)
                VALUES (?, ?, ?, ?, ?)
                RETURNING approval_id
                """, UUID.class, tenantId, snapshotId, actorId, policyVersion, key);
    }

    int distinctApprovals(long tenantId, UUID snapshotId, long policyVersion) {
        Integer count = jdbc.queryForObject("""
                SELECT count(DISTINCT approver_user_id)
                  FROM mail_purge_approvals
                 WHERE tenant_id = ? AND candidate_snapshot_id = ? AND policy_version = ?
                """, Integer.class, tenantId, snapshotId, policyVersion);
        return count == null ? 0 : count;
    }

    Optional<PurgeJobRow> purgeJobByCommand(long tenantId, long actorId, UUID key) {
        return one(PURGE_JOB_BY + " AND actor_user_id = ? AND idempotency_key = ?",
                PURGE_JOB, tenantId, actorId, key);
    }

    Optional<PurgeJobRow> purgeJob(long tenantId, UUID jobId) {
        return one(PURGE_JOB_BY + " AND job_id = ?", PURGE_JOB, tenantId, jobId);
    }

    List<PurgeJobRow> purgeJobs(long tenantId, int limit) {
        return jdbc.query(PURGE_JOB_BY + " ORDER BY started_at DESC LIMIT ?",
                PURGE_JOB, tenantId, limit);
    }

    UUID insertPurgeJob(long tenantId, UUID snapshotId, long actorId, UUID key) {
        return jdbc.queryForObject("""
                INSERT INTO mail_purge_jobs (
                    tenant_id, candidate_snapshot_id, actor_user_id,
                    job_state, verification_state, idempotency_key)
                VALUES (?, ?, ?, 'RUNNING', 'PENDING', ?)
                RETURNING job_id
                """, UUID.class, tenantId, snapshotId, actorId, key);
    }

    DeleteCounts deletePurgeCandidates(long tenantId, OffsetDateTime before) {
        Integer messages = jdbc.queryForObject("""
                SELECT count(*) FROM mail_messages message
                 WHERE message.tenant_id = ? AND EXISTS (
                    SELECT 1 FROM mail_threads thread
                     WHERE thread.tenant_id = message.tenant_id
                       AND thread.thread_id = message.thread_id
                       AND thread.workflow_state = 'TRASHED'
                       AND thread.updated_at < ?)
                """, Integer.class, tenantId, before);
        int threads = jdbc.update("""
                DELETE FROM mail_threads
                 WHERE tenant_id = ? AND workflow_state = 'TRASHED' AND updated_at < ?
                """, tenantId, before);
        return new DeleteCounts(threads, messages == null ? 0 : messages);
    }

    void completePurgeJob(
            long tenantId, UUID jobId, String state, DeleteCounts counts,
            List<Map<String, Object>> steps, String verification, String errorCode) {
        jdbc.update("""
                UPDATE mail_purge_jobs
                   SET job_state = ?, deleted_threads = ?, deleted_messages = ?,
                       step_results = ?::jsonb, verification_state = ?, error_code = ?,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ?
                """, state, counts.threads(), counts.messages(), json(steps), verification,
                errorCode, tenantId, jobId);
    }

    long remainingPurgeCandidates(long tenantId, OffsetDateTime before) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM mail_threads
                 WHERE tenant_id = ? AND workflow_state = 'TRASHED' AND updated_at < ?
                """, Long.class, tenantId, before);
        return count == null ? 0 : count;
    }

    List<DeliveryRow> deliveries(
            long tenantId, String state, String query, int limit, int offset) {
        String normalizedState = state == null ? "" : state.trim().toUpperCase();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
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
                  JOIN mail_threads thread ON thread.thread_id = delivery.thread_id
                  JOIN mail_accounts account ON account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.connection_id = account.connection_id
                 WHERE delivery.tenant_id = ?
                   AND (? = '' OR delivery.delivery_status = ?)
                   AND (? = '' OR lower(delivery.delivery_id::text) LIKE '%' || ? || '%'
                        OR lower(delivery.correlation_id) LIKE '%' || ? || '%')
                 ORDER BY delivery.updated_at DESC
                 LIMIT ? OFFSET ?
                """, DELIVERY, tenantId, normalizedState, normalizedState,
                normalizedQuery, normalizedQuery, normalizedQuery, limit, offset);
    }

    long deliveryCount(long tenantId, String state, String query) {
        String normalizedState = state == null ? "" : state.trim().toUpperCase();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM mail_delivery_outbox delivery
                 WHERE delivery.tenant_id = ?
                   AND (? = '' OR delivery.delivery_status = ?)
                   AND (? = '' OR lower(delivery.delivery_id::text) LIKE '%' || ? || '%'
                        OR lower(delivery.correlation_id) LIKE '%' || ? || '%')
                """, Long.class, tenantId, normalizedState, normalizedState,
                normalizedQuery, normalizedQuery, normalizedQuery);
        return count == null ? 0 : count;
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
                   AND lease_owner IS NULL AND lease_expires_at IS NULL
                   AND request_fingerprint IS NOT NULL
                """, tenantId, deliveryId, version);
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

    Optional<ExportRow> export(long tenantId, UUID exportId) {
        return one(EXPORT_BY + " AND export_id = ?", EXPORT, tenantId, exportId);
    }

    UUID insertExport(
            long tenantId, long actorId, Map<String, Object> filters, String purpose,
            String watermark, UUID key, OffsetDateTime expiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO mail_delivery_audit_exports (
                    tenant_id, actor_user_id, filters, purpose, export_state,
                    storage_reference, watermark, idempotency_key, expires_at)
                VALUES (?, ?, ?::jsonb, ?, 'READY', 'DATABASE_SNAPSHOT', ?, ?, ?)
                RETURNING export_id
                """, UUID.class, tenantId, actorId, json(filters), purpose,
                watermark, key, expiresAt);
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

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbc.query(sql, mapper, args);
        return rows.stream().findFirst();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize mail administrator evidence", exception);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid mail administrator object evidence", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return value == null ? List.of() : objectMapper.readValue(value, STRINGS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid mail administrator list evidence", exception);
        }
    }

    private List<Map<String, Object>> maps(String value) {
        try {
            return value == null ? List.of() : objectMapper.readValue(value, MAPS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid mail administrator step evidence", exception);
        }
    }

    private static UUID uuid(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    private static OffsetDateTime offset(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, OffsetDateTime.class);
    }

    private static final RowMapper<ConnectionRow> CONNECTION = (rs, ignored) -> new ConnectionRow(
            uuid(rs, "connection_id"), rs.getString("provider_type"),
            rs.getString("connection_state"), rs.getString("credential_ref"),
            rs.getString("mail_domain"), rs.getLong("version"),
            offset(rs, "last_synchronized_at"), rs.getString("last_error_code"),
            offset(rs, "updated_at"));

    private static final RowMapper<ConnectionOperationRow> CONNECTION_OPERATION = (rs, ignored) ->
            new ConnectionOperationRow(
                    uuid(rs, "operation_id"), uuid(rs, "connection_id"),
                    rs.getString("operation_kind"), rs.getString("operation_state"),
                    uuid(rs, "idempotency_key"), rs.getString("request_fingerprint"),
                    rs.getString("correlation_id"), offset(rs, "evidence_generated_at"),
                    rs.getString("error_code"), offset(rs, "accepted_at"),
                    offset(rs, "completed_at"));

    private static final RowMapper<AccessGrantRow> ACCESS_GRANT = (rs, ignored) -> new AccessGrantRow(
            uuid(rs, "member_id"), rs.getLong("user_id"), rs.getString("display_name"),
            rs.getString("department"), rs.getString("member_state"),
            offset(rs, "expires_at"), rs.getBoolean("can_read"),
            rs.getBoolean("can_send_as"), rs.getBoolean("can_send_on_behalf"),
            rs.getBoolean("can_assign"), rs.getBoolean("can_manage"),
            rs.getString("provider_state"), rs.getLong("version"));

    private static final RowMapper<PolicyRow> POLICY = (rs, ignored) -> new PolicyRow(
            rs.getBoolean("external_sender_banner"), rs.getBoolean("block_remote_images"),
            rs.getBoolean("allow_shared_inboxes"), rs.getBoolean("ai_assistance_enabled"),
            rs.getBoolean("ai_cross_app_actions_enabled"), rs.getBoolean("ai_auto_execute_enabled"),
            rs.getInt("retention_days"), rs.getInt("maximum_attachment_mb"),
            rs.getLong("version"), offset(rs, "updated_at"));

    private final RowMapper<LegalHoldRow> LEGAL_HOLD = (rs, ignored) -> new LegalHoldRow(
            uuid(rs, "hold_id"), rs.getString("display_name"),
            rs.getString("safe_case_reference"), map(rs.getString("hold_scope")),
            rs.getString("hold_status"), offset(rs, "starts_at"),
            offset(rs, "expires_at"), rs.getLong("version"));

    private static final String PURGE_PREVIEW_BY = """
            SELECT candidate_snapshot_id, actor_user_id, purge_scope, resource_types,
                   before_at, snapshot_fingerprint, total_candidates, held_count,
                   eligible_count, partial_sources, policy_version, idempotency_key,
                   expires_at, created_at
              FROM mail_purge_previews WHERE tenant_id = ?
            """;

    private final RowMapper<PurgePreviewRow> PURGE_PREVIEW = (rs, ignored) -> new PurgePreviewRow(
            uuid(rs, "candidate_snapshot_id"), rs.getLong("actor_user_id"),
            map(rs.getString("purge_scope")), strings(rs.getString("resource_types")),
            offset(rs, "before_at"), rs.getString("snapshot_fingerprint"),
            rs.getInt("total_candidates"), rs.getInt("held_count"),
            rs.getInt("eligible_count"), strings(rs.getString("partial_sources")),
            rs.getLong("policy_version"), uuid(rs, "idempotency_key"),
            offset(rs, "expires_at"), offset(rs, "created_at"));

    private static final RowMapper<PurgeApprovalRow> PURGE_APPROVAL = (rs, ignored) ->
            new PurgeApprovalRow(
                    uuid(rs, "approval_id"), uuid(rs, "candidate_snapshot_id"),
                    rs.getLong("approver_user_id"), rs.getLong("policy_version"),
                    offset(rs, "approved_at"));

    private static final String PURGE_JOB_BY = """
            SELECT job_id, candidate_snapshot_id, actor_user_id, job_state,
                   deleted_threads, deleted_messages, step_results,
                   verification_state, error_code, idempotency_key,
                   started_at, completed_at
              FROM mail_purge_jobs WHERE tenant_id = ?
            """;

    private final RowMapper<PurgeJobRow> PURGE_JOB = (rs, ignored) -> new PurgeJobRow(
            uuid(rs, "job_id"), uuid(rs, "candidate_snapshot_id"),
            rs.getLong("actor_user_id"), rs.getString("job_state"),
            rs.getInt("deleted_threads"), rs.getInt("deleted_messages"),
            maps(rs.getString("step_results")), rs.getString("verification_state"),
            rs.getString("error_code"), uuid(rs, "idempotency_key"),
            offset(rs, "started_at"), offset(rs, "completed_at"));

    private static final RowMapper<DeliveryRow> DELIVERY = (rs, ignored) -> new DeliveryRow(
            uuid(rs, "delivery_id"), uuid(rs, "thread_id"), uuid(rs, "message_id"),
            rs.getString("delivery_status"), rs.getInt("attempt_count"),
            offset(rs, "next_attempt_at"), rs.getString("lease_owner"),
            offset(rs, "lease_expires_at"), rs.getString("provider_message_ref"),
            rs.getString("provider_thread_ref"), rs.getString("last_error_code"),
            rs.getString("correlation_id"), offset(rs, "accepted_at"),
            offset(rs, "created_at"), rs.getLong("created_by"),
            offset(rs, "updated_at"), rs.getLong("version"),
            rs.getString("account_name"), rs.getString("provider_type"));

    private static final String EXPORT_BY = """
            SELECT export_id, actor_user_id, filters, purpose, export_state,
                   storage_reference, watermark, idempotency_key, created_at, expires_at
              FROM mail_delivery_audit_exports WHERE tenant_id = ?
            """;

    private final RowMapper<ExportRow> EXPORT = (rs, ignored) -> new ExportRow(
            uuid(rs, "export_id"), rs.getLong("actor_user_id"),
            map(rs.getString("filters")), rs.getString("purpose"),
            rs.getString("export_state"), rs.getString("storage_reference"),
            rs.getString("watermark"), uuid(rs, "idempotency_key"),
            offset(rs, "created_at"), offset(rs, "expires_at"));

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
    record CandidateRow(UUID threadId, int messageCount, String providerType) { }
    record CandidateSet(List<CandidateRow> rows) {
        CandidateSet { rows = List.copyOf(rows); }
        int threadCount() { return rows.size(); }
        int messageCount() { return rows.stream().mapToInt(CandidateRow::messageCount).sum(); }
        boolean hasExternalProvider() {
            return rows.stream().anyMatch(row -> !"DWP_SANDBOX".equals(row.providerType()));
        }
        List<UUID> threadIds() { return rows.stream().map(CandidateRow::threadId).toList(); }
    }
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
    record DeleteCounts(int threads, int messages) { }
    record DeliveryRow(UUID id, UUID threadId, UUID messageId, String status,
                       int attemptCount, OffsetDateTime nextAttemptAt, String leaseOwner,
                       OffsetDateTime leaseExpiresAt, String providerMessageRef,
                       String providerThreadRef, String errorCode, String correlationId,
                       OffsetDateTime acceptedAt, OffsetDateTime createdAt, long actorId,
                       OffsetDateTime updatedAt, long version, String accountName,
                       String providerType) { }
    record RecoveryRow(UUID id, String action, String result, Map<String, Object> evidence,
                       String correlationId, OffsetDateTime occurredAt) { }
    record RecoveryCommandRow(UUID id, UUID deliveryId, String action,
                              String result, String fingerprint) { }
    record ExportRow(UUID id, long actorId, Map<String, Object> filters, String purpose,
                     String state, String storageReference, String watermark,
                     UUID idempotencyKey, OffsetDateTime createdAt,
                     OffsetDateTime expiresAt) { }
}
