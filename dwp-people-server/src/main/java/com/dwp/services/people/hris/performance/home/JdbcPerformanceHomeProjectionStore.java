package com.dwp.services.people.hris.performance.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Closed aggregate over assigned-review state; never selects answers, scores or feedback. */
@Repository
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
final class JdbcPerformanceHomeProjectionStore implements PerformanceHomeProjectionPort {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    JdbcPerformanceHomeProjectionStore(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    JdbcPerformanceHomeProjectionStore(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot load(PerformanceHomeAuthorityPort.AuthorityDecision authority) {
        bindTenant(authority.tenantId());
        List<Row> rows = jdbc.query("""
                SELECT review_state, aggregate_version, updated_at
                  FROM hris_performance.prf_review_assignments
                 WHERE tenant_id = :tenantId
                   AND participant_id IN (:participantIds)
                   AND review_state <> 'CANCELLED'
                 ORDER BY review_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", authority.tenantId())
                .addValue("participantIds", authority.authorizedParticipantIds()),
                (result, rowNumber) -> new Row(
                        result.getString("review_state"),
                        result.getLong("aggregate_version"),
                        result.getTimestamp("updated_at").toInstant()));

        long version = rows.stream().mapToLong(Row::version).max().orElse(0L) + 1L;
        Instant asOf = rows.stream().map(Row::updatedAt).max(Instant::compareTo)
                .orElseGet(() -> clock.instant().truncatedTo(ChronoUnit.MINUTES));
        // Source-derived time keeps the stateless reference stable until owner data changes.
        Instant generatedAt = asOf;
        int pending = (int) Math.min(999, rows.stream()
                .filter(row -> switch (row.state()) {
                    case "NOT_STARTED", "IN_PROGRESS", "RETURNED" -> true;
                    default -> false;
                }).count());
        return new Snapshot(
                GoalProgress.NOT_APPLICABLE,
                reviewStatus(rows, authority.browserAudience()),
                pending,
                version,
                asOf,
                generatedAt,
                generatedAt.plusSeconds(60),
                generatedAt.plusSeconds(300),
                HrisHomeContributionMaterializationV1.State.FRESH,
                List.of());
    }

    private ReviewStatus reviewStatus(
            List<Row> rows,
            HrisHomeContributionMaterializationV1.BrowserAudience audience) {
        if (rows.isEmpty()) return ReviewStatus.NOT_APPLICABLE;
        if (rows.stream().anyMatch(row -> "NOT_STARTED".equals(row.state()))) {
            return ReviewStatus.NOT_STARTED;
        }
        if (rows.stream().anyMatch(row ->
                "IN_PROGRESS".equals(row.state()) || "RETURNED".equals(row.state()))) {
            return audience == HrisHomeContributionMaterializationV1.BrowserAudience.SELF
                    ? ReviewStatus.SELF_REVIEW : ReviewStatus.MANAGER_REVIEW;
        }
        if (rows.stream().anyMatch(row -> "SUBMITTED".equals(row.state()))) {
            return ReviewStatus.MANAGER_REVIEW;
        }
        return ReviewStatus.RELEASED;
    }

    private void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenantId AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenantId", tenantId), String.class);
    }

    private record Row(String state, long version, Instant updatedAt) {
    }
}
