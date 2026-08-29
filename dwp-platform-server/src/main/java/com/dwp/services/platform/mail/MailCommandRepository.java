package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.*;

@Repository
class MailCommandRepository {

    record ComposeResult(UUID threadId, boolean created) {
    }

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailCommandRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    int applyAction(
            Long tenantId,
            Long userId,
            UUID threadId,
            ThreadAction action,
            long version) {
        if (action == ThreadAction.ARCHIVE) {
            return moveToArchive(tenantId, userId, threadId, version);
        }
        if (action == ThreadAction.RESTORE) {
            return restoreFromLifecycleFolder(tenantId, userId, threadId, version);
        }
        String assignment = switch (action) {
            case MARK_READ -> "unread = FALSE";
            case MARK_UNREAD -> "unread = TRUE";
            case STAR -> "starred = TRUE";
            case UNSTAR -> "starred = FALSE";
            case ARCHIVE, RESTORE -> throw new IllegalStateException("Lifecycle action was not routed.");
            case REOPEN -> "workflow_state = 'OPEN', snoozed_until = NULL";
            case COMPLETE -> "workflow_state = 'DONE', snoozed_until = NULL";
        };
        return jdbc.update("""
                UPDATE mail_threads thread
                   SET %s, version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts account
                 WHERE thread.tenant_id = ? AND thread.thread_id = ? AND thread.version = ?
                """.formatted(assignment) + MailAccessSql.THREAD_ACCESS,
                userId, tenantId, threadId, version, userId, userId);
    }

    private int moveToArchive(
            Long tenantId, Long userId, UUID threadId, long version) {
        return jdbc.update("""
                UPDATE mail_threads thread
                   SET previous_folder_id = CASE
                           WHEN current_folder.folder_type IN ('ARCHIVE', 'TRASH', 'SPAM')
                           THEN thread.previous_folder_id ELSE thread.folder_id END,
                       folder_id = archive_folder.folder_id,
                       workflow_state = 'ARCHIVED', snoozed_until = NULL,
                       trashed_at = NULL, spam_reported_at = NULL,
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts account,
                       mail_folders current_folder,
                       mail_folders archive_folder
                 WHERE thread.tenant_id = ? AND thread.thread_id = ? AND thread.version = ?
                   AND current_folder.tenant_id = thread.tenant_id
                   AND current_folder.account_id = thread.account_id
                   AND current_folder.folder_id = thread.folder_id
                   AND archive_folder.tenant_id = thread.tenant_id
                   AND archive_folder.account_id = thread.account_id
                   AND archive_folder.folder_type = 'ARCHIVE'
                   AND archive_folder.lifecycle_state = 'ACTIVE'
                """ + MailAccessSql.THREAD_ACCESS,
                userId, tenantId, threadId, version, userId, userId);
    }

    private int restoreFromLifecycleFolder(
            Long tenantId, Long userId, UUID threadId, long version) {
        return jdbc.update("""
                UPDATE mail_threads thread
                   SET folder_id = COALESCE(previous_folder.folder_id, inbox_folder.folder_id),
                       previous_folder_id = NULL, workflow_state = 'OPEN',
                       snoozed_until = NULL, trashed_at = NULL, spam_reported_at = NULL,
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts account,
                       mail_folders current_folder
                  JOIN mail_folders inbox_folder
                    ON inbox_folder.tenant_id = current_folder.tenant_id
                   AND inbox_folder.account_id = current_folder.account_id
                   AND inbox_folder.folder_type = 'INBOX'
                   AND inbox_folder.lifecycle_state = 'ACTIVE'
                  LEFT JOIN mail_folders previous_folder
                    ON previous_folder.folder_id = (
                        SELECT source.previous_folder_id
                          FROM mail_threads source
                         WHERE source.tenant_id = ? AND source.thread_id = ?)
                   AND previous_folder.tenant_id = current_folder.tenant_id
                   AND previous_folder.account_id = current_folder.account_id
                   AND previous_folder.lifecycle_state = 'ACTIVE'
                   AND previous_folder.folder_type IN ('INBOX', 'SENT', 'CUSTOM')
                 WHERE thread.tenant_id = ? AND thread.thread_id = ? AND thread.version = ?
                   AND current_folder.tenant_id = thread.tenant_id
                   AND current_folder.account_id = thread.account_id
                   AND current_folder.folder_id = thread.folder_id
                   AND current_folder.folder_type IN ('ARCHIVE', 'TRASH', 'SPAM')
                """ + MailAccessSql.THREAD_ACCESS,
                userId, tenantId, threadId, tenantId, threadId, version, userId, userId);
    }

    int snooze(
            Long tenantId,
            Long userId,
            UUID threadId,
            OffsetDateTime until,
            long version) {
        return jdbc.update("""
                UPDATE mail_threads thread
                   SET workflow_state = 'SNOOZED', snoozed_until = ?, unread = FALSE,
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts account
                 WHERE thread.tenant_id = ? AND thread.thread_id = ? AND thread.version = ?
                """ + MailAccessSql.THREAD_ACCESS,
                until, userId, tenantId, threadId, version, userId, userId);
    }

    int assign(
            Long tenantId,
            Long userId,
            UUID threadId,
            Long assignedUserId,
            String assignedName,
            long version) {
        return jdbc.update("""
                UPDATE mail_threads thread
                   SET assigned_user_id = ?, assigned_name = ?, triage_lane = 'ASSIGNED',
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts account
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND thread.shared_inbox_id IS NOT NULL AND thread.version = ?
                """ + MailAccessSql.THREAD_ACCESS
                        + "\n AND " + MailAccessSql.ACTIVE_SHARED_MEMBER,
                assignedUserId, assignedName, userId, tenantId, threadId, version,
                userId, userId, assignedUserId);
    }

    UUID insertComment(
            Long tenantId,
            Long userId,
            String authorName,
            UUID threadId,
            String body,
            List<Long> mentions) {
        UUID commentId = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                INSERT INTO mail_internal_comments (
                    comment_id, tenant_id, thread_id, author_user_id,
                    author_name, body, mentioned_user_ids)
                SELECT ?, thread.tenant_id, thread.thread_id, ?, ?, ?, ?::jsonb
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                RETURNING comment_id
                """, (result, ignored) -> result.getObject("comment_id", UUID.class),
                commentId, userId, authorName, body.trim(), json.write(mentions),
                tenantId, threadId, userId, userId);
        return inserted.isEmpty() ? null : inserted.get(0);
    }

    boolean insertReply(
            Long tenantId,
            Long userId,
            UUID threadId,
            String body,
            UUID idempotencyKey) {
        UUID messageId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO mail_messages (
                    message_id, tenant_id, thread_id, provider_message_ref,
                    sender_email, sender_name, recipients, message_direction,
                    body_format, body_content, attachments, sent_at, created_by)
                SELECT ?, thread.tenant_id, thread.thread_id, ?,
                       account.email_address, account.display_name,
                       thread.participants, 'OUTBOUND', 'TEXT', ?, '[]'::jsonb,
                       CURRENT_TIMESTAMP, ?
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                ON CONFLICT (thread_id, provider_message_ref) DO NOTHING
                """, messageId, "dwp:reply:" + idempotencyKey, body.trim(), userId,
                tenantId, threadId, userId, userId);
        if (inserted == 0) return false;
        int updated = jdbc.update("""
                UPDATE mail_threads thread
                   SET preview = ?, latest_message_at = CURRENT_TIMESTAMP,
                       unread = FALSE, triage_lane = 'UPDATES',
                       workflow_state = 'OPEN', snoozed_until = NULL,
                       message_count = thread.message_count + 1,
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts account
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """ + MailAccessSql.THREAD_ACCESS,
                preview(body), userId, tenantId, threadId, userId, userId);
        if (updated != 1) {
            throw new IllegalStateException("Mail thread access changed while replying.");
        }
        return true;
    }

    ComposeResult compose(
            Long tenantId,
            Long userId,
            MailDtos.ComposeRequest request) {
        String folderType = request.deliveryMode() == DeliveryMode.DRAFT ? "DRAFTS" : "SENT";
        String workflowState = request.deliveryMode() == DeliveryMode.DRAFT ? "DRAFT" : "OPEN";
        String providerRef = "dwp:compose:" + request.idempotencyKey();
        UUID existingThreadId = composedThread(
                tenantId, userId, request.idempotencyKey(), providerRef);
        if (existingThreadId != null) {
            return new ComposeResult(existingThreadId, false);
        }
        List<UUID> threadIds = jdbc.query("""
                INSERT INTO mail_threads (
                    thread_id, tenant_id, account_id, folder_id, provider_thread_ref,
                    subject, preview, participants, latest_message_at,
                    unread, importance, triage_lane, workflow_state,
                    external_sender, classification, message_count,
                    created_by, updated_by)
                SELECT ?, account.tenant_id, account.account_id, folder.folder_id, ?,
                       ?, ?, jsonb_build_array(jsonb_build_object(
                           'name', ?, 'email', LOWER(?))), CURRENT_TIMESTAMP,
                       FALSE, 'NORMAL', 'UPDATES', ?,
                       SPLIT_PART(LOWER(?), '@', 2) <> SPLIT_PART(account.email_address, '@', 2),
                       'INTERNAL', 1, ?, ?
                  FROM mail_accounts account
                  JOIN mail_folders folder
                    ON folder.tenant_id = account.tenant_id
                   AND folder.account_id = account.account_id
                   AND folder.folder_type = ?
                   AND folder.lifecycle_state = 'ACTIVE'
                 WHERE account.tenant_id = ? AND account.owner_user_id = ?
                   AND account.is_default = TRUE AND account.connection_state = 'ACTIVE'
                ON CONFLICT (account_id, provider_thread_ref) DO NOTHING
                RETURNING thread_id
                """, (result, ignored) -> result.getObject("thread_id", UUID.class),
                UUID.randomUUID(), providerRef, request.subject().trim(),
                preview(request.body()), recipientName(request), request.toEmail().trim(),
                workflowState, request.toEmail().trim(), userId, userId,
                folderType, tenantId, userId);
        if (threadIds.isEmpty()) {
            UUID concurrentThreadId = composedThread(
                    tenantId, userId, request.idempotencyKey(), providerRef);
            if (concurrentThreadId == null) {
                throw new IllegalStateException("Composed thread projection is missing.");
            }
            return new ComposeResult(concurrentThreadId, false);
        }
        UUID threadId = threadIds.get(0);
        int messageInserted = jdbc.update("""
                INSERT INTO mail_messages (
                    message_id, tenant_id, thread_id, provider_message_ref,
                    sender_email, sender_name, recipients, message_direction,
                    body_format, body_content, attachments, sent_at, created_by)
                SELECT ?, thread.tenant_id, thread.thread_id, ?,
                       account.email_address, account.display_name,
                       thread.participants, ?, 'TEXT', ?, '[]'::jsonb,
                       CURRENT_TIMESTAMP, ?
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                ON CONFLICT (thread_id, provider_message_ref) DO NOTHING
                """, UUID.randomUUID(), providerRef + ":message",
                request.deliveryMode() == DeliveryMode.DRAFT ? "DRAFT" : "OUTBOUND",
                request.body().trim(), userId, tenantId, threadId);
        if (messageInserted != 1) {
            throw new IllegalStateException("Composed message projection is missing.");
        }
        return new ComposeResult(threadId, true);
    }

    private UUID composedThread(
            Long tenantId,
            Long userId,
            UUID idempotencyKey,
            String providerRef) {
        UUID deliveredThreadId = deliveryThread(tenantId, userId, idempotencyKey);
        if (deliveredThreadId != null) {
            return deliveredThreadId;
        }
        List<UUID> threadIds = jdbc.query("""
                SELECT thread.thread_id
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND thread.provider_thread_ref = ?
                   AND account.owner_user_id = ?
                 ORDER BY thread.created_at DESC
                 LIMIT 1
                """, (result, ignored) -> result.getObject("thread_id", UUID.class),
                tenantId, providerRef, userId);
        return threadIds.isEmpty() ? null : threadIds.get(0);
    }

    UUID deliveryThread(Long tenantId, Long userId, UUID idempotencyKey) {
        List<UUID> threadIds = jdbc.query("""
                SELECT delivery.thread_id
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread
                    ON thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE delivery.tenant_id = ? AND delivery.idempotency_key = ?
                """ + MailAccessSql.THREAD_ACCESS + """
                 LIMIT 1
                """, (result, ignored) -> result.getObject("thread_id", UUID.class),
                tenantId, idempotencyKey, userId, userId);
        return threadIds.isEmpty() ? null : threadIds.get(0);
    }

    int updateDraft(
            Long tenantId,
            Long userId,
            UUID threadId,
            MailDtos.DraftUpdateRequest request) {
        String folderType = request.deliveryMode() == DeliveryMode.DRAFT ? "DRAFTS" : "SENT";
        String workflowState = request.deliveryMode() == DeliveryMode.DRAFT ? "DRAFT" : "OPEN";
        int updated = jdbc.update("""
                UPDATE mail_threads thread
                   SET folder_id = folder.folder_id,
                       subject = ?, preview = ?,
                       participants = jsonb_build_array(jsonb_build_object(
                           'name', ?, 'email', LOWER(?))),
                       latest_message_at = CURRENT_TIMESTAMP,
                       unread = FALSE, triage_lane = 'UPDATES',
                       workflow_state = ?, snoozed_until = NULL,
                       external_sender = SPLIT_PART(LOWER(?), '@', 2)
                           <> SPLIT_PART(account.email_address, '@', 2),
                       version = thread.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_accounts account, mail_folders folder
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                   AND account.tenant_id = thread.tenant_id
                   AND thread.account_id = account.account_id
                   AND account.owner_user_id = ?
                   AND folder.tenant_id = thread.tenant_id
                   AND folder.account_id = thread.account_id
                   AND folder.folder_type = ?
                   AND folder.lifecycle_state = 'ACTIVE'
                   AND thread.workflow_state = 'DRAFT'
                   AND thread.version = ?
                """, request.subject().trim(), preview(request.body()),
                recipientName(request.toName(), request.toEmail()), request.toEmail().trim(),
                workflowState, request.toEmail().trim(), userId,
                tenantId, threadId, userId, folderType, request.version());
        if (updated == 0) return 0;
        int messageUpdated = jdbc.update("""
                UPDATE mail_messages
                   SET recipients = jsonb_build_array(jsonb_build_object(
                           'name', ?, 'email', LOWER(?), 'type', 'TO')),
                       message_direction = ?, body_content = ?,
                       sent_at = CURRENT_TIMESTAMP
                 WHERE message_id = (
                       SELECT message_id
                         FROM mail_messages
                        WHERE tenant_id = ? AND thread_id = ?
                        ORDER BY sent_at, message_id
                        LIMIT 1)
                """, recipientName(request.toName(), request.toEmail()),
                request.toEmail().trim(),
                request.deliveryMode() == DeliveryMode.DRAFT ? "DRAFT" : "OUTBOUND",
                request.body().trim(), tenantId, threadId);
        if (messageUpdated != 1) {
            throw new IllegalStateException("Draft message projection is missing.");
        }
        return updated;
    }

    int decideProposal(
            Long tenantId,
            Long userId,
            UUID proposalId,
            ProposalDecision decision,
            long version) {
        String status = decision == ProposalDecision.ACCEPT ? "ACCEPTED" : "DISMISSED";
        return jdbc.update("""
                UPDATE mail_action_proposals proposal
                   SET proposal_status = ?, decided_at = CURRENT_TIMESTAMP,
                       decided_by = ?, version = proposal.version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                  FROM mail_threads thread, mail_accounts account
                 WHERE proposal.tenant_id = ? AND proposal.proposal_id = ?
                   AND thread.tenant_id = proposal.tenant_id
                   AND thread.thread_id = proposal.thread_id
                   AND proposal.proposal_status = 'PROPOSED' AND proposal.version = ?
                   AND (proposal.expires_at IS NULL OR proposal.expires_at > CURRENT_TIMESTAMP)
                """ + MailAccessSql.THREAD_ACCESS,
                status, userId, userId, tenantId, proposalId, version, userId, userId);
    }

    void enqueueDelivery(
            Long tenantId,
            Long userId,
            UUID threadId,
            UUID idempotencyKey,
            String correlationId) {
        int inserted = jdbc.update("""
                INSERT INTO mail_delivery_outbox (
                    delivery_id, tenant_id, thread_id, message_id,
                    idempotency_key, correlation_id, created_by)
                SELECT ?, message.tenant_id, message.thread_id, message.message_id,
                       ?, NULLIF(?, ''), ?
                  FROM mail_messages message
                  JOIN mail_threads thread
                    ON thread.tenant_id = message.tenant_id
                   AND thread.thread_id = message.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE message.tenant_id = ? AND message.thread_id = ?
                   AND message.message_direction = 'OUTBOUND'
                """ + MailAccessSql.THREAD_ACCESS + """
                 ORDER BY message.sent_at DESC, message.message_id DESC
                 LIMIT 1
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), idempotencyKey, value(correlationId), userId,
                tenantId, threadId, userId, userId);
        if (inserted == 0) {
            Integer existing = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM mail_delivery_outbox delivery
                      JOIN mail_threads thread
                        ON thread.tenant_id = delivery.tenant_id
                       AND thread.thread_id = delivery.thread_id
                      JOIN mail_accounts account
                        ON account.tenant_id = thread.tenant_id
                       AND account.account_id = thread.account_id
                     WHERE delivery.tenant_id = ? AND delivery.idempotency_key = ?
                       AND delivery.thread_id = ?
                    """ + MailAccessSql.THREAD_ACCESS,
                    Integer.class, tenantId, idempotencyKey, threadId, userId, userId);
            if (existing == null || existing == 0) {
                throw new IllegalStateException("Outbound message projection is missing.");
            }
        }
    }

    int updatePolicy(
            Long tenantId,
            Long userId,
            MailDtos.TenantPolicyRequest request) {
        return jdbc.update("""
                UPDATE mail_tenant_policies
                   SET external_sender_banner = ?, block_remote_images = ?,
                       allow_shared_inboxes = ?, ai_assistance_enabled = ?,
                       ai_cross_app_actions_enabled = ?, retention_days = ?,
                       maximum_attachment_mb = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND version = ?
                """, request.externalSenderBanner(), request.blockRemoteImages(),
                request.allowSharedInboxes(), request.aiAssistanceEnabled(),
                request.aiCrossAppActionsEnabled(), request.retentionDays(),
                request.maximumAttachmentMb(), userId, tenantId, request.version());
    }

    int updateConnection(
            Long tenantId,
            Long userId,
            UUID connectionId,
            MailDtos.ConnectionUpdateRequest request) {
        return jdbc.update("""
                UPDATE mail_provider_connections
                   SET display_name = ?, mail_domain = NULLIF(?, ''),
                       credential_ref = COALESCE(NULLIF(?, ''), credential_ref),
                       connection_state = ?, last_error_code = NULL,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND connection_id = ? AND version = ?
                """, request.displayName().trim(), value(request.mailDomain()),
                value(request.credentialRef()), request.state().name(), userId,
                tenantId, connectionId, request.version());
    }

    int updateSharedInbox(
            Long tenantId,
            Long userId,
            UUID sharedInboxId,
            MailDtos.SharedInboxUpdateRequest request) {
        return jdbc.update("""
                UPDATE mail_shared_inboxes
                   SET display_name = ?, purpose = NULLIF(?, ''),
                       service_target_minutes = ?, lifecycle_state = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND version = ?
                """, request.displayName().trim(), value(request.purpose()),
                request.serviceTargetMinutes(), request.lifecycleState(), userId,
                tenantId, sharedInboxId, request.version());
    }

    void audit(
            Long tenantId,
            Long userId,
            String action,
            String targetType,
            String targetId,
            String correlationId,
            Map<String, Object> before,
            Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO mail_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action,
                    target_type, target_id, correlation_id,
                    before_snapshot, after_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, UUID.randomUUID(), tenantId, userId, action,
                targetType, targetId, value(correlationId),
                json.write(before), json.write(after));
    }

    void domainEvent(
            Long tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Map<String, Object> payload,
            String correlationId) {
        jdbc.update("""
                INSERT INTO mail_domain_events (
                    domain_event_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, payload, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, aggregateType, aggregateId,
                eventType, json.write(payload), value(correlationId));
    }

    private String preview(String body) {
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1200 ? normalized : normalized.substring(0, 1197) + "...";
    }

    private String recipientName(MailDtos.ComposeRequest request) {
        return recipientName(request.toName(), request.toEmail());
    }

    private String recipientName(String name, String email) {
        return name != null && !name.isBlank() ? name.trim() : email.trim();
    }

    private String value(String input) {
        return input == null ? "" : input.trim();
    }
}
