package com.dwp.services.payroll.preparation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** JDBC operations for payroll commits. */
final class PayrollPreparationCommitJdbc extends PayrollPreparationJdbcSupport {

    PayrollPreparationCommitJdbc(
            NamedParameterJdbcTemplate jdbc,
            PayrollPreparationCanonical canonical,
            PayrollPreparationSemanticSnapshotPort semantics,
            PayrollPreparationPopulationSnapshotPort populations,
            Clock clock) {
        super(jdbc, canonical, semantics, populations, clock);
    }

    public void commit(PayrollPreparationModels.PreparationCommit commit) {
        long tenantId = commit.actor().subject().tenantId();
        bindTenant(tenantId);
        try {
            insertPhysicalPreparingRun(commit);
            insertPreparationState(commit, 1, "PREPARING", null);
            insertSources(commit);
            insertDecisions(commit);
            insertLineage(commit);
            insertCalculationSnapshot(commit);
            insertReceipt(commit);
            insertPhysicalReadyRun(commit);
            insertPreparationState(commit, 2, "READY", commit.eventId());
            insertPreparedEvent(commit);
        } catch (DataIntegrityViolationException exception) {
            String detail = exception.getMostSpecificCause().getMessage();
            if (detail != null && (detail.contains(
                    "pay_command_receipt_challenge_unique")
                    || detail.contains("pay_command_receipt_nonce_unique"))) {
                throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_REPLAY,
                        "The Payroll step-up challenge was already consumed.", exception);
            }
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The immutable Payroll preparation conflicts.", exception);
        }
    }

    void insertPhysicalPreparingRun(
            PayrollPreparationModels.PreparationCommit commit) {
        PayrollPreparationModels.FrozenMaterial material =
                commit.candidate().material();
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, legal_entity_id, payroll_group_id,
                    population_kind, population_scope_digest, state,
                    maker_actor_id, maker_subject_public_id, latest_attempt_no,
                    worker_count, gross_amount, net_amount, currency,
                    result_digest, approval_case_id, decision_digest,
                    approved_by_actor_id, approved_at, audit_bundle_digest,
                    command_receipt_id, recorded_at)
                VALUES (:tenantId, :runId, 1, :runType, :payPeriodId,
                    :sourceSnapshotId, :sourceSnapshotRevision, :sourcePayloadDigest,
                    :legalEntityId, :payrollGroupId, :populationKind,
                    :scopeDigest, 'PREPARING', :actorId, :subjectId, 0, 0,
                    0.0000, 0.0000, :currency, NULL, NULL, NULL, NULL, NULL,
                    NULL, NULL, :recordedAt)
                """, common(commit)
                .addValue("runType", commit.command().runType().name())
                .addValue("sourceSnapshotId", material.sourceSnapshotId())
                .addValue("sourceSnapshotRevision", material.sourceSnapshotRevision())
                .addValue("sourcePayloadDigest", material.sourcePayloadDigest())
                .addValue("currency", material.currency()));
    }

    void insertPhysicalReadyRun(
            PayrollPreparationModels.PreparationCommit commit) {
        PayrollPreparationModels.FrozenMaterial material =
                commit.candidate().material();
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, legal_entity_id, payroll_group_id,
                    population_kind, population_scope_digest, state,
                    maker_actor_id, maker_subject_public_id, latest_attempt_no,
                    worker_count, gross_amount, net_amount, currency,
                    result_digest, approval_case_id, decision_digest,
                    approved_by_actor_id, approved_at, audit_bundle_digest,
                    command_receipt_id, recorded_at)
                VALUES (:tenantId, :runId, 2, :runType, :payPeriodId,
                    :sourceSnapshotId, :sourceSnapshotRevision, :sourcePayloadDigest,
                    :legalEntityId, :payrollGroupId, :populationKind,
                    :scopeDigest, 'READY', :actorId, :subjectId, 0, 0,
                    0.0000, 0.0000, :currency, NULL, NULL, NULL, NULL, NULL,
                    NULL, :receiptId, :recordedAt)
                """, common(commit)
                .addValue("runType", commit.command().runType().name())
                .addValue("sourceSnapshotId", material.sourceSnapshotId())
                .addValue("sourceSnapshotRevision", material.sourceSnapshotRevision())
                .addValue("sourcePayloadDigest", material.sourcePayloadDigest())
                .addValue("currency", material.currency()));
    }

    void insertPreparationState(
            PayrollPreparationModels.PreparationCommit commit,
            int sequence,
            String state,
            UUID eventId) {
        jdbc.update("""
                INSERT INTO pay_run_preparations (
                    tenant_id, preparation_id, state_sequence, preparation_state,
                    run_id, request_digest, snapshot_id, snapshot_digest,
                    population_revision, target_population_digest,
                    authorization_scope_digest,
                    legal_entity_id, payroll_group_id, pay_period_id,
                    population_kind, named_population_id, maker_actor_id,
                    maker_subject_public_id, context_scope_key,
                    authorization_revision, receipt_id, event_id,
                    source_as_of, recorded_at)
                VALUES (:tenantId, :preparationId, :sequence, :state,
                    :runId, :requestDigest, :snapshotId, :snapshotDigest,
                    :populationRevision, :targetPopulationDigest, :scopeDigest,
                    :legalEntityId, :payrollGroupId, :payPeriodId,
                    :populationKind, :namedPopulationId, :actorId,
                    :subjectId, :contextScopeKey, :authorizationRevision,
                    :receiptId, :eventId, :sourceAsOf, :recordedAt)
                """, common(commit)
                .addValue("sequence", sequence)
                .addValue("state", state)
                .addValue("sourceAsOf", timestamp(commit.command().asOf()))
                .addValue("eventId", eventId));
    }

    void insertSources(PayrollPreparationModels.PreparationCommit commit) {
        int ordinal = 0;
        for (PayrollPreparationModels.SourceRevision source
                : commit.candidate().population().sources()) {
            jdbc.update("""
                    INSERT INTO pay_run_preparation_sources (
                        tenant_id, preparation_id, source_ordinal, source_type,
                        source_id, source_revision, payload_digest,
                        approval_receipt_id, approval_revision, source_line_count,
                        source_status, recorded_at)
                    VALUES (:tenantId, :preparationId, :ordinal, :sourceType,
                        :sourceId, :sourceRevision, :payloadDigest,
                        :approvalReceiptId, :approvalRevision, :lineCount,
                        :sourceStatus, :recordedAt)
                    """, common(commit)
                    .addValue("ordinal", ++ordinal)
                    .addValue("sourceType", source.sourceType().name())
                    .addValue("sourceId", source.sourceId())
                    .addValue("sourceRevision", source.revision())
                    .addValue("payloadDigest", source.payloadDigest())
                    .addValue("approvalReceiptId", source.approvalReceiptId())
                    .addValue("approvalRevision", source.approvalRevision())
                    .addValue("lineCount", source.lineCount())
                    .addValue("sourceStatus", source.status().name()));
        }
    }

    void insertDecisions(PayrollPreparationModels.PreparationCommit commit) {
        int ordinal = 0;
        for (PayrollPreparationModels.PreflightDecision decision
                : commit.candidate().population().preflight().decisions()) {
            jdbc.update("""
                    INSERT INTO pay_run_preparation_decisions (
                        tenant_id, preparation_id, decision_ordinal,
                        decision_code, decision_status, source_type,
                        source_id, source_revision, evidence_digest, recorded_at)
                    VALUES (:tenantId, :preparationId, :ordinal,
                        :code, :status, :sourceType, :sourceId,
                        :sourceRevision, :evidenceDigest, :recordedAt)
                    """, common(commit)
                    .addValue("ordinal", ++ordinal)
                    .addValue("code", decision.code())
                    .addValue("status", decision.status().name())
                    .addValue("sourceType", decision.sourceType() == null
                            ? null : decision.sourceType().name())
                    .addValue("sourceId", decision.sourceId())
                    .addValue("sourceRevision", decision.revision())
                    .addValue("evidenceDigest", decision.evidenceDigest()));
        }
    }

    void insertLineage(PayrollPreparationModels.PreparationCommit commit) {
        for (PayrollPreparationModels.PreparedLine line
                : commit.candidate().material().lines()) {
            jdbc.update("""
                    INSERT INTO pay_run_preparation_lines (
                        tenant_id, preparation_id, line_ordinal,
                        compensation_plan_input_id, source_public_id,
                        source_event_id, snapshot_id, snapshot_revision,
                        snapshot_payload_digest, plan_id, cycle_id, worker_id,
                        assignment_id, component_code, currency, approved_amount,
                        effective_from, effective_to, source_payload_digest,
                        semantic_binding, semantic_binding_digest, recorded_at)
                    VALUES (:tenantId, :preparationId, :ordinal,
                        :sourceInputId, :sourcePublicId, :sourceEventId,
                        :sourceSnapshotId, :sourceSnapshotRevision,
                        :sourceSnapshotDigest, :planId, :cycleId, :workerId,
                        :assignmentId, :componentCode, :currency, :amount,
                        :effectiveFrom, :effectiveTo, :payloadDigest,
                        CAST(:semanticBinding AS jsonb),
                        :semanticBindingDigest, :recordedAt)
                    """, common(commit)
                    .addValue("ordinal", line.lineSequence())
                    .addValue("sourceInputId", line.sourceInputId())
                    .addValue("sourcePublicId", line.sourcePublicId())
                    .addValue("sourceEventId", line.sourceEventId())
                    .addValue("sourceSnapshotId", line.snapshotId())
                    .addValue("sourceSnapshotRevision", line.snapshotRevision())
                    .addValue("sourceSnapshotDigest", line.snapshotPayloadDigest())
                    .addValue("planId", line.planId())
                    .addValue("cycleId", line.cycleId())
                    .addValue("workerId", line.workerId())
                    .addValue("assignmentId", line.assignmentId())
                    .addValue("componentCode", line.componentCode())
                    .addValue("currency", line.currency())
                    .addValue("amount", line.approvedAmount())
                    .addValue("effectiveFrom", Date.valueOf(line.effectiveFrom()))
                    .addValue("effectiveTo", line.effectiveTo() == null
                            ? null : Date.valueOf(line.effectiveTo()))
                    .addValue("payloadDigest", line.payloadDigest())
                    .addValue("semanticBinding", line.semanticBindingJson())
                    .addValue("semanticBindingDigest", line.semanticBindingDigest()));
        }
        for (PayrollPreparationModels.FormulaRef formula
                : commit.candidate().material().formulas()) {
            jdbc.update("""
                    INSERT INTO pay_run_preparation_formula_refs (
                        tenant_id, preparation_id, formula_id,
                        formula_version, ast_digest, recorded_at)
                    VALUES (:tenantId, :preparationId, :formulaId,
                        :formulaVersion, :astDigest, :recordedAt)
                    """, common(commit)
                    .addValue("formulaId", formula.formulaId())
                    .addValue("formulaVersion", formula.version())
                    .addValue("astDigest", formula.astDigest()));
        }
    }

    void insertCalculationSnapshot(
            PayrollPreparationModels.PreparationCommit commit) {
        PayrollPreparationModels.FrozenMaterial material =
                commit.candidate().material();
        String formulaJson = canonical.json(material.formulas().stream()
                .sorted(Comparator.comparing(
                        PayrollPreparationModels.FormulaRef::formulaId))
                .map(value -> Map.of(
                        "formulaId", value.formulaId().toString(),
                        "version", value.version(),
                        "astDigest", value.astDigest())).toList());
        Long internalId = jdbc.queryForObject("""
                INSERT INTO pay_input_snapshots (
                    tenant_id, input_snapshot_id, run_id, attempt_no,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, formula_set_digest, formula_bindings,
                    engine_version, function_set_version,
                    semantic_binding_digest, target_population_digest,
                    compensation_input_digest, source_line_count, pinned_at)
                VALUES (:tenantId, :inputSnapshotId, :runId, 1,
                    :sourceSnapshotId, :sourceSnapshotRevision,
                    :sourcePayloadDigest, :formulaSetDigest,
                    CAST(:formulaBindings AS jsonb), :engineVersion,
                    :functionSetVersion, :semanticBindingDigest,
                    :targetPopulationDigest, :compensationInputDigest,
                    :lineCount, :recordedAt)
                RETURNING input_snapshot_internal_id
                """, common(commit)
                    .addValue("inputSnapshotId", commit.snapshotId())
                .addValue("sourceSnapshotId", material.sourceSnapshotId())
                .addValue("sourceSnapshotRevision", material.sourceSnapshotRevision())
                .addValue("sourcePayloadDigest", material.sourcePayloadDigest())
                .addValue("formulaSetDigest", material.formulaSetDigest())
                .addValue("formulaBindings", formulaJson)
                .addValue("engineVersion", PayrollPreparationModels.ENGINE_VERSION)
                .addValue("functionSetVersion", material.functionSetVersion())
                .addValue("semanticBindingDigest", material.semanticBindingDigest())
                    .addValue("targetPopulationDigest",
                        commit.actor().scope().scopeDigest())
                .addValue("compensationInputDigest",
                        material.compensationInputDigest())
                .addValue("lineCount", material.lines().size()), Long.class);
        int ordinal = 0;
        for (PayrollPreparationModels.PreparedLine line : material.lines()) {
            jdbc.update("""
                    INSERT INTO pay_input_snapshot_compensation_refs (
                        tenant_id, input_snapshot_internal_id, input_ordinal,
                        compensation_plan_input_id, source_payload_digest,
                        worker_id, assignment_id, component_code, currency,
                        approved_amount, semantic_binding,
                        semantic_binding_digest)
                    VALUES (:tenantId, :internalId, :ordinal, :sourceInputId,
                        :sourcePayloadDigest, :workerId, :assignmentId,
                        :componentCode, :currency, :amount,
                        CAST(:semanticBinding AS jsonb), :semanticBindingDigest)
                    """, common(commit)
                    .addValue("internalId", internalId)
                    .addValue("ordinal", ++ordinal)
                    .addValue("sourceInputId", line.sourceInputId())
                    .addValue("sourcePayloadDigest", line.payloadDigest())
                    .addValue("workerId", line.workerId())
                    .addValue("assignmentId", line.assignmentId())
                    .addValue("componentCode", line.componentCode())
                    .addValue("currency", line.currency())
                    .addValue("amount", line.approvedAmount())
                    .addValue("semanticBinding", line.semanticBindingJson())
                    .addValue("semanticBindingDigest", line.semanticBindingDigest()));
        }
    }

    void insertReceipt(PayrollPreparationModels.PreparationCommit commit) {
        PayrollStepUpEvidence stepUp = commit.stepUp();
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
                VALUES (:tenantId, :receiptId, 1, 'SUCCEEDED',
                    :idempotencyKey, 'RUN_PREPARE', 'PAY_RUN_PREPARE',
                    :subjectId, :actorId, :runId, :populationKind,
                    :scopeDigest, :contextScopeKey, :fieldPolicyRevision,
                    'PAYROLL_PROCESSING', :authorizationRevision,
                    :correlationId, :requestDigest, 2,
                    :resultRef, :challengeId, :nonce, :issuer, :expiresAt,
                    :payloadSha256, :decisionRevision,
                    'PAYROLL_RUN_PREPARED', CAST(:auditPayload AS jsonb),
                    :recordedAt, :recordedAt)
                """, common(commit)
                .addValue("idempotencyKey", commit.idempotencyKey())
                .addValue("correlationId", commit.correlationId())
                .addValue("resultRef", "payroll-run:" + commit.runId())
                .addValue("challengeId", stepUp.challengeId())
                .addValue("nonce", stepUp.nonce())
                .addValue("issuer", stepUp.issuer())
                .addValue("expiresAt", timestamp(stepUp.expiresAt()))
                .addValue("payloadSha256", stepUp.payloadSha256())
                .addValue("decisionRevision", stepUp.decisionRevision())
                .addValue("auditPayload", canonical.json(Map.of(
                        "preparationId", commit.command().preparationId(),
                        "requestDigest", commit.requestDigest(),
                        "snapshotDigest", commit.snapshotDigest()))));
    }

    void insertPreparedEvent(
            PayrollPreparationModels.PreparationCommit commit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("preparationId", commit.command().preparationId());
        data.put("runId", commit.runId());
        data.put("runRevision", 2);
        data.put("snapshotId", commit.snapshotId());
        data.put("snapshotDigest", commit.snapshotDigest());
        data.put("populationRevision",
                commit.candidate().population().populationRevision());
        data.put("targetPopulationDigest",
                commit.command().inputSnapshotDigest());
        data.put("sourceCount", commit.candidate().population().sources().size());
        data.put("preflightDecisionCount",
                commit.candidate().population().preflight().decisions().size());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specVersion", "1.0");
        envelope.put("id", commit.eventId());
        envelope.put("source", "urn:dwp:payroll:preparation");
        envelope.put("type", "PayrollRunPrepared.v1");
        envelope.put("schemaVersion", 1);
        envelope.put("time", commit.preparedAt());
        envelope.put("tenantId", commit.actor().subject().tenantId());
        envelope.put("aggregateType", "PAYROLL_RUN");
        envelope.put("aggregateId", commit.runId());
        envelope.put("aggregateSequence", 2);
        envelope.put("correlationId", commit.correlationId());
        envelope.put("data", data);
        jdbc.update("""
                INSERT INTO pay_outbox_events (
                    tenant_id, event_id, run_id, attempt_no, run_revision,
                    receipt_id, event_type, schema_version,
                    aggregate_sequence, correlation_id, event_payload, occurred_at)
                VALUES (:tenantId, :eventId, :runId, NULL, 2,
                    :receiptId, 'PayrollRunPrepared', 1, 2,
                    :correlationId, CAST(:payload AS jsonb), :recordedAt)
                """, common(commit)
                .addValue("eventId", commit.eventId())
                .addValue("correlationId", commit.correlationId())
                .addValue("payload", canonical.json(envelope)));
    }

    MapSqlParameterSource common(
            PayrollPreparationModels.PreparationCommit commit) {
        PayrollPreparationAuthority.Subject subject = commit.actor().subject();
        return new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("preparationId", commit.command().preparationId())
                .addValue("runId", commit.runId())
                .addValue("requestDigest", commit.requestDigest())
                .addValue("snapshotId", commit.snapshotId())
                .addValue("snapshotDigest", commit.snapshotDigest())
                .addValue("populationRevision",
                        commit.candidate().population().populationRevision())
                .addValue("targetPopulationDigest",
                        commit.command().inputSnapshotDigest())
                .addValue("legalEntityId", commit.command().payrollEntityId())
                .addValue("payrollGroupId", commit.command().payGroupId())
                .addValue("payPeriodId", commit.command().payPeriodId())
                .addValue("populationKind", subject.populationKind())
                .addValue("namedPopulationId", null)
                .addValue("actorId", subject.actorId())
                .addValue("subjectId", subject.subjectPrincipalPublicId())
                .addValue("contextScopeKey", subject.contextScopeKey())
                .addValue("fieldPolicyRevision", subject.fieldPolicyRevision())
                .addValue("authorizationRevision",
                        subject.authorizationRevision())
                .addValue("scopeDigest", commit.actor().scope().scopeDigest())
                .addValue("receiptId", commit.receiptId())
                .addValue("recordedAt", timestamp(commit.preparedAt()));
    }
}
