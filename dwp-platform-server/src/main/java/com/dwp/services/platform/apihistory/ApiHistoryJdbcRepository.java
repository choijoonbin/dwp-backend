package com.dwp.services.platform.apihistory;

import com.dwp.observability.api.ApiHistoryEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ApiHistoryJdbcRepository {

    private static final String EVENT_COLUMNS = """
            history_id, occurred_at, completed_at, ingested_at, tenant_id,
            actor_type, actor_id, auth_type, service_name, service_version,
            service_instance, environment, observation_point, route_id,
            http_method, route_template, request_path, http_scheme, http_protocol,
            status_code, outcome, duration_ms, request_size_bytes, response_size_bytes,
            correlation_id, trace_id, span_id, parent_span_id, client_address_hash,
            user_agent_family, user_agent_hash, error_type, capture_policy_version
            """;

    private static final String INSERT_SQL = """
            INSERT INTO sys_api_history (
                history_id, occurred_at, completed_at, tenant_id,
                actor_type, actor_id, auth_type, service_name, service_version,
                service_instance, environment, observation_point, route_id,
                http_method, route_template, request_path, http_scheme, http_protocol,
                status_code, outcome, duration_ms, request_size_bytes, response_size_bytes,
                correlation_id, trace_id, span_id, parent_span_id, client_address_hash,
                user_agent_family, user_agent_hash, error_type, capture_policy_version)
            VALUES (
                :historyId, :occurredAt, :completedAt, :tenantId,
                :actorType, :actorId, :authType, :serviceName, :serviceVersion,
                :serviceInstance, :environment, :observationPoint, :routeId,
                :httpMethod, :routeTemplate, :requestPath, :httpScheme, :httpProtocol,
                :statusCode, :outcome, :durationMs, :requestSizeBytes, :responseSizeBytes,
                :correlationId, :traceId, :spanId, :parentSpanId, :clientAddressHash,
                :userAgentFamily, :userAgentHash, :errorType, :capturePolicyVersion)
            ON CONFLICT (occurred_at, history_id) DO NOTHING
            """;

    private static final RowMapper<ApiHistoryDtos.EventResponse> EVENT_MAPPER =
            ApiHistoryJdbcRepository::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;

    public ApiHistoryJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ingest(List<ApiHistoryEvent> events) {
        ensurePartitions(events);
        SqlParameterSource[] batch = events.stream()
                .map(ApiHistoryJdbcRepository::parameters)
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_SQL, batch);
    }

    public int maintainPartitions(int retentionDays) {
        Integer result = jdbc.getJdbcTemplate().queryForObject(
                "SELECT sys_maintain_api_history_partitions(?)",
                Integer.class,
                retentionDays);
        return result == null ? 0 : result;
    }

    public List<ApiHistoryDtos.EventResponse> list(
            ApiHistoryCriteria criteria,
            ApiHistoryCursorCodec.CursorPosition cursor,
            int limit) {
        MapSqlParameterSource parameters = baseParameters(criteria)
                .addValue("limit", limit);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(EVENT_COLUMNS)
                .append(" FROM sys_api_history ")
                .append(where(criteria, parameters));
        if (cursor != null) {
            sql.append(" AND (occurred_at < :cursorTime OR "
                    + "(occurred_at = :cursorTime AND history_id < :cursorId)) ");
            parameters
                    .addValue("cursorTime", Timestamp.from(cursor.occurredAt()))
                    .addValue("cursorId", cursor.historyId());
        }
        sql.append(" ORDER BY occurred_at DESC, history_id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), parameters, EVENT_MAPPER);
    }

    public ApiHistoryDtos.Summary summary(ApiHistoryCriteria criteria) {
        MapSqlParameterSource parameters = baseParameters(criteria);
        String sql = """
                SELECT
                    COUNT(*) AS total_requests,
                    COUNT(*) FILTER (WHERE status_code < 400) AS successful_requests,
                    COUNT(*) FILTER (WHERE status_code BETWEEN 400 AND 499) AS client_errors,
                    COUNT(*) FILTER (WHERE status_code >= 500) AS server_errors,
                    COALESCE(percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ms), 0) AS p50,
                    COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0) AS p95,
                    COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0) AS p99,
                    COUNT(DISTINCT COALESCE(route_id, service_name)) AS active_services
                FROM sys_api_history
                """ + where(criteria, parameters);
        Map<String, Object> row = jdbc.queryForMap(sql, parameters);
        long total = number(row.get("total_requests")).longValue();
        long serverErrors = number(row.get("server_errors")).longValue();
        return new ApiHistoryDtos.Summary(
                total,
                number(row.get("successful_requests")).longValue(),
                number(row.get("client_errors")).longValue(),
                serverErrors,
                total == 0 ? 0 : round((serverErrors * 100.0) / total, 2),
                Math.round(number(row.get("p50")).doubleValue()),
                Math.round(number(row.get("p95")).doubleValue()),
                Math.round(number(row.get("p99")).doubleValue()),
                round(total / Math.max(1.0, criteria.window().duration().toSeconds() / 60.0), 2),
                number(row.get("active_services")).intValue());
    }

    public List<ApiHistoryDtos.TrendPoint> trend(ApiHistoryCriteria criteria) {
        MapSqlParameterSource parameters = baseParameters(criteria)
                .addValue("bucketSeconds", criteria.window().bucketSeconds());
        String sql = """
                SELECT
                    to_timestamp(
                        floor(extract(epoch FROM occurred_at) / :bucketSeconds)
                        * :bucketSeconds) AS bucket,
                    COUNT(*) AS total_requests,
                    COUNT(*) FILTER (WHERE status_code BETWEEN 400 AND 499) AS client_errors,
                    COUNT(*) FILTER (WHERE status_code >= 500) AS server_errors,
                    COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0) AS p95
                FROM sys_api_history
                """ + where(criteria, parameters) + """
                GROUP BY bucket
                ORDER BY bucket ASC
                """;
        return jdbc.query(sql, parameters, (result, rowNumber) -> new ApiHistoryDtos.TrendPoint(
                instant(result, "bucket"),
                result.getLong("total_requests"),
                result.getLong("client_errors"),
                result.getLong("server_errors"),
                Math.round(result.getDouble("p95"))));
    }

    public List<ApiHistoryDtos.RouteMetric> topRoutes(ApiHistoryCriteria criteria) {
        MapSqlParameterSource parameters = baseParameters(criteria);
        String sql = """
                SELECT
                    route_id,
                    service_name,
                    http_method,
                    route_template,
                    COUNT(*) AS total_requests,
                    COUNT(*) FILTER (WHERE status_code >= 500) AS server_errors,
                    COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0) AS p95
                FROM sys_api_history
                """ + where(criteria, parameters) + """
                GROUP BY route_id, service_name, http_method, route_template
                ORDER BY total_requests DESC, p95 DESC
                LIMIT 8
                """;
        return jdbc.query(sql, parameters, (result, rowNumber) -> {
            long total = result.getLong("total_requests");
            long errors = result.getLong("server_errors");
            return new ApiHistoryDtos.RouteMetric(
                    result.getString("route_id"),
                    result.getString("service_name"),
                    result.getString("http_method"),
                    result.getString("route_template"),
                    total,
                    errors,
                    total == 0 ? 0 : round((errors * 100.0) / total, 2),
                    Math.round(result.getDouble("p95")));
        });
    }

    public List<ApiHistoryDtos.StatusMetric> statusDistribution(ApiHistoryCriteria criteria) {
        MapSqlParameterSource parameters = baseParameters(criteria);
        String sql = """
                SELECT ((status_code / 100)::text || 'xx') AS status_family, COUNT(*) AS count
                FROM sys_api_history
                """ + where(criteria, parameters) + """
                GROUP BY status_family
                ORDER BY status_family
                """;
        return jdbc.query(sql, parameters, (result, rowNumber) -> new ApiHistoryDtos.StatusMetric(
                result.getString("status_family"), result.getLong("count")));
    }

    public Optional<ApiHistoryDtos.EventResponse> findById(Long tenantId, UUID historyId) {
        String sql = "SELECT " + EVENT_COLUMNS + " FROM sys_api_history "
                + "WHERE tenant_id = :tenantId AND history_id = :historyId "
                + "ORDER BY occurred_at DESC LIMIT 1";
        List<ApiHistoryDtos.EventResponse> result = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("historyId", historyId),
                EVENT_MAPPER);
        return result.stream().findFirst();
    }

    public List<ApiHistoryDtos.EventResponse> findTrace(
            Long tenantId,
            String traceId,
            Instant occurredAt) {
        if (traceId == null || traceId.isBlank()) return List.of();
        String sql = "SELECT " + EVENT_COLUMNS + " FROM sys_api_history "
                + "WHERE tenant_id = :tenantId AND trace_id = :traceId "
                + "AND occurred_at BETWEEN :from AND :to "
                + "ORDER BY occurred_at ASC, history_id ASC LIMIT 100";
        return jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("traceId", traceId)
                        .addValue("from", Timestamp.from(occurredAt.minusSeconds(3_600)))
                        .addValue("to", Timestamp.from(occurredAt.plusSeconds(3_600))),
                EVENT_MAPPER);
    }

    private void ensurePartitions(List<ApiHistoryEvent> events) {
        Set<YearMonth> months = new LinkedHashSet<>();
        for (ApiHistoryEvent event : events) {
            months.add(YearMonth.from(event.occurredAt().atZone(ZoneOffset.UTC)));
        }
        for (YearMonth month : months) {
            jdbc.getJdbcTemplate().queryForObject(
                    "SELECT sys_ensure_api_history_partition(?)",
                    Object.class,
                    java.sql.Date.valueOf(month.atDay(1)));
        }
    }

    private static MapSqlParameterSource parameters(ApiHistoryEvent event) {
        return new MapSqlParameterSource()
                .addValue("historyId", event.historyId())
                .addValue("occurredAt", Timestamp.from(event.occurredAt()))
                .addValue("completedAt", Timestamp.from(event.completedAt()))
                .addValue("tenantId", event.tenantId())
                .addValue("actorType", event.actorType())
                .addValue("actorId", event.actorId())
                .addValue("authType", event.authType())
                .addValue("serviceName", event.serviceName())
                .addValue("serviceVersion", event.serviceVersion())
                .addValue("serviceInstance", event.serviceInstance())
                .addValue("environment", event.environment())
                .addValue("observationPoint", event.observationPoint())
                .addValue("routeId", event.routeId())
                .addValue("httpMethod", event.httpMethod())
                .addValue("routeTemplate", event.routeTemplate())
                .addValue("requestPath", event.requestPath())
                .addValue("httpScheme", event.httpScheme())
                .addValue("httpProtocol", event.httpProtocol())
                .addValue("statusCode", event.statusCode())
                .addValue("outcome", event.outcome())
                .addValue("durationMs", event.durationMs())
                .addValue("requestSizeBytes", event.requestSizeBytes())
                .addValue("responseSizeBytes", event.responseSizeBytes())
                .addValue("correlationId", event.correlationId())
                .addValue("traceId", event.traceId())
                .addValue("spanId", event.spanId())
                .addValue("parentSpanId", event.parentSpanId())
                .addValue("clientAddressHash", event.clientAddressHash())
                .addValue("userAgentFamily", event.userAgentFamily())
                .addValue("userAgentHash", event.userAgentHash())
                .addValue("errorType", event.errorType())
                .addValue("capturePolicyVersion", event.capturePolicyVersion());
    }

    private static MapSqlParameterSource baseParameters(ApiHistoryCriteria criteria) {
        return new MapSqlParameterSource()
                .addValue("tenantId", criteria.tenantId())
                .addValue("from", Timestamp.from(criteria.from()))
                .addValue("to", Timestamp.from(criteria.to()));
    }

    private static String where(
            ApiHistoryCriteria criteria,
            MapSqlParameterSource parameters) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = :tenantId ")
                .append("AND occurred_at >= :from AND occurred_at < :to ");
        if (!"ALL".equals(criteria.observationPoint())) {
            where.append("AND observation_point = :observationPoint ");
            parameters.addValue("observationPoint", criteria.observationPoint());
        }
        if (criteria.serviceName() != null) {
            where.append("AND (service_name = :serviceName OR route_id = :serviceName) ");
            parameters.addValue("serviceName", criteria.serviceName());
        }
        if (criteria.httpMethod() != null) {
            where.append("AND http_method = :httpMethod ");
            parameters.addValue("httpMethod", criteria.httpMethod());
        }
        if (!"ALL".equals(criteria.outcome())) {
            where.append("AND outcome = :outcome ");
            parameters.addValue("outcome", criteria.outcome());
        }
        if (criteria.query() != null) {
            String safeQuery = criteria.query().replace("%", "").replace("_", "");
            where.append("AND (route_template ILIKE :query OR request_path ILIKE :query ")
                    .append("OR correlation_id = :exactQuery OR trace_id = :exactQuery) ");
            parameters
                    .addValue("query", "%" + safeQuery + "%")
                    .addValue("exactQuery", safeQuery);
        }
        return where.toString();
    }

    private static ApiHistoryDtos.EventResponse mapEvent(ResultSet result, int rowNumber)
            throws SQLException {
        return new ApiHistoryDtos.EventResponse(
                result.getObject("history_id", UUID.class),
                instant(result, "occurred_at"),
                instant(result, "completed_at"),
                instant(result, "ingested_at"),
                nullableLong(result, "tenant_id"),
                result.getString("actor_type"),
                result.getString("actor_id"),
                result.getString("auth_type"),
                result.getString("service_name"),
                result.getString("service_version"),
                result.getString("service_instance"),
                result.getString("environment"),
                result.getString("observation_point"),
                result.getString("route_id"),
                result.getString("http_method"),
                result.getString("route_template"),
                result.getString("request_path"),
                result.getString("http_scheme"),
                result.getString("http_protocol"),
                result.getInt("status_code"),
                result.getString("outcome"),
                result.getLong("duration_ms"),
                nullableLong(result, "request_size_bytes"),
                nullableLong(result, "response_size_bytes"),
                result.getString("correlation_id"),
                result.getString("trace_id"),
                result.getString("span_id"),
                result.getString("parent_span_id"),
                result.getString("client_address_hash"),
                result.getString("user_agent_family"),
                result.getString("user_agent_hash"),
                result.getString("error_type"),
                result.getString("capture_policy_version"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
