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

class AdminMailCompletionConnectionSupport extends AdminMailCompletionRetentionSupport {

    AdminMailCompletionConnectionSupport(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
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

}
