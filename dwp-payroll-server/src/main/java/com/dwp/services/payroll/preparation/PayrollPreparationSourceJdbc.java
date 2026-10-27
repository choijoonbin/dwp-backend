package com.dwp.services.payroll.preparation;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** JDBC operations for payroll sources. */
final class PayrollPreparationSourceJdbc extends PayrollPreparationJdbcSupport {

    PayrollPreparationSourceJdbc(
            NamedParameterJdbcTemplate jdbc,
            PayrollPreparationCanonical canonical,
            PayrollPreparationSemanticSnapshotPort semantics,
            PayrollPreparationPopulationSnapshotPort populations,
            Clock clock) {
        super(jdbc, canonical, semantics, populations, clock);
    }

    Optional<Foundation> foundation(
            long tenantId,
            UUID legalEntityId,
            UUID payrollGroupId,
            LocalDate effectiveOn,
            Instant sourceAsOf) {
        LocalDate date = effectiveOn == null
                ? LocalDate.ofInstant(sourceAsOf, ZoneOffset.UTC) : effectiveOn;
        List<Foundation> values = jdbc.query("""
                SELECT c.configuration_id, c.current_version,
                       v.definition_digest
                  FROM pay_foundation_configurations c
                  JOIN pay_foundation_versions v
                    ON v.tenant_id = c.tenant_id
                   AND v.configuration_id = c.configuration_id
                   AND v.version = c.current_version
                   AND v.lifecycle_state = 'PUBLISHED'
                 WHERE c.tenant_id = :tenantId
                   AND c.legal_entity_id = :legalEntityId
                   AND c.payroll_group_id = :payrollGroupId
                   AND c.lifecycle_state = 'PUBLISHED'
                   AND c.updated_at <= :sourceAsOf
                   AND v.recorded_at <= :sourceAsOf
                   AND c.effective_from <= :effectiveOn
                   AND (c.effective_to IS NULL OR c.effective_to >= :effectiveOn)
                 ORDER BY c.current_version DESC, c.configuration_id
                 LIMIT 2
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", legalEntityId)
                .addValue("payrollGroupId", payrollGroupId)
                .addValue("effectiveOn", Date.valueOf(date))
                .addValue("sourceAsOf", timestamp(sourceAsOf)),
                (result, row) -> new Foundation(
                        result.getObject("configuration_id", UUID.class),
                        result.getLong("current_version"),
                        result.getString("definition_digest")));
        if (values.size() > 1) {
            throw invalid("Payroll foundation scope is ambiguous.");
        }
        return values.stream().findFirst();
    }

    List<PayrollPreparationModels.FormulaRef> formulas(
            long tenantId, UUID legalEntityId, UUID payrollGroupId,
            Instant sourceAsOf) {
        List<PayrollPreparationModels.FormulaRef> values = jdbc.query("""
                SELECT DISTINCT ON (formula_id) formula_id, version, ast_digest
                  FROM pay_formula_versions
                 WHERE tenant_id = :tenantId
                   AND legal_entity_id = :legalEntityId
                   AND payroll_group_id = :payrollGroupId
                   AND lifecycle_state = 'PUBLISHED'
                   AND recorded_at <= :sourceAsOf
                 ORDER BY formula_id, version DESC
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", legalEntityId)
                .addValue("payrollGroupId", payrollGroupId)
                .addValue("sourceAsOf", timestamp(sourceAsOf)),
                (result, row) -> new PayrollPreparationModels.FormulaRef(
                        result.getObject("formula_id", UUID.class),
                        result.getLong("version"), result.getString("ast_digest")));
        return orderFormulas(values);
    }

    static List<PayrollPreparationModels.FormulaRef> orderFormulas(
            List<PayrollPreparationModels.FormulaRef> values) {
        return values.stream().sorted(Comparator.comparing(
                PayrollPreparationModels.FormulaRef::formulaId)).toList();
    }

    int formulaCount(
            long tenantId, PayrollPreparationAuthority.PopulationTuple tuple,
            Instant sourceAsOf) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT formula_id)
                  FROM pay_formula_versions
                 WHERE tenant_id = :tenantId
                   AND legal_entity_id = :legalEntityId
                   AND payroll_group_id = :payrollGroupId
                   AND lifecycle_state = 'PUBLISHED'
                   AND recorded_at <= :sourceAsOf
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", tuple.legalEntityId())
                .addValue("payrollGroupId", tuple.payrollGroupId())
                .addValue("sourceAsOf", timestamp(sourceAsOf)), Integer.class);
        return count == null ? 0 : count;
    }

    boolean populationAuthority(
            long tenantId,
            PayrollPreparationAuthority.PopulationTuple tuple,
            String populationKind,
            UUID sourceId,
            long sourceRevision,
            String payloadDigest,
            long itemCount,
            Instant sourceAsOf,
            List<PayrollPreparationModels.SourceLine> expectedLines) {
        String sourcePredicate = sourceId == null
                ? "" : " AND authority.source_id = :sourceId";
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pay_population_source_revisions authority
                 WHERE authority.tenant_id = :tenantId
                   AND authority.legal_entity_id = :legalEntityId
                   AND authority.payroll_group_id = :payrollGroupId
                   AND authority.population_kind = :populationKind
                   AND authority.named_population_id IS NULL
                   AND authority.source_revision = :sourceRevision
                   AND authority.payload_digest = :payloadDigest
                   AND authority.item_count = :itemCount
                   AND authority.effective_at <= :sourceAsOf
                   AND authority.recorded_at <= :sourceAsOf
                   AND authority.source_revision = (
                       SELECT MAX(current.source_revision)
                         FROM pay_population_source_revisions current
                        WHERE current.tenant_id = authority.tenant_id
                          AND current.legal_entity_id = authority.legal_entity_id
                          AND current.payroll_group_id = authority.payroll_group_id
                          AND current.population_kind = authority.population_kind
                          AND current.named_population_id IS NOT DISTINCT FROM
                              authority.named_population_id
                          AND current.effective_at <= :sourceAsOf
                          AND current.recorded_at <= :sourceAsOf)
                """ + sourcePredicate, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("legalEntityId", tuple.legalEntityId())
                .addValue("payrollGroupId", tuple.payrollGroupId())
                .addValue("populationKind", populationKind)
                .addValue("sourceRevision", sourceRevision)
                .addValue("payloadDigest", payloadDigest)
                .addValue("itemCount", itemCount)
                .addValue("sourceAsOf", timestamp(sourceAsOf))
                .addValue("sourceId", sourceId), Long.class);
        if (count == null || count != 1) return false;
        UUID resolvedSourceId = sourceId == null
                ? jdbc.queryForObject("""
                    SELECT authority.source_id
                      FROM pay_population_source_revisions authority
                     WHERE authority.tenant_id = :tenantId
                       AND authority.legal_entity_id = :legalEntityId
                       AND authority.payroll_group_id = :payrollGroupId
                       AND authority.population_kind = :populationKind
                       AND authority.named_population_id IS NULL
                       AND authority.source_revision = :sourceRevision
                       AND authority.payload_digest = :payloadDigest
                       AND authority.item_count = :itemCount
                       AND authority.effective_at <= :sourceAsOf
                       AND authority.recorded_at <= :sourceAsOf
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("legalEntityId", tuple.legalEntityId())
                    .addValue("payrollGroupId", tuple.payrollGroupId())
                    .addValue("populationKind", populationKind)
                    .addValue("sourceRevision", sourceRevision)
                    .addValue("payloadDigest", payloadDigest)
                    .addValue("itemCount", itemCount)
                    .addValue("sourceAsOf", timestamp(sourceAsOf)), UUID.class)
                : sourceId;
        List<PopulationMember> members = jdbc.query("""
                SELECT compensation_plan_input_id, worker_id,
                       source_payload_digest
                  FROM pay_population_source_members
                 WHERE tenant_id = :tenantId
                   AND source_id = :sourceId
                   AND source_revision = :sourceRevision
                   AND recorded_at <= :sourceAsOf
                 ORDER BY member_ordinal
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", resolvedSourceId)
                .addValue("sourceRevision", sourceRevision)
                .addValue("sourceAsOf", timestamp(sourceAsOf)),
                (result, row) -> new PopulationMember(
                        result.getLong("compensation_plan_input_id"),
                        result.getObject("worker_id", UUID.class),
                        result.getString("source_payload_digest")));
        long distinctWorkers = members.stream()
                .map(PopulationMember::workerId).distinct().count();
        String derivedDigest = canonical.digest(Map.of(
                "schema", "PayrollPopulationSourceRevision.v1",
                "scope", Map.of(
                        "tenantId", tenantId,
                        "legalEntityId", tuple.legalEntityId(),
                        "payrollGroupId", tuple.payrollGroupId(),
                        "populationKind", populationKind),
                "sourceId", resolvedSourceId,
                "sourceRevision", sourceRevision,
                "members", members.stream().map(member -> Map.of(
                        "compensationPlanInputId",
                        member.compensationPlanInputId(),
                        "workerId", member.workerId(),
                        "payloadDigest", member.sourcePayloadDigest())).toList()));
        if (distinctWorkers != itemCount || !derivedDigest.equals(payloadDigest)) {
            return false;
        }
        if (expectedLines == null) return true;
        List<PopulationMember> expectedMembers = expectedLines.stream()
                .sorted(Comparator.comparingInt(
                        PayrollPreparationModels.SourceLine::lineSequence))
                .map(line -> new PopulationMember(
                        line.sourceInputId(), line.workerId(), line.payloadDigest()))
                .toList();
        return members.equals(expectedMembers);
    }

    boolean semanticAuthority(
            long tenantId,
            PayrollPreparationModels.PrepareCommand command,
            PayrollPreparationPopulationSnapshotPort.Materialization population,
            PayrollPreparationModels.SourceLine source,
            String formulaSetDigest,
            PayrollPreparationSemanticSnapshotPort.Resolution semantic,
            Instant sourceAsOf) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pay_semantic_source_revisions authority
                 WHERE authority.tenant_id = :tenantId
                   AND authority.source_id = :sourceId
                   AND authority.source_revision = :sourceRevision
                   AND authority.payload_digest = :payloadDigest
                   AND authority.legal_entity_id = :legalEntityId
                   AND authority.payroll_group_id = :payrollGroupId
                   AND authority.pay_period_id = :payPeriodId
                   AND authority.target_population_digest = :targetDigest
                   AND authority.source_snapshot_id = :snapshotId
                   AND authority.source_snapshot_revision = :snapshotRevision
                   AND authority.source_payload_digest = :snapshotDigest
                   AND authority.formula_set_digest = :formulaSetDigest
                   AND authority.function_set_version = :functionSetVersion
                   AND authority.line_count = :lineCount
                   AND authority.effective_at <= :sourceAsOf
                   AND authority.recorded_at <= :sourceAsOf
                   AND authority.source_revision = (
                       SELECT MAX(current.source_revision)
                         FROM pay_semantic_source_revisions current
                        WHERE current.tenant_id = authority.tenant_id
                          AND current.legal_entity_id = authority.legal_entity_id
                          AND current.payroll_group_id = authority.payroll_group_id
                          AND current.pay_period_id = authority.pay_period_id
                          AND current.target_population_digest =
                              authority.target_population_digest
                          AND current.source_snapshot_id =
                              authority.source_snapshot_id
                          AND current.source_snapshot_revision =
                              authority.source_snapshot_revision
                          AND current.formula_set_digest =
                              authority.formula_set_digest
                          AND current.effective_at <= :sourceAsOf
                          AND current.recorded_at <= :sourceAsOf)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", semantic.sourceId())
                .addValue("sourceRevision", semantic.sourceRevision())
                .addValue("payloadDigest", semantic.semanticBindingDigest())
                .addValue("legalEntityId", command.payrollEntityId())
                .addValue("payrollGroupId", command.payGroupId())
                .addValue("payPeriodId", command.payPeriodId())
                .addValue("targetDigest", population.projectionDigest())
                .addValue("snapshotId", source.snapshotId())
                .addValue("snapshotRevision", source.snapshotRevision())
                .addValue("snapshotDigest", source.snapshotPayloadDigest())
                .addValue("formulaSetDigest", formulaSetDigest)
                .addValue("functionSetVersion", semantic.functionSetVersion())
                .addValue("lineCount", semantic.lines().size())
                .addValue("sourceAsOf", timestamp(sourceAsOf)), Long.class);
        return count != null && count == 1;
    }

    record PopulationMember(
            long compensationPlanInputId,
            UUID workerId,
            String sourcePayloadDigest) {
    }

    List<PayrollPreparationModels.SourceLine> sourceLines(
            long tenantId,
            UUID payPeriodId,
            Instant asOf,
            UUID snapshotId,
            long snapshotRevision,
            String snapshotPayloadDigest) {
        return jdbc.query("""
                SELECT i.compensation_plan_input_id, i.public_id,
                       i.source_event_id, i.snapshot_id, i.snapshot_revision,
                       i.snapshot_payload_digest, i.plan_id, i.cycle_id,
                       i.line_sequence, i.worker_id, i.assignment_id,
                       i.component_code, i.currency, i.approved_amount,
                       i.effective_from, i.effective_to, i.payload_digest
                  FROM pay_compensation_plan_inputs i
                 WHERE i.tenant_id = :tenantId AND i.cycle_id = :payPeriodId
                   AND i.snapshot_id = :snapshotId
                   AND i.snapshot_revision = :snapshotRevision
                   AND i.snapshot_payload_digest = :snapshotPayloadDigest
                   AND i.recorded_at <= :asOf
                 ORDER BY i.line_sequence, i.public_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("payPeriodId", payPeriodId)
                .addValue("snapshotId", snapshotId)
                .addValue("snapshotRevision", snapshotRevision)
                .addValue("snapshotPayloadDigest", snapshotPayloadDigest)
                .addValue("asOf", timestamp(asOf)),
                (result, row) -> new PayrollPreparationModels.SourceLine(
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
                        result.getString("component_code"),
                        result.getString("currency"),
                        result.getBigDecimal("approved_amount"),
                        result.getObject("effective_from", LocalDate.class),
                        result.getObject("effective_to", LocalDate.class),
                        result.getString("payload_digest")));
    }

    Approval approval(
            long tenantId,
            UUID snapshotId,
            long revision,
            String digest,
            UUID payPeriodId,
            int lineCount,
            Instant sourceAsOf) {
        List<Approval> approvals = jdbc.query("""
                SELECT MIN(i.approval_receipt_id::TEXT)::UUID AS approval_receipt_id,
                       MIN(i.approval_revision) AS approval_revision,
                       COUNT(DISTINCT i.approval_receipt_id) AS receipt_count,
                       COUNT(DISTINCT i.approval_revision) AS revision_count,
                       COUNT(*) AS actual_count, r.line_count AS receipt_line_count
                  FROM pay_compensation_plan_inputs i
                  JOIN pay_compensation_plan_input_receipts r
                    ON r.tenant_id = i.tenant_id
                   AND r.snapshot_id = i.snapshot_id
                   AND r.snapshot_revision = i.snapshot_revision
                   AND r.snapshot_payload_digest = i.snapshot_payload_digest
                 WHERE i.tenant_id = :tenantId
                   AND i.snapshot_id = :snapshotId
                   AND i.snapshot_revision = :revision
                   AND i.snapshot_payload_digest = :digest
                   AND i.cycle_id = :payPeriodId
                   AND i.recorded_at <= :sourceAsOf
                   AND r.applied_at <= :sourceAsOf
                 GROUP BY r.line_count
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("snapshotId", snapshotId)
                .addValue("revision", revision)
                .addValue("digest", digest)
                .addValue("payPeriodId", payPeriodId)
                .addValue("sourceAsOf", timestamp(sourceAsOf)),
                (result, row) -> {
                    if (result.getLong("receipt_count") != 1
                            || result.getLong("revision_count") != 1
                            || result.getInt("actual_count") != lineCount
                            || result.getInt("receipt_line_count") != lineCount) {
                        return null;
                    }
                    return new Approval(
                            result.getObject("approval_receipt_id", UUID.class),
                            result.getLong("approval_revision"));
                });
        return approvals.stream().filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow(() -> invalid(
                        "Compensation source approval/cardinality is incomplete."));
    }
}
