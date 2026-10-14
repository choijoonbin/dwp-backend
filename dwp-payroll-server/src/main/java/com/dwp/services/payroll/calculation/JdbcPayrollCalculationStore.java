package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.payroll.security.PayrollStepUpEvidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Attempt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.AttemptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculatedLine;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CalculationOutcome;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Checkpoint;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandIdentity;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.CommandType;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.EventFact;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FormulaBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.Lease;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PayrollCommandReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PersistedReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ReceiptStatus;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunState;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunType;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SemanticBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SourceInput;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.WorkerResult;

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
class JdbcPayrollCalculationStore implements PayrollCalculationStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final PayrollCalculationCanonical canonical;

    JdbcPayrollCalculationStore(
            NamedParameterJdbcTemplate jdbc,
            PayrollCalculationCanonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    @Override
    public void bindTenant(long tenantId) {
        if (tenantId < 1) {
            throw new BaseException(ErrorCode.TENANT_MISSING,
                    "A positive Payroll tenant is required.");
        }
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
    }

    @Override
    public void lockRun(long tenantId, UUID runId) {
        bindTenant(tenantId);
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock("
                        + "hashtextextended(:lockKey, 0))) locked",
                new MapSqlParameterSource(
                        "lockKey", tenantId + ":PAY011:" + runId),
                Integer.class);
    }

    @Override
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

    @Override
    public Optional<PersistedReceipt> receipt(long tenantId, UUID receiptId) {
        bindTenant(tenantId);
        return receiptQuery("""
                 WHERE r.tenant_id = :tenantId AND r.receipt_id = :receiptId
                   AND r.status_sequence = 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("receiptId", receiptId));
    }

    private Optional<PersistedReceipt> receiptQuery(
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

    @Override
    public Optional<RunRevision> currentRun(long tenantId, UUID runId) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT tenant_id, run_id, run_revision, run_type, pay_period_id,
                       source_snapshot_id, source_snapshot_revision,
                       source_payload_digest,
                       legal_entity_id, payroll_group_id, population_kind,
                       population_scope_digest, state, maker_actor_id,
                       maker_subject_public_id, latest_attempt_no, worker_count,
                       gross_amount, net_amount, currency, result_digest,
                       approval_case_id, decision_digest, approved_by_actor_id,
                       approved_at, audit_bundle_digest, command_receipt_id, recorded_at
                  FROM pay_payroll_runs
                 WHERE tenant_id = :tenantId AND run_id = :runId
                 ORDER BY run_revision DESC LIMIT 1
                """, params(tenantId, runId), (result, row) -> mapRun(result))
                .stream().findFirst();
    }

    @Override
    public Optional<AttemptStatus> currentAttemptStatus(
            long tenantId, UUID runId, int attemptNo) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT tenant_id, run_id, attempt_no, status_sequence,
                       attempt_state, failure_code, occurred_at
                  FROM pay_payroll_run_attempts
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                 ORDER BY status_sequence DESC LIMIT 1
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new AttemptStatus(
                        result.getLong("tenant_id"),
                        result.getObject("run_id", UUID.class),
                        result.getInt("attempt_no"), result.getInt("status_sequence"),
                        AttemptState.valueOf(result.getString("attempt_state")),
                        result.getString("failure_code"),
                        result.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .stream().findFirst();
    }

    @Override
    public Preparation prepare(
            RunRevision run,
            int attemptNo,
            String targetPopulationDigest,
            Instant pinnedAt) {
        bindTenant(run.tenantId());
        List<SourceInput> inputs = sourceInputs(run);
        if (inputs.isEmpty()) {
            throw invalid("The pinned PAY-016 compensation snapshot is unavailable.");
        }
        SourceInput first = inputs.getFirst();
        FoundationScope scope = foundationScope(
                run.tenantId(), run.legalEntityId(), run.payrollGroupId(),
                first.effectiveFrom());
        List<FormulaBinding> formulas = formulas(
                run.tenantId(), scope.legalEntityId(), scope.payrollGroupId());
        if (formulas.isEmpty()) {
            throw invalid("The pinned Payroll scope has no published formula set.");
        }
        String formulaSetDigest = canonical.digest(formulas.stream()
                .sorted(Comparator.comparing(FormulaBinding::formulaId))
                .map(value -> Map.of(
                        "formulaId", value.formulaId(),
                        "version", value.version(),
                        "astDigest", value.astDigest()))
                .toList());
        String compensationDigest = canonical.digest(inputs.stream()
                .sorted(Comparator.comparingInt(SourceInput::lineSequence))
                .map(value -> Map.of(
                        "sourcePublicId", value.sourcePublicId(),
                        "lineSequence", value.lineSequence(),
                        "payloadDigest", value.payloadDigest(),
                        "amount", value.approvedAmount().setScale(4).toPlainString(),
                        "currency", value.currency()))
                .toList());
        FrozenSnapshot snapshot = new FrozenSnapshot(
                run.tenantId(), run.runId(), attemptNo,
                first.snapshotId(), first.snapshotRevision(),
                first.snapshotPayloadDigest(), formulaSetDigest,
                targetPopulationDigest, compensationDigest, null,
                inputs, formulas, List.of(), pinnedAt);
        return new Preparation(
                run.payPeriodId(), scope.legalEntityId(), scope.payrollGroupId(),
                first.currency(), snapshot);
    }

    private List<SourceInput> sourceInputs(RunRevision run) {
        return jdbc.query("""
                WITH exact_receipt AS (
                    SELECT source_event_id, snapshot_id, snapshot_revision,
                           snapshot_payload_digest, line_count
                      FROM pay_compensation_plan_input_receipts
                     WHERE tenant_id = :tenantId
                       AND snapshot_id = :snapshotId
                       AND snapshot_revision = :snapshotRevision
                       AND snapshot_payload_digest = :snapshotPayloadDigest
                ), matched_inputs AS (
                    SELECT i.compensation_plan_input_id, i.public_id,
                           i.source_event_id, i.snapshot_id, i.snapshot_revision,
                           i.snapshot_payload_digest, i.plan_id, i.cycle_id,
                           i.line_sequence, i.worker_id, i.assignment_id,
                           i.component_code, i.currency, i.approved_amount,
                           i.effective_from, i.effective_to, i.payload_digest,
                           receipt.line_count,
                           COUNT(*) OVER () AS actual_line_count
                      FROM pay_compensation_plan_inputs i
                      JOIN exact_receipt receipt
                        ON receipt.source_event_id = i.source_event_id
                       AND receipt.snapshot_id = i.snapshot_id
                       AND receipt.snapshot_revision = i.snapshot_revision
                       AND receipt.snapshot_payload_digest = i.snapshot_payload_digest
                     WHERE i.tenant_id = :tenantId
                       AND i.snapshot_id = :snapshotId
                       AND i.snapshot_revision = :snapshotRevision
                       AND i.snapshot_payload_digest = :snapshotPayloadDigest
                       AND i.cycle_id = :payPeriodId
                )
                SELECT compensation_plan_input_id, public_id, source_event_id,
                       snapshot_id, snapshot_revision, snapshot_payload_digest,
                       plan_id, cycle_id, line_sequence, worker_id,
                       assignment_id, component_code, currency, approved_amount,
                       effective_from, effective_to, payload_digest
                  FROM matched_inputs
                 WHERE actual_line_count = line_count
                 ORDER BY line_sequence
                """, new MapSqlParameterSource()
                .addValue("tenantId", run.tenantId())
                .addValue("snapshotId", run.sourceSnapshotId())
                .addValue("snapshotRevision", run.sourceSnapshotRevision())
                .addValue("snapshotPayloadDigest", run.sourcePayloadDigest())
                .addValue("payPeriodId", run.payPeriodId()),
                (result, row) -> mapSourceInput(result));
    }

    private FoundationScope foundationScope(
            long tenantId,
            UUID legalEntityId,
            UUID payrollGroupId,
            java.time.LocalDate effectiveOn) {
        List<FoundationScope> scopes = jdbc.query("""
                SELECT legal_entity_id, payroll_group_id
                  FROM pay_foundation_configurations
                 WHERE tenant_id = :tenantId
                   AND legal_entity_id = :legalEntityId
                   AND payroll_group_id = :payrollGroupId
                   AND lifecycle_state = 'PUBLISHED'
                   AND effective_from <= :effectiveOn
                   AND (effective_to IS NULL OR effective_to >= :effectiveOn)
                 ORDER BY current_version DESC, updated_at DESC
                 LIMIT 2
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", legalEntityId)
                .addValue("payrollGroupId", payrollGroupId)
                .addValue("effectiveOn", Date.valueOf(effectiveOn)),
                (result, row) -> new FoundationScope(
                        result.getObject("legal_entity_id", UUID.class),
                        result.getObject("payroll_group_id", UUID.class)));
        if (scopes.size() != 1) {
            throw invalid("The Payroll run does not resolve to one published scope version.");
        }
        return scopes.getFirst();
    }

    private List<FormulaBinding> formulas(
            long tenantId, UUID legalEntityId, UUID payrollGroupId) {
        return jdbc.query("""
                SELECT DISTINCT ON (formula_id) formula_id, version, ast_digest
                  FROM pay_formula_versions
                 WHERE tenant_id = :tenantId
                   AND legal_entity_id = :legalEntityId
                   AND payroll_group_id = :payrollGroupId
                   AND lifecycle_state = 'PUBLISHED'
                 ORDER BY formula_id, version DESC
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", legalEntityId)
                .addValue("payrollGroupId", payrollGroupId),
                (result, row) -> new FormulaBinding(
                        result.getObject("formula_id", UUID.class),
                        result.getLong("version"), result.getString("ast_digest")));
    }

    @Override
    public void freeze(Preparation preparation) {
        FrozenSnapshot snapshot = preparation.snapshot();
        bindTenant(snapshot.tenantId());
        if (snapshot.semanticBindingDigest() == null
                || snapshot.semanticBindings().size() != snapshot.inputs().size()) {
            throw invalid("The Payroll input snapshot has no complete semantic binding set.");
        }
        Map<UUID, SemanticBinding> bindingsBySource = new LinkedHashMap<>();
        for (SemanticBinding binding : snapshot.semanticBindings()) {
            if (!PayrollCalculationModels.ENGINE_VERSION.equals(binding.engineVersion())
                    || bindingsBySource.put(binding.sourcePublicId(), binding) != null) {
                throw invalid("The Payroll semantic binding set is invalid.");
            }
        }
        String functionSetVersion = snapshot.semanticBindings().getFirst()
                .functionSetVersion();
        if (snapshot.semanticBindings().stream().anyMatch(binding ->
                !functionSetVersion.equals(binding.functionSetVersion()))) {
            throw invalid("The Payroll semantic binding set mixes function versions.");
        }
        String aggregateBindingDigest = canonical.digest(Map.of(
                "schema", "PayrollSemanticBindingSet.v1",
                "engineVersion", PayrollCalculationModels.ENGINE_VERSION,
                "bindingDigests", snapshot.semanticBindings().stream()
                        .map(SemanticBinding::bindingDigest).toList()));
        if (!aggregateBindingDigest.equals(snapshot.semanticBindingDigest())) {
            throw invalid("The Payroll semantic binding set digest is invalid.");
        }
        String formulaJson = canonical.json(snapshot.formulas().stream()
                .sorted(Comparator.comparing(FormulaBinding::formulaId))
                .map(value -> Map.of(
                        "formulaId", value.formulaId().toString(),
                        "version", value.version(),
                        "astDigest", value.astDigest()))
                .toList());
        UUID inputSnapshotId = canonical.deterministicUuid(
                "payroll-input-snapshot-v1", Map.of(
                        "tenantId", snapshot.tenantId(),
                        "runId", snapshot.runId(),
                        "attemptNo", snapshot.attemptNo(),
                        "sourceSnapshotId", snapshot.sourceSnapshotId(),
                        "sourceSnapshotRevision", snapshot.sourceSnapshotRevision(),
                        "sourcePayloadDigest", snapshot.sourcePayloadDigest()));
        try {
            Long internalId = jdbc.queryForObject("""
                    INSERT INTO pay_input_snapshots (
                        tenant_id, input_snapshot_id, run_id, attempt_no,
                        source_snapshot_id, source_snapshot_revision,
                        source_payload_digest, formula_set_digest, formula_bindings,
                        engine_version, function_set_version,
                        semantic_binding_digest,
                        target_population_digest, compensation_input_digest,
                        source_line_count, pinned_at)
                    VALUES (:tenantId, :inputSnapshotId, :runId, :attemptNo,
                        :snapshotId, :snapshotRevision, :payloadDigest,
                        :formulaSetDigest, CAST(:formulaBindings AS jsonb),
                        :engineVersion, :functionSetVersion,
                        :semanticBindingDigest,
                        :targetPopulationDigest, :compensationInputDigest,
                        :lineCount, :pinnedAt)
                    RETURNING input_snapshot_internal_id
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", snapshot.tenantId())
                    .addValue("inputSnapshotId", inputSnapshotId)
                    .addValue("runId", snapshot.runId())
                    .addValue("attemptNo", snapshot.attemptNo())
                    .addValue("snapshotId", snapshot.sourceSnapshotId())
                    .addValue("snapshotRevision", snapshot.sourceSnapshotRevision())
                    .addValue("payloadDigest", snapshot.sourcePayloadDigest())
                    .addValue("formulaSetDigest", snapshot.formulaSetDigest())
                    .addValue("formulaBindings", formulaJson)
                    .addValue("engineVersion", PayrollCalculationModels.ENGINE_VERSION)
                    .addValue("functionSetVersion", functionSetVersion)
                    .addValue("semanticBindingDigest", snapshot.semanticBindingDigest())
                    .addValue("targetPopulationDigest", snapshot.targetPopulationDigest())
                    .addValue("compensationInputDigest", snapshot.compensationInputDigest())
                    .addValue("lineCount", snapshot.inputs().size())
                    .addValue("pinnedAt", timestamp(snapshot.pinnedAt())), Long.class);
            int ordinal = 0;
            for (SourceInput input : snapshot.inputs()) {
                SemanticBinding binding = bindingsBySource.get(input.sourcePublicId());
                if (binding == null) {
                    throw invalid("A Payroll source input has no semantic binding.");
                }
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
                        """, new MapSqlParameterSource()
                        .addValue("tenantId", snapshot.tenantId())
                        .addValue("internalId", internalId)
                        .addValue("ordinal", ++ordinal)
                        .addValue("sourceInputId", input.sourceInputId())
                        .addValue("sourcePayloadDigest", input.payloadDigest())
                        .addValue("workerId", input.workerId())
                        .addValue("assignmentId", input.assignmentId())
                        .addValue("componentCode", input.componentCode())
                        .addValue("currency", input.currency())
                        .addValue("amount", input.approvedAmount())
                        .addValue("semanticBinding", canonical.json(binding))
                        .addValue("semanticBindingDigest", binding.bindingDigest()));
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The immutable payroll input snapshot conflicts.", exception);
        }
    }

    @Override
    public void appendAttempt(Attempt attempt) {
        bindTenant(attempt.tenantId());
        jdbc.update("""
                INSERT INTO pay_payroll_run_attempts (
                    tenant_id, run_id, attempt_no, attempt_id,
                    predecessor_attempt_no, status_sequence, attempt_state,
                    engine_version, command_receipt_id, failure_code, occurred_at)
                VALUES (:tenantId, :runId, :attemptNo, :attemptId,
                    :predecessorAttemptNo, 1, 'READY', :engineVersion,
                    :receiptId, NULL, :occurredAt)
                """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("runId", attempt.runId())
                .addValue("attemptNo", attempt.attemptNo())
                .addValue("attemptId", attempt.attemptId())
                .addValue("predecessorAttemptNo", attempt.predecessorAttemptNo())
                .addValue("engineVersion", attempt.engineVersion())
                .addValue("receiptId", attempt.commandReceiptId())
                .addValue("occurredAt", timestamp(attempt.createdAt())));
    }

    @Override
    public void appendAttemptStatus(AttemptStatus status) {
        bindTenant(status.tenantId());
        if (status.statusSequence() == 1 && status.state() == AttemptState.READY) {
            AttemptStatus persisted = currentAttemptStatus(
                    status.tenantId(), status.runId(), status.attemptNo())
                    .orElseThrow(() -> notFound("The Payroll attempt is unavailable."));
            if (persisted.statusSequence() != 1 || persisted.state() != AttemptState.READY) {
                throw conflict("The Payroll attempt READY fact differs.", null);
            }
            return;
        }
        int inserted = jdbc.update("""
                INSERT INTO pay_payroll_run_attempts (
                    tenant_id, run_id, attempt_no, attempt_id,
                    predecessor_attempt_no, status_sequence, attempt_state,
                    engine_version, command_receipt_id, failure_code, occurred_at)
                SELECT tenant_id, run_id, attempt_no, attempt_id,
                       predecessor_attempt_no, :sequence, :state,
                       engine_version, command_receipt_id, :failureCode, :occurredAt
                  FROM pay_payroll_run_attempts
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo AND status_sequence = 1
                """, params(status.tenantId(), status.runId())
                .addValue("attemptNo", status.attemptNo())
                .addValue("sequence", status.statusSequence())
                .addValue("state", status.state().name())
                .addValue("failureCode", status.failureCode())
                .addValue("occurredAt", timestamp(status.occurredAt())));
        if (inserted != 1) throw notFound("The Payroll attempt is unavailable.");
    }

    @Override
    public void appendRunRevision(RunRevision revision) {
        bindTenant(revision.tenantId());
        jdbc.update("""
                INSERT INTO pay_payroll_runs (
                    tenant_id, run_id, run_revision, run_type, pay_period_id,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest,
                    legal_entity_id, payroll_group_id, population_kind,
                    population_scope_digest, state, maker_actor_id,
                    maker_subject_public_id, latest_attempt_no, worker_count,
                    gross_amount, net_amount, currency, result_digest,
                    approval_case_id, decision_digest, approved_by_actor_id,
                    approved_at, audit_bundle_digest, command_receipt_id, recorded_at)
                VALUES (:tenantId, :runId, :runRevision, :runType, :payPeriodId,
                    :sourceSnapshotId, :sourceSnapshotRevision, :sourcePayloadDigest,
                    :legalEntityId, :payrollGroupId, :populationKind,
                    :populationScopeDigest, :state, :makerActorId,
                    :makerSubjectId, :latestAttemptNo, :workerCount,
                    :grossAmount, :netAmount, :currency, :resultDigest,
                    :approvalCaseId, :decisionDigest, :approvedByActorId,
                    :approvedAt, :auditBundleDigest, :receiptId, :recordedAt)
                """, runParameters(revision));
    }

    @Override
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

    @Override
    public void appendReceiptStatus(
            long tenantId, UUID receiptId, ReceiptStatus status, Instant occurredAt) {
        bindTenant(tenantId);
        ReceiptHead head = receiptHead(tenantId, receiptId);
        if (head.status() == status) return;
        appendReceiptRecord(tenantId, receiptId, status,
                "RECEIPT_STATUS_CHANGED",
                canonical.json(Map.of("status", status.name())), occurredAt);
    }

    @Override
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

    private ReceiptHead receiptHead(long tenantId, UUID receiptId) {
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

    private void appendReceiptRecord(
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

    @Override
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

    @Override
    public Optional<Lease> claim(
            long tenantId,
            UUID runId,
            int attemptNo,
            String workerInstanceId,
            Instant now,
            Duration leaseDuration) {
        lockRun(tenantId, runId);
        UUID attemptId = jdbc.queryForObject("""
                SELECT attempt_id
                  FROM pay_payroll_run_attempts
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                 ORDER BY status_sequence DESC LIMIT 1
                """, params(tenantId, runId).addValue("attemptNo", attemptNo), UUID.class);
        if (attemptId == null) throw notFound("The Payroll attempt is unavailable.");
        Optional<Lease> previous = jdbc.query("""
                SELECT tenant_id, run_id, attempt_no, attempt_id, claim_no, lease_token,
                       worker_instance_id, claimed_at, expires_at
                  FROM pay_payroll_run_workers
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                   AND record_kind IN ('LEASE', 'RENEWAL')
                 ORDER BY claim_no DESC, run_worker_record_internal_id DESC LIMIT 1
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new Lease(
                        result.getLong("tenant_id"),
                        result.getObject("run_id", UUID.class),
                        result.getInt("attempt_no"),
                        result.getObject("attempt_id", UUID.class),
                        result.getInt("claim_no"),
                        result.getObject("lease_token", UUID.class),
                        result.getString("worker_instance_id"),
                        result.getObject("claimed_at", OffsetDateTime.class).toInstant(),
                        result.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .stream().findFirst();
        if (previous.isPresent() && previous.get().expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        int claimNo = previous.map(value -> value.claimNo() + 1).orElse(1);
        Lease lease = new Lease(
                tenantId, runId, attemptNo, attemptId, claimNo, UUID.randomUUID(),
                workerInstanceId, now, now.plus(leaseDuration));
        jdbc.update("""
                INSERT INTO pay_payroll_run_workers (
                    tenant_id, run_id, attempt_no, attempt_id, record_kind, claim_no,
                    lease_token, worker_instance_id, claimed_at, expires_at,
                    checkpoint_no, next_input_ordinal, completed_worker_count,
                    checkpoint_digest, recorded_at)
                VALUES (:tenantId, :runId, :attemptNo, :attemptId, 'LEASE', :claimNo,
                    :leaseToken, :workerInstanceId, :claimedAt, :expiresAt,
                    NULL, NULL, NULL, NULL, :recordedAt)
                """, leaseParameters(lease).addValue("recordedAt", timestamp(now)));
        return Optional.of(lease);
    }

    @Override
    public Optional<Lease> renew(Lease lease, Instant now, Duration leaseDuration) {
        lockRun(lease.tenantId(), lease.runId());
        try {
            requireCurrentLease(lease);
        } catch (BaseException exception) {
            return Optional.empty();
        }
        Lease renewed = new Lease(
                lease.tenantId(), lease.runId(), lease.attemptNo(), lease.attemptId(),
                lease.claimNo(), lease.leaseToken(), lease.workerInstanceId(),
                lease.claimedAt(), now.plus(leaseDuration));
        int inserted = jdbc.update("""
                INSERT INTO pay_payroll_run_workers (
                    tenant_id, run_id, attempt_no, attempt_id, record_kind, claim_no,
                    lease_token, worker_instance_id, claimed_at, expires_at,
                    checkpoint_no, next_input_ordinal, completed_worker_count,
                    checkpoint_digest, recorded_at)
                VALUES (:tenantId, :runId, :attemptNo, :attemptId, 'RENEWAL', :claimNo,
                    :leaseToken, :workerInstanceId, :claimedAt, :expiresAt,
                    NULL, NULL, NULL, NULL, :recordedAt)
                """, leaseParameters(renewed).addValue("recordedAt", timestamp(now)));
        return inserted == 1 ? Optional.of(renewed) : Optional.empty();
    }

    @Override
    public void verifyLease(Lease lease) {
        requireCurrentLease(lease);
    }

    @Override
    public FrozenSnapshot snapshot(long tenantId, UUID runId, int attemptNo) {
        bindTenant(tenantId);
        SnapshotHeader header = jdbc.query("""
                SELECT input_snapshot_internal_id, source_snapshot_id,
                       source_snapshot_revision, source_payload_digest,
                       formula_set_digest, engine_version, function_set_version,
                       semantic_binding_digest, target_population_digest,
                       compensation_input_digest, pinned_at
                  FROM pay_input_snapshots
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new SnapshotHeader(
                        result.getLong("input_snapshot_internal_id"),
                        result.getObject("source_snapshot_id", UUID.class),
                        result.getLong("source_snapshot_revision"),
                        result.getString("source_payload_digest"),
                        result.getString("formula_set_digest"),
                        result.getString("engine_version"),
                        result.getString("function_set_version"),
                        result.getString("semantic_binding_digest"),
                        result.getString("target_population_digest"),
                        result.getString("compensation_input_digest"),
                        result.getObject("pinned_at", OffsetDateTime.class).toInstant()))
                .stream().findFirst().orElseThrow(() -> notFound(
                        "The frozen Payroll input snapshot is unavailable."));
        List<SourceInput> inputs = jdbc.query("""
                SELECT ref.compensation_plan_input_id, source.public_id,
                       source.source_event_id, source.snapshot_id,
                       source.snapshot_revision, source.snapshot_payload_digest,
                       source.plan_id, source.cycle_id, source.line_sequence,
                       ref.worker_id, ref.assignment_id, ref.component_code,
                       ref.currency, ref.approved_amount, source.effective_from,
                       source.effective_to, ref.source_payload_digest AS payload_digest
                  FROM pay_input_snapshot_compensation_refs ref
                  JOIN pay_compensation_plan_inputs source
                    ON source.tenant_id = ref.tenant_id
                   AND source.compensation_plan_input_id = ref.compensation_plan_input_id
                 WHERE ref.tenant_id = :tenantId
                   AND ref.input_snapshot_internal_id = :internalId
                 ORDER BY ref.input_ordinal
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("internalId", header.internalId()),
                (result, row) -> mapSourceInput(result));
        List<FormulaBinding> formulas = jdbc.query("""
                SELECT (entry ->> 'formulaId')::uuid AS formula_id,
                       (entry ->> 'version')::bigint AS formula_version,
                       entry ->> 'astDigest' AS ast_digest
                  FROM pay_input_snapshots snapshot,
                       LATERAL jsonb_array_elements(snapshot.formula_bindings) entry
                 WHERE snapshot.tenant_id = :tenantId
                   AND snapshot.input_snapshot_internal_id = :internalId
                 ORDER BY formula_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("internalId", header.internalId()),
                (result, row) -> new FormulaBinding(
                        result.getObject("formula_id", UUID.class),
                        result.getLong("formula_version"),
                        result.getString("ast_digest")));
        List<SemanticBinding> semanticBindings = jdbc.query("""
                SELECT semantic_binding::text AS semantic_binding
                  FROM pay_input_snapshot_compensation_refs
                 WHERE tenant_id = :tenantId
                   AND input_snapshot_internal_id = :internalId
                 ORDER BY input_ordinal
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("internalId", header.internalId()),
                (result, row) -> canonical.read(
                        result.getString("semantic_binding"), SemanticBinding.class));
        if (!PayrollCalculationModels.ENGINE_VERSION.equals(header.engineVersion())
                || semanticBindings.size() != inputs.size()
                || semanticBindings.stream().anyMatch(binding ->
                !header.engineVersion().equals(binding.engineVersion())
                        || !header.functionSetVersion().equals(
                        binding.functionSetVersion()))) {
            throw conflict("The frozen Payroll semantic binding set is invalid.", null);
        }
        String aggregateBindingDigest = canonical.digest(Map.of(
                "schema", "PayrollSemanticBindingSet.v1",
                "engineVersion", header.engineVersion(),
                "bindingDigests", semanticBindings.stream()
                        .map(SemanticBinding::bindingDigest).toList()));
        if (!aggregateBindingDigest.equals(header.semanticBindingDigest())) {
            throw conflict("The frozen Payroll semantic binding digest is invalid.", null);
        }
        return new FrozenSnapshot(
                tenantId, runId, attemptNo, header.snapshotId(),
                header.snapshotRevision(), header.payloadDigest(),
                header.formulaSetDigest(), header.targetPopulationDigest(),
                header.compensationInputDigest(), header.semanticBindingDigest(),
                inputs, formulas, semanticBindings, header.pinnedAt());
    }

    @Override
    public Optional<Checkpoint> latestCheckpoint(long tenantId, UUID runId, int attemptNo) {
        bindTenant(tenantId);
        return jdbc.query("""
                SELECT checkpoint_no, next_input_ordinal,
                       completed_worker_count, checkpoint_digest
                  FROM pay_payroll_run_workers
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo AND record_kind = 'CHECKPOINT'
                 ORDER BY checkpoint_no DESC LIMIT 1
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new Checkpoint(
                        result.getInt("checkpoint_no"),
                        result.getInt("next_input_ordinal"),
                        result.getInt("completed_worker_count"),
                        result.getString("checkpoint_digest")))
                .stream().findFirst();
    }

    @Override
    public void verifyCheckpoint(
            Lease lease, Checkpoint checkpoint, CalculationOutcome outcome) {
        lockRun(lease.tenantId(), lease.runId());
        requireCurrentLease(lease);
        int prefixSize = checkpoint.nextInputOrdinal() - 1;
        if (prefixSize < 1 || prefixSize > outcome.lines().size()) {
            throw conflict("The Payroll checkpoint prefix is out of bounds.", null);
        }
        List<DurableLine> durable = durableLines(lease);
        if (durable.size() != prefixSize) {
            throw conflict("The Payroll checkpoint durable prefix size changed.", null);
        }
        verifyLines(durable, outcome.lines().subList(0, prefixSize));
        int completedWorkers = (int) durable.stream().map(DurableLine::workerId)
                .distinct().count();
        String digest = canonical.checkpointDigest(
                lease.runId(), lease.attemptNo(), checkpoint.checkpointNo(),
                checkpoint.nextInputOrdinal(), durable.stream()
                .map(DurableLine::lineDigest).toList());
        if (completedWorkers != checkpoint.completedWorkerCount()
                || !digest.equals(checkpoint.checkpointDigest())) {
            throw conflict("The Payroll checkpoint digest does not match durable facts.", null);
        }
    }

    @Override
    public void appendProgress(
            Lease lease,
            CalculationOutcome outcome,
            int fromInputOrdinal,
            int toInputOrdinal,
            Checkpoint checkpoint,
            Instant recordedAt) {
        lockRun(lease.tenantId(), lease.runId());
        requireCurrentLease(lease);
        if (fromInputOrdinal < 1 || toInputOrdinal < fromInputOrdinal
                || toInputOrdinal > outcome.lines().size()
                || checkpoint.nextInputOrdinal() != toInputOrdinal + 1) {
            throw invalid("Payroll worker checkpoint bounds are invalid.");
        }
        for (int index = fromInputOrdinal - 1; index < toInputOrdinal; index++) {
            CalculatedLine line = outcome.lines().get(index);
            boolean insertedEveryTrace = true;
            for (PayrollCalculationModels.CalculatedTraceNode trace : line.traceNodes()) {
                int traceInserted = jdbc.update("""
                        INSERT INTO pay_calculation_trace_nodes (
                            tenant_id, run_id, attempt_no,
                            input_snapshot_internal_id, trace_id, trace_sequence,
                            parent_trace_id, semantic_binding_digest,
                            worker_id, compensation_plan_input_id,
                            element_id, element_version, formula_id, formula_version,
                            formula_ast_digest, formula_input_digest,
                            formula_output_digest, line_category,
                            source_payload_digest, unrounded_output_amount,
                            rounded_output_amount, currency, rounding_stage,
                            rounding_scale, rounding_mode, rounding_increment,
                            rounding_policy_digest, trace_digest, engine_version,
                            recorded_at)
                        SELECT :tenantId, :runId, :attemptNo,
                               snapshot.input_snapshot_internal_id,
                               :traceId, :traceSequence, :parentTraceId,
                               :semanticBindingDigest,
                               :workerId, :sourceInputId, :elementId,
                               :elementVersion, :formulaId, :formulaVersion,
                               :formulaAstDigest, :formulaInputDigest,
                               :formulaOutputDigest, :lineCategory,
                               :sourcePayloadDigest, :unroundedAmount,
                               :roundedAmount, :currency, :roundingStage,
                               :roundingScale, :roundingMode, :roundingIncrement,
                               :roundingPolicyDigest, :nodeTraceDigest,
                               :engineVersion, :recordedAt
                          FROM pay_input_snapshots snapshot
                          JOIN pay_input_snapshot_compensation_refs ref
                            ON ref.tenant_id = snapshot.tenant_id
                           AND ref.input_snapshot_internal_id =
                               snapshot.input_snapshot_internal_id
                           AND ref.compensation_plan_input_id = :sourceInputId
                           AND ref.semantic_binding_digest = :semanticBindingDigest
                         WHERE snapshot.tenant_id = :tenantId
                           AND snapshot.run_id = :runId
                           AND snapshot.attempt_no = :attemptNo
                        ON CONFLICT (tenant_id, run_id, attempt_no,
                            compensation_plan_input_id, trace_sequence) DO NOTHING
                        """, traceParameters(lease, line, trace, recordedAt));
                insertedEveryTrace &= traceInserted == 1;
            }
            int lineInserted = jdbc.update("""
                    INSERT INTO pay_result_lines (
                        tenant_id, run_id, attempt_no,
                        input_snapshot_internal_id, result_line_id,
                        compensation_plan_input_id, worker_id, component_code,
                        worker_result_id,
                        source_payload_digest, semantic_binding_digest,
                        element_id, element_version,
                        formula_id, formula_version,
                        formula_ast_digest, line_category,
                        formula_input_digest, formula_output_digest, currency, amount,
                        terminal_trace_id, trace_digest, result_line_digest, recorded_at)
                    SELECT :tenantId, :runId, :attemptNo,
                           snapshot.input_snapshot_internal_id, :resultLineId,
                           :sourceInputId, :workerId, :componentCode,
                           :workerResultId,
                           :sourcePayloadDigest, :semanticBindingDigest,
                           :elementId, :elementVersion,
                           :formulaId, :formulaVersion,
                           :formulaAstDigest, :lineCategory,
                           :formulaInputDigest, :formulaOutputDigest, :currency, :amount,
                           :terminalTraceId, :traceDigest, :lineDigest, :recordedAt
                      FROM pay_input_snapshots snapshot
                      JOIN pay_input_snapshot_compensation_refs ref
                        ON ref.tenant_id = snapshot.tenant_id
                       AND ref.input_snapshot_internal_id =
                           snapshot.input_snapshot_internal_id
                       AND ref.compensation_plan_input_id = :sourceInputId
                       AND ref.semantic_binding_digest = :semanticBindingDigest
                     WHERE snapshot.tenant_id = :tenantId
                       AND snapshot.run_id = :runId
                       AND snapshot.attempt_no = :attemptNo
                    ON CONFLICT (tenant_id, run_id, attempt_no,
                        compensation_plan_input_id) DO NOTHING
                    """, lineParameters(lease, line, recordedAt));
            if (lineInserted == 0 || !insertedEveryTrace) {
                verifyExistingLine(lease, line);
            }
        }
        jdbc.update("""
                INSERT INTO pay_payroll_run_workers (
                    tenant_id, run_id, attempt_no, attempt_id, record_kind, claim_no,
                    lease_token, worker_instance_id, claimed_at, expires_at,
                    checkpoint_no, next_input_ordinal, completed_worker_count,
                    checkpoint_digest, recorded_at)
                VALUES (:tenantId, :runId, :attemptNo, :attemptId, 'CHECKPOINT', :claimNo,
                    :leaseToken, :workerInstanceId, :claimedAt, :expiresAt,
                    :checkpointNo, :nextOrdinal, :workerCount, :digest, :recordedAt)
                """, leaseParameters(lease)
                .addValue("checkpointNo", checkpoint.checkpointNo())
                .addValue("nextOrdinal", checkpoint.nextInputOrdinal())
                .addValue("workerCount", checkpoint.completedWorkerCount())
                .addValue("digest", checkpoint.checkpointDigest())
                .addValue("recordedAt", timestamp(recordedAt)));
    }

    private void verifyExistingLine(Lease lease, CalculatedLine line) {
        DurableLine existing = durableLines(lease).stream()
                .filter(value -> value.sourceInputId() == line.sourceInputId())
                .findFirst().orElse(null);
        if (existing == null || !lineMatches(existing, line)) {
            throw conflict("A recovered Payroll result differs from its durable fact.", null);
        }
    }

    private List<DurableLine> durableLines(Lease lease) {
        return durableLines(lease.tenantId(), lease.runId(), lease.attemptNo());
    }

    private List<DurableLine> durableLines(
            long tenantId, UUID runId, int attemptNo) {
        List<DurableLineHeader> headers = jdbc.query("""
                SELECT ref.input_ordinal, result.result_line_id,
                       result.compensation_plan_input_id, result.worker_id,
                       result.worker_result_id, result.component_code,
                       result.source_payload_digest,
                       result.semantic_binding_digest,
                       result.element_id,
                       result.element_version, result.formula_id,
                       result.formula_version, result.formula_ast_digest,
                       result.line_category, result.currency, result.amount,
                       result.formula_input_digest, result.formula_output_digest,
                       result.terminal_trace_id, result.trace_digest,
                       result.result_line_digest
                  FROM pay_input_snapshots snapshot
                  JOIN pay_input_snapshot_compensation_refs ref
                    ON ref.tenant_id = snapshot.tenant_id
                   AND ref.input_snapshot_internal_id = snapshot.input_snapshot_internal_id
                  JOIN pay_result_lines result
                    ON result.tenant_id = snapshot.tenant_id
                   AND result.input_snapshot_internal_id =
                       snapshot.input_snapshot_internal_id
                   AND result.compensation_plan_input_id = ref.compensation_plan_input_id
                 WHERE snapshot.tenant_id = :tenantId
                   AND snapshot.run_id = :runId
                   AND snapshot.attempt_no = :attemptNo
                 ORDER BY ref.input_ordinal
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new DurableLineHeader(
                        result.getInt("input_ordinal"),
                        result.getObject("result_line_id", UUID.class),
                        result.getLong("compensation_plan_input_id"),
                        result.getObject("worker_id", UUID.class),
                        result.getObject("worker_result_id", UUID.class),
                        result.getString("component_code"),
                        result.getString("source_payload_digest"),
                        result.getString("semantic_binding_digest"),
                        result.getObject("element_id", UUID.class),
                        result.getLong("element_version"),
                        result.getObject("formula_id", UUID.class),
                        result.getLong("formula_version"),
                        result.getString("formula_ast_digest"),
                        result.getString("line_category"),
                        result.getString("currency"),
                        result.getBigDecimal("amount"),
                        result.getString("formula_input_digest"),
                        result.getString("formula_output_digest"),
                        result.getObject("terminal_trace_id", UUID.class),
                        result.getString("trace_digest"),
                        result.getString("result_line_digest")));
        Map<Long, List<DurableTraceNode>> traces = new LinkedHashMap<>();
        jdbc.query("""
                SELECT compensation_plan_input_id, trace_id, trace_sequence,
                       parent_trace_id, semantic_binding_digest,
                       element_id, element_version,
                       formula_id, formula_version, formula_ast_digest,
                       line_category, source_payload_digest,
                       formula_input_digest, formula_output_digest,
                       unrounded_output_amount, rounded_output_amount, currency,
                       rounding_stage, rounding_scale, rounding_mode,
                       rounding_increment, rounding_policy_digest,
                       trace_digest, engine_version
                  FROM pay_calculation_trace_nodes
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                 ORDER BY compensation_plan_input_id, trace_sequence
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (org.springframework.jdbc.core.RowCallbackHandler) result ->
                        traces.computeIfAbsent(
                        result.getLong("compensation_plan_input_id"),
                        ignored -> new ArrayList<>()).add(new DurableTraceNode(
                        result.getObject("trace_id", UUID.class),
                        result.getInt("trace_sequence"),
                        result.getObject("parent_trace_id", UUID.class),
                        result.getString("semantic_binding_digest"),
                        result.getObject("element_id", UUID.class),
                        result.getLong("element_version"),
                        result.getObject("formula_id", UUID.class),
                        result.getObject("formula_version", Long.class),
                        result.getString("formula_ast_digest"),
                        result.getString("line_category"),
                        result.getString("source_payload_digest"),
                        result.getString("formula_input_digest"),
                        result.getString("formula_output_digest"),
                        result.getBigDecimal("unrounded_output_amount"),
                        result.getBigDecimal("rounded_output_amount"),
                        result.getString("currency"),
                        result.getString("rounding_stage"),
                        result.getInt("rounding_scale"),
                        result.getString("rounding_mode"),
                        result.getBigDecimal("rounding_increment"),
                        result.getString("rounding_policy_digest"),
                        result.getString("trace_digest"),
                        result.getString("engine_version"))));
        return headers.stream().map(header -> new DurableLine(
                header.inputOrdinal(), header.resultLineId(), header.sourceInputId(),
                header.workerId(), header.workerResultId(), header.componentCode(),
                header.sourcePayloadDigest(), header.semanticBindingDigest(),
                header.elementId(),
                header.elementVersion(), header.formulaId(), header.formulaVersion(),
                header.formulaAstDigest(), header.category(), header.currency(),
                header.amount(), header.formulaInputDigest(),
                header.formulaOutputDigest(), header.terminalTraceId(),
                header.traceDigest(), header.lineDigest(),
                List.copyOf(traces.getOrDefault(
                        header.sourceInputId(), List.of())))).toList();
    }

    private void verifyLines(
            List<DurableLine> durable, List<CalculatedLine> expected) {
        if (durable.size() != expected.size()) {
            throw conflict("Payroll durable result line count changed.", null);
        }
        for (int index = 0; index < expected.size(); index++) {
            DurableLine actual = durable.get(index);
            CalculatedLine line = expected.get(index);
            if (actual.inputOrdinal() != index + 1 || !lineMatches(actual, line)) {
                throw conflict(
                        "A durable Payroll result or trace differs from recomputation.", null);
            }
        }
    }

    private boolean lineMatches(DurableLine actual, CalculatedLine line) {
        if (!actual.resultLineId().equals(line.resultLineId())
                || actual.sourceInputId() != line.sourceInputId()
                || !actual.workerId().equals(line.workerId())
                || !actual.workerResultId().equals(line.workerResultId())
                || !actual.componentCode().equals(line.componentCode())
                || !actual.sourcePayloadDigest().equals(line.sourcePayloadDigest())
                || !actual.semanticBindingDigest().equals(
                line.semanticBindingDigest())
                || !actual.elementId().equals(line.elementId())
                || actual.elementVersion() != line.elementVersion()
                || !actual.formulaId().equals(line.formulaId())
                || actual.formulaVersion() != line.formulaVersion()
                || !actual.formulaAstDigest().equals(line.formulaAstDigest())
                || !actual.category().equals(line.category().name())
                || !actual.currency().equals(line.currency())
                || actual.amount().compareTo(line.amount()) != 0
                || !actual.formulaInputDigest().equals(line.formulaInputDigest())
                || !actual.formulaOutputDigest().equals(line.formulaOutputDigest())
                || !actual.terminalTraceId().equals(line.terminalTraceId())
                || !actual.lineDigest().equals(line.resultLineDigest())
                || !actual.traceDigest().equals(line.traceDigest())
                || actual.traces().size() != line.traceNodes().size()) {
            return false;
        }
        for (int index = 0; index < line.traceNodes().size(); index++) {
            if (!traceMatches(
                    actual.traces().get(index), line.traceNodes().get(index), line)) {
                return false;
            }
        }
        return true;
    }

    private boolean traceMatches(
            DurableTraceNode actual,
            PayrollCalculationModels.CalculatedTraceNode expected,
            CalculatedLine line) {
        return actual.traceId().equals(expected.traceId())
                && actual.sequence() == expected.sequence()
                && java.util.Objects.equals(
                actual.parentTraceId(), expected.parentTraceId())
                && actual.semanticBindingDigest().equals(
                expected.semanticBindingDigest())
                && actual.elementId().equals(line.elementId())
                && actual.elementVersion() == line.elementVersion()
                && java.util.Objects.equals(actual.formulaId(), expected.formulaId())
                && java.util.Objects.equals(
                actual.formulaVersion(), expected.formulaVersion())
                && java.util.Objects.equals(
                actual.formulaAstDigest(), expected.formulaAstDigest())
                && actual.category().equals(line.category().name())
                && actual.sourcePayloadDigest().equals(line.sourcePayloadDigest())
                && actual.inputDigest().equals(expected.inputDigest())
                && actual.outputDigest().equals(expected.outputDigest())
                && actual.unroundedAmount().compareTo(expected.unroundedAmount()) == 0
                && actual.roundedAmount().compareTo(expected.roundedAmount()) == 0
                && actual.currency().equals(expected.currency())
                && actual.roundingStage().equals(expected.stage().name())
                && actual.roundingScale() == expected.roundingScale()
                && actual.roundingMode().equals(expected.roundingMode().name())
                && actual.roundingIncrement().compareTo(
                expected.roundingIncrement()) == 0
                && actual.roundingPolicyDigest().equals(
                expected.roundingPolicyDigest())
                && actual.traceDigest().equals(expected.traceDigest())
                && PayrollCalculationModels.ENGINE_VERSION.equals(actual.engineVersion());
    }

    private void verifyWorkers(
            long tenantId, UUID runId, int attemptNo,
            List<WorkerResult> expected,
            String calculationResultDigest) {
        Integer wrongResultDigest = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_worker_results
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                   AND calculation_result_digest <> :resultDigest
                """, params(tenantId, runId)
                .addValue("attemptNo", attemptNo)
                .addValue("resultDigest", calculationResultDigest), Integer.class);
        if (wrongResultDigest == null || wrongResultDigest != 0) {
            throw conflict("Payroll worker facts use a different result digest.", null);
        }
        List<WorkerResult> durable = jdbc.query("""
                SELECT worker_result_id, worker_id, currency,
                       gross_amount, deduction_amount,
                       net_amount, employer_cost_amount, line_count,
                       worker_result_digest, calculation_result_digest
                  FROM pay_worker_results
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                 ORDER BY worker_id
                """, params(tenantId, runId).addValue("attemptNo", attemptNo),
                (result, row) -> new WorkerResult(
                        result.getObject("worker_result_id", UUID.class),
                        result.getObject("worker_id", UUID.class),
                        result.getString("currency"),
                        result.getBigDecimal("gross_amount"),
                        result.getBigDecimal("deduction_amount"),
                        result.getBigDecimal("net_amount"),
                        result.getBigDecimal("employer_cost_amount"),
                        result.getInt("line_count"),
                        result.getString("worker_result_digest")));
        List<WorkerResult> sortedExpected = expected.stream()
                .sorted(Comparator.comparing(WorkerResult::workerId)).toList();
        if (durable.size() != sortedExpected.size()) {
            throw conflict("Payroll durable worker result count changed.", null);
        }
        for (int index = 0; index < sortedExpected.size(); index++) {
            WorkerResult actual = durable.get(index);
            WorkerResult expectedWorker = sortedExpected.get(index);
            if (!actual.workerResultId().equals(expectedWorker.workerResultId())
                    || !actual.workerId().equals(expectedWorker.workerId())
                    || !actual.currency().equals(expectedWorker.currency())
                    || actual.gross().compareTo(expectedWorker.gross()) != 0
                    || actual.deductions().compareTo(
                    expectedWorker.deductions()) != 0
                    || actual.net().compareTo(expectedWorker.net()) != 0
                    || actual.employerCost().compareTo(
                    expectedWorker.employerCost()) != 0
                    || actual.lineCount() != expectedWorker.lineCount()
                    || !actual.workerResultDigest().equals(
                    expectedWorker.workerResultDigest())) {
                throw conflict(
                        "A durable Payroll worker result differs from recomputation.", null);
            }
        }
    }

    @Override
    public void complete(
            Lease lease,
            CalculationOutcome outcome,
            RunRevision completedRun,
            UUID receiptId,
            EventFact event,
            String auditPayload,
            Instant completedAt) {
        lockRun(lease.tenantId(), lease.runId());
        requireCurrentLease(lease);
        Integer lineCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_result_lines
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :attemptNo
                """, params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo()), Integer.class);
        if (lineCount == null || lineCount != outcome.lines().size()) {
            throw conflict("Payroll calculation cannot complete before all durable lines.", null);
        }
        verifyLines(durableLines(lease), outcome.lines());
        for (WorkerResult worker : outcome.workers()) {
            jdbc.update("""
                    INSERT INTO pay_worker_results (
                        tenant_id, run_id, attempt_no, worker_result_id,
                        worker_id, currency,
                        gross_amount, deduction_amount, net_amount,
                        employer_cost_amount, line_count,
                        worker_result_digest, calculation_result_digest, recorded_at)
                    VALUES (:tenantId, :runId, :attemptNo, :workerResultId,
                        :workerId, :currency,
                        :gross, :deductions, :net, :employerCost, :lineCount,
                        :digest, :calculationResultDigest, :recordedAt)
                    ON CONFLICT (tenant_id, run_id, attempt_no, worker_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", lease.tenantId()).addValue("runId", lease.runId())
                    .addValue("attemptNo", lease.attemptNo())
                    .addValue("workerResultId", worker.workerResultId())
                    .addValue("workerId", worker.workerId())
                    .addValue("currency", worker.currency())
                    .addValue("gross", worker.gross())
                    .addValue("deductions", worker.deductions())
                    .addValue("net", worker.net())
                    .addValue("employerCost", worker.employerCost())
                    .addValue("lineCount", worker.lineCount())
                    .addValue("digest", worker.workerResultDigest())
                    .addValue("calculationResultDigest", outcome.resultDigest())
                    .addValue("recordedAt", timestamp(completedAt)));
        }
        verifyWorkers(lease.tenantId(), lease.runId(), lease.attemptNo(),
                outcome.workers(), outcome.resultDigest());
        AttemptStatus status = currentAttemptStatus(
                lease.tenantId(), lease.runId(), lease.attemptNo()).orElseThrow();
        appendAttemptStatus(new AttemptStatus(
                lease.tenantId(), lease.runId(), lease.attemptNo(),
                status.statusSequence() + 1, AttemptState.VALIDATED, null, completedAt));
        appendReceiptStatus(lease.tenantId(), receiptId, ReceiptStatus.SUCCEEDED, completedAt);
        appendRunRevision(completedRun);
        PersistedReceipt receipt = receipt(lease.tenantId(), receiptId).orElseThrow();
        appendAudit(receipt, completedRun.revision(),
                "PAYROLL_CALCULATION_COMPLETED", auditPayload, completedAt);
        appendEvent(lease.tenantId(), event);
    }

    @Override
    public void terminalFailure(
            Lease lease,
            UUID receiptId,
            AttemptState state,
            String failureCode,
            RunRevision failedRun,
            String auditPayload,
            Instant occurredAt) {
        lockRun(lease.tenantId(), lease.runId());
        requireCurrentLease(lease);
        if (!List.of(AttemptState.FAILED, AttemptState.RESULT_UNKNOWN).contains(state)) {
            throw new IllegalArgumentException("terminal worker state is invalid");
        }
        RunRevision run = currentRun(lease.tenantId(), lease.runId()).orElseThrow();
        if (run.latestAttemptNo() != lease.attemptNo()
                || !List.of(RunState.READY, RunState.CALCULATING).contains(run.state())) {
            throw conflict("The Payroll worker was fenced before terminalization.", null);
        }
        AttemptStatus current = currentAttemptStatus(
                lease.tenantId(), lease.runId(), lease.attemptNo())
                .orElseThrow();
        appendAttemptStatus(new AttemptStatus(
                lease.tenantId(), lease.runId(), lease.attemptNo(),
                current.statusSequence() + 1,
                state, failureCode, occurredAt));
        appendReceiptStatus(lease.tenantId(), receiptId,
                state == AttemptState.RESULT_UNKNOWN
                        ? ReceiptStatus.RESULT_UNKNOWN : ReceiptStatus.FAILED,
                occurredAt);
        appendRunRevision(failedRun);
        PersistedReceipt receipt = receipt(lease.tenantId(), receiptId).orElseThrow();
        appendAudit(receipt, failedRun.revision(),
                state == AttemptState.RESULT_UNKNOWN
                        ? "PAYROLL_RESULT_UNKNOWN" : "PAYROLL_CALCULATION_FAILED",
                auditPayload, occurredAt);
    }

    @Override
    public boolean completionExists(
            long tenantId, UUID runId, int attemptNo, CalculationOutcome outcome) {
        bindTenant(tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pay_payroll_runs run
                  JOIN pay_outbox_events event
                    ON event.tenant_id = run.tenant_id
                   AND event.run_id = run.run_id
                   AND event.run_revision = run.run_revision
                 WHERE run.tenant_id = :tenantId AND run.run_id = :runId
                   AND event.attempt_no = :attemptNo
                   AND run.state = 'VALIDATED' AND run.result_digest = :resultDigest
                   AND event.event_type = 'PayrollCalculated'
                   AND event.event_payload -> 'data' ->> 'resultDigest' = :resultDigest
                   AND (event.event_payload -> 'data' ->> 'workerCount')::INTEGER
                       = :workerCount
                   AND event.event_payload -> 'data' -> 'controlTotals'
                       = CAST(:controlTotals AS jsonb)
                """, params(tenantId, runId)
                .addValue("attemptNo", attemptNo)
                .addValue("resultDigest", outcome.resultDigest())
                .addValue("workerCount", outcome.workers().size())
                .addValue("controlTotals", canonical.json(outcome.controlTotals().stream()
                        .map(total -> Map.of(
                                "code", total.code(),
                                "amount", total.amount().toPlainString(),
                                "currency", total.currency()))
                        .toList())), Integer.class);
        if (count == null || count == 0) return false;
        if (count != 1) {
            throw conflict("Payroll completion evidence is ambiguous.", null);
        }
        verifyLines(durableLines(tenantId, runId, attemptNo), outcome.lines());
        verifyWorkers(tenantId, runId, attemptNo, outcome.workers(),
                outcome.resultDigest());
        return true;
    }

    @Override
    public long outboxCount(long tenantId, UUID runId, String eventType) {
        bindTenant(tenantId);
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pay_outbox_events
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND event_type = :eventType
                """, params(tenantId, runId).addValue("eventType", eventType), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public List<PayrollCalculationStore.ClaimableAttempt> claimable(
            long tenantId,
            int limit,
            String workerInstanceId,
            Instant now,
            Duration leaseDuration) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Payroll claim poll bound is invalid");
        }
        if (workerInstanceId == null || workerInstanceId.isBlank()
                || workerInstanceId.length() > 160
                || now == null
                || leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMillis(100)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Payroll claim lease bounds are invalid");
        }
        return jdbc.query("""
                SELECT tenant_id, run_id, attempt_no, attempt_id, claim_no,
                       lease_token, worker_instance_id, claimed_at, expires_at
                  FROM pay_v50_claim_attempts(
                       :tenantId, :workerInstanceId, :limit, :leaseMillis)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("workerInstanceId", workerInstanceId)
                .addValue("limit", limit)
                .addValue("leaseMillis", leaseDuration.toMillis()),
                (result, row) -> new PayrollCalculationStore.ClaimableAttempt(
                        new Lease(
                                result.getLong("tenant_id"),
                                result.getObject("run_id", UUID.class),
                                result.getInt("attempt_no"),
                                result.getObject("attempt_id", UUID.class),
                                result.getInt("claim_no"),
                                result.getObject("lease_token", UUID.class),
                                result.getString("worker_instance_id"),
                                result.getObject("claimed_at", OffsetDateTime.class).toInstant(),
                                result.getObject("expires_at", OffsetDateTime.class).toInstant())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayrollCalculationStore.RecoveryCandidate> recoveryCandidates(
            long tenantId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Payroll recovery poll bound is invalid");
        }
        return jdbc.query("""
                SELECT tenant_id, run_id
                  FROM pay_v50_recovery_candidates(:tenantId, :limit)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit),
                (result, row) -> new PayrollCalculationStore.RecoveryCandidate(
                        result.getLong("tenant_id"),
                        result.getObject("run_id", UUID.class)));
    }

    private void requireCurrentLease(Lease lease) {
        bindTenant(lease.tenantId());
        Integer count = jdbc.queryForObject("""
                WITH current_owner AS (
                    SELECT worker.*
                      FROM pay_payroll_run_workers worker
                     WHERE worker.tenant_id = :tenantId AND worker.run_id = :runId
                       AND worker.attempt_no = :attemptNo
                       AND worker.record_kind IN ('LEASE', 'RENEWAL')
                     ORDER BY worker.claim_no DESC,
                              worker.run_worker_record_internal_id DESC LIMIT 1
                ), current_attempt AS (
                    SELECT attempt_id
                      FROM pay_payroll_run_attempts
                     WHERE tenant_id = :tenantId AND run_id = :runId
                       AND attempt_no = :attemptNo
                     ORDER BY status_sequence DESC LIMIT 1
                )
                SELECT COUNT(*)
                  FROM current_owner owner_record
                  JOIN current_attempt attempt ON attempt.attempt_id = owner_record.attempt_id
                 WHERE owner_record.attempt_id = :attemptId
                   AND owner_record.claim_no = :claimNo
                   AND owner_record.lease_token = :leaseToken
                   AND owner_record.worker_instance_id = :workerInstanceId
                   AND owner_record.expires_at > clock_timestamp()
                """, params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo())
                .addValue("attemptId", lease.attemptId())
                .addValue("claimNo", lease.claimNo())
                .addValue("workerInstanceId", lease.workerInstanceId())
                .addValue("leaseToken", lease.leaseToken()), Integer.class);
        if (count == null || count != 1) {
            throw conflict("The Payroll worker lease is expired or fenced.", null);
        }
    }

    private MapSqlParameterSource receiptParameters(PersistedReceipt persisted) {
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

    private MapSqlParameterSource leaseParameters(Lease lease) {
        return params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo())
                .addValue("attemptId", lease.attemptId())
                .addValue("claimNo", lease.claimNo())
                .addValue("leaseToken", lease.leaseToken())
                .addValue("workerInstanceId", lease.workerInstanceId())
                .addValue("claimedAt", timestamp(lease.claimedAt()))
                .addValue("expiresAt", timestamp(lease.expiresAt()));
    }

    private MapSqlParameterSource lineParameters(
            Lease lease, CalculatedLine line, Instant recordedAt) {
        return params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo())
                .addValue("resultLineId", line.resultLineId())
                .addValue("sourceInputId", line.sourceInputId())
                .addValue("workerId", line.workerId())
                .addValue("workerResultId", line.workerResultId())
                .addValue("componentCode", line.componentCode())
                .addValue("sourcePayloadDigest", line.sourcePayloadDigest())
                .addValue("semanticBindingDigest", line.semanticBindingDigest())
                .addValue("elementId", line.elementId())
                .addValue("elementVersion", line.elementVersion())
                .addValue("formulaId", line.formulaId())
                .addValue("formulaVersion", line.formulaVersion())
                .addValue("formulaAstDigest", line.formulaAstDigest())
                .addValue("lineCategory", line.category().name())
                .addValue("formulaInputDigest", line.formulaInputDigest())
                .addValue("formulaOutputDigest", line.formulaOutputDigest())
                .addValue("currency", line.currency()).addValue("amount", line.amount())
                .addValue("terminalTraceId", line.terminalTraceId())
                .addValue("traceDigest", line.traceDigest())
                .addValue("lineDigest", line.resultLineDigest())
                .addValue("recordedAt", timestamp(recordedAt));
    }

    private MapSqlParameterSource traceParameters(
            Lease lease,
            CalculatedLine line,
            PayrollCalculationModels.CalculatedTraceNode trace,
            Instant recordedAt) {
        return params(lease.tenantId(), lease.runId())
                .addValue("attemptNo", lease.attemptNo())
                .addValue("sourceInputId", line.sourceInputId())
                .addValue("workerId", line.workerId())
                .addValue("elementId", line.elementId())
                .addValue("elementVersion", line.elementVersion())
                .addValue("lineCategory", line.category().name())
                .addValue("sourcePayloadDigest", line.sourcePayloadDigest())
                .addValue("traceId", trace.traceId())
                .addValue("traceSequence", trace.sequence())
                .addValue("parentTraceId", trace.parentTraceId())
                .addValue("semanticBindingDigest", trace.semanticBindingDigest())
                .addValue("formulaId", trace.formulaId())
                .addValue("formulaVersion", trace.formulaVersion())
                .addValue("formulaAstDigest", trace.formulaAstDigest())
                .addValue("formulaInputDigest", trace.inputDigest())
                .addValue("formulaOutputDigest", trace.outputDigest())
                .addValue("unroundedAmount", trace.unroundedAmount())
                .addValue("roundedAmount", trace.roundedAmount())
                .addValue("currency", trace.currency())
                .addValue("roundingStage", trace.stage().name())
                .addValue("roundingScale", trace.roundingScale())
                .addValue("roundingMode", trace.roundingMode().name())
                .addValue("roundingIncrement", trace.roundingIncrement())
                .addValue("roundingPolicyDigest", trace.roundingPolicyDigest())
                .addValue("nodeTraceDigest", trace.traceDigest())
                .addValue("engineVersion", PayrollCalculationModels.ENGINE_VERSION)
                .addValue("recordedAt", timestamp(recordedAt));
    }

    private MapSqlParameterSource runParameters(RunRevision revision) {
        return params(revision.tenantId(), revision.runId())
                .addValue("runRevision", revision.revision())
                .addValue("runType", revision.runType().name())
                .addValue("payPeriodId", revision.payPeriodId())
                .addValue("sourceSnapshotId", revision.sourceSnapshotId())
                .addValue("sourceSnapshotRevision", revision.sourceSnapshotRevision())
                .addValue("sourcePayloadDigest", revision.sourcePayloadDigest())
                .addValue("legalEntityId", revision.legalEntityId())
                .addValue("payrollGroupId", revision.payrollGroupId())
                .addValue("populationKind", revision.populationKind())
                .addValue("populationScopeDigest", revision.populationScopeDigest())
                .addValue("state", revision.state().name())
                .addValue("makerActorId", revision.makerActorId())
                .addValue("makerSubjectId", revision.makerSubjectPublicId())
                .addValue("latestAttemptNo", revision.latestAttemptNo())
                .addValue("workerCount", revision.workerCount())
                .addValue("grossAmount", revision.gross())
                .addValue("netAmount", revision.net())
                .addValue("currency", revision.currency())
                .addValue("resultDigest", revision.resultDigest())
                .addValue("approvalCaseId", revision.approvalCaseId())
                .addValue("decisionDigest", revision.decisionDigest())
                .addValue("approvedByActorId", revision.approvedByActorId())
                .addValue("approvedAt", revision.approvedAt() == null
                        ? null : timestamp(revision.approvedAt()))
                .addValue("auditBundleDigest", revision.auditBundleDigest())
                .addValue("receiptId", revision.commandReceiptId())
                .addValue("recordedAt", timestamp(revision.recordedAt()));
    }

    private RunRevision mapRun(java.sql.ResultSet result) throws java.sql.SQLException {
        OffsetDateTime approved = result.getObject("approved_at", OffsetDateTime.class);
        return new RunRevision(
                result.getLong("tenant_id"), result.getObject("run_id", UUID.class),
                result.getLong("run_revision"),
                RunType.valueOf(result.getString("run_type")),
                result.getObject("pay_period_id", UUID.class),
                result.getObject("source_snapshot_id", UUID.class),
                result.getLong("source_snapshot_revision"),
                result.getString("source_payload_digest"),
                result.getObject("legal_entity_id", UUID.class),
                result.getObject("payroll_group_id", UUID.class),
                result.getString("population_kind"),
                result.getString("population_scope_digest"),
                RunState.valueOf(result.getString("state")),
                result.getLong("maker_actor_id"),
                result.getObject("maker_subject_public_id", UUID.class),
                result.getInt("latest_attempt_no"), result.getInt("worker_count"),
                result.getBigDecimal("gross_amount"), result.getBigDecimal("net_amount"),
                result.getString("currency"), result.getString("result_digest"),
                result.getObject("approval_case_id", UUID.class),
                result.getString("decision_digest"),
                result.getObject("approved_by_actor_id", Long.class),
                approved == null ? null : approved.toInstant(),
                result.getString("audit_bundle_digest"),
                result.getObject("command_receipt_id", UUID.class),
                result.getObject("recorded_at", OffsetDateTime.class).toInstant());
    }

    private SourceInput mapSourceInput(java.sql.ResultSet result)
            throws java.sql.SQLException {
        return new SourceInput(
                result.getLong("compensation_plan_input_id"),
                result.getObject("public_id", UUID.class),
                result.getObject("source_event_id", UUID.class),
                result.getObject("snapshot_id", UUID.class),
                result.getLong("snapshot_revision"),
                result.getString("snapshot_payload_digest"),
                result.getObject("plan_id", UUID.class),
                result.getObject("cycle_id", UUID.class),
                result.getInt("line_sequence"),
                result.getObject("worker_id", UUID.class),
                result.getObject("assignment_id", UUID.class),
                result.getString("component_code"), result.getString("currency"),
                result.getBigDecimal("approved_amount"),
                result.getDate("effective_from").toLocalDate(),
                result.getDate("effective_to") == null
                        ? null : result.getDate("effective_to").toLocalDate(),
                result.getString("payload_digest"));
    }

    private MapSqlParameterSource params(long tenantId, UUID runId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("runId", runId);
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    private BaseException conflict(String message, RuntimeException cause) {
        return cause == null ? new BaseException(ErrorCode.RESOURCE_CONFLICT, message)
                : new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }

    private record FoundationScope(UUID legalEntityId, UUID payrollGroupId) {
    }

    private record SnapshotHeader(
            long internalId,
            UUID snapshotId,
            long snapshotRevision,
            String payloadDigest,
            String formulaSetDigest,
            String engineVersion,
            String functionSetVersion,
            String semanticBindingDigest,
            String targetPopulationDigest,
            String compensationInputDigest,
            Instant pinnedAt) {
    }

    private record ReceiptHead(int sequence, ReceiptStatus status) {
    }

    private record DurableLineHeader(
            int inputOrdinal,
            UUID resultLineId,
            long sourceInputId,
            UUID workerId,
            UUID workerResultId,
            String componentCode,
            String sourcePayloadDigest,
            String semanticBindingDigest,
            UUID elementId,
            long elementVersion,
            UUID formulaId,
            long formulaVersion,
            String formulaAstDigest,
            String category,
            String currency,
            java.math.BigDecimal amount,
            String formulaInputDigest,
            String formulaOutputDigest,
            UUID terminalTraceId,
            String traceDigest,
            String lineDigest) {
    }

    private record DurableLine(
            int inputOrdinal,
            UUID resultLineId,
            long sourceInputId,
            UUID workerId,
            UUID workerResultId,
            String componentCode,
            String sourcePayloadDigest,
            String semanticBindingDigest,
            UUID elementId,
            long elementVersion,
            UUID formulaId,
            long formulaVersion,
            String formulaAstDigest,
            String category,
            String currency,
            java.math.BigDecimal amount,
            String formulaInputDigest,
            String formulaOutputDigest,
            UUID terminalTraceId,
            String traceDigest,
            String lineDigest,
            List<DurableTraceNode> traces) {
    }

    private record DurableTraceNode(
            UUID traceId,
            int sequence,
            UUID parentTraceId,
            String semanticBindingDigest,
            UUID elementId,
            long elementVersion,
            UUID formulaId,
            Long formulaVersion,
            String formulaAstDigest,
            String category,
            String sourcePayloadDigest,
            String inputDigest,
            String outputDigest,
            java.math.BigDecimal unroundedAmount,
            java.math.BigDecimal roundedAmount,
            String currency,
            String roundingStage,
            int roundingScale,
            String roundingMode,
            java.math.BigDecimal roundingIncrement,
            String roundingPolicyDigest,
            String traceDigest,
            String engineVersion) {
    }
}
