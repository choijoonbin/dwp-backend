package com.dwp.services.platform.hrisconfiguration.automation;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation repository support operations shared by the owner facade. */
final class JdbcAutomationRepositorySupport {

    private final JdbcAutomationRepositoryOperations owner;
    private final JdbcTemplate jdbc;

    JdbcAutomationRepositorySupport(
            JdbcAutomationRepositoryOperations owner,
            JdbcTemplate jdbc) {
        this.owner = owner;
        this.jdbc = jdbc;
    }


    public OffsetDateTime databaseNow() {
        return jdbc.queryForObject(
                "SELECT statement_timestamp()", OffsetDateTime.class);
    }

    public void bindTenantPrincipal(long tenantId, UUID subjectPrincipalPublicId) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                String.class, Long.toString(tenantId));
        jdbc.queryForObject("SELECT set_config('dwp.principal_public_id', ?, true)",
                String.class, subjectPrincipalPublicId.toString());
    }

    public Optional<CommandReceipt> findReplay(CommandSeal command) {
        List<StoredReceipt> rows = jdbc.query("""
                SELECT request_digest, public_id, command_type, originating_action,
                       subject_principal_public_id, population_scope_digest,
                       field_policy_revision, purpose_code, authorization_revision,
                       status, correlation_id, created_at, aggregate_revision, result_ref
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND subject_principal_public_id = ?
                   AND originating_action = ? AND idempotency_key = ?
                """, (rs, row) -> owner.storedReceipt(rs), command.tenantId(),
                command.subjectPrincipalPublicId(), command.originatingAction(),
                command.idempotencyKey());
        if (rows.isEmpty()) return Optional.empty();
        StoredReceipt stored = rows.getFirst();
        if (!stored.requestDigest().equals(command.requestDigest())) {
            throw conflict("AUTOMATION_IDEMPOTENCY_CONFLICT");
        }
        return Optional.of(stored.receipt());
    }

    public CommandReceipt beginCommand(CommandSeal command, OffsetDateTime now) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> null, command.tenantId() + "\u001f"
                        + command.subjectPrincipalPublicId() + "\u001f"
                        + command.originatingAction() + "\u001f"
                        + command.idempotencyKey());
        Optional<CommandReceipt> replay = findReplay(command);
        if (replay.isPresent()) return replay.get();
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
        return owner.receipt(command, receiptId, ReceiptStatus.ACCEPTED, now, null, null);
    }

    public CommandReceipt completeCommand(
            CommandSeal command,
            UUID receiptId,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            OffsetDateTime now) {
        int running = jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'RUNNING', aggregate_public_id = ?,
                       aggregate_revision = ?, result_ref = ?
                 WHERE tenant_id = ? AND public_id = ? AND status = 'ACCEPTED'
                """, aggregatePublicId, aggregateRevision, resultRef,
                command.tenantId(), receiptId);
        int succeeded = running == 1 ? jdbc.update("""
                UPDATE sys_hris_command_receipts
                   SET status = 'SUCCEEDED', completed_at = ?
                 WHERE tenant_id = ? AND public_id = ? AND status = 'RUNNING'
                """, now, command.tenantId(), receiptId) : 0;
        if (succeeded != 1) throw conflict("AUTOMATION_RECEIPT_CAS_CONFLICT");
        return owner.receipt(command, receiptId, ReceiptStatus.SUCCEEDED, now,
                aggregateRevision, resultRef);
    }
}
