package com.dwp.services.payroll.home;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.payroll.home.PayrollHomePorts.*;

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll.home-producer.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcPayrollHomeProjectionSource implements PayrollHomeProjectionSource {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPayrollHomeProjectionSource(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollHomeSourceSnapshot load(ResolvedPayrollHomeAuthority authority) {
        bindTenant(authority.tenantId());
        return authority.workerId().isPresent()
                ? loadSelf(authority.tenantId(), authority.workerId().orElseThrow())
                : loadAggregate(authority);
    }

    private PayrollHomeSourceSnapshot loadSelf(long tenantId, UUID workerId) {
        return jdbc.query("""
                WITH run_head AS (
                    SELECT DISTINCT ON (tenant_id, run_id)
                           tenant_id, run_id, run_revision, latest_attempt_no,
                           state, recorded_at
                      FROM pay_payroll_runs
                     WHERE tenant_id = :tenantId
                     ORDER BY tenant_id, run_id, run_revision DESC
                )
                SELECT run.run_revision, run.recorded_at,
                       result.gross_amount, result.net_amount, result.currency
                  FROM run_head run
                  JOIN pay_worker_results result
                    ON result.tenant_id = run.tenant_id
                   AND result.run_id = run.run_id
                   AND result.attempt_no = run.latest_attempt_no
                 WHERE run.tenant_id = :tenantId
                   AND run.state = 'FINALIZED'
                   AND result.worker_id = :workerId
                 ORDER BY run.recorded_at DESC, run.run_revision DESC
                 LIMIT 1
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workerId", workerId),
                (result, ignored) -> new PayrollHomeSourceSnapshot(
                        result.getLong("run_revision"),
                        result.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        Optional.empty(),
                        Optional.of(money(result.getBigDecimal("gross_amount"),
                                result.getString("currency"))),
                        Optional.of(money(result.getBigDecimal("net_amount"),
                                result.getString("currency"))),
                        Optional.empty()))
                .stream().findFirst().orElseGet(PayrollHomeSourceSnapshotUnavailable::value);
    }

    private PayrollHomeSourceSnapshot loadAggregate(ResolvedPayrollHomeAuthority authority) {
        Set<UUID> legalEntities = authority.populations().stream()
                .map(PayrollPopulation::legalEntityId).collect(Collectors.toSet());
        Set<UUID> payrollGroups = authority.populations().stream()
                .map(PayrollPopulation::payrollGroupId).collect(Collectors.toSet());
        List<AggregateRow> rows = jdbc.query("""
                SELECT DISTINCT ON (tenant_id, run_id)
                       run_id, run_revision, legal_entity_id, payroll_group_id, recorded_at
                  FROM pay_payroll_runs
                 WHERE tenant_id = :tenantId
                   AND state = 'FINALIZED'
                   AND legal_entity_id IN (:legalEntityIds)
                   AND payroll_group_id IN (:payrollGroupIds)
                 ORDER BY tenant_id, run_id, run_revision DESC
                """, new MapSqlParameterSource()
                        .addValue("tenantId", authority.tenantId())
                        .addValue("legalEntityIds", legalEntities)
                        .addValue("payrollGroupIds", payrollGroups),
                (result, ignored) -> new AggregateRow(
                        result.getLong("run_revision"),
                        result.getObject("legal_entity_id", UUID.class),
                        result.getObject("payroll_group_id", UUID.class),
                        result.getObject("recorded_at", OffsetDateTime.class)));
        return rows.stream()
                .filter(row -> authority.populations().contains(
                        new PayrollPopulation(row.legalEntityId(), row.payrollGroupId())))
                .max(Comparator.comparing(AggregateRow::recordedAt)
                        .thenComparingLong(AggregateRow::runRevision))
                .map(row -> new PayrollHomeSourceSnapshot(
                        row.runRevision(), row.recordedAt().toInstant(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty()))
                .orElseGet(PayrollHomeSourceSnapshotUnavailable::value);
    }

    private MoneyValue money(BigDecimal amount, String currency) {
        return new MoneyValue(amount.stripTrailingZeros().toPlainString(), currency);
    }

    private void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)),
                String.class);
    }

    private record AggregateRow(
            long runRevision,
            UUID legalEntityId,
            UUID payrollGroupId,
            OffsetDateTime recordedAt) {
    }

    private static final class PayrollHomeSourceSnapshotUnavailable {
        private static PayrollHomeSourceSnapshot value() {
            return new PayrollHomeSourceSnapshot(
                    1, OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0,
                    ZoneOffset.UTC).toInstant(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty());
        }
    }
}
