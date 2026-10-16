package com.dwp.services.time.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.OwnerPopulationScope;
import com.dwp.services.time.home.TimeHomeCurrentAuthorityPort.CurrentAuthority;
import com.dwp.services.time.home.TimeHomeProjectionRepository.Projection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant-RLS-bound owner projection; location evidence and leave reasons are never selected. */
@Repository
@ConditionalOnProperty(
        name = "dwp.hris.time.home.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcTimeHomeProjectionRepository implements TimeHomeProjectionRepository {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('dwp.tenant_id', ?, true)";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transactions;

    JdbcTimeHomeProjectionRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        Objects.requireNonNull(transactionManager, "transactionManager");
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
    }

    @Override
    public Projection project(CurrentAuthority authority, Instant cutoff) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(cutoff, "cutoff");
        return inTenant(authority.tenantId(), () -> projectInTenant(authority, cutoff));
    }

    private Projection projectInTenant(CurrentAuthority authority, Instant cutoff) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", authority.tenantId())
                .addValue("workers", authority.workerPublicIds())
                .addValue("cutoff", Timestamp.from(cutoff));
        CountRow counts = namedJdbc.queryForObject("""
                SELECT COUNT(*) FILTER (
                           WHERE state IN (
                               'OPEN', 'SUBMITTED', 'REJECTED',
                               'CORRECTION_REQUIRED')) AS open_count,
                       COUNT(*) FILTER (
                           WHERE state = 'SUBMITTED') AS pending_count,
                       COALESCE(MAX(
                           (EXTRACT(EPOCH FROM updated_at) * 1000)::BIGINT), 0) AS source_version
                  FROM tme_timecards
                 WHERE tenant_id = :tenantId
                   AND worker_public_id IN (:workers)
                   AND updated_at <= :cutoff
                """, parameters, (row, ignored) -> new CountRow(
                        row.getLong("open_count"),
                        row.getLong("pending_count"),
                        row.getLong("source_version")));
        if (counts == null) {
            throw new IllegalStateException("Timecard Home projection returned no result");
        }

        String clockState = "NOT_APPLICABLE";
        long clockVersion = 0;
        if (authority.ownerPopulationScope() == OwnerPopulationScope.SELF) {
            List<ClockRow> clocks = namedJdbc.query("""
                    SELECT e.event_type, e.event_order
                      FROM tme_clock_events e
                     WHERE e.tenant_id = :tenantId
                       AND e.worker_public_id IN (:workers)
                       AND e.event_type <> 'VOID'
                       AND e.occurred_at <= :cutoff
                       AND e.received_at <= :cutoff
                       AND NOT EXISTS (
                           SELECT 1
                             FROM tme_clock_events voided
                            WHERE voided.tenant_id = e.tenant_id
                              AND voided.voids_event_public_id = e.public_id
                              AND voided.event_type = 'VOID'
                              AND voided.received_at <= :cutoff)
                     ORDER BY e.occurred_at DESC, e.event_order DESC
                     LIMIT 1
                    """, parameters, (row, ignored) -> new ClockRow(
                            row.getString("event_type"), row.getLong("event_order")));
            if (clocks.isEmpty()) {
                clockState = "NOT_CLOCKED_IN";
            } else {
                ClockRow latest = clocks.getFirst();
                clockState = switch (latest.eventType()) {
                    case "IN", "BREAK_END" -> "CLOCKED_IN";
                    case "BREAK_START" -> "ON_BREAK";
                    case "OUT" -> "CLOCKED_OUT";
                    default -> throw new IllegalStateException(
                            "Unexpected Time clock event type in Home projection");
                };
                clockVersion = latest.eventOrder();
            }
        }
        long sourceVersion = Math.max(1L, Math.max(counts.sourceVersion(), clockVersion));
        return new Projection(
                clockState, counts.openCount(), counts.pendingCount(), sourceVersion, cutoff);
    }

    private <T> T inTenant(long tenantId, Supplier<T> work) {
        T result = transactions.execute(status -> {
            String expected = Long.toString(tenantId);
            String configured = jdbc.queryForObject(SET_TENANT_SQL, String.class, expected);
            if (!expected.equals(configured)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return work.get();
        });
        return Objects.requireNonNull(result, "Time Home projection transaction returned null");
    }

    private record CountRow(long openCount, long pendingCount, long sourceVersion) {
    }

    private record ClockRow(String eventType, long eventOrder) {
    }
}
