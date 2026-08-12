package com.dwp.services.platform.auditcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EventCorrelationRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final String DOMAIN_EXPRESSION = """
            CASE
              WHEN source_service = 'dwp-auth-server' THEN 'IDENTITY_ACCESS'
              WHEN source_service = 'dwp-people-server' THEN 'PEOPLE_WORKFORCE'
              WHEN source_service = 'dwp-provider-server' THEN 'PROVIDER_OPERATIONS'
              WHEN source_service = 'dwp-agent-runtime' THEN 'AI_AUTOMATION'
              WHEN category IN ('DATA_ACCESS', 'DATA_EXPORT') THEN 'DATA_GOVERNANCE'
              ELSE 'PLATFORM_WORKSPACE'
            END
            """;
    private static final String CLASSIFICATION_EXPRESSION = """
            CASE
              WHEN UPPER(metadata ->> 'classification')
                   IN ('INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')
                THEN UPPER(metadata ->> 'classification')
              WHEN retention_class = 'LEGAL_HOLD' OR category = 'DATA_EXPORT' THEN 'RESTRICTED'
              WHEN category IN ('DATA_ACCESS', 'AUTHENTICATION', 'AUTHORIZATION', 'POLICY_DENIED')
                OR severity IN ('HIGH', 'CRITICAL') THEN 'CONFIDENTIAL'
              ELSE 'INTERNAL'
            END
            """;
    private static final String CORRELATION_EXPRESSION =
            "COALESCE(NULLIF(correlation_id, ''), 'event:' || event_id::text)";
    private static final String PROJECTION = """
            WITH projected AS (
              SELECT event_id, event_version, occurred_at, ingested_at, tenant_id,
                     action AS event_type,
                     %s AS domain,
                     %s AS classification,
                     source_service, source_module, target_type, target_id,
                     target_display_name, actor_type, actor_id, actor_display_name,
                     outcome, severity, risk_score,
                     %s AS correlation_key,
                     COALESCE(metadata ->> 'causationId', metadata ->> 'parentEventId') AS causation_id,
                     trace_id, before_state, after_state, metadata, record_hash
                FROM sys_audit_events
               WHERE tenant_id = :tenantId
                 AND occurred_at >= :from
                 AND occurred_at <= :to
            )
            """.formatted(DOMAIN_EXPRESSION, CLASSIFICATION_EXPRESSION, CORRELATION_EXPRESSION);
    private static final String DETAIL_PROJECTION = """
            WITH projected AS (
              SELECT event_id, event_version, occurred_at, ingested_at, tenant_id,
                     action AS event_type,
                     %s AS domain,
                     %s AS classification,
                     source_service, source_module, target_type, target_id,
                     target_display_name, actor_type, actor_id, actor_display_name,
                     outcome, severity, risk_score,
                     %s AS correlation_key,
                     COALESCE(metadata ->> 'causationId', metadata ->> 'parentEventId') AS causation_id,
                     trace_id, before_state, after_state, metadata, record_hash
                FROM sys_audit_events
               WHERE tenant_id = :tenantId
                 AND (correlation_id = :correlationId
                      OR ('event:' || event_id::text) = :correlationId)
            )
            """.formatted(DOMAIN_EXPRESSION, CLASSIFICATION_EXPRESSION, CORRELATION_EXPRESSION);
    private static final String SUMMARY_COLUMNS = """
            correlation_key,
            MIN(occurred_at) AS first_occurred_at,
            MAX(occurred_at) AS last_occurred_at,
            COUNT(*) AS event_count,
            COUNT(DISTINCT domain) AS domain_count,
            COUNT(DISTINCT source_service) AS service_count,
            ARRAY_AGG(DISTINCT domain ORDER BY domain) AS domains,
            ARRAY_AGG(DISTINCT classification ORDER BY classification) AS classifications,
            ARRAY_AGG(DISTINCT source_service ORDER BY source_service) AS source_services,
            ARRAY_AGG(DISTINCT outcome ORDER BY outcome) AS outcomes,
            (ARRAY_AGG(event_type ORDER BY occurred_at DESC, event_id DESC))[1] AS latest_event_type,
            (ARRAY_AGG(target_type ORDER BY occurred_at DESC, event_id DESC))[1] AS latest_subject_type,
            (ARRAY_AGG(target_id ORDER BY occurred_at DESC, event_id DESC))[1] AS latest_subject_id,
            (ARRAY_AGG(target_display_name ORDER BY occurred_at DESC, event_id DESC))[1]
                AS latest_subject_display_name,
            CASE MAX(CASE severity WHEN 'CRITICAL' THEN 5 WHEN 'HIGH' THEN 4
                     WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 2 ELSE 1 END)
              WHEN 5 THEN 'CRITICAL' WHEN 4 THEN 'HIGH' WHEN 3 THEN 'MEDIUM'
              WHEN 2 THEN 'LOW' ELSE 'INFO'
            END AS max_severity,
            MAX(risk_score) AS max_risk_score,
            BOOL_OR(outcome IN ('DENIED', 'FAILED') OR severity IN ('HIGH', 'CRITICAL'))
                AS attention_required
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EventCorrelationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public EventEnvelopeDtos.CorrelationPage correlations(
            EventCorrelationCriteria criteria, int page, int size) {
        MapSqlParameterSource parameters = parameters(criteria);
        String filter = filter(criteria, parameters);
        String matching = ", matching AS (SELECT DISTINCT correlation_key FROM projected "
                + filter + ") ";
        long total = jdbc.queryForObject(
                PROJECTION + matching + "SELECT COUNT(*) FROM matching",
                parameters,
                Long.class);
        parameters.addValue("limit", size).addValue("offset", (long) page * size);
        List<EventEnvelopeDtos.Correlation> content = jdbc.query(
                PROJECTION + matching + "SELECT " + SUMMARY_COLUMNS
                        + " FROM projected JOIN matching USING (correlation_key)"
                        + " GROUP BY correlation_key"
                        + " ORDER BY last_occurred_at DESC, correlation_key"
                        + " LIMIT :limit OFFSET :offset",
                parameters,
                correlationMapper());
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) size);
        return new EventEnvelopeDtos.CorrelationPage(content, page, size, total, totalPages);
    }

    public Optional<EventEnvelopeDtos.Correlation> correlation(Long tenantId, String correlationId) {
        return jdbc.query(
                DETAIL_PROJECTION + "SELECT " + SUMMARY_COLUMNS
                        + " FROM projected GROUP BY correlation_key",
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("correlationId", correlationId),
                correlationMapper()).stream().findFirst();
    }

    public List<EventEnvelopeDtos.Envelope> envelopes(Long tenantId, String correlationId) {
        return jdbc.query(
                DETAIL_PROJECTION + "SELECT * FROM projected"
                        + " ORDER BY occurred_at, event_id",
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("correlationId", correlationId),
                envelopeMapper());
    }

    private MapSqlParameterSource parameters(EventCorrelationCriteria criteria) {
        return new MapSqlParameterSource("tenantId", criteria.tenantId())
                .addValue("from", Timestamp.from(criteria.from()))
                .addValue("to", Timestamp.from(criteria.to()));
    }

    private String filter(EventCorrelationCriteria criteria, MapSqlParameterSource parameters) {
        StringBuilder sql = new StringBuilder("WHERE 1 = 1");
        if (criteria.domain() != null) {
            sql.append(" AND domain = :domain");
            parameters.addValue("domain", criteria.domain());
        }
        if (criteria.classification() != null) {
            sql.append(" AND classification = :classification");
            parameters.addValue("classification", criteria.classification());
        }
        if (criteria.query() != null) {
            sql.append(" AND (event_type ILIKE :query OR source_service ILIKE :query")
                    .append(" OR COALESCE(actor_display_name, actor_id, '') ILIKE :query")
                    .append(" OR COALESCE(target_display_name, target_id, '') ILIKE :query")
                    .append(" OR correlation_key ILIKE :query)");
            parameters.addValue("query", like(criteria.query()));
        }
        return sql.toString();
    }

    private RowMapper<EventEnvelopeDtos.Correlation> correlationMapper() {
        return (result, ignored) -> new EventEnvelopeDtos.Correlation(
                result.getString("correlation_key"),
                instant(result, "first_occurred_at"),
                instant(result, "last_occurred_at"),
                result.getLong("event_count"),
                result.getInt("domain_count"),
                result.getInt("service_count"),
                strings(result, "domains"),
                strings(result, "classifications"),
                strings(result, "source_services"),
                strings(result, "outcomes"),
                result.getString("latest_event_type"),
                result.getString("latest_subject_type"),
                result.getString("latest_subject_id"),
                result.getString("latest_subject_display_name"),
                result.getString("max_severity"),
                result.getInt("max_risk_score"),
                result.getBoolean("attention_required"));
    }

    private RowMapper<EventEnvelopeDtos.Envelope> envelopeMapper() {
        return (result, ignored) -> new EventEnvelopeDtos.Envelope(
                result.getObject("event_id", UUID.class),
                result.getString("event_type"),
                result.getString("event_version"),
                instant(result, "occurred_at"),
                instant(result, "ingested_at"),
                result.getLong("tenant_id"),
                result.getString("domain"),
                result.getString("classification"),
                result.getString("source_service"),
                result.getString("source_module"),
                result.getString("target_type"),
                result.getString("target_id"),
                result.getString("target_display_name"),
                result.getString("actor_type"),
                result.getString("actor_id"),
                result.getString("actor_display_name"),
                result.getString("outcome"),
                result.getString("severity"),
                result.getInt("risk_score"),
                result.getString("correlation_key"),
                result.getString("causation_id"),
                result.getString("trace_id"),
                jsonMap(result.getString("before_state")),
                jsonMap(result.getString("after_state")),
                jsonMap(result.getString("metadata")),
                result.getString("record_hash"));
    }

    private List<String> strings(ResultSet result, String column) throws SQLException {
        java.sql.Array value = result.getArray(column);
        return value == null ? List.of() : List.copyOf(Arrays.asList((String[]) value.getArray()));
    }

    private Map<String, Object> jsonMap(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored event-envelope JSON is invalid", exception);
        }
    }

    private static java.time.Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String like(String value) {
        return "%" + value.replace("\\", "").replace("%", "").replace("_", "") + "%";
    }
}
