package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandIdentity;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandType;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.EventFact;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PayrollCommandReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PersistedReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ReceiptStatus;

/** JDBC operations for payroll receipts. */
final class PayrollCalculationReceiptJdbc extends PayrollCalculationJdbcSupport {

    PayrollCalculationReceiptJdbc(
            NamedParameterJdbcTemplate jdbc,
            PayrollCalculationCanonical canonical) {
        super(jdbc, canonical);
    }

    public Optional<PersistedReceipt> command(CommandIdentity identity) {
        bindTenant(identity.tenantId());
        return receiptQuery("""
                 WHERE r.tenant_id = :tenantId
                   AND r.subject_principal_public_id = :subjectId
                   AND r.originating_action = :originatingAction
                   AND r.idempotency_key = :idempotencyKey
                   AND r.status_sequence = 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", identity.tenantId())
                .addValue("subjectId", identity.subjectPrincipalPublicId())
                .addValue("originatingAction", identity.commandType().originatingAction())
                .addValue("idempotencyKey", identity.idempotencyKey()));
    }

    public Optional<PersistedReceipt> receipt(long tenantId, UUID receiptId) {
        bindTenant(tenantId);
        return receiptQuery("""
                 WHERE r.tenant_id = :tenantId AND r.receipt_id = :receiptId
                   AND r.status_sequence = 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId));
    }

    Optional<PersistedReceipt> receiptQuery(
            String predicate, MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT r.tenant_id, r.actor_id, r.run_id, r.population_kind,
                       r.context_scope_key, r.idempotency_key, r.receipt_id,
                       r.command_type, r.originating_action,
                       r.subject_principal_public_id, r.population_scope_digest,
                       r.field_policy_revision, r.purpose_code,
                       r.authorization_revision, latest.receipt_status,
                       r.correlation_id, r.request_digest, r.created_at,
                       r.aggregate_version, r.result_ref,
                       r.step_up_challenge_id, r.step_up_nonce, r.step_up_issuer,
                       r.step_up_expires_at, r.step_up_payload_sha256,
                       r.step_up_decision_revision
                  FROM pay_command_receipts r
                  JOIN LATERAL (
                        SELECT receipt_status
                          FROM pay_command_receipts h
                         WHERE h.tenant_id = r.tenant_id
                           AND h.receipt_id = r.receipt_id
                         ORDER BY status_sequence DESC LIMIT 1) latest ON TRUE
                """ + predicate, parameters, (result, row) -> {
                    CommandType type = CommandType.valueOf(result.getString("command_type"));
                    PayrollCommandReceipt publicReceipt = new PayrollCommandReceipt(
                            result.getObject("receipt_id", UUID.class), type.name(),
                            result.getString("originating_action"),
                            result.getObject("subject_principal_public_id", UUID.class),
                            result.getString("population_scope_digest"),
                            result.getLong("field_policy_revision"),
                            result.getString("purpose_code"),
                            result.getLong("authorization_revision"),
                            ReceiptStatus.valueOf(result.getString("receipt_status")),
                            result.getObject("correlation_id", UUID.class),
                            result.getString("request_digest"),
                            result.getObject("created_at", OffsetDateTime.class).toInstant(),
                            result.getObject("aggregate_version", Long.class),
                            result.getString("result_ref"));
                    OffsetDateTime expiry = result.getObject(
                            "step_up_expires_at", OffsetDateTime.class);
                    PayrollStepUpEvidence evidence = result.getString(
                            "step_up_challenge_id") == null ? null : new PayrollStepUpEvidence(
                            result.getString("step_up_challenge_id"),
                            result.getString("step_up_nonce"),
                            result.getString("step_up_issuer"), expiry.toInstant(),
                            result.getString("step_up_payload_sha256"),
                            result.getString("step_up_decision_revision"));
                    return new PersistedReceipt(
                            result.getLong("tenant_id"), result.getLong("actor_id"),
                            result.getObject("run_id", UUID.class),
                            result.getString("population_kind"),
                            result.getString("context_scope_key"),
                            result.getString("idempotency_key"), type, publicReceipt, evidence);
                }).stream().findFirst();
    }

    public void insertReceipt(PersistedReceipt persisted) {
        PayrollCommandReceipt receipt = persisted.receipt();
        PayrollStepUpEvidence stepUp = persisted.stepUpEvidence();
        bindTenant(persisted.tenantId());
        try {
            jdbc.update("""
                    INSERT INTO pay_command_receipts (
                        tenant_id, receipt_id, status_sequence, receipt_status,
                        idempotency_key, command_type, originating_action,
                        subject_principal_public_id, actor_id, run_id,
                        population_kind, population_scope_digest, context_scope_key,
                        field_policy_revision, purpose_code, authorization_revision,
                        correlation_id, request_digest, aggregate_version, result_ref,
                        step_up_challenge_id, step_up_nonce, step_up_issuer,
                        step_up_expires_at, step_up_payload_sha256,
                        step_up_decision_revision, audit_type, audit_payload,
                        created_at, occurred_at)
                    VALUES (:tenantId, :receiptId, 1, :status, :idempotencyKey,
                        :commandType, :originatingAction, :subjectId, :actorId, :runId,
                        :populationKind, :scopeDigest, :contextScopeKey,
                        :fieldPolicyRevision, :purposeCode, :authorizationRevision,
                        :correlationId, :requestDigest, :aggregateVersion, :resultRef,
                        :challengeId, :nonce, :issuer, :stepUpExpiresAt,
                        :payloadSha256, :decisionRevision,
                        'COMMAND_ACCEPTED', CAST(:auditPayload AS jsonb),
                        :createdAt, :occurredAt)
                    """, receiptParameters(persisted)
                    .addValue("status", receipt.status().name())
                    .addValue("challengeId", stepUp == null ? null : stepUp.challengeId())
                    .addValue("nonce", stepUp == null ? null : stepUp.nonce())
                    .addValue("issuer", stepUp == null ? null : stepUp.issuer())
                    .addValue("stepUpExpiresAt", stepUp == null
                            ? null : timestamp(stepUp.expiresAt()))
                    .addValue("payloadSha256", stepUp == null
                            ? null : stepUp.payloadSha256())
                    .addValue("decisionRevision", stepUp == null
                            ? null : stepUp.decisionRevision())
                    .addValue("auditPayload", canonical.json(Map.of(
                            "originatingAction", receipt.originatingAction(),
                            "requestDigest", receipt.requestDigest())))
                    .addValue("occurredAt", timestamp(receipt.createdAt())));
        } catch (DataIntegrityViolationException exception) {
            String detail = exception.getMostSpecificCause().getMessage();
            boolean challengeReplay = stepUp != null && detail != null
                    && (detail.contains("pay_command_receipt_challenge_unique")
                    || detail.contains("pay_command_receipt_nonce_unique"));
            if (challengeReplay) {
                throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_REPLAY,
                        "The Payroll step-up challenge was already consumed.", exception);
            }
            throw conflict("The Payroll command receipt conflicts.", exception);
        }
    }

    public void appendReceiptStatus(
            long tenantId, UUID receiptId, ReceiptStatus status, Instant occurredAt) {
        bindTenant(tenantId);
        ReceiptHead head = receiptHead(tenantId, receiptId);
        if (head.status() == status) return;
        appendReceiptRecord(tenantId, receiptId, status,
                "RECEIPT_STATUS_CHANGED",
                canonical.json(Map.of("status", status.name())), occurredAt);
    }

    public void appendAudit(
            PersistedReceipt receipt,
            long runRevision,
            String eventType,
            String eventPayload,
            Instant occurredAt) {
        bindTenant(receipt.tenantId());
        ReceiptHead head = receiptHead(receipt.tenantId(), receipt.receipt().receiptId());
        appendReceiptRecord(receipt.tenantId(), receipt.receipt().receiptId(), head.status(),
                eventType, canonical.auditPayload(runRevision, eventPayload), occurredAt);
    }

    ReceiptHead receiptHead(long tenantId, UUID receiptId) {
        return jdbc.query("""
                SELECT status_sequence, receipt_status
                  FROM pay_command_receipts
                 WHERE tenant_id = :tenantId AND receipt_id = :receiptId
                 ORDER BY status_sequence DESC LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("receiptId", receiptId),
                (result, row) -> new ReceiptHead(
                        result.getInt("status_sequence"),
                        ReceiptStatus.valueOf(result.getString("receipt_status"))))
                .stream().findFirst().orElseThrow(() -> notFound(
                        "The Payroll command receipt is unavailable."));
    }

    void appendReceiptRecord(
            long tenantId,
            UUID receiptId,
            ReceiptStatus status,
            String auditType,
            String auditPayload,
            Instant occurredAt) {
        ReceiptHead head = receiptHead(tenantId, receiptId);
        int inserted = jdbc.update("""
                INSERT INTO pay_command_receipts (
                    tenant_id, receipt_id, status_sequence, receipt_status,
                    idempotency_key, command_type, originating_action,
                    subject_principal_public_id, actor_id, run_id,
                    population_kind, population_scope_digest, context_scope_key,
                    field_policy_revision, purpose_code, authorization_revision,
                    correlation_id, request_digest, aggregate_version, result_ref,
                    step_up_challenge_id, step_up_nonce, step_up_issuer,
                    step_up_expires_at, step_up_payload_sha256,
                    step_up_decision_revision, audit_type, audit_payload,
                    created_at, occurred_at)
                SELECT tenant_id, receipt_id, :sequence, :status,
                       idempotency_key, command_type, originating_action,
                       subject_principal_public_id, actor_id, run_id,
                       population_kind, population_scope_digest, context_scope_key,
                       field_policy_revision, purpose_code, authorization_revision,
                       correlation_id, request_digest, aggregate_version, result_ref,
                       step_up_challenge_id, step_up_nonce, step_up_issuer,
                       step_up_expires_at, step_up_payload_sha256,
                       step_up_decision_revision, :auditType, CAST(:auditPayload AS jsonb),
                       created_at, :occurredAt
                  FROM pay_command_receipts
                 WHERE tenant_id = :tenantId AND receipt_id = :receiptId
                   AND status_sequence = 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("receiptId", receiptId)
                .addValue("sequence", head.sequence() + 1)
                .addValue("status", status.name()).addValue("auditType", auditType)
                .addValue("auditPayload", auditPayload)
                .addValue("occurredAt", timestamp(occurredAt)));
        if (inserted != 1) throw notFound("The Payroll command receipt is unavailable.");
    }

    public void appendEvent(long tenantId, EventFact event) {
        bindTenant(tenantId);
        jdbc.update("""
                INSERT INTO pay_outbox_events (
                    tenant_id, event_id, run_id, attempt_no, run_revision,
                    receipt_id, event_type, schema_version, aggregate_sequence,
                    correlation_id, event_payload, occurred_at)
                VALUES (:tenantId, :eventId, :runId, :attemptNo, :runRevision,
                    :receiptId, :eventType, :schemaVersion, :aggregateSequence,
                    :correlationId, CAST(:payload AS jsonb), :occurredAt)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("eventId", event.eventId())
                .addValue("runId", event.runId()).addValue("attemptNo", event.attemptNo())
                .addValue("runRevision", event.runRevision())
                .addValue("receiptId", event.receiptId())
                .addValue("eventType", event.eventType())
                .addValue("schemaVersion", event.schemaVersion())
                .addValue("aggregateSequence", event.aggregateSequence())
                .addValue("correlationId", event.correlationId())
                .addValue("payload", event.payloadJson())
                .addValue("occurredAt", timestamp(event.occurredAt())));
    }

    MapSqlParameterSource receiptParameters(PersistedReceipt persisted) {
        PayrollCommandReceipt receipt = persisted.receipt();
        return new MapSqlParameterSource()
                .addValue("tenantId", persisted.tenantId())
                .addValue("receiptId", receipt.receiptId())
                .addValue("idempotencyKey", persisted.idempotencyKey())
                .addValue("commandType", persisted.commandType().name())
                .addValue("originatingAction", receipt.originatingAction())
                .addValue("subjectId", receipt.subjectPrincipalPublicId())
                .addValue("actorId", persisted.actorId())
                .addValue("runId", persisted.runId())
                .addValue("populationKind", persisted.populationKind())
                .addValue("scopeDigest", receipt.populationScopeDigest())
                .addValue("contextScopeKey", persisted.contextScopeKey())
                .addValue("fieldPolicyRevision", receipt.fieldPolicyRevision())
                .addValue("purposeCode", receipt.purposeCode())
                .addValue("authorizationRevision", receipt.authorizationRevision())
                .addValue("correlationId", receipt.correlationId())
                .addValue("requestDigest", receipt.requestDigest())
                .addValue("aggregateVersion", receipt.aggregateVersion())
                .addValue("resultRef", receipt.resultRef())
                .addValue("createdAt", timestamp(receipt.createdAt()));
    }

    record ReceiptHead(int sequence, ReceiptStatus status) {
    }
}
