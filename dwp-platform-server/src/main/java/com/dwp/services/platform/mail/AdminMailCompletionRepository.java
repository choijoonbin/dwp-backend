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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class AdminMailCompletionRepository {

    /**
     * Evidence rows below are intentionally retained by RESTRICT foreign keys. Keep this
     * predicate shared by preview, deletion, and post-delete verification so an approved
     * candidate set can never expand at execution time.
     */
    private static final String PURGE_IMMUTABLE_EVIDENCE_EXISTS = """
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

    Optional<UUID> insertConnectionOperation(
            long tenantId, long actorId, UUID connectionId, String kind, String scope,
            Map<String, Object> payload, UUID key, String fingerprint, String correlationId) {
        return jdbc.query("""
                INSERT INTO mail_connection_operations (
                    tenant_id, connection_id, actor_user_id, operation_kind,
                    operation_scope, request_payload, idempotency_key,
                    request_fingerprint, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (tenant_id, actor_user_id, idempotency_key) DO NOTHING
                RETURNING operation_id
                """, (rs, ignored) -> uuid(rs, "operation_id"),
                tenantId, connectionId, actorId, kind, scope,
                json(payload), key, fingerprint, correlationId).stream().findFirst();
    }

    Optional<UUID> insertDurableTestSendOperation(
            long tenantId,
            long actorId,
            UUID connectionId,
            String scope,
            Map<String, Object> payload,
            UUID key,
            String fingerprint,
            String correlationId) {
        return jdbc.query("""
                INSERT INTO mail_connection_operations (
                    tenant_id, connection_id, actor_user_id, operation_kind,
                    operation_scope, request_payload, operation_state,
                    idempotency_key, request_fingerprint, correlation_id,
                    evidence_generated_at, error_code)
                VALUES (?, ?, ?, 'TEST_SEND', ?, ?::jsonb, 'UNKNOWN', ?, ?, ?,
                        CURRENT_TIMESTAMP, 'TEST_SEND_RESULT_UNKNOWN')
                ON CONFLICT (tenant_id, actor_user_id, idempotency_key) DO NOTHING
                RETURNING operation_id
                """, (result, ignored) -> uuid(result, "operation_id"),
                tenantId, connectionId, actorId, scope, json(payload), key,
                fingerprint, correlationId).stream().findFirst();
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

    Optional<SyncAccountRow> lockSyncAccount(long tenantId, UUID accountId) {
        return one("""
                SELECT account.account_id, account.email_address,
                       account.owner_user_id, account.synchronization_cursor,
                       inbox.shared_inbox_id
                  FROM mail_accounts account
                  LEFT JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = account.tenant_id
                   AND inbox.account_id = account.account_id
                   AND inbox.lifecycle_state = 'ACTIVE'
                 WHERE account.tenant_id = ? AND account.account_id = ?
                   AND account.connection_state = 'ACTIVE'
                 FOR UPDATE OF account
                """, (result, ignored) -> new SyncAccountRow(
                uuid(result, "account_id"), result.getString("email_address"),
                nullableLong(result, "owner_user_id"),
                result.getString("synchronization_cursor"),
                uuid(result, "shared_inbox_id")), tenantId, accountId);
    }

    Optional<UUID> synchronizationFolder(
            long tenantId, UUID accountId, String providerFolderReference) {
        return jdbc.query("""
                SELECT folder_id
                  FROM mail_folders
                 WHERE tenant_id = ? AND account_id = ?
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY CASE WHEN provider_folder_ref = ? THEN 0
                               WHEN folder_type = 'INBOX' THEN 1 ELSE 2 END,
                          sort_order, folder_id
                 LIMIT 1
                """, (result, ignored) -> uuid(result, "folder_id"),
                tenantId, accountId, providerFolderReference).stream().findFirst();
    }

    Optional<InboundIdentity> inboundMessage(
            long tenantId,
            UUID accountId,
            String providerThreadReference,
            String providerMessageReference) {
        return one("""
                SELECT thread.thread_id, message.message_id
                  FROM mail_threads thread
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                 WHERE thread.tenant_id = ? AND thread.account_id = ?
                   AND thread.provider_thread_ref = ?
                   AND message.provider_message_ref = ?
                """, (result, ignored) -> new InboundIdentity(
                uuid(result, "thread_id"), uuid(result, "message_id")),
                tenantId, accountId, providerThreadReference, providerMessageReference);
    }

    InboundMaterialized materializeInboundMessage(
            long tenantId,
            long actorId,
            SyncAccountRow account,
            UUID folderId,
            InboundMessageRow message,
            List<InboundAttachmentRow> attachments) {
        List<UUID> insertedThreads = jdbc.query("""
                INSERT INTO mail_threads (
                    thread_id, tenant_id, account_id, folder_id, shared_inbox_id,
                    provider_thread_ref, subject, preview, participants,
                    latest_message_at, unread, importance, triage_lane,
                    workflow_state, has_attachments, external_sender,
                    classification, message_count, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, TRUE, 'NORMAL',
                        'PRIORITY', 'OPEN', ?, ?, ?, 1, ?, ?)
                ON CONFLICT (account_id, provider_thread_ref) DO NOTHING
                RETURNING thread_id
                """, (result, ignored) -> uuid(result, "thread_id"),
                UUID.randomUUID(), tenantId, account.id(), folderId,
                account.sharedInboxId(), message.providerThreadReference(),
                message.subject(), message.preview(), json(message.participants()),
                message.occurredAt(), !attachments.isEmpty(), message.externalSender(),
                message.classification(), actorId, actorId);
        boolean threadCreated = !insertedThreads.isEmpty();
        UUID threadId = threadCreated ? insertedThreads.getFirst() : jdbc.queryForObject("""
                SELECT thread_id FROM mail_threads
                 WHERE tenant_id = ? AND account_id = ? AND provider_thread_ref = ?
                """, UUID.class, tenantId, account.id(), message.providerThreadReference());
        if (threadId == null) {
            throw new IllegalStateException("Provider thread could not be materialized");
        }

        UUID messageId = UUID.randomUUID();
        List<UUID> insertedMessages = jdbc.query("""
                INSERT INTO mail_messages (
                    message_id, tenant_id, thread_id, provider_message_ref,
                    sender_email, sender_name, recipients, message_direction,
                    body_format, body_content, attachments, sent_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'INBOUND', ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (thread_id, provider_message_ref) DO NOTHING
                RETURNING message_id
                """, (result, ignored) -> uuid(result, "message_id"),
                messageId, tenantId, threadId, message.providerMessageReference(),
                message.senderEmail(), message.senderName(), json(message.recipients()),
                message.bodyFormat(), message.body(), json(message.attachmentProjection()),
                message.occurredAt(), actorId);
        if (insertedMessages.isEmpty()) {
            InboundIdentity existing = inboundMessage(
                    tenantId, account.id(), message.providerThreadReference(),
                    message.providerMessageReference()).orElseThrow(() ->
                    new IllegalStateException("Provider message conflict could not be resolved"));
            return new InboundMaterialized(existing.threadId(), existing.messageId(), false);
        }

        if (!threadCreated) {
            jdbc.update("""
                    UPDATE mail_threads
                       SET folder_id = CASE WHEN latest_message_at <= ? THEN ? ELSE folder_id END,
                           subject = CASE WHEN latest_message_at <= ? THEN ? ELSE subject END,
                           preview = CASE WHEN latest_message_at <= ? THEN ? ELSE preview END,
                           participants = CASE WHEN latest_message_at <= ?
                                               THEN ?::jsonb ELSE participants END,
                           latest_message_at = GREATEST(latest_message_at, ?),
                           unread = TRUE, workflow_state = 'OPEN',
                           has_attachments = has_attachments OR ?,
                           external_sender = external_sender OR ?,
                           message_count = message_count + 1,
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND thread_id = ?
                    """, message.occurredAt(), folderId,
                    message.occurredAt(), message.subject(),
                    message.occurredAt(), message.preview(),
                    message.occurredAt(), json(message.participants()),
                    message.occurredAt(), !attachments.isEmpty(), message.externalSender(),
                    actorId, tenantId, threadId);
        }
        for (InboundAttachmentRow attachment : attachments) {
            jdbc.update("""
                    INSERT INTO mail_compose_attachments (
                        attachment_id, tenant_id, uploader_user_id, thread_id,
                        storage_reference, file_name, content_type, size_bytes,
                        checksum_sha256, scan_state, scan_evidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (attachment_id) DO NOTHING
                    """, attachment.id(), tenantId,
                    account.ownerUserId() == null ? actorId : account.ownerUserId(),
                    threadId, attachment.storageReference(), attachment.fileName(),
                    attachment.contentType(), attachment.sizeBytes(),
                    attachment.checksumSha256(), attachment.scanState(),
                    attachment.scanEvidence());
        }
        return new InboundMaterialized(threadId, messageId, true);
    }

    int updateAccountSyncCursor(
            long tenantId,
            UUID accountId,
            String expectedCursor,
            String nextCursor,
            long actorId) {
        return jdbc.update("""
                UPDATE mail_accounts
                   SET synchronization_cursor = ?, synchronization_state = 'READY',
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND account_id = ?
                   AND synchronization_cursor IS NOT DISTINCT FROM ?
                """, nextCursor, actorId, tenantId, accountId, expectedCursor);
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

    UUID insertMemberRevokePreview(
            long tenantId, UUID inboxId, UUID memberId, long actorId, long memberVersion,
            ImpactRow impact, boolean providerRevocationRequired, String fingerprint,
            OffsetDateTime expiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO mail_shared_member_revoke_previews (
                    tenant_id, shared_inbox_id, member_id, actor_user_id, member_version,
                    active_assignments, open_drafts, pending_commands,
                    provider_revocation_required, snapshot_fingerprint, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING preview_id
                """, UUID.class, tenantId, inboxId, memberId, actorId, memberVersion,
                impact.activeAssignments(), impact.openDrafts(), impact.pendingCommands(),
                providerRevocationRequired, fingerprint, expiresAt);
    }

    Optional<MemberRevokePreviewRow> memberRevokePreview(
            long tenantId, UUID previewId, UUID inboxId, UUID memberId, long actorId) {
        return one("""
                SELECT preview_id, shared_inbox_id, member_id, actor_user_id,
                       member_version, active_assignments, open_drafts, pending_commands,
                       provider_revocation_required, snapshot_fingerprint,
                       created_at, expires_at, consumed_at
                  FROM mail_shared_member_revoke_previews
                 WHERE tenant_id = ? AND preview_id = ? AND shared_inbox_id = ?
                   AND member_id = ? AND actor_user_id = ?
                """, (rs, ignored) -> new MemberRevokePreviewRow(
                uuid(rs, "preview_id"), uuid(rs, "shared_inbox_id"),
                uuid(rs, "member_id"), rs.getLong("actor_user_id"),
                rs.getLong("member_version"), rs.getInt("active_assignments"),
                rs.getInt("open_drafts"), rs.getInt("pending_commands"),
                rs.getBoolean("provider_revocation_required"),
                rs.getString("snapshot_fingerprint"), offset(rs, "created_at"),
                offset(rs, "expires_at"), offset(rs, "consumed_at")),
                tenantId, previewId, inboxId, memberId, actorId);
    }

    int consumeMemberRevokePreview(long tenantId, UUID previewId, long actorId) {
        return jdbc.update("""
                UPDATE mail_shared_member_revoke_previews
                   SET consumed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND preview_id = ? AND actor_user_id = ?
                   AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                """, tenantId, previewId, actorId);
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

    CandidateSet purgeCandidates(long tenantId, OffsetDateTime before) {
        List<CandidateRow> rows = jdbc.query("""
                SELECT thread.thread_id, thread.account_id,
                       (SELECT count(*) FROM mail_messages message
                         WHERE message.tenant_id = thread.tenant_id
                           AND message.thread_id = thread.thread_id) message_count,
                       (SELECT count(*) FROM mail_compose_attachments attachment
                         WHERE attachment.tenant_id = thread.tenant_id
                           AND attachment.thread_id = thread.thread_id) attachment_count,
                       (SELECT count(*) FROM mail_draft_options draft
                         WHERE draft.tenant_id = thread.tenant_id
                           AND draft.thread_id = thread.thread_id) draft_count,
                       connection.provider_type,
                       (%s) immutable_evidence_blocked
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE thread.tenant_id = ? AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                 ORDER BY thread.thread_id
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                (rs, ignored) -> new CandidateRow(
                uuid(rs, "thread_id"), uuid(rs, "account_id"),
                rs.getInt("message_count"), rs.getInt("attachment_count"),
                rs.getInt("draft_count"),
                rs.getString("provider_type"),
                rs.getBoolean("immutable_evidence_blocked")), tenantId, before);
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

    List<PurgePreviewRow> activePurgePreviews(long tenantId, int limit) {
        return jdbc.query(PURGE_PREVIEW_BY + """
                 AND policy_version = (
                     SELECT policy.version
                       FROM mail_tenant_policies policy
                      WHERE policy.tenant_id = mail_purge_previews.tenant_id)
                 AND expires_at > CURRENT_TIMESTAMP
                 AND NOT EXISTS (
                     SELECT 1
                       FROM mail_purge_jobs job
                      WHERE job.tenant_id = mail_purge_previews.tenant_id
                        AND job.candidate_snapshot_id = mail_purge_previews.candidate_snapshot_id)
                 ORDER BY created_at DESC, candidate_snapshot_id DESC
                 LIMIT ?
                """, PURGE_PREVIEW, tenantId, limit);
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

    void insertPurgeCandidateRows(
            long tenantId, UUID snapshotId, List<PurgeCandidateSnapshotRow> rows) {
        for (PurgeCandidateSnapshotRow row : rows) {
            jdbc.update("""
                    INSERT INTO mail_purge_candidate_snapshot_rows (
                        candidate_snapshot_id, tenant_id, thread_id, account_id,
                        message_count, attachment_count, draft_count, provider_type,
                        held, hold_ids, evidence)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    """, snapshotId, tenantId, row.threadId(), row.accountId(),
                    row.messageCount(), row.attachmentCount(), row.draftCount(),
                    row.providerType(), row.held(),
                    json(row.holdIds().stream().map(UUID::toString).toList()),
                    json(row.evidence()));
        }
    }

    List<PurgeCandidateSnapshotRow> purgeCandidateRows(long tenantId, UUID snapshotId) {
        return jdbc.query("""
                SELECT thread_id, account_id, message_count, attachment_count,
                       draft_count, provider_type, held, hold_ids, evidence
                  FROM mail_purge_candidate_snapshot_rows
                 WHERE tenant_id = ? AND candidate_snapshot_id = ?
                 ORDER BY thread_id
                """, (rs, ignored) -> new PurgeCandidateSnapshotRow(
                uuid(rs, "thread_id"), uuid(rs, "account_id"),
                rs.getInt("message_count"), rs.getInt("attachment_count"),
                rs.getInt("draft_count"), rs.getString("provider_type"),
                rs.getBoolean("held"), strings(rs.getString("hold_ids")).stream()
                        .map(UUID::fromString).toList(),
                map(rs.getString("evidence"))), tenantId, snapshotId);
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

    Optional<PurgeJobRow> purgeJobBySnapshot(long tenantId, UUID snapshotId) {
        return one(PURGE_JOB_BY + " AND candidate_snapshot_id = ?",
                PURGE_JOB, tenantId, snapshotId);
    }

    Optional<PurgeJobRow> purgeJob(long tenantId, UUID jobId) {
        return one(PURGE_JOB_BY + " AND job_id = ?", PURGE_JOB, tenantId, jobId);
    }

    List<PurgeJobRow> purgeJobs(long tenantId, int limit) {
        return jdbc.query(PURGE_JOB_BY + " ORDER BY started_at DESC LIMIT ?",
                PURGE_JOB, tenantId, limit);
    }

    UUID insertPurgeJob(long tenantId, UUID snapshotId, long actorId, UUID key) {
        return insertPurgeJob(tenantId, snapshotId, actorId, key, List.of());
    }

    UUID insertPurgeJob(
            long tenantId, UUID snapshotId, long actorId, UUID key,
            List<UUID> candidateThreadIds) {
        return insertPurgeJob(
                tenantId, snapshotId, actorId, key, candidateThreadIds, List.of());
    }

    UUID insertPurgeJob(
            long tenantId, UUID snapshotId, long actorId, UUID key,
            List<UUID> candidateThreadIds, List<Map<String, Object>> initialSteps) {
        return jdbc.queryForObject("""
                INSERT INTO mail_purge_jobs (
                    tenant_id, candidate_snapshot_id, actor_user_id,
                    job_state, verification_state, idempotency_key, candidate_thread_ids,
                    step_results, current_step)
                VALUES (?, ?, ?, 'ACCEPTED', 'PENDING', ?, ?::jsonb, ?::jsonb, 'ACCEPTED')
                RETURNING job_id
                """, UUID.class, tenantId, snapshotId, actorId, key,
                json(candidateThreadIds.stream().map(UUID::toString).toList()),
                json(initialSteps));
    }

    int completeLeasedPurgeJob(
            long tenantId, UUID jobId, String workerId, String state,
            DeleteCounts counts, List<Map<String, Object>> steps,
            String verification, String errorCode) {
        return jdbc.update("""
                UPDATE mail_purge_jobs
                   SET job_state = ?, deleted_threads = ?, deleted_messages = ?,
                       step_results = ?::jsonb, verification_state = ?, error_code = ?,
                       completed_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED')
                                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       current_step = CASE WHEN ? IN ('SUCCEEDED', 'FAILED')
                                           THEN 'COMPLETED' ELSE 'RETRY_PENDING' END,
                       lease_owner = NULL,
                       lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ? AND job_state = 'RUNNING'
                   AND lease_owner = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, state, counts.threads(), counts.messages(), json(steps), verification,
                errorCode, state, state, tenantId, jobId, workerId);
    }

    int releaseExpiredPurgeLeases(int maximumAttempts) {
        return jdbc.update("""
                UPDATE mail_purge_jobs
                   SET job_state = CASE WHEN attempt_count >= ? THEN 'FAILED' ELSE 'UNKNOWN' END,
                       verification_state = 'UNKNOWN',
                       error_code = 'PURGE_WORKER_LEASE_EXPIRED',
                       current_step = 'LEASE_EXPIRED',
                       step_results = step_results || jsonb_build_array(jsonb_build_object(
                           'step', current_step, 'state', 'UNKNOWN',
                           'errorCode', 'PURGE_WORKER_LEASE_EXPIRED',
                           'at', CURRENT_TIMESTAMP)),
                       lease_owner = NULL, lease_expires_at = NULL,
                       completed_at = CASE WHEN attempt_count >= ?
                                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE job_state = 'RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP
                """, maximumAttempts, maximumAttempts);
    }

    List<PurgeLeaseRow> claimPurgeJobs(
            String workerId, int limit, int leaseSeconds, int maximumAttempts) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT job_id, current_step previous_step,
                           deleted_threads previous_deleted_threads,
                           deleted_messages previous_deleted_messages,
                           started_at
                      FROM mail_purge_jobs
                     WHERE job_state IN ('ACCEPTED', 'PARTIAL', 'UNKNOWN')
                       AND attempt_count < ?
                     ORDER BY started_at, job_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE mail_purge_jobs job
                   SET job_state = 'RUNNING', lease_owner = ?,
                       lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                       attempt_count = attempt_count + 1,
                       current_step = 'LEASED', error_code = NULL,
                       completed_at = NULL, updated_at = CURRENT_TIMESTAMP
                  FROM candidates
                 WHERE job.job_id = candidates.job_id
                RETURNING job.job_id, job.tenant_id, job.candidate_snapshot_id,
                          job.actor_user_id, job.candidate_thread_ids,
                          job.step_results, job.attempt_count,
                          candidates.previous_step,
                          candidates.previous_deleted_threads,
                          candidates.previous_deleted_messages,
                          candidates.started_at
                """, (rs, ignored) -> new PurgeLeaseRow(
                uuid(rs, "job_id"), rs.getLong("tenant_id"),
                uuid(rs, "candidate_snapshot_id"), rs.getLong("actor_user_id"),
                strings(rs.getString("candidate_thread_ids")).stream()
                        .map(UUID::fromString).toList(),
                maps(rs.getString("step_results")), rs.getInt("attempt_count"),
                rs.getString("previous_step"),
                rs.getInt("previous_deleted_threads"),
                rs.getInt("previous_deleted_messages"),
                offset(rs, "started_at")),
                maximumAttempts, limit, workerId, leaseSeconds);
    }

    int checkpointPurgeJob(
            long tenantId, UUID jobId, String workerId, String step,
            List<Map<String, Object>> steps, DeleteCounts counts) {
        return jdbc.update("""
                UPDATE mail_purge_jobs
                   SET current_step = ?, step_results = ?::jsonb,
                       deleted_threads = ?, deleted_messages = ?,
                       updated_at = CURRENT_TIMESTAMP,
                       lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                 WHERE tenant_id = ? AND job_id = ? AND job_state = 'RUNNING'
                   AND lease_owner = ? AND lease_expires_at > CURRENT_TIMESTAMP
                """, step, json(steps), counts.threads(), counts.messages(),
                tenantId, jobId, workerId);
    }

    PurgeEventEvidence ensurePurgeCompletionEvent(
            long tenantId, UUID jobId, long actorId, DeleteCounts counts) {
        UUID eventId = UUID.nameUUIDFromBytes(
                ("mail-purge:" + tenantId + ':' + jobId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ensurePurgeCompletionEvent(tenantId, jobId, actorId, counts, eventId);
    }

    PurgeEventEvidence ensurePurgeCompletionEvent(
            long tenantId, UUID jobId, long actorId, DeleteCounts counts, UUID eventId) {
        jdbc.update("""
                INSERT INTO mail_domain_events (
                    domain_event_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, payload)
                VALUES (?, ?, 'MAIL_PURGE_JOB', ?, 'mail.purge.completed', ?::jsonb)
                ON CONFLICT (tenant_id, aggregate_id, event_type)
                    WHERE aggregate_type = 'MAIL_PURGE_JOB'
                      AND event_type = 'mail.purge.completed'
                DO NOTHING
                """, eventId, tenantId, jobId, json(Map.of(
                "jobId", jobId, "actorId", actorId,
                "deletedThreads", counts.threads(), "deletedMessages", counts.messages())));
        jdbc.update("""
                UPDATE mail_domain_events event
                   SET published_at = outbox.published_at,
                       publish_attempts = outbox.attempt_count
                  FROM sys_domain_event_outbox outbox
                 WHERE event.tenant_id = ?
                   AND event.aggregate_type = 'MAIL_PURGE_JOB'
                   AND event.aggregate_id = ?
                   AND event.event_type = 'mail.purge.completed'
                   AND outbox.event_id = event.domain_event_id
                """, tenantId, jobId);
        return jdbc.queryForObject("""
                SELECT event.domain_event_id, event.occurred_at,
                       outbox.published_at, outbox.attempt_count publish_attempts,
                       outbox.status, outbox.last_error
                  FROM mail_domain_events event
                  JOIN sys_domain_event_outbox outbox
                    ON outbox.event_id = event.domain_event_id
                 WHERE event.tenant_id = ? AND event.aggregate_type = 'MAIL_PURGE_JOB'
                   AND event.aggregate_id = ? AND event.event_type = 'mail.purge.completed'
                """, (rs, ignored) -> new PurgeEventEvidence(
                uuid(rs, "domain_event_id"), offset(rs, "occurred_at"),
                offset(rs, "published_at"), rs.getInt("publish_attempts"),
                rs.getString("status"), rs.getString("last_error")),
                tenantId, jobId);
    }

    DeleteCounts deletePurgeCandidates(long tenantId, OffsetDateTime before) {
        List<String> attachmentStorageReferences = jdbc.queryForList("""
                SELECT DISTINCT attachment.storage_reference
                  FROM mail_compose_attachments attachment
                  JOIN mail_threads thread
                    ON thread.tenant_id = attachment.tenant_id
                   AND thread.thread_id = attachment.thread_id
                 WHERE thread.tenant_id = ?
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                String.class, tenantId, before);
        Integer messages = jdbc.queryForObject("""
                SELECT count(*) FROM mail_messages message
                 WHERE message.tenant_id = ? AND EXISTS (
                    SELECT 1 FROM mail_threads thread
                     WHERE thread.tenant_id = message.tenant_id
                       AND thread.thread_id = message.thread_id
                       AND thread.workflow_state = 'TRASHED'
                       AND thread.updated_at < ?
                       AND NOT (%s))
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                Integer.class, tenantId, before);
        int threads = jdbc.update("""
                DELETE FROM mail_threads thread
                 WHERE thread.tenant_id = ?
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS), tenantId, before);
        int cleanupCommands = 0;
        for (String storageReference : attachmentStorageReferences) {
            cleanupCommands += jdbc.update("""
                    INSERT INTO sys_tenant_media_cleanup_outbox (
                        tenant_id, storage_key, cleanup_reason)
                    SELECT ?, ?, 'MAIL_RETENTION_PURGE'
                     WHERE NOT EXISTS (
                         SELECT 1
                           FROM mail_compose_attachments attachment
                          WHERE attachment.tenant_id = ?
                            AND attachment.storage_reference = ?)
                    ON CONFLICT DO NOTHING
                    """, tenantId, storageReference, tenantId, storageReference);
        }
        return new DeleteCounts(threads, messages == null ? 0 : messages, cleanupCommands);
    }

    DeleteCounts deletePurgeCandidates(long tenantId, List<UUID> threadIds) {
        if (threadIds.isEmpty()) return new DeleteCounts(0, 0);
        String placeholders = String.join(", ", java.util.Collections.nCopies(threadIds.size(), "?"));
        Object[] parameters = new Object[threadIds.size() + 1];
        parameters[0] = tenantId;
        for (int index = 0; index < threadIds.size(); index++) {
            parameters[index + 1] = threadIds.get(index);
        }
        List<String> attachmentStorageReferences = jdbc.queryForList("""
                SELECT DISTINCT storage_reference
                  FROM mail_compose_attachments
                 WHERE tenant_id = ? AND thread_id IN (%s)
                """.formatted(placeholders), String.class, parameters);
        Integer messages = jdbc.queryForObject("""
                SELECT count(*) FROM mail_messages
                 WHERE tenant_id = ? AND thread_id IN (%s)
                """.formatted(placeholders), Integer.class, parameters);
        int threads = jdbc.update("""
                DELETE FROM mail_threads
                 WHERE tenant_id = ? AND thread_id IN (%s)
                """.formatted(placeholders), parameters);
        int cleanupCommands = 0;
        for (String storageReference : attachmentStorageReferences) {
            cleanupCommands += jdbc.update("""
                    INSERT INTO sys_tenant_media_cleanup_outbox (
                        tenant_id, storage_key, cleanup_reason)
                    SELECT ?, ?, 'MAIL_RETENTION_PURGE'
                     WHERE NOT EXISTS (
                         SELECT 1 FROM mail_compose_attachments
                          WHERE tenant_id = ? AND storage_reference = ?)
                    ON CONFLICT DO NOTHING
                    """, tenantId, storageReference, tenantId, storageReference);
        }
        return new DeleteCounts(threads, messages == null ? 0 : messages, cleanupCommands);
    }

    java.util.Set<UUID> purgeDeletionReceiptIds(long tenantId, UUID jobId) {
        return java.util.Set.copyOf(jdbc.queryForList("""
                SELECT thread_id
                  FROM mail_purge_deleted_candidate_receipts
                 WHERE tenant_id = ? AND job_id = ?
                """, UUID.class, tenantId, jobId));
    }

    DeleteCounts purgeDeletionReceiptCounts(long tenantId, UUID jobId) {
        return jdbc.queryForObject("""
                SELECT count(*) deleted_threads,
                       COALESCE(sum(message_count), 0) deleted_messages
                  FROM mail_purge_deleted_candidate_receipts
                 WHERE tenant_id = ? AND job_id = ?
                """, (rs, ignored) -> new DeleteCounts(
                rs.getInt("deleted_threads"), rs.getInt("deleted_messages")),
                tenantId, jobId);
    }

    DeleteCounts deleteEligiblePurgeCandidates(
            PurgeLeaseRow job, OffsetDateTime before) {
        java.util.Set<UUID> completed = purgeDeletionReceiptIds(job.tenantId(), job.id());
        List<UUID> pending = job.candidateThreadIds().stream()
                .filter(id -> !completed.contains(id)).toList();
        if (pending.isEmpty()) return purgeDeletionReceiptCounts(job.tenantId(), job.id());

        String placeholders = String.join(", ",
                java.util.Collections.nCopies(pending.size(), "?"));
        Object[] parameters = new Object[pending.size() + 2];
        parameters[0] = job.tenantId();
        for (int index = 0; index < pending.size(); index++) {
            parameters[index + 1] = pending.get(index);
        }
        parameters[parameters.length - 1] = before;
        List<UUID> lockedEligible = jdbc.queryForList("""
                SELECT thread.thread_id
                  FROM mail_threads thread
                 WHERE thread.tenant_id = ?
                   AND thread.thread_id IN (%s)
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                 ORDER BY thread.thread_id
                 FOR UPDATE
                """.formatted(placeholders, PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                UUID.class, parameters);
        if (!java.util.Set.copyOf(lockedEligible).equals(java.util.Set.copyOf(pending))) {
            throw new AdminMailPurgeGuard.PurgeBlockedException(
                    "PURGE_CANDIDATE_NO_LONGER_ELIGIBLE");
        }

        Object[] lockedParameters = new Object[lockedEligible.size() + 2];
        lockedParameters[0] = job.tenantId();
        for (int index = 0; index < lockedEligible.size(); index++) {
            lockedParameters[index + 1] = lockedEligible.get(index);
        }
        lockedParameters[lockedParameters.length - 1] = before;
        List<String> attachmentStorageReferences = jdbc.queryForList("""
                SELECT DISTINCT attachment.storage_reference
                  FROM mail_compose_attachments attachment
                  JOIN mail_threads thread
                    ON thread.tenant_id = attachment.tenant_id
                   AND thread.thread_id = attachment.thread_id
                 WHERE thread.tenant_id = ? AND thread.thread_id IN (%s)
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                """.formatted(placeholders), String.class, lockedParameters);
        List<UUID> deleted = jdbc.queryForList("""
                DELETE FROM mail_threads thread
                 WHERE thread.tenant_id = ? AND thread.thread_id IN (%s)
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                RETURNING thread.thread_id
                """.formatted(placeholders, PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                UUID.class, lockedParameters);
        if (!java.util.Set.copyOf(deleted).equals(java.util.Set.copyOf(pending))) {
            throw new AdminMailPurgeGuard.PurgeBlockedException(
                    "PURGE_DELETE_ELIGIBILITY_RACE");
        }
        for (UUID threadId : deleted) {
            if (jdbc.update("""
                    INSERT INTO mail_purge_deleted_candidate_receipts (
                        job_id, candidate_snapshot_id, tenant_id, thread_id,
                        message_count, attachment_count, draft_count)
                    SELECT ?, candidate_snapshot_id, tenant_id, thread_id,
                           message_count, attachment_count, draft_count
                      FROM mail_purge_candidate_snapshot_rows
                     WHERE tenant_id = ? AND candidate_snapshot_id = ? AND thread_id = ?
                    ON CONFLICT (job_id, thread_id) DO NOTHING
                    """, job.id(), job.tenantId(), job.snapshotId(), threadId) != 1) {
                throw new AdminMailPurgeGuard.PurgeBlockedException(
                        "PURGE_DELETE_RECEIPT_NOT_RECORDED");
            }
        }
        int cleanupCommands = 0;
        for (String storageReference : attachmentStorageReferences) {
            cleanupCommands += jdbc.update("""
                    INSERT INTO sys_tenant_media_cleanup_outbox (
                        tenant_id, storage_key, cleanup_reason)
                    SELECT ?, ?, 'MAIL_RETENTION_PURGE'
                     WHERE NOT EXISTS (
                         SELECT 1 FROM mail_compose_attachments
                          WHERE tenant_id = ? AND storage_reference = ?)
                    ON CONFLICT DO NOTHING
                    """, job.tenantId(), storageReference,
                    job.tenantId(), storageReference);
        }
        DeleteCounts cumulative = purgeDeletionReceiptCounts(job.tenantId(), job.id());
        return new DeleteCounts(
                cumulative.threads(), cumulative.messages(), cleanupCommands);
    }

    void completePurgeJob(
            long tenantId, UUID jobId, String state, DeleteCounts counts,
            List<Map<String, Object>> steps, String verification, String errorCode) {
        jdbc.update("""
                UPDATE mail_purge_jobs
                   SET job_state = ?, deleted_threads = ?, deleted_messages = ?,
                       step_results = ?::jsonb, verification_state = ?, error_code = ?,
                       completed_at = CURRENT_TIMESTAMP, current_step = 'COMPLETED',
                       lease_owner = NULL, lease_expires_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND job_id = ?
                """, state, counts.threads(), counts.messages(), json(steps), verification,
                errorCode, tenantId, jobId);
    }

    long remainingPurgeCandidates(long tenantId, OffsetDateTime before) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM mail_threads thread
                 WHERE thread.tenant_id = ?
                   AND thread.workflow_state = 'TRASHED'
                   AND thread.updated_at < ?
                   AND NOT (%s)
                """.formatted(PURGE_IMMUTABLE_EVIDENCE_EXISTS),
                Long.class, tenantId, before);
        return count == null ? 0 : count;
    }

    long remainingPurgeCandidates(long tenantId, List<UUID> threadIds) {
        if (threadIds.isEmpty()) return 0;
        String placeholders = String.join(", ", java.util.Collections.nCopies(threadIds.size(), "?"));
        Object[] parameters = new Object[threadIds.size() + 1];
        parameters[0] = tenantId;
        for (int index = 0; index < threadIds.size(); index++) {
            parameters[index + 1] = threadIds.get(index);
        }
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM mail_threads
                 WHERE tenant_id = ? AND thread_id IN (%s)
                """.formatted(placeholders), Long.class, parameters);
        return count == null ? 0 : count;
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
            OffsetDateTime snapshotCutoff) {
        return insertExport(
                exportId, tenantId, actorId, filters, purpose, watermark, key,
                expiresAt, snapshotPayload, payloadSha256, itemCount, truncated,
                snapshotCutoff, "DELIVERY_AUDIT", Map.of(), null, null);
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

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
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

    private static final String LEGAL_HOLD_RELEASE_PREVIEW_BY = """
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

    private final RowMapper<LegalHoldReleasePreviewRow> LEGAL_HOLD_RELEASE_PREVIEW =
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

    private static final RowMapper<LegalHoldReleaseApprovalRow> LEGAL_HOLD_RELEASE_APPROVAL =
            (rs, ignored) -> new LegalHoldReleaseApprovalRow(
                    uuid(rs, "approval_id"), uuid(rs, "release_preview_id"),
                    rs.getLong("approver_user_id"), rs.getString("decision"),
                    rs.getLong("hold_version"), rs.getLong("policy_version"),
                    rs.getString("preview_fingerprint"),
                    rs.getString("request_fingerprint"), uuid(rs, "idempotency_key"),
                    offset(rs, "decided_at"));

    private static final String LEGAL_HOLD_RELEASE_EXECUTION_BY = """
            SELECT execution_id, release_preview_id, hold_id, requester_user_id,
                   approved_by_user_id, executed_by_user_id, hold_version_before,
                   hold_version_after, policy_version, preview_fingerprint,
                   request_fingerprint, idempotency_key, executed_at
              FROM mail_legal_hold_release_executions WHERE tenant_id = ?
            """;

    private static final RowMapper<LegalHoldReleaseExecutionRow> LEGAL_HOLD_RELEASE_EXECUTION =
            (rs, ignored) -> new LegalHoldReleaseExecutionRow(
                    uuid(rs, "execution_id"), uuid(rs, "release_preview_id"),
                    uuid(rs, "hold_id"), rs.getLong("requester_user_id"),
                    rs.getLong("approved_by_user_id"), rs.getLong("executed_by_user_id"),
                    rs.getLong("hold_version_before"), rs.getLong("hold_version_after"),
                    rs.getLong("policy_version"), rs.getString("preview_fingerprint"),
                    rs.getString("request_fingerprint"), uuid(rs, "idempotency_key"),
                    offset(rs, "executed_at"));

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
                   storage_reference, watermark, idempotency_key, created_at, expires_at,
                   snapshot_payload, payload_sha256, item_count, truncated, snapshot_cutoff,
                   export_kind, export_scope, policy_version, required_approvals,
                   request_fingerprint
              FROM mail_delivery_audit_exports WHERE tenant_id = ?
            """;

    private final RowMapper<ExportRow> EXPORT = (rs, ignored) -> new ExportRow(
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

    private static final RowMapper<ExportApprovalRow> EXPORT_APPROVAL = (rs, ignored) ->
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
                     int requiredApprovals, String requestFingerprint) {
        ExportRow(
                UUID id, long actorId, Map<String, Object> filters, String purpose,
                String state, String storageReference, String watermark,
                UUID idempotencyKey, OffsetDateTime createdAt,
                OffsetDateTime expiresAt, String snapshotPayload,
                String payloadSha256, Integer itemCount, Boolean truncated,
                OffsetDateTime snapshotCutoff) {
            this(id, actorId, filters, purpose, state, storageReference, watermark,
                    idempotencyKey, createdAt, expiresAt, snapshotPayload,
                    payloadSha256, itemCount, truncated, snapshotCutoff,
                    "DELIVERY_AUDIT", Map.of(), null, 1, null);
        }
    }
    record ExportApprovalRow(UUID id, UUID exportId, long approverUserId,
                             String decision, String requestFingerprint,
                             OffsetDateTime decidedAt) { }
}
