package com.dwp.services.approval.analytics;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class ApprovalAnalyticsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ApprovalAnalyticsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    CoverageRow coverage(
            ApprovalAnalyticsModels.Scope scope,
            ApprovalAnalyticsModels.Query query) {
        return jdbc.queryForObject("""
                SELECT count(*)::INTEGER AS candidate_count,
                       count(*) FILTER (WHERE submitted_at IS NOT NULL)::INTEGER AS included_count,
                       count(*) FILTER (WHERE submitted_at IS NULL)::INTEGER AS missing_submitted,
                       count(*) FILTER (WHERE submitted_at IS NOT NULL
                           AND completed_at IS NULL)::INTEGER AS in_flight_cycle,
                       count(*) FILTER (WHERE completed_at IS NOT NULL
                           AND cycle_seconds IS NULL)::INTEGER AS invalid_cycle,
                       max(source_last_event_at) AS source_through,
                       max(projected_at) AS projected_at
                  FROM apr_analytics_request_facts
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND request_created_at >= :from
                   AND request_created_at < :to
                """, params(scope, query), (result, row) -> new CoverageRow(
                result.getInt("candidate_count"),
                result.getInt("included_count"),
                result.getInt("missing_submitted"),
                result.getInt("in_flight_cycle"),
                result.getInt("invalid_cycle"),
                instant(result, "source_through"),
                instant(result, "projected_at")));
    }

    MetricRow overall(
            ApprovalAnalyticsModels.Scope scope,
            ApprovalAnalyticsModels.Query query,
            Instant generatedAt) {
        return jdbc.queryForObject(metricSql("", ""),
                params(scope, query).addValue("generatedAt", Timestamp.from(generatedAt)),
                this::metric);
    }

    List<GroupMetricRow> cohorts(
            ApprovalAnalyticsModels.Scope scope,
            ApprovalAnalyticsModels.Query query,
            Instant generatedAt) {
        String column = query.cohortDimension() == ApprovalAnalyticsModels.CohortDimension.FORM
                ? "fact.form_version_id" : "fact.workflow_version_id";
        String sql = metricSql(column + "::text AS group_key,", "GROUP BY " + column);
        return jdbc.query(sql,
                params(scope, query).addValue("generatedAt", Timestamp.from(generatedAt)),
                (result, row) -> new GroupMetricRow(
                        result.getString("group_key"), metric(result, row)));
    }

    List<StageMetricRow> stageWaits(
            ApprovalAnalyticsModels.Scope scope,
            ApprovalAnalyticsModels.Query query) {
        return jdbc.query("""
                SELECT stage.step_key,
                       stage.sequence_number,
                       count(*)::INTEGER AS sample_count,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY stage.wait_seconds)
                           FILTER (WHERE stage.wait_seconds IS NOT NULL)::BIGINT AS cycle_p50,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY stage.wait_seconds)
                           FILTER (WHERE stage.wait_seconds IS NOT NULL)::BIGINT AS cycle_p90
                  FROM apr_analytics_stage_facts stage
                  JOIN apr_analytics_request_facts fact
                    ON fact.tenant_id = stage.tenant_id
                   AND fact.management_resource_set_key = stage.management_resource_set_key
                   AND fact.request_id = stage.request_id
                 WHERE fact.tenant_id = :tenant
                   AND fact.management_resource_set_key = :scope
                   AND fact.submitted_at >= :from
                   AND fact.submitted_at < :to
                 GROUP BY stage.step_key, stage.sequence_number
                 ORDER BY stage.sequence_number, stage.step_key
                """, params(scope, query), (result, row) -> new StageMetricRow(
                result.getString("step_key"),
                result.getInt("sequence_number"),
                result.getInt("sample_count"),
                nullableLong(result, "cycle_p50"),
                nullableLong(result, "cycle_p90")));
    }

    int cohortSize(
            ApprovalAnalyticsModels.Scope scope,
            ApprovalAnalyticsModels.Query query,
            String cohortKey) {
        String column = query.cohortDimension() == ApprovalAnalyticsModels.CohortDimension.FORM
                ? "form_version_id" : "workflow_version_id";
        String sql = """
                SELECT count(*)::INTEGER
                  FROM apr_analytics_request_facts
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND submitted_at >= :from
                   AND submitted_at < :to
                   AND %s::text = :cohort
                """.formatted(column);
        Integer count = jdbc.queryForObject(sql, params(scope, query)
                .addValue("cohort", cohortKey), Integer.class);
        return count == null ? 0 : count;
    }

    List<ApprovalAnalyticsModels.Representative> representatives(
            ApprovalAnalyticsModels.Scope scope,
            ApprovalAnalyticsModels.Query query,
            String cohortKey,
            int candidateLimit) {
        String column = query.cohortDimension() == ApprovalAnalyticsModels.CohortDimension.FORM
                ? "form_version_id" : "workflow_version_id";
        String sql = """
                SELECT request_id, request_status, submitted_at, completed_at,
                       cycle_seconds, rework_count, delegation_count,
                       escalation_count, route_override_count
                  FROM apr_analytics_request_facts
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND submitted_at >= :from
                   AND submitted_at < :to
                   AND %s::text = :cohort
                 ORDER BY completed_at DESC NULLS LAST, request_id
                 LIMIT :limit
                """.formatted(column);
        return jdbc.query(sql, params(scope, query)
                .addValue("cohort", cohortKey)
                .addValue("limit", candidateLimit), (result, row) ->
                new ApprovalAnalyticsModels.Representative(
                        result.getObject("request_id", UUID.class),
                        result.getString("request_status"),
                        instant(result, "submitted_at"),
                        instant(result, "completed_at"),
                        nullableLong(result, "cycle_seconds"),
                        result.getInt("rework_count"),
                        result.getInt("delegation_count"),
                        result.getInt("escalation_count"),
                        result.getInt("route_override_count") == 0));
    }

    boolean canViewRepresentative(
            long tenantId,
            String resourceSetKey,
            long actorUserId,
            UUID requestId) {
        Boolean visible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM apr_analytics_request_facts fact
                      JOIN apr_requests request
                        ON request.tenant_id = fact.tenant_id
                       AND request.request_id = fact.request_id
                     WHERE fact.tenant_id = :tenant
                       AND fact.management_resource_set_key = :scope
                       AND fact.request_id = :requestId
                       AND request.management_resource_set_key = :scope)
                """, new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("scope", resourceSetKey)
                .addValue("requestId", requestId), Boolean.class);
        return Boolean.TRUE.equals(visible);
    }

    private String metricSql(String prefix, String suffix) {
        return """
                SELECT %s
                       count(*)::INTEGER AS sample_count,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY fact.cycle_seconds)
                           FILTER (WHERE fact.cycle_seconds IS NOT NULL)::BIGINT AS cycle_p50,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY fact.cycle_seconds)
                           FILTER (WHERE fact.cycle_seconds IS NOT NULL)::BIGINT AS cycle_p90,
                       count(*) FILTER (WHERE fact.due_at IS NOT NULL
                           AND fact.due_at <= :generatedAt)::INTEGER AS sla_eligible,
                       count(*) FILTER (WHERE fact.due_at IS NOT NULL
                           AND fact.due_at <= :generatedAt
                           AND COALESCE(fact.completed_at, :generatedAt) > fact.due_at)::INTEGER
                           AS sla_breached,
                       count(*) FILTER (WHERE fact.rework_count > 0)::INTEGER AS reworked,
                       count(*) FILTER (WHERE fact.delegation_count > 0)::INTEGER AS delegated,
                       count(*) FILTER (WHERE fact.escalation_count > 0)::INTEGER AS escalated,
                       count(*) FILTER (WHERE fact.route_override_count = 0)::INTEGER
                           AS route_conformant
                  FROM apr_analytics_request_facts fact
                 WHERE fact.tenant_id = :tenant
                   AND fact.management_resource_set_key = :scope
                   AND fact.submitted_at >= :from
                   AND fact.submitted_at < :to
                 %s
                 ORDER BY 1
                """.formatted(prefix, suffix);
    }

    private MetricRow metric(ResultSet result, int row) throws SQLException {
        return new MetricRow(
                result.getInt("sample_count"),
                nullableLong(result, "cycle_p50"),
                nullableLong(result, "cycle_p90"),
                result.getInt("sla_eligible"),
                result.getInt("sla_breached"),
                result.getInt("reworked"),
                result.getInt("delegated"),
                result.getInt("escalated"),
                result.getInt("route_conformant"));
    }

    private MapSqlParameterSource params(
            ApprovalAnalyticsModels.Scope scope,
            ApprovalAnalyticsModels.Query query) {
        return new MapSqlParameterSource()
                .addValue("tenant", scope.tenantId())
                .addValue("scope", scope.resourceSetKey())
                .addValue("from", Timestamp.from(query.from()))
                .addValue("to", Timestamp.from(query.to()));
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    record CoverageRow(
            int candidateCount,
            int includedCount,
            int missingSubmitted,
            int inFlightCycle,
            int invalidCycle,
            Instant sourceThrough,
            Instant projectedAt) {
    }

    record MetricRow(
            int sampleCount,
            Long cycleP50,
            Long cycleP90,
            int slaEligible,
            int slaBreached,
            int reworked,
            int delegated,
            int escalated,
            int routeConformant) {
    }

    record GroupMetricRow(String key, MetricRow metrics) {
    }

    record StageMetricRow(
            String stepKey,
            int sequence,
            int sampleCount,
            Long waitP50,
            Long waitP90) {
    }
}
