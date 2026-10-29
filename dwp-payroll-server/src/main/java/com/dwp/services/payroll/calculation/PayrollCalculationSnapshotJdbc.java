package com.dwp.services.payroll.calculation;

import com.dwp.services.payroll.calculation.PayrollCalculationStore.Preparation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticContract.FormulaBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunRevision;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SemanticBinding;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SourceInput;

/** JDBC operations for payroll snapshots. */
final class PayrollCalculationSnapshotJdbc extends PayrollCalculationJdbcSupport {

    PayrollCalculationSnapshotJdbc(
            NamedParameterJdbcTemplate jdbc,
            PayrollCalculationCanonical canonical) {
        super(jdbc, canonical);
    }

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

    List<SourceInput> sourceInputs(RunRevision run) {
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

    FoundationScope foundationScope(
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

    List<FormulaBinding> formulas(
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

    public FrozenSnapshot cloneSnapshot(
            long tenantId,
            UUID runId,
            int sourceAttemptNo,
            int targetAttemptNo) {
        bindTenant(tenantId);
        if (sourceAttemptNo < 1 || targetAttemptNo != sourceAttemptNo + 1) {
            throw invalid("Payroll retry snapshot attempt sequence is invalid.");
        }
        FrozenSnapshot source = snapshot(tenantId, runId, sourceAttemptNo);
        UUID inputSnapshotId = canonical.deterministicUuid(
                "payroll-input-snapshot-v1", Map.of(
                        "tenantId", tenantId,
                        "runId", runId,
                        "attemptNo", targetAttemptNo,
                        "sourceSnapshotId", source.sourceSnapshotId(),
                        "sourceSnapshotRevision", source.sourceSnapshotRevision(),
                        "sourcePayloadDigest", source.sourcePayloadDigest()));
        Long targetInternalId = jdbc.queryForObject("""
                INSERT INTO pay_input_snapshots (
                    tenant_id, input_snapshot_id, run_id, attempt_no,
                    source_snapshot_id, source_snapshot_revision,
                    source_payload_digest, formula_set_digest, formula_bindings,
                    engine_version, function_set_version,
                    semantic_binding_digest, target_population_digest,
                    compensation_input_digest, source_line_count, pinned_at)
                SELECT tenant_id, :inputSnapshotId, run_id, :targetAttemptNo,
                       source_snapshot_id, source_snapshot_revision,
                       source_payload_digest, formula_set_digest, formula_bindings,
                       engine_version, function_set_version,
                       semantic_binding_digest, target_population_digest,
                       compensation_input_digest, source_line_count, pinned_at
                  FROM pay_input_snapshots
                 WHERE tenant_id = :tenantId AND run_id = :runId
                   AND attempt_no = :sourceAttemptNo
                RETURNING input_snapshot_internal_id
                """, params(tenantId, runId)
                .addValue("inputSnapshotId", inputSnapshotId)
                .addValue("sourceAttemptNo", sourceAttemptNo)
                .addValue("targetAttemptNo", targetAttemptNo), Long.class);
        int copied = jdbc.update("""
                INSERT INTO pay_input_snapshot_compensation_refs (
                    tenant_id, input_snapshot_internal_id, input_ordinal,
                    compensation_plan_input_id, source_payload_digest,
                    worker_id, assignment_id, component_code, currency,
                    approved_amount, semantic_binding, semantic_binding_digest)
                SELECT ref.tenant_id, :targetInternalId, ref.input_ordinal,
                       ref.compensation_plan_input_id, ref.source_payload_digest,
                       ref.worker_id, ref.assignment_id, ref.component_code,
                       ref.currency, ref.approved_amount, ref.semantic_binding,
                       ref.semantic_binding_digest
                  FROM pay_input_snapshot_compensation_refs ref
                  JOIN pay_input_snapshots source
                    ON source.tenant_id = ref.tenant_id
                   AND source.input_snapshot_internal_id =
                       ref.input_snapshot_internal_id
                 WHERE source.tenant_id = :tenantId AND source.run_id = :runId
                   AND source.attempt_no = :sourceAttemptNo
                 ORDER BY ref.input_ordinal
                """, params(tenantId, runId)
                .addValue("targetInternalId", targetInternalId)
                .addValue("sourceAttemptNo", sourceAttemptNo));
        if (copied != source.inputs().size()) {
            throw conflict("The Payroll retry snapshot copy is incomplete.", null);
        }
        return snapshot(tenantId, runId, targetAttemptNo);
    }

    SourceInput mapSourceInput(java.sql.ResultSet result)
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

    record FoundationScope(UUID legalEntityId, UUID payrollGroupId) {
    }

    record SnapshotHeader(
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
}
