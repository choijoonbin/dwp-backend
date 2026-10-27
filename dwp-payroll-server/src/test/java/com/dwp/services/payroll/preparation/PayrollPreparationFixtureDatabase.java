package com.dwp.services.payroll.preparation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** JDBC source reads and transaction binding for preparation fixtures. */
final class PayrollPreparationFixtureDatabase {

    private PayrollPreparationFixtureDatabase() {
    }

    static List<PayrollPreparationModels.SourceLine> readLines(
            DataSource dataSource,
            UUID sourceSnapshotId,
            long sourceSnapshotRevision,
            String sourceSnapshotDigest,
            UUID payPeriodId,
            Instant asOf) {
        return transaction(dataSource, jdbc -> {
            bindTenant(jdbc);
            return jdbc.query("""
                    SELECT compensation_plan_input_id, public_id, source_event_id,
                           snapshot_id, snapshot_revision, snapshot_payload_digest,
                           plan_id, cycle_id, line_sequence, worker_id,
                           assignment_id, component_code, currency,
                           approved_amount, effective_from, effective_to,
                           payload_digest
                      FROM pay_compensation_plan_inputs
                     WHERE tenant_id = 1 AND snapshot_id = :snapshotId
                       AND snapshot_revision = :snapshotRevision
                       AND snapshot_payload_digest = :snapshotDigest
                       AND cycle_id = :payPeriodId AND recorded_at <= :asOf
                     ORDER BY line_sequence, public_id
                    """, new MapSqlParameterSource()
                    .addValue("snapshotId", sourceSnapshotId)
                    .addValue("snapshotRevision", sourceSnapshotRevision)
                    .addValue("snapshotDigest", sourceSnapshotDigest)
                    .addValue("payPeriodId", payPeriodId)
                    .addValue("asOf", Timestamp.from(asOf)),
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
        });
    }

    static List<PayrollPreparationModels.FormulaRef> readFormulas(
            DataSource dataSource,
            UUID legalEntityId,
            UUID payrollGroupId,
            Instant asOf) {
        return transaction(dataSource, jdbc -> {
            bindTenant(jdbc);
            List<PayrollPreparationModels.FormulaRef> result = jdbc.query("""
                    SELECT DISTINCT ON (formula_id)
                           formula_id, version, ast_digest
                      FROM pay_formula_versions
                     WHERE tenant_id = 1 AND legal_entity_id = :legalEntityId
                       AND payroll_group_id = :payrollGroupId
                       AND lifecycle_state = 'PUBLISHED'
                       AND recorded_at <= :asOf
                     ORDER BY formula_id, version DESC
                    """, new MapSqlParameterSource()
                    .addValue("legalEntityId", legalEntityId)
                    .addValue("payrollGroupId", payrollGroupId)
                    .addValue("asOf", Timestamp.from(asOf)),
                    (row, ignored) -> new PayrollPreparationModels.FormulaRef(
                            row.getObject("formula_id", UUID.class),
                            row.getLong("version"), row.getString("ast_digest")));
            return JdbcPayrollPreparationStore.orderFormulas(result);
        });
    }

    static Instant databaseNow(DataSource dataSource) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT CURRENT_TIMESTAMP", java.time.OffsetDateTime.class).toInstant();
    }

    static long nextPopulationRevision(
            DataSource dataSource,
            UUID legalEntityId,
            UUID payrollGroupId) {
        return transaction(dataSource, jdbc -> {
            bindTenant(jdbc);
            Long current = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(source_revision), 0)
                      FROM pay_population_source_revisions
                     WHERE tenant_id = 1
                       AND legal_entity_id = :legalEntityId
                       AND payroll_group_id = :payrollGroupId
                       AND population_kind = 'LEGAL_ENTITY'
                       AND named_population_id IS NULL
                    """, new MapSqlParameterSource()
                    .addValue("legalEntityId", legalEntityId)
                    .addValue("payrollGroupId", payrollGroupId), Long.class);
            return Math.addExact(current == null ? 0 : current, 1);
        });
    }

    static void bindTenant(NamedParameterJdbcTemplate jdbc) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', '1', true)",
                new MapSqlParameterSource(), String.class);
    }

    static <T> T transaction(
            DataSource dataSource,
            java.util.function.Function<NamedParameterJdbcTemplate, T> work) {
        TransactionTemplate template = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        return template.execute(status -> work.apply(
                new NamedParameterJdbcTemplate(dataSource)));
    }
}
