package com.dwp.services.platform.hrisconfiguration.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal jdbc integration commands operations shared by the owner facade. */
final class JdbcIntegrationCommands {
    private final JdbcTemplate jdbc;

    JdbcIntegrationCommands(
            JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }


    public Optional<CommandReceipt> findReplay(CommandSeal command) {
        List<StoredReceipt> rows = jdbc.query("""
                SELECT request_digest, public_id, command_type, originating_action,
                       subject_principal_public_id, population_scope_digest,
                       field_policy_revision, purpose_code, authorization_revision,
                       authority_population_scope_digest,
                       authority_field_policy_revision,
                       authority_authorization_revision,
                       origin_route_contract_key, origin_context_key,
                       origin_context_scope_key, origin_decision_revision,
                       status, correlation_id, created_at, aggregate_revision,
                       result_ref
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND subject_principal_public_id = ?
                   AND originating_action = ? AND idempotency_key = ?
                """, (rs, row) -> storedReceipt(rs), command.tenantId(),
                command.subjectPrincipalPublicId(), command.originatingAction(),
                command.idempotencyKey());
        if (rows.isEmpty()) return Optional.empty();
        StoredReceipt stored = rows.getFirst();
        if (!stored.matchesCurrentAuthority(command)) {
            throw notFound("SYS008_COMMAND_RECEIPT_NOT_AVAILABLE");
        }
        if (!stored.requestDigest().equals(command.requestDigest())) {
            throw conflict("SYS008_IDEMPOTENCY_DIGEST_CONFLICT");
        }
        return Optional.of(stored.receipt());
    }

    public CommandReservation beginCommand(
            CommandSeal command, OffsetDateTime now) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, command.tenantId() + "\u001f"
                        + command.subjectPrincipalPublicId() + "\u001f"
                        + command.originatingAction() + "\u001f"
                        + command.idempotencyKey());
        Optional<CommandReceipt> replay = findReplay(command);
        if (replay.isPresent()) {
            return new CommandReservation(replay.get(), false);
        }
        UUID receiptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    public_id, tenant_id, command_type, originating_action,
                    subject_principal_public_id, population_scope_digest,
                    authority_population_scope_digest, field_policy_revision,
                    authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key, origin_context_key,
                    origin_context_scope_key, origin_decision_revision,
                    idempotency_key, request_digest, status, correlation_id,
                    created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'ACCEPTED', ?, ?)
                """, receiptId, command.tenantId(), command.commandType(),
                command.originatingAction(), command.subjectPrincipalPublicId(),
                command.populationScopeDigest(), command.populationScopeDigest(),
                command.fieldPolicyRevision(), command.fieldPolicyRevision(),
                command.purposeCode(), command.authorizationRevision(),
                command.authorizationRevision(), command.routeContractKey(),
                command.contextKey(), command.contextScopeKey(),
                command.decisionRevision(), command.idempotencyKey(),
                command.requestDigest(), command.correlationId(), now);
        return new CommandReservation(receipt(
                command, receiptId, ReceiptStatus.ACCEPTED, now,
                null, null), true);
    }

    public CommandReceipt progressCommand(
            CommandSeal command,
            UUID receiptId,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'RUNNING', aggregate_public_id = ?,
                       aggregate_revision = ?, result_ref = ?
                 WHERE tenant_id = ? AND public_id = ? AND status = 'ACCEPTED'
                """, aggregatePublicId, aggregateRevision, resultRef,
                command.tenantId(), receiptId);
        if (changed != 1) {
            throw conflict("SYS008_RECEIPT_RUNNING_CAS_CONFLICT");
        }
        return receipt(command, receiptId, ReceiptStatus.RUNNING, now,
                aggregateRevision, resultRef);
    }

    public CommandReceipt finishCommand(
            CommandSeal command,
            UUID receiptId,
            ReceiptStatus status,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now) {
        if (!Set.of(ReceiptStatus.SUCCEEDED, ReceiptStatus.REJECTED,
                ReceiptStatus.FAILED, ReceiptStatus.RESULT_UNKNOWN)
                .contains(status)) {
            throw new IllegalArgumentException("receipt status is not terminal");
        }
        boolean rejected = status == ReceiptStatus.REJECTED;
        int progressed;
        if (rejected) {
            progressed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = 'RUNNING', aggregate_public_id = NULL,
                           aggregate_revision = NULL, result_ref = NULL
                     WHERE tenant_id = ? AND public_id = ?
                       AND status = 'ACCEPTED'
                    """, command.tenantId(), receiptId);
        } else {
            if (aggregatePublicId == null || aggregateRevision < 1
                    || resultRef == null || resultRef.isBlank()) {
                throw new IllegalArgumentException(
                        "terminal receipt aggregate binding is required");
            }
            progressed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = 'RUNNING', aggregate_public_id = ?,
                           aggregate_revision = ?, result_ref = ?
                     WHERE tenant_id = ? AND public_id = ?
                       AND status = 'ACCEPTED'
                    """, aggregatePublicId, aggregateRevision, resultRef,
                    command.tenantId(), receiptId);
        }
        int changed;
        if (rejected) {
            changed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = 'REJECTED', error_code = ?, completed_at = ?,
                           aggregate_public_id = NULL, aggregate_revision = NULL,
                           result_ref = NULL
                     WHERE tenant_id = ? AND public_id = ? AND status = 'RUNNING'
                    """, receiptError(status), now,
                    command.tenantId(), receiptId);
        } else {
            changed = jdbc.update("""
                    UPDATE sys_hris_command_receipts
                       SET status = ?, error_code = ?, completed_at = ?,
                           aggregate_revision = ?, result_ref = ?
                     WHERE tenant_id = ? AND public_id = ? AND status = 'RUNNING'
                       AND aggregate_public_id = ? AND result_ref = ?
                       AND aggregate_revision <= ?
                    """, status.name(), receiptError(status), now,
                    aggregateRevision, resultRef, command.tenantId(), receiptId,
                    aggregatePublicId, resultRef, aggregateRevision);
        }
        if (progressed == 0 && changed == 0) {
            throw conflict("SYS008_RECEIPT_RUNNING_CAS_CONFLICT");
        }
        if (changed != 1) {
            throw conflict("SYS008_RECEIPT_TERMINAL_CAS_CONFLICT");
        }
        return receipt(command, receiptId, status, now,
                rejected ? null : aggregateRevision,
                rejected ? null : resultRef);
    }

    public void markOriginalReceiptOutcome(
            ExecutionRecord execution,
            ReceiptStatus status,
            OffsetDateTime now) {
        if (!Set.of(ReceiptStatus.SUCCEEDED, ReceiptStatus.FAILED,
                ReceiptStatus.RESULT_UNKNOWN).contains(status)) {
            throw new IllegalArgumentException("provider receipt outcome is invalid");
        }
        int changed = jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = ?, error_code = ?, aggregate_revision = ?,
                       completed_at = ?
                 WHERE tenant_id = ? AND public_id = ? AND status = 'RUNNING'
                """, status.name(), receiptError(status),
                execution.aggregateRevision(), now,
                execution.tenantId(), execution.originalCommandReceiptId());
        if (changed == 1) return;
        List<String> existing = jdbc.query("""
                SELECT status
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND public_id = ?
                """, (rs, row) -> rs.getString(1), execution.tenantId(),
                execution.originalCommandReceiptId());
        if (existing.size() == 1 && Set.of(
                ReceiptStatus.SUCCEEDED.name(), ReceiptStatus.FAILED.name(),
                ReceiptStatus.RESULT_UNKNOWN.name())
                .contains(existing.getFirst())) {
            return;
        }
        throw conflict("SYS008_ORIGINAL_RECEIPT_TERMINAL_CAS_CONFLICT");
    }

    StoredReceipt storedReceipt(ResultSet rs) throws SQLException {
        String requestDigest = rs.getString("request_digest");
        CommandReceipt receipt = new CommandReceipt(
                uuid(rs, "public_id"), rs.getString("command_type"),
                rs.getString("originating_action"),
                uuid(rs, "subject_principal_public_id"),
                rs.getString("population_scope_digest"),
                rs.getLong("field_policy_revision"),
                rs.getString("purpose_code"), rs.getLong("authorization_revision"),
                ReceiptStatus.valueOf(rs.getString("status")),
                uuid(rs, "correlation_id"), requestDigest,
                rs.getObject("created_at", OffsetDateTime.class),
                nullableLong(rs, "aggregate_revision"), rs.getString("result_ref"));
        return new StoredReceipt(
                requestDigest,
                rs.getString("authority_population_scope_digest"),
                rs.getLong("authority_field_policy_revision"),
                rs.getLong("authority_authorization_revision"),
                rs.getString("purpose_code"),
                rs.getString("origin_route_contract_key"),
                rs.getString("origin_context_key"),
                rs.getString("origin_context_scope_key"),
                rs.getString("origin_decision_revision"),
                receipt);
    }
}
