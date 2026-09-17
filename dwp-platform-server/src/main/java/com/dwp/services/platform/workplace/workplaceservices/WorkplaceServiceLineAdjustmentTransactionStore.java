package com.dwp.services.platform.workplace.workplaceservices;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;

/** Transaction boundary and persistence owned by the provider crash-consistency protocol. */
@Repository
public class WorkplaceServiceLineAdjustmentTransactionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public WorkplaceServiceLineAdjustmentTransactionStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    <T> T committed(Supplier<T> work) {
        return Objects.requireNonNull(requiresNew.execute(status -> work.get()));
    }

    void createPendingAdjustment(LineAdjustmentRow row, UUID originCommandId) {
        jdbc.update("""
                INSERT INTO wp_service_line_adjustments (
                    line_adjustment_id, tenant_id, actor_user_id, service_order_id,
                    service_order_line_id, cancellation_preview_id, adjustment_type,
                    cancel_quantity, refund_scope, refundable_amount, refunded_amount,
                    currency, adjustment_state, provider_code_snapshot,
                    provider_configuration_version, provider_credential_binding_reference,
                    provider_operation_reference,
                    refund_receipt_reference, result_detail, version, origin_command_id,
                    provider_operation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'CANCEL_REFUND', ?, ?, ?, 0, ?,
                        'CANCELLATION_PENDING', ?, ?, ?, NULL, NULL, ?, 1, ?, ?, ?, ?)
                """, row.adjustmentId(), row.tenantId(), row.actorUserId(), row.orderId(),
                row.lineId(), row.previewId(), row.cancelQuantity(), row.refundScope().name(),
                row.refundableAmount(), row.currency(), row.providerCodeSnapshot(),
                row.providerConfigurationVersion(), row.providerCredentialBindingReference(),
                row.resultDetail(), originCommandId,
                row.adjustmentId(), row.createdAt(), row.updatedAt());
    }

    void lockOrderAndLine(long tenantId, UUID orderId, UUID lineId) {
        jdbc.query("""
                SELECT 1
                  FROM wp_service_orders order_row
                  JOIN wp_service_order_lines line
                    ON line.tenant_id = order_row.tenant_id
                   AND line.service_order_id = order_row.service_order_id
                 WHERE order_row.tenant_id = ? AND order_row.service_order_id = ?
                   AND line.service_order_line_id = ?
                 FOR UPDATE OF order_row, line
                """, statement -> {
                    statement.setLong(1, tenantId);
                    statement.setObject(2, orderId);
                    statement.setObject(3, lineId);
                }, resultSet -> null);
    }

    boolean hasUnresolvedAdjustment(long tenantId, UUID orderId, UUID lineId) {
        Boolean present = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM wp_service_line_adjustments
                     WHERE tenant_id = ? AND service_order_id = ?
                       AND service_order_line_id = ?
                       AND adjustment_state IN ('CANCELLATION_PENDING',
                           'RECONCILIATION_PENDING', 'RESULT_UNKNOWN',
                           'REFUND_NOT_CONFIGURED'))
                """, Boolean.class, tenantId, orderId, lineId);
        return Boolean.TRUE.equals(present);
    }

    boolean beginReconciliation(
            long tenantId, UUID orderId, UUID adjustmentId, long expectedVersion,
            UUID reconciliationCommandId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_line_adjustments
                   SET adjustment_state = 'RECONCILIATION_PENDING',
                       reconciliation_command_id = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND service_order_id = ? AND line_adjustment_id = ?
                   AND version = ?
                   AND adjustment_state IN ('CANCELLATION_PENDING',
                       'RECONCILIATION_PENDING', 'RESULT_UNKNOWN', 'REFUND_NOT_CONFIGURED')
                """, reconciliationCommandId, now, tenantId, orderId, adjustmentId,
                expectedVersion) == 1;
    }

    UUID originCommandId(long tenantId, UUID orderId, UUID adjustmentId) {
        return jdbc.queryForObject("""
                SELECT origin_command_id FROM wp_service_line_adjustments
                 WHERE tenant_id = ? AND service_order_id = ? AND line_adjustment_id = ?
                """, UUID.class, tenantId, orderId, adjustmentId);
    }

    Optional<CommandRow> command(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_service_order_commands
                 WHERE tenant_id = ? AND command_id = ?
                """, (rs, row) -> new CommandRow(
                        rs.getObject("command_id", UUID.class), rs.getLong("tenant_id"),
                        rs.getLong("actor_user_id"), rs.getString("command_scope"),
                        rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                        rs.getObject("service_order_id", UUID.class),
                        CommandState.valueOf(rs.getString("command_state")),
                        rs.getString("status_href"), rs.getString("correlation_id"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                tenantId, commandId).stream().findFirst();
    }

    boolean updateCommandState(
            long tenantId, UUID commandId, CommandState state, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE wp_service_order_commands
                   SET command_state = ?, updated_at = ?
                 WHERE tenant_id = ? AND command_id = ?
                   AND command_state IN ('ACCEPTED', 'RESULT_UNKNOWN')
                """, state.name(), now, tenantId, commandId);
        if (updated == 1) return true;
        String current = jdbc.queryForObject("""
                SELECT command_state FROM wp_service_order_commands
                 WHERE tenant_id = ? AND command_id = ?
                """, String.class, tenantId, commandId);
        return state.name().equals(current);
    }
}
