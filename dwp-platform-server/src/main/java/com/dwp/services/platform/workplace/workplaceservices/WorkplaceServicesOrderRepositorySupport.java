package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;

abstract class WorkplaceServicesOrderRepositorySupport extends WorkplaceServicesRepositorySupport {
    protected WorkplaceServicesOrderRepositorySupport(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    public List<LineRow> linesForOrders(long tenantId, List<UUID> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM wp_service_order_lines"
                        + " WHERE tenant_id = ? AND service_order_id IN ("
                        + placeholders(orderIds.size()) + ")"
                        + " ORDER BY service_order_id, created_at, service_order_line_id",
                this::lineRow, tenantAndIds(tenantId, orderIds));
    }

    public List<TaskRow> tasksForOrders(long tenantId, List<UUID> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        return jdbc.query("SELECT * FROM wp_service_fulfillment_tasks"
                        + " WHERE tenant_id = ? AND service_order_id IN ("
                        + placeholders(orderIds.size()) + ")"
                        + " ORDER BY service_order_id, due_at, fulfillment_task_id",
                this::taskRow, tenantAndIds(tenantId, orderIds));
    }

    public Map<UUID, CatalogRow> catalogItems(long tenantId, List<UUID> catalogItemIds) {
        if (catalogItemIds.isEmpty()) return Map.of();
        List<CatalogRow> rows = jdbc.query("""
                SELECT item.*, truth.configured, truth.configuration_version,
                       truth.observed_configuration_version, truth.reported_state,
                       truth.evidence_reference, truth.observed_at, truth.received_at,
                       truth.error_code provider_error_code,
                       profile.lifecycle_state provider_profile_state,
                       profile.credential_binding_reference,
                       profile.configuration_version provider_profile_configuration_version
                  FROM wp_service_catalog_items item
                  JOIN wp_service_provider_truth truth
                    ON truth.tenant_id = item.tenant_id
                   AND truth.provider_code = item.provider_code
                  LEFT JOIN wp_service_provider_profiles profile
                    ON profile.tenant_id = item.tenant_id
                   AND profile.provider_code = item.provider_code
                 WHERE item.tenant_id = ? AND item.catalog_item_id IN ("""
                        + placeholders(catalogItemIds.size()) + ")",
                this::catalogRow, tenantAndIds(tenantId, catalogItemIds));
        Map<UUID, CatalogRow> result = new LinkedHashMap<>();
        rows.forEach(value -> result.put(value.catalogItemId(), value));
        return Map.copyOf(result);
    }

    public Map<UUID, OrderCollectionCounts> collectionCountsForOrders(
            long tenantId, List<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        List<OrderCollectionCountsRow> rows = jdbc.query("""
                SELECT order_row.service_order_id,
                       (SELECT COUNT(*) FROM wp_service_order_events event
                         WHERE event.tenant_id = order_row.tenant_id
                           AND event.service_order_id = order_row.service_order_id) event_count,
                       (SELECT COUNT(*) FROM wp_service_order_messages message
                         WHERE message.tenant_id = order_row.tenant_id
                           AND message.service_order_id = order_row.service_order_id) message_count,
                       (SELECT COUNT(*) FROM wp_service_order_attachments attachment
                         WHERE attachment.tenant_id = order_row.tenant_id
                           AND attachment.service_order_id = order_row.service_order_id) attachment_count
                  FROM wp_service_orders order_row
                 WHERE order_row.tenant_id = ? AND order_row.service_order_id IN ("""
                        + placeholders(orderIds.size()) + ")",
                (rs, row) -> new OrderCollectionCountsRow(
                        rs.getObject("service_order_id", UUID.class),
                        new OrderCollectionCounts(rs.getLong("event_count"),
                                rs.getLong("message_count"), rs.getLong("attachment_count"))),
                tenantAndIds(tenantId, orderIds));
        Map<UUID, OrderCollectionCounts> result = new LinkedHashMap<>();
        rows.forEach(value -> result.put(value.orderId(), value.counts()));
        return Map.copyOf(result);
    }

    public List<LineRow> lines(long tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM wp_service_order_lines
                 WHERE tenant_id = ? AND service_order_id = ?
                 ORDER BY created_at, service_order_line_id
                """, this::lineRow, tenantId, orderId);
    }

    protected static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    protected static Object[] tenantAndIds(long tenantId, List<UUID> ids) {
        Object[] values = new Object[ids.size() + 1];
        values[0] = tenantId;
        for (int index = 0; index < ids.size(); index++) values[index + 1] = ids.get(index);
        return values;
    }

    public Optional<LineRow> line(long tenantId, UUID orderId, UUID lineId) {
        return jdbc.query("""
                SELECT * FROM wp_service_order_lines
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND service_order_line_id = ?
                """, this::lineRow, tenantId, orderId, lineId).stream().findFirst();
    }

    public void saveLineCancellationPreview(LineCancellationPreviewRow row) {
        jdbc.update("""
                INSERT INTO wp_service_line_cancellation_previews (
                    cancellation_preview_id, tenant_id, actor_user_id,
                    service_order_id, service_order_line_id, order_version, line_version,
                    cancel_quantity, fulfilled_quantity, previously_cancelled_quantity,
                    catalog_version, provider_configuration_version, unit_price, currency,
                    cancellation_cutoff_minutes, cancellation_policy_ko,
                    cancellation_policy_en, refund_scope, refundable_amount, eligible,
                    reason, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.previewId(), row.tenantId(), row.actorUserId(), row.orderId(),
                row.lineId(), row.orderVersion(), row.lineVersion(), row.cancelQuantity(),
                row.fulfilledQuantity(), row.previouslyCancelledQuantity(), row.catalogVersion(),
                row.providerConfigurationVersion(), row.unitPrice(), row.currency(),
                row.cancellationCutoffMinutes(), row.cancellationPolicyKo(),
                row.cancellationPolicyEn(), row.refundScope().name(), row.refundableAmount(),
                row.eligible(), row.reason(), row.expiresAt(), row.createdAt());
    }

    public Optional<LineCancellationPreviewRow> lineCancellationPreview(
            long tenantId, long actorUserId, UUID orderId, UUID lineId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_service_line_cancellation_previews
                 WHERE tenant_id = ? AND actor_user_id = ? AND service_order_id = ?
                   AND service_order_line_id = ? AND cancellation_preview_id = ?
                """, this::lineCancellationPreviewRow, tenantId, actorUserId, orderId,
                lineId, previewId).stream().findFirst();
    }

    public Optional<LineCancellationPreviewRow> lineCancellationPreviewForUpdate(
            long tenantId, long actorUserId, UUID orderId, UUID lineId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_service_line_cancellation_previews
                 WHERE tenant_id = ? AND actor_user_id = ? AND service_order_id = ?
                   AND service_order_line_id = ? AND cancellation_preview_id = ?
                 FOR UPDATE
                """, this::lineCancellationPreviewRow, tenantId, actorUserId, orderId,
                lineId, previewId).stream().findFirst();
    }

    public void createLineAdjustment(LineAdjustmentRow row) {
        jdbc.update("""
                INSERT INTO wp_service_line_adjustments (
                    line_adjustment_id, tenant_id, actor_user_id, service_order_id,
                    service_order_line_id, cancellation_preview_id, adjustment_type,
                    cancel_quantity, refund_scope, refundable_amount, refunded_amount,
                    currency, adjustment_state, provider_code_snapshot,
                    provider_configuration_version, provider_credential_binding_reference,
                    provider_operation_reference,
                    refund_receipt_reference, result_detail, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CANCEL_REFUND', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.adjustmentId(), row.tenantId(), row.actorUserId(), row.orderId(),
                row.lineId(), row.previewId(), row.cancelQuantity(), row.refundScope().name(),
                row.refundableAmount(), row.refundedAmount(), row.currency(), row.state().name(),
                row.providerCodeSnapshot(), row.providerConfigurationVersion(),
                row.providerCredentialBindingReference(),
                row.providerOperationReference(), row.refundReceiptReference(), row.resultDetail(),
                row.version(), row.createdAt(), row.updatedAt());
    }

    public Optional<LineAdjustmentRow> lineAdjustment(
            long tenantId, long actorUserId, UUID orderId, UUID adjustmentId) {
        return jdbc.query("""
                SELECT * FROM wp_service_line_adjustments
                 WHERE tenant_id = ? AND actor_user_id = ? AND service_order_id = ?
                   AND line_adjustment_id = ?
                """, this::lineAdjustmentRow, tenantId, actorUserId, orderId,
                adjustmentId).stream().findFirst();
    }

    public Optional<LineAdjustmentRow> adminLineAdjustment(
            long tenantId, UUID orderId, UUID adjustmentId) {
        return jdbc.query("""
                SELECT * FROM wp_service_line_adjustments
                 WHERE tenant_id = ? AND service_order_id = ? AND line_adjustment_id = ?
                """, this::lineAdjustmentRow, tenantId, orderId, adjustmentId)
                .stream().findFirst();
    }

    public Optional<LineAdjustmentRow> adminLineAdjustmentForUpdate(
            long tenantId, UUID orderId, UUID adjustmentId) {
        return jdbc.query("""
                SELECT * FROM wp_service_line_adjustments
                 WHERE tenant_id = ? AND service_order_id = ? AND line_adjustment_id = ?
                 FOR UPDATE
                """, this::lineAdjustmentRow, tenantId, orderId, adjustmentId)
                .stream().findFirst();
    }

    public List<LineAdjustmentRow> pendingLineAdjustments(int limit) {
        return jdbc.query("""
                SELECT * FROM wp_service_line_adjustments
                 WHERE adjustment_state IN ('CANCELLATION_PENDING',
                       'RECONCILIATION_PENDING', 'RESULT_UNKNOWN')
                   AND provider_next_attempt_at <= CURRENT_TIMESTAMP
                 ORDER BY provider_next_attempt_at, tenant_id, line_adjustment_id
                 LIMIT ?
                """, this::lineAdjustmentRow, limit);
    }

    public boolean claimLineAdjustmentRecovery(
            long tenantId, UUID adjustmentId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_line_adjustments
                   SET provider_recovery_attempt_count = provider_recovery_attempt_count + 1,
                       provider_next_attempt_at = ? + make_interval(secs =>
                           (5 * (1 << LEAST(provider_recovery_attempt_count, 9))))
                 WHERE tenant_id = ? AND line_adjustment_id = ?
                   AND adjustment_state IN ('CANCELLATION_PENDING',
                       'RECONCILIATION_PENDING', 'RESULT_UNKNOWN')
                   AND provider_next_attempt_at <= ?
                """, now, tenantId, adjustmentId, now) == 1;
    }

    public Optional<LineAdjustmentRow> lineAdjustmentByPreview(
            long tenantId, long actorUserId, UUID previewId) {
        return jdbc.query("""
                SELECT * FROM wp_service_line_adjustments
                 WHERE tenant_id = ? AND actor_user_id = ? AND cancellation_preview_id = ?
                """, this::lineAdjustmentRow, tenantId, actorUserId, previewId)
                .stream().findFirst();
    }

    public boolean hasUnresolvedLineAdjustment(
            long tenantId, UUID orderId, UUID lineId) {
        Boolean present = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM wp_service_line_adjustments
                     WHERE tenant_id = ? AND service_order_id = ?
                       AND service_order_line_id = ?
                       AND adjustment_state IN ('RESULT_UNKNOWN', 'REFUND_NOT_CONFIGURED'))
                """, Boolean.class, tenantId, orderId, lineId);
        return Boolean.TRUE.equals(present);
    }

    public boolean updateLineAdjustment(
            long tenantId, UUID orderId, UUID adjustmentId, long expectedVersion,
            LineAdjustmentState state, String providerOperationReference,
            BigDecimal refundedAmount, String refundReceiptReference,
            String resultDetail, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_line_adjustments
                   SET adjustment_state = ?, provider_operation_reference = ?,
                       refunded_amount = ?, refund_receipt_reference = ?, result_detail = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND line_adjustment_id = ? AND version = ?
                """, state.name(), providerOperationReference, refundedAmount,
                refundReceiptReference, resultDetail, now, tenantId, orderId,
                adjustmentId, expectedVersion) == 1;
    }

    public boolean applyLineCancellation(
            long tenantId, UUID orderId, UUID lineId, long expectedVersion,
            int cancelQuantity, BigDecimal refundedAmount, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_order_lines
                   SET cancelled_quantity = cancelled_quantity + ?,
                       refunded_amount = refunded_amount + ?,
                       line_state = CASE
                         WHEN fulfilled_quantity = 0
                              AND cancelled_quantity + ? = quantity THEN 'CANCELLED'
                         WHEN fulfilled_quantity > 0
                              AND fulfilled_quantity + cancelled_quantity + ? = quantity
                           THEN 'PARTIALLY_FULFILLED'
                         ELSE line_state END,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND service_order_line_id = ? AND version = ?
                   AND line_state NOT IN ('FULFILLED', 'CANCELLED')
                   AND fulfilled_quantity + cancelled_quantity + ? <= quantity
                """, cancelQuantity, refundedAmount, cancelQuantity, cancelQuantity, now,
                tenantId, orderId, lineId, expectedVersion, cancelQuantity) == 1;
    }

    public void cancelFullyCancelledLineTasks(
            long tenantId, UUID orderId, UUID lineId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_service_fulfillment_tasks task
                   SET task_state = 'CANCELLED', completed_at = ?,
                       provider_receipt_at = COALESCE(provider_receipt_at, ?),
                       version = task.version + 1, updated_at = ?
                  FROM wp_service_order_lines line
                 WHERE task.tenant_id = ? AND task.service_order_id = ?
                   AND task.service_order_line_id = ?
                   AND line.tenant_id = task.tenant_id
                   AND line.service_order_line_id = task.service_order_line_id
                   AND line.line_state = 'CANCELLED'
                   AND task.task_state NOT IN ('FULFILLED', 'CANCELLED')
                """, now, now, now, tenantId, orderId, lineId);
    }

    public List<TaskRow> tasks(long tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM wp_service_fulfillment_tasks
                 WHERE tenant_id = ? AND service_order_id = ?
                 ORDER BY due_at, fulfillment_task_id
                """, this::taskRow, tenantId, orderId);
    }

    public Optional<TaskRow> task(long tenantId, UUID taskId) {
        return jdbc.query("""
                SELECT * FROM wp_service_fulfillment_tasks
                 WHERE tenant_id = ? AND fulfillment_task_id = ?
                """, this::taskRow, tenantId, taskId).stream().findFirst();
    }

    public List<ServiceOrderEvent> events(long tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM wp_service_order_events
                 WHERE tenant_id = ? AND service_order_id = ?
                 ORDER BY occurred_at, service_order_event_id
                """, (rs, row) -> new ServiceOrderEvent(
                rs.getObject("service_order_event_id", UUID.class), rs.getString("event_type"),
                rs.getLong("actor_user_id"), node(rs.getString("detail")),
                rs.getObject("occurred_at", OffsetDateTime.class)), tenantId, orderId);
    }

    public List<ServiceOrderEvent> eventPage(
            long tenantId, UUID orderId, OffsetDateTime cursorAt, UUID cursorId,
            int fetchLimit) {
        if (cursorAt == null) {
            return jdbc.query("""
                    SELECT * FROM wp_service_order_events
                     WHERE tenant_id = ? AND service_order_id = ?
                     ORDER BY occurred_at DESC, service_order_event_id DESC LIMIT ?
                    """, this::serviceOrderEvent, tenantId, orderId, fetchLimit);
        }
        return jdbc.query("""
                SELECT * FROM wp_service_order_events
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND (occurred_at, service_order_event_id) < (?, ?)
                 ORDER BY occurred_at DESC, service_order_event_id DESC LIMIT ?
                """, this::serviceOrderEvent, tenantId, orderId, cursorAt, cursorId, fetchLimit);
    }

    public List<ServiceOrderMessage> messages(long tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM wp_service_order_messages
                 WHERE tenant_id = ? AND service_order_id = ?
                 ORDER BY created_at, message_id
                """, (rs, row) -> new ServiceOrderMessage(
                rs.getObject("message_id", UUID.class), rs.getLong("author_user_id"),
                rs.getString("author_display_name"), rs.getString("author_role"),
                rs.getString("message"), rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, orderId);
    }

    public List<ServiceOrderMessage> messagePage(
            long tenantId, UUID orderId, OffsetDateTime cursorAt, UUID cursorId,
            int fetchLimit) {
        if (cursorAt == null) {
            return jdbc.query("""
                    SELECT * FROM wp_service_order_messages
                     WHERE tenant_id = ? AND service_order_id = ?
                     ORDER BY created_at DESC, message_id DESC LIMIT ?
                    """, this::serviceOrderMessage, tenantId, orderId, fetchLimit);
        }
        return jdbc.query("""
                SELECT * FROM wp_service_order_messages
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND (created_at, message_id) < (?, ?)
                 ORDER BY created_at DESC, message_id DESC LIMIT ?
                """, this::serviceOrderMessage, tenantId, orderId, cursorAt, cursorId, fetchLimit);
    }

    public List<ServiceOrderAttachment> attachments(long tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT attachment_id, file_name, content_type, byte_size,
                       scan_state, scan_version, scanner_evidence_reference,
                       scan_detail, scanned_at, created_at
                  FROM wp_service_order_attachments
                 WHERE tenant_id = ? AND service_order_id = ?
                 ORDER BY created_at, attachment_id
                """, (rs, row) -> new ServiceOrderAttachment(
                rs.getObject("attachment_id", UUID.class), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("byte_size"),
                AttachmentScanState.valueOf(rs.getString("scan_state")),
                rs.getLong("scan_version"), rs.getString("scanner_evidence_reference"),
                rs.getString("scan_detail"), rs.getObject("scanned_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)), tenantId, orderId);
    }

    public List<ServiceOrderAttachment> attachmentPage(
            long tenantId, UUID orderId, OffsetDateTime cursorAt, UUID cursorId,
            int fetchLimit) {
        String columns = """
                attachment_id, file_name, content_type, byte_size, scan_state, scan_version,
                scanner_evidence_reference, scan_detail, scanned_at, created_at
                """;
        if (cursorAt == null) {
            return jdbc.query("SELECT " + columns + " FROM wp_service_order_attachments"
                    + " WHERE tenant_id = ? AND service_order_id = ?"
                    + " ORDER BY created_at DESC, attachment_id DESC LIMIT ?",
                    this::serviceOrderAttachment, tenantId, orderId, fetchLimit);
        }
        return jdbc.query("SELECT " + columns + " FROM wp_service_order_attachments"
                + " WHERE tenant_id = ? AND service_order_id = ?"
                + " AND (created_at, attachment_id) < (?, ?)"
                + " ORDER BY created_at DESC, attachment_id DESC LIMIT ?",
                this::serviceOrderAttachment, tenantId, orderId, cursorAt, cursorId, fetchLimit);
    }

    public OrderCollectionCounts collectionCounts(long tenantId, UUID orderId) {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM wp_service_order_events
                         WHERE tenant_id = ? AND service_order_id = ?) event_count,
                       (SELECT COUNT(*) FROM wp_service_order_messages
                         WHERE tenant_id = ? AND service_order_id = ?) message_count,
                       (SELECT COUNT(*) FROM wp_service_order_attachments
                         WHERE tenant_id = ? AND service_order_id = ?) attachment_count
                """, (rs, row) -> new OrderCollectionCounts(rs.getLong("event_count"),
                    rs.getLong("message_count"), rs.getLong("attachment_count")),
                tenantId, orderId, tenantId, orderId, tenantId, orderId);
    }

    public Optional<AttachmentRow> attachment(
            long tenantId, UUID orderId, UUID attachmentId) {
        return jdbc.query("""
                SELECT attachment_id, tenant_id, service_order_id, uploader_user_id,
                       storage_reference, file_name, content_type, byte_size,
                       checksum_sha256, scan_state, scan_version,
                       scanner_evidence_reference, scan_detail, scanned_at, created_at
                  FROM wp_service_order_attachments
                 WHERE tenant_id = ? AND service_order_id = ? AND attachment_id = ?
                """, (rs, row) -> new AttachmentRow(
                rs.getObject("attachment_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("service_order_id", UUID.class), rs.getLong("uploader_user_id"),
                rs.getString("storage_reference"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("byte_size"),
                rs.getString("checksum_sha256"),
                AttachmentScanState.valueOf(rs.getString("scan_state")),
                rs.getLong("scan_version"), rs.getString("scanner_evidence_reference"),
                rs.getString("scan_detail"), rs.getObject("scanned_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, orderId, attachmentId).stream().findFirst();
    }

    public Optional<AttachmentRow> attachmentForUpdate(
            long tenantId, UUID orderId, UUID attachmentId) {
        return jdbc.query("""
                SELECT attachment_id, tenant_id, service_order_id, uploader_user_id,
                       storage_reference, file_name, content_type, byte_size,
                       checksum_sha256, scan_state, scan_version,
                       scanner_evidence_reference, scan_detail, scanned_at, created_at
                  FROM wp_service_order_attachments
                 WHERE tenant_id = ? AND service_order_id = ? AND attachment_id = ?
                 FOR UPDATE
                """, (rs, row) -> new AttachmentRow(
                rs.getObject("attachment_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("service_order_id", UUID.class), rs.getLong("uploader_user_id"),
                rs.getString("storage_reference"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("byte_size"),
                rs.getString("checksum_sha256"),
                AttachmentScanState.valueOf(rs.getString("scan_state")),
                rs.getLong("scan_version"), rs.getString("scanner_evidence_reference"),
                rs.getString("scan_detail"), rs.getObject("scanned_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class)),
                tenantId, orderId, attachmentId).stream().findFirst();
    }

    public boolean updateAttachmentScan(
            long tenantId, UUID orderId, UUID attachmentId, long expectedVersion,
            AttachmentScanState state, String evidenceReference, String detail,
            OffsetDateTime scannedAt) {
        return jdbc.update("""
                UPDATE wp_service_order_attachments
                   SET scan_state = ?, scan_version = scan_version + 1,
                       scanner_evidence_reference = ?, scan_detail = ?, scanned_at = ?
                 WHERE tenant_id = ? AND service_order_id = ? AND attachment_id = ?
                   AND scan_version = ?
                """, state.name(), evidenceReference, detail, scannedAt,
                tenantId, orderId, attachmentId, expectedVersion) == 1;
    }

    public void appendAttachmentAccessAudit(
            long tenantId, long actorUserId, UUID orderId, UUID attachmentId,
            boolean administrator, String correlationId) {
        jdbc.update("""
                INSERT INTO wp_audit_events (
                    audit_event_id, tenant_id, action, aggregate_type, aggregate_id,
                    actor_user_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, 'workplace.service.attachment.downloaded',
                        'WORKPLACE_SERVICE_ORDER', ?, ?, ?,
                        jsonb_build_object('attachmentId', ?, 'accessRole', ?),
                        CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), tenantId, orderId, actorUserId, correlationId,
                attachmentId.toString(), administrator ? "OPERATOR" : "REQUESTER");
    }

    public boolean cancelOrder(
            long tenantId, long actorUserId, UUID orderId, long expectedVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_orders
                   SET order_state = 'CANCELLED', cancelled_at = ?, version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND requester_user_id = ? AND service_order_id = ?
                   AND version = ? AND order_state NOT IN ('FULFILLED', 'CANCELLED')
                """, now, now, tenantId, actorUserId, orderId, expectedVersion) == 1;
    }

    public void cancelTasks(long tenantId, UUID orderId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_service_fulfillment_tasks
                   SET task_state = 'CANCELLED', completed_at = ?, version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND task_state NOT IN ('FULFILLED', 'CANCELLED')
                """, now, now, tenantId, orderId);
        jdbc.update("""
                UPDATE wp_service_order_lines
                   SET line_state = 'CANCELLED', cancelled_quantity = quantity,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND line_state NOT IN ('FULFILLED', 'CANCELLED')
                   AND fulfilled_quantity = 0
                """, now, tenantId, orderId);
    }

    public boolean updateTask(
            long tenantId, UUID taskId, long expectedVersion, WorkState state,
            Long assigneeUserId, String externalReference, String blockerCode,
            String blockerDetail, String resultDetail, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_fulfillment_tasks
                   SET task_state = ?, assignee_user_id = COALESCE(?, assignee_user_id),
                       external_fulfillment_reference = COALESCE(?, external_fulfillment_reference),
                       blocker_code = ?, blocker_detail = ?, result_detail = ?,
                       provider_receipt_at = COALESCE(provider_receipt_at, ?),
                       accepted_at = CASE WHEN ? = 'ACCEPTED' THEN COALESCE(accepted_at, ?) ELSE accepted_at END,
                       completed_at = CASE WHEN ? IN ('FULFILLED', 'CANCELLED') THEN ? ELSE NULL END,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND fulfillment_task_id = ? AND version = ?
                """, state.name(), assigneeUserId, externalReference, blockerCode,
                blockerDetail, resultDetail, now, state.name(), now, state.name(), now, now,
                tenantId, taskId, expectedVersion) == 1;
    }

    public void updateLineFromTask(
            long tenantId, UUID lineId, WorkState state, Integer fulfilledQuantity,
            String blockerCode, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_service_order_lines
                   SET line_state = ?, fulfilled_quantity = COALESCE(?, fulfilled_quantity),
                       blocker_code = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND service_order_line_id = ?
                """, state.name(), fulfilledQuantity, blockerCode, now, tenantId, lineId);
    }

    private ServiceOrderEvent serviceOrderEvent(ResultSet rs, int row) throws SQLException {
        return new ServiceOrderEvent(
                rs.getObject("service_order_event_id", UUID.class), rs.getString("event_type"),
                rs.getLong("actor_user_id"), node(rs.getString("detail")),
                rs.getObject("occurred_at", OffsetDateTime.class));
    }

    private ServiceOrderMessage serviceOrderMessage(ResultSet rs, int row) throws SQLException {
        return new ServiceOrderMessage(
                rs.getObject("message_id", UUID.class), rs.getLong("author_user_id"),
                rs.getString("author_display_name"), rs.getString("author_role"),
                rs.getString("message"), rs.getObject("created_at", OffsetDateTime.class));
    }

    private ServiceOrderAttachment serviceOrderAttachment(ResultSet rs, int row)
            throws SQLException {
        return new ServiceOrderAttachment(
                rs.getObject("attachment_id", UUID.class), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("byte_size"),
                AttachmentScanState.valueOf(rs.getString("scan_state")),
                rs.getLong("scan_version"), rs.getString("scanner_evidence_reference"),
                rs.getString("scan_detail"), rs.getObject("scanned_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    public void recalculateOrderState(long tenantId, UUID orderId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_service_orders order_row
                   SET order_state = state.next_state,
                       cancelled_at = CASE WHEN state.next_state = 'CANCELLED'
                           THEN COALESCE(order_row.cancelled_at, ?) ELSE order_row.cancelled_at END,
                       version = order_row.version + 1, updated_at = ?
                  FROM (SELECT service_order_id,
                        CASE
                          WHEN bool_and(task_state = 'FULFILLED') THEN 'FULFILLED'
                          WHEN bool_and(task_state = 'CANCELLED') THEN 'CANCELLED'
                          WHEN bool_or(task_state = 'RESULT_UNKNOWN') THEN 'RESULT_UNKNOWN'
                          WHEN bool_or(task_state = 'BLOCKED') THEN 'BLOCKED'
                          WHEN bool_or(task_state = 'DELAYED') THEN 'DELAYED'
                          WHEN bool_or(task_state = 'PARTIALLY_FULFILLED')
                               OR (bool_or(task_state = 'FULFILLED') AND NOT bool_and(task_state = 'FULFILLED'))
                            THEN 'PARTIALLY_FULFILLED'
                          WHEN bool_or(task_state = 'IN_PREPARATION') THEN 'IN_PREPARATION'
                          WHEN bool_or(task_state = 'ACCEPTED') THEN 'ACCEPTED'
                          ELSE 'SUBMITTED'
                        END next_state
                          FROM wp_service_fulfillment_tasks
                         WHERE tenant_id = ? AND service_order_id = ?
                         GROUP BY service_order_id) state
                 WHERE order_row.tenant_id = ? AND order_row.service_order_id = state.service_order_id
                   AND order_row.order_state <> 'CANCELLED'
                   AND order_row.order_state <> state.next_state
                """, now, now, tenantId, orderId, tenantId);
    }
}
