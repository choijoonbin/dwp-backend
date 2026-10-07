package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

class MailWorkspaceDeliveryRepositorySupport {

    record AdvancedComposeCreated(UUID threadId, UUID deliveryId, boolean replayed) {
    }

    record AdvancedComposeCommand(
            UUID threadId, UUID deliveryId, UUID accountId, String requestFingerprint) {
    }

    record AttachmentContentReference(
            String storageReference,
            String fileName,
            String contentType,
            long sizeBytes,
            String checksumSha256) {
    }

    record ComposeProviderContext(
            UUID accountId,
            MailTypes.ProviderType providerType,
            UUID connectionId,
            URI credentialReference,
            String mailDomain,
            String providerAccountReference) {
    }

    record AttachmentAggregate(long count, long totalBytes) {
    }

    record DeletedAttachment(String storageReference, UUID threadId) {
    }

    final JdbcTemplate jdbc;
    final MailJsonCodec json;

    MailWorkspaceDeliveryRepositorySupport(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<DeliverySummary> deliveries(
            long tenantId, long userId, String bucket, int page, int pageSize) {
        return jdbc.query(deliverySelect() + """
                   AND (%s)
                 ORDER BY delivery.created_at DESC, delivery.delivery_id
                 LIMIT ? OFFSET ?
                """.formatted(bucketPredicate(bucket)),
                (result, ignored) -> deliverySummary(result, userId),
                tenantId, userId, userId, pageSize, page * pageSize);
    }

    long deliveryCount(long tenantId, long userId, String bucket) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread
                    ON thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE delivery.tenant_id = ?
                """ + MailAccessSql.THREAD_ACCESS + " AND (" + bucketPredicate(bucket) + ")",
                Long.class, tenantId, userId, userId);
        return count == null ? 0 : count;
    }

    Optional<DeliveryReceipt> delivery(long tenantId, long userId, UUID deliveryId) {
        return jdbc.query(deliverySelect() + " AND delivery.delivery_id = ?",
                (result, ignored) -> deliveryReceipt(result, userId),
                tenantId, userId, userId, deliveryId).stream().findFirst();
    }

    boolean rescheduleDelivery(
            long tenantId, long userId, UUID deliveryId, OffsetDateTime scheduledAt, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox delivery
                   SET next_attempt_at = ?, version = delivery.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM mail_threads thread, mail_accounts account
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                   AND delivery.delivery_status IN ('QUEUED', 'RETRY_WAIT')
                   AND delivery.accepted_at IS NULL
                   AND delivery.provider_message_ref IS NULL
                   AND delivery.provider_thread_ref IS NULL
                   AND delivery.lease_owner IS NULL AND delivery.lease_expires_at IS NULL
                   AND delivery.version = ?
                   AND thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                """ + MailAccessSql.THREAD_SEND_ACCESS,
                scheduledAt, tenantId, deliveryId, version, userId, userId) == 1;
    }

    boolean cancelDelivery(long tenantId, long userId, UUID deliveryId, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox delivery
                   SET delivery_status = 'CANCELLED', version = delivery.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM mail_threads thread, mail_accounts account
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                   AND delivery.delivery_status IN ('QUEUED', 'RETRY_WAIT')
                   AND delivery.accepted_at IS NULL
                   AND delivery.provider_message_ref IS NULL
                   AND delivery.provider_thread_ref IS NULL
                   AND delivery.lease_owner IS NULL AND delivery.lease_expires_at IS NULL
                   AND delivery.version = ?
                   AND thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                """ + MailAccessSql.THREAD_SEND_ACCESS,
                tenantId, deliveryId, version, userId, userId) == 1;
    }

    boolean reconcileSandboxDelivery(
            long tenantId, long userId, UUID deliveryId, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox delivery
                   SET delivery_status = 'QUEUED', attempt_count = 0,
                       next_attempt_at = CURRENT_TIMESTAMP, last_error_code = NULL,
                       lease_owner = NULL, lease_expires_at = NULL,
                       version = delivery.version + 1, updated_at = CURRENT_TIMESTAMP
                  FROM mail_threads thread, mail_accounts account,
                       mail_provider_connections connection
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                   AND delivery.version = ?
                   AND delivery.accepted_at IS NULL
                   AND delivery.provider_message_ref IS NULL
                   AND delivery.provider_thread_ref IS NULL
                   AND (
                       (delivery.delivery_status = 'LEASED'
                        AND delivery.lease_expires_at < CURRENT_TIMESTAMP)
                       OR
                       (delivery.delivery_status = 'FAILED'
                        AND delivery.last_error_code = 'MAIL_PROVIDER_RESULT_UNKNOWN'
                        AND delivery.lease_owner IS NULL
                        AND delivery.lease_expires_at IS NULL)
                   )
                   AND thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                   AND connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                   AND connection.provider_type = 'DWP_SANDBOX'
                """ + MailAccessSql.THREAD_SEND_ACCESS,
                tenantId, deliveryId, version, userId, userId) == 1;
    }

    boolean retryDelivery(long tenantId, long userId, UUID deliveryId, long version) {
        return jdbc.update("""
                UPDATE mail_delivery_outbox delivery
                   SET delivery_status = 'RETRY_WAIT', next_attempt_at = CURRENT_TIMESTAMP,
                       last_error_code = NULL, version = delivery.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM mail_threads thread, mail_accounts account
                 WHERE delivery.tenant_id = ? AND delivery.delivery_id = ?
                   AND delivery.version = ? AND delivery.delivery_status = 'FAILED'
                   AND delivery.accepted_at IS NULL
                   AND delivery.provider_message_ref IS NULL
                   AND delivery.provider_thread_ref IS NULL
                   AND delivery.lease_owner IS NULL AND delivery.lease_expires_at IS NULL
                   AND delivery.request_fingerprint IS NOT NULL
                   AND COALESCE(delivery.last_error_code, '') <> 'MAIL_PROVIDER_RESULT_UNKNOWN'
                   AND thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                """ + MailAccessSql.THREAD_SEND_ACCESS,
                tenantId, deliveryId, version, userId, userId) == 1;
    }

    void audit(
            long tenantId, long userId, String action, String targetType, String targetId,
            String correlationId, Map<String, Object> before, Map<String, Object> after) {
        jdbc.update("""
                INSERT INTO mail_audit_events (
                    audit_event_id, tenant_id, actor_user_id, action, target_type,
                    target_id, correlation_id, before_snapshot, after_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, UUID.randomUUID(), tenantId, userId, action, targetType, targetId,
                correlationId, json.write(before), json.write(after));
    }

    String deliverySelect() {
        return """
                SELECT delivery.delivery_id, delivery.delivery_id AS receipt_id,
                       delivery.thread_id, thread.subject,
                       account.display_name AS account_name, account.account_kind,
                       delivery.created_at AS requested_at, delivery.next_attempt_at AS scheduled_at,
                       delivery.delivery_status, delivery.attempt_count, delivery.accepted_at,
                       delivery.last_error_code, delivery.correlation_id,
                       delivery.lease_owner, delivery.lease_expires_at,
                       delivery.provider_message_ref, delivery.provider_thread_ref,
                       delivery.request_fingerprint,
                       connection.provider_type,
                       delivery.updated_at, delivery.version, delivery.created_by,
                       message.recipients::text AS recipients,
                       EXISTS (SELECT 1 FROM mail_group_recipient_snapshots snapshot
                                WHERE snapshot.tenant_id = delivery.tenant_id
                                  AND snapshot.delivery_id = delivery.delivery_id) AS group_delivery
                  FROM mail_delivery_outbox delivery
                  JOIN mail_threads thread
                    ON thread.tenant_id = delivery.tenant_id
                   AND thread.thread_id = delivery.thread_id
                  JOIN mail_messages message
                    ON message.tenant_id = delivery.tenant_id
                   AND message.message_id = delivery.message_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE delivery.tenant_id = ?
                """ + MailAccessSql.THREAD_ACCESS;
    }

    String bucketPredicate(String bucket) {
        return switch (bucket) {
            case "SCHEDULED" -> "delivery.delivery_status IN ('QUEUED','RETRY_WAIT') "
                    + "AND delivery.next_attempt_at > CURRENT_TIMESTAMP";
            case "PROCESSING" -> "delivery.delivery_status IN ('QUEUED','RETRY_WAIT','LEASED') "
                    + "AND delivery.next_attempt_at <= CURRENT_TIMESTAMP";
            case "COMPLETED" -> "delivery.delivery_status = 'DELIVERED'";
            case "ATTENTION" -> "delivery.delivery_status IN ('FAILED','CANCELLED')";
            default -> "TRUE";
        };
    }

    Template template(ResultSet result, long userId) throws SQLException {
        AssetScope scope = AssetScope.valueOf(result.getString("template_scope"));
        return new Template(
                result.getObject("template_id", UUID.class), result.getString("display_name"),
                result.getString("subject_template"), result.getString("body_content"),
                BodyFormat.valueOf(result.getString("body_format")), scope,
                result.getObject("account_id", UUID.class),
                scope != AssetScope.ORGANIZATION && result.getLong("owner_user_id") == userId,
                result.getString("mandatory_content"),
                result.getString("publication_state"),
                result.getInt("publication_version"),
                "ACTIVE".equals(result.getString("lifecycle_state")), result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    Signature signature(ResultSet result, long userId) throws SQLException {
        AssetScope scope = AssetScope.valueOf(result.getString("signature_scope"));
        return new Signature(
                result.getObject("signature_id", UUID.class), result.getString("display_name"),
                result.getString("body_content"),
                BodyFormat.valueOf(result.getString("body_format")), scope,
                result.getObject("account_id", UUID.class), result.getBoolean("default_for_new"),
                result.getBoolean("default_for_reply"),
                scope != AssetScope.ORGANIZATION && result.getLong("owner_user_id") == userId,
                result.getString("mandatory_content"),
                result.getString("publication_state"),
                result.getInt("publication_version"),
                "ACTIVE".equals(result.getString("lifecycle_state")), result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    Preferences preferences(ResultSet result) throws SQLException {
        return new Preferences(
                result.getString("density"), result.getString("remote_images"),
                result.getInt("send_delay_seconds"), result.getBoolean("keyboard_shortcuts"),
                result.getBoolean("notify_new_mail"),
                result.getBoolean("notify_shared_assignment"),
                result.getBoolean("notify_follow_up_due"),
                result.getObject("default_account_id", UUID.class),
                result.getObject("default_signature_id", UUID.class), Map.of(),
                result.getLong("version"));
    }

    SavedView savedView(ResultSet result) throws SQLException {
        Map<String, Object> filters = json.map(result.getString("filters"));
        return new SavedView(
                result.getObject("saved_view_id", UUID.class), result.getString("display_name"),
                criteria(filters), result.getInt("sort_order"), result.getBoolean("is_default"),
                result.getLong("version"), result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    SearchCriteria criteria(Map<String, Object> value) {
        return new SearchCriteria(
                text(value.get("query")), uuid(value.get("accountId")), text(value.get("scope")),
                text(value.get("from")), text(value.get("to")), date(value.get("dateFrom")),
                date(value.get("dateTo")), bool(value.get("unread")),
                bool(value.get("needsReply")), bool(value.get("hasAttachment")),
                uuid(value.get("folderId")), text(value.get("state")), text(value.get("lane")));
    }

    FollowUp followUp(ResultSet result) throws SQLException {
        return new FollowUp(
                result.getObject("follow_up_id", UUID.class),
                result.getObject("thread_id", UUID.class), result.getString("subject"),
                result.getString("participant_name"), result.getString("participant_email"),
                result.getObject("expected_reply_at", OffsetDateTime.class),
                result.getString("time_zone"), result.getString("note"),
                result.getString("tracker_status"),
                result.getObject("last_checked_at", OffsetDateTime.class), result.getLong("version"));
    }

    Attachment attachment(ResultSet result) throws SQLException {
        return new Attachment(
                result.getObject("attachment_id", UUID.class), result.getString("file_name"),
                result.getString("content_type"), result.getLong("size_bytes"),
                result.getString("scan_state"), result.getLong("version"),
                result.getObject("created_at", OffsetDateTime.class));
    }

    DeliverySummary deliverySummary(ResultSet result, long userId) throws SQLException {
        List<Recipient> recipients = visibleDeliveryRecipients(result, userId);
        return new DeliverySummary(
                result.getObject("delivery_id", UUID.class),
                result.getObject("receipt_id", UUID.class),
                result.getObject("thread_id", UUID.class), result.getString("subject"),
                recipientSummary(recipients), result.getString("account_name"),
                result.getBoolean("group_delivery") ? "GROUP" : "PERSONAL",
                result.getObject("requested_at", OffsetDateTime.class),
                result.getObject("scheduled_at", OffsetDateTime.class), deliveryState(result),
                reschedulable(result), cancellable(result), reconcilable(result),
                result.getLong("version"));
    }

    DeliveryReceipt deliveryReceipt(ResultSet result, long userId) throws SQLException {
        DeliverySummary summary = deliverySummary(result, userId);
        List<DeliveryTimeline> timeline = new ArrayList<>();
        timeline.add(new DeliveryTimeline("QUEUED",
                result.getObject("requested_at", OffsetDateTime.class),
                "Delivery request accepted", "DWP_OUTBOX", "VERIFIED", null));
        if ("SENDING".equals(summary.state())) {
            timeline.add(new DeliveryTimeline("SENDING",
                    result.getObject("updated_at", OffsetDateTime.class),
                    "Provider submission in progress", "DWP_WORKER", "REPORTED", null));
        }
        OffsetDateTime accepted = result.getObject("accepted_at", OffsetDateTime.class);
        if (accepted != null) {
            timeline.add(new DeliveryTimeline("ACCEPTED", accepted,
                    "Provider accepted the message", "PROVIDER_RECEIPT", "VERIFIED", null));
        }
        String error = result.getString("last_error_code");
        if (error != null) {
            String attentionState = deliveryAttentionState(summary.state());
            timeline.add(new DeliveryTimeline(attentionState,
                    result.getObject("updated_at", OffsetDateTime.class),
                    "UNKNOWN".equals(attentionState)
                            ? "Provider outcome is unknown"
                            : "Delivery requires attention",
                    "DWP_OUTBOX", "VERIFIED", error));
        }
        String eligibility = retryEligible(result) ? "ELIGIBLE" : "INELIGIBLE";
        if ("UNKNOWN".equals(summary.state())) eligibility = "UNKNOWN";
        return new DeliveryReceipt(
                summary.deliveryId(), summary.receiptId(), summary.threadId(), summary.subject(),
                summary.recipientSummary(), summary.accountName(), summary.kind(),
                summary.requestedAt(), summary.scheduledAt(), summary.state(),
                summary.canReschedule(), summary.canCancel(), summary.canReconcile(),
                summary.version(), visibleDeliveryRecipients(result, userId), timeline,
                result.getObject("updated_at", OffsetDateTime.class),
                List.of(Map.of(
                        "source", "mail_delivery_outbox",
                        "correlationId", value(result.getString("correlation_id")),
                        "attemptCount", result.getInt("attempt_count"))), eligibility);
    }

    String deliveryState(ResultSet result) throws SQLException {
        String status = result.getString("delivery_status");
        OffsetDateTime scheduledAt = result.getObject("scheduled_at", OffsetDateTime.class);
        OffsetDateTime leaseExpiresAt = result.getObject(
                "lease_expires_at", OffsetDateTime.class);
        if ("MAIL_PROVIDER_RESULT_UNKNOWN".equals(result.getString("last_error_code"))) {
            return "UNKNOWN";
        }
        if ("LEASED".equals(status) && leaseExpiresAt != null
                && leaseExpiresAt.isBefore(OffsetDateTime.now())) {
            return "UNKNOWN";
        }
        return switch (status) {
            case "QUEUED", "RETRY_WAIT" -> scheduledAt != null
                    && scheduledAt.isAfter(OffsetDateTime.now()) ? "SCHEDULED" : "QUEUED";
            case "LEASED" -> "SENDING";
            case "DELIVERED" -> "ACCEPTED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    static String deliveryAttentionState(String summaryState) {
        return "UNKNOWN".equals(summaryState) ? "UNKNOWN" : "FAILED";
    }

    boolean reschedulable(ResultSet result) throws SQLException {
        return mutableQueuedDelivery(result);
    }

    boolean cancellable(ResultSet result) throws SQLException {
        return mutableQueuedDelivery(result);
    }

    boolean reconcilable(ResultSet result) throws SQLException {
        if (!"DWP_SANDBOX".equals(result.getString("provider_type"))
                || result.getObject("accepted_at") != null
                || result.getString("provider_message_ref") != null
                || result.getString("provider_thread_ref") != null) {
            return false;
        }
        String status = result.getString("delivery_status");
        OffsetDateTime leaseExpiresAt = result.getObject(
                "lease_expires_at", OffsetDateTime.class);
        return ("LEASED".equals(status) && leaseExpiresAt != null
                    && leaseExpiresAt.isBefore(OffsetDateTime.now()))
                || ("FAILED".equals(status)
                    && "MAIL_PROVIDER_RESULT_UNKNOWN".equals(
                            result.getString("last_error_code"))
                    && result.getString("lease_owner") == null
                    && leaseExpiresAt == null);
    }

    boolean retryEligible(ResultSet result) throws SQLException {
        return "FAILED".equals(result.getString("delivery_status"))
                && result.getObject("accepted_at") == null
                && result.getString("provider_message_ref") == null
                && result.getString("provider_thread_ref") == null
                && result.getString("lease_owner") == null
                && result.getObject("lease_expires_at") == null
                && result.getString("request_fingerprint") != null
                && !"MAIL_PROVIDER_RESULT_UNKNOWN".equals(result.getString("last_error_code"));
    }

    boolean mutableQueuedDelivery(ResultSet result) throws SQLException {
        return List.of("QUEUED", "RETRY_WAIT").contains(result.getString("delivery_status"))
                && result.getObject("accepted_at") == null
                && result.getString("provider_message_ref") == null
                && result.getString("provider_thread_ref") == null
                && result.getString("lease_owner") == null
                && result.getObject("lease_expires_at") == null;
    }

    void reconcileDraftAttachments(
            long tenantId, long userId, UUID threadId, List<UUID> attachmentIds) {
        UUID[] selected = attachmentIds.toArray(UUID[]::new);
        jdbc.update("""
                UPDATE mail_compose_attachments
                   SET thread_id = NULL, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND uploader_user_id = ? AND thread_id = ?
                   AND NOT (attachment_id = ANY (?::uuid[]))
                """, tenantId, userId, threadId, selected);
        if (selected.length == 0) return;
        jdbc.update("""
                UPDATE mail_compose_attachments
                   SET thread_id = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND uploader_user_id = ?
                   AND attachment_id = ANY (?::uuid[]) AND scan_state = 'READY'
                   AND (thread_id IS NULL OR thread_id = ?)
                """, threadId, tenantId, userId, selected, threadId);
    }

    List<Recipient> recipients(String raw) {
        return json.mapList(raw).stream().map(item -> new Recipient(
                recipientType(item.get("type")), text(item.get("name")), text(item.get("email"))))
                .filter(item -> item.email() != null && !item.email().isBlank()).toList();
    }

    List<Recipient> visibleDeliveryRecipients(ResultSet result, long userId)
            throws SQLException {
        List<Recipient> recipients = recipients(result.getString("recipients"));
        if (result.getLong("created_by") == userId) return recipients;
        return recipients.stream()
                .filter(recipient -> recipient.type() != RecipientType.BCC)
                .toList();
    }

    RecipientType recipientType(Object value) {
        try {
            return RecipientType.valueOf(text(value).toUpperCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return RecipientType.TO;
        }
    }

    String recipientSummary(List<Recipient> recipients) {
        if (recipients.isEmpty()) return "No recipients";
        String first = recipients.get(0).name() == null || recipients.get(0).name().isBlank()
                ? recipients.get(0).email() : recipients.get(0).name();
        return recipients.size() == 1 ? first : first + " +" + (recipients.size() - 1);
    }

    UUID uuid(Object value) {
        try {
            return value == null || String.valueOf(value).isBlank()
                    ? null : UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    LocalDate date(Object value) {
        try {
            return value == null || String.valueOf(value).isBlank()
                    ? null : LocalDate.parse(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    Boolean bool(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue
                : value == null ? null : Boolean.valueOf(String.valueOf(value));
    }

    String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    String value(String value) {
        return value == null ? "" : value.trim();
    }
}
