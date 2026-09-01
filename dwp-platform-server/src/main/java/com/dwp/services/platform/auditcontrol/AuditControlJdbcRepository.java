package com.dwp.services.platform.auditcontrol;

import com.dwp.audit.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

abstract class AuditControlJdbcRepository {
    protected static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    protected static final String EVENT_COLUMNS = """
            event_id, occurred_at, ingested_at, tenant_id, category, action, outcome,
            severity, risk_score, actor_type, actor_id, actor_principal, actor_display_name,
            actor_roles, source_service, source_module, source_instance, environment,
            target_type, target_id, target_display_name, reason, correlation_id, trace_id,
            authentication_method, policy_id, policy_decision, approval_id,
            before_state, after_state, changed_fields, metadata, retention_class, record_hash
            """;

    protected final NamedParameterJdbcTemplate jdbc;
    protected final ObjectMapper objectMapper;


    AuditControlJdbcRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    protected void updateSourceHealth(AuditEvent event) {
        jdbc.update("""
                INSERT INTO sys_audit_source_health (
                    tenant_id, source_service, last_event_at, last_ingested_at,
                    event_count_24h, delivery_status, updated_at)
                VALUES (:tenantId, :source, :occurredAt, CURRENT_TIMESTAMP, 1, 'HEALTHY', CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, source_service) DO UPDATE SET
                    last_event_at = GREATEST(sys_audit_source_health.last_event_at, EXCLUDED.last_event_at),
                    last_ingested_at = CURRENT_TIMESTAMP,
                    event_count_24h = CASE
                        WHEN sys_audit_source_health.updated_at < CURRENT_TIMESTAMP - INTERVAL '24 hours' THEN 1
                        ELSE sys_audit_source_health.event_count_24h + 1 END,
                    delivery_status = 'HEALTHY', last_error = NULL, updated_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", event.tenantId())
                .addValue("source", event.sourceService())
                .addValue("occurredAt", Timestamp.from(event.occurredAt())));
    }

    protected MapSqlParameterSource eventParameters(
            AuditEvent event, List<String> changedFields, String recordHash) {
        return new MapSqlParameterSource()
                .addValue("eventId", event.eventId()).addValue("occurredAt", Timestamp.from(event.occurredAt()))
                .addValue("eventVersion", event.eventVersion()).addValue("tenantId", event.tenantId())
                .addValue("category", event.category()).addValue("action", event.action())
                .addValue("outcome", event.outcome()).addValue("severity", event.severity())
                .addValue("riskScore", event.riskScore()).addValue("actorType", event.actorType())
                .addValue("actorId", event.actorId()).addValue("actorPrincipal", event.actorPrincipal())
                .addValue("actorDisplayName", event.actorDisplayName())
                .addValue("actorRoles", String.join("|", event.actorRoles()))
                .addValue("sourceService", event.sourceService()).addValue("sourceModule", event.sourceModule())
                .addValue("sourceInstance", event.sourceInstance()).addValue("environment", event.environment())
                .addValue("targetType", event.targetType()).addValue("targetId", event.targetId())
                .addValue("targetDisplayName", event.targetDisplayName()).addValue("reason", event.reason())
                .addValue("correlationId", event.correlationId()).addValue("traceId", event.traceId())
                .addValue("sessionIdHash", event.sessionIdHash()).addValue("clientAddressHash", event.clientAddressHash())
                .addValue("authenticationMethod", event.authenticationMethod()).addValue("policyId", event.policyId())
                .addValue("policyDecision", event.policyDecision()).addValue("approvalId", event.approvalId())
                .addValue("beforeState", json(event.beforeState())).addValue("afterState", json(event.afterState()))
                .addValue("changedFields", String.join("|", changedFields)).addValue("metadata", json(event.metadata()))
                .addValue("retentionClass", event.retentionClass()).addValue("recordHash", recordHash);
    }

    protected MapSqlParameterSource criteriaParameters(AuditCriteria criteria) {
        return new MapSqlParameterSource("tenantId", criteria.tenantId())
                .addValue("from", Timestamp.from(criteria.from())).addValue("to", Timestamp.from(criteria.to()));
    }

    protected MapSqlParameterSource caseParameters(Long tenantId, UUID caseId) {
        return new MapSqlParameterSource("tenantId", tenantId).addValue("caseId", caseId);
    }

    protected String where(AuditCriteria criteria, MapSqlParameterSource parameters) {
        StringBuilder sql = new StringBuilder(
                " WHERE tenant_id = :tenantId AND occurred_at >= :from AND occurred_at < :to ");
        if (!"ALL".equals(criteria.category())) {
            sql.append("AND category = :category "); parameters.addValue("category", criteria.category());
        }
        if (!"ALL".equals(criteria.severity())) {
            sql.append("AND severity = :severity "); parameters.addValue("severity", criteria.severity());
        }
        if (!"ALL".equals(criteria.outcome())) {
            sql.append("AND outcome = :outcome "); parameters.addValue("outcome", criteria.outcome());
        }
        if (criteria.sourceService() != null) {
            sql.append("AND source_service = :source "); parameters.addValue("source", criteria.sourceService());
        }
        if (criteria.actor() != null) {
            sql.append("AND (actor_id ILIKE :actor OR actor_principal ILIKE :actor OR actor_display_name ILIKE :actor) ");
            parameters.addValue("actor", like(criteria.actor()));
        }
        if (criteria.query() != null) {
            sql.append("AND (action ILIKE :query OR target_id ILIKE :query OR target_display_name ILIKE :query "
                    + "OR correlation_id ILIKE :query OR trace_id ILIKE :query) ");
            parameters.addValue("query", like(criteria.query()));
        }
        return sql.toString();
    }

    protected RowMapper<AuditControlDtos.Event> eventMapper() {
        return (rs, row) -> new AuditControlDtos.Event(
                rs.getObject("event_id", UUID.class), instant(rs, "occurred_at"), instant(rs, "ingested_at"),
                rs.getLong("tenant_id"), rs.getString("category"), rs.getString("action"),
                rs.getString("outcome"), rs.getString("severity"), rs.getInt("risk_score"),
                rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("actor_principal"),
                rs.getString("actor_display_name"), strings(rs, "actor_roles"),
                rs.getString("source_service"), rs.getString("source_module"), rs.getString("source_instance"),
                rs.getString("environment"), rs.getString("target_type"), rs.getString("target_id"),
                rs.getString("target_display_name"), rs.getString("reason"), rs.getString("correlation_id"),
                rs.getString("trace_id"), rs.getString("authentication_method"), rs.getString("policy_id"),
                rs.getString("policy_decision"), rs.getString("approval_id"), jsonMap(rs.getString("before_state")),
                jsonMap(rs.getString("after_state")), strings(rs, "changed_fields"),
                jsonMap(rs.getString("metadata")), rs.getString("retention_class"), rs.getString("record_hash"));
    }

    protected RowMapper<AuditControlDtos.Finding> findingMapper() {
        return (rs, row) -> new AuditControlDtos.Finding(
                rs.getObject("finding_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("finding_type"), rs.getString("rule_key"), rs.getString("severity"),
                rs.getInt("risk_score"), rs.getString("status"), rs.getString("title"),
                rs.getString("description"), rs.getString("source_service"), rs.getString("actor_id"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getInt("occurrence_count"),
                instant(rs, "first_seen_at"), instant(rs, "last_seen_at"), rs.getString("assigned_to"),
                rs.getObject("case_id", UUID.class), rs.getString("resolution"), instant(rs, "updated_at"));
    }

    protected RowMapper<AuditControlDtos.AuditCase> caseMapper() {
        return (rs, row) -> new AuditControlDtos.AuditCase(
                rs.getObject("case_id", UUID.class), rs.getLong("case_number"), rs.getString("title"),
                rs.getString("description"), rs.getString("severity"), rs.getString("status"),
                rs.getString("owner_actor_id"), rs.getString("resolution"), instant(rs, "opened_at"),
                instant(rs, "due_at"), rs.getString("sla_state"), instant(rs, "closed_at"),
                rs.getString("created_by"), rs.getString("updated_by"),
                instant(rs, "updated_at"), rs.getInt("linked_events"), rs.getInt("linked_findings"));
    }

    protected RowMapper<AuditControlDtos.CaseTask> caseTaskMapper() {
        return (rs, row) -> new AuditControlDtos.CaseTask(
                rs.getObject("task_id", UUID.class), rs.getString("title"),
                rs.getString("description"), rs.getString("status"), rs.getString("priority"),
                rs.getString("owner_actor_id"), instant(rs, "due_at"), instant(rs, "completed_at"),
                rs.getString("created_by"), rs.getString("updated_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    protected RowMapper<AuditControlDtos.SavedSearch> savedSearchMapper() {
        return (rs, row) -> new AuditControlDtos.SavedSearch(
                rs.getObject("saved_search_id", UUID.class), rs.getString("name"),
                jsonMap(rs.getString("criteria")), rs.getBoolean("shared"),
                rs.getBoolean("editable"), rs.getString("owner_actor_id"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    protected RowMapper<AuditControlDtos.PolicyRevision> policyRevisionMapper() {
        return (rs, row) -> {
            UUID approvalId = rs.getObject("audit_policy_approval_id", UUID.class);
            AuditControlDtos.PolicyApproval approval = approvalId == null
                    ? null
                    : new AuditControlDtos.PolicyApproval(
                            approvalId, rs.getString("approval_state"),
                            rs.getString("requested_by"), instant(rs, "requested_at"),
                            instant(rs, "expires_at"), rs.getString("decided_by"),
                            instant(rs, "decided_at"), rs.getString("decision_reason"),
                            rs.getLong("approval_version"));
            return new AuditControlDtos.PolicyRevision(
                    rs.getObject("audit_policy_revision_id", UUID.class),
                    rs.getLong("revision_number"), rs.getString("lifecycle_state"),
                    rs.getInt("standard_retention_days"),
                    rs.getInt("extended_retention_days"), rs.getInt("export_limit_rows"),
                    rs.getBoolean("require_export_reason"),
                    rs.getBoolean("integrity_enabled"), rs.getInt("high_risk_threshold"),
                    rs.getObject("baseline_revision_id", UUID.class),
                    rs.getObject("rollback_of_revision_id", UUID.class),
                    rs.getObject("incident_case_id", UUID.class),
                    rs.getString("change_reason"), jsonMap(rs.getString("diff_data")),
                    rs.getString("content_sha256"), rs.getString("created_by"),
                    instant(rs, "created_at"), rs.getString("submitted_by"),
                    instant(rs, "submitted_at"), rs.getString("published_by"),
                    instant(rs, "published_at"), rs.getLong("version"), approval);
        };
    }

    protected List<String> strings(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) return List.of();
        return List.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    protected Map<String, Object> jsonMap(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored audit JSON is invalid", exception);
        }
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit JSON cannot be serialized", exception);
        }
    }

    protected long scalar(String sql, Long tenantId) {
        Long value = jdbc.queryForObject(sql, new MapSqlParameterSource("tenantId", tenantId), Long.class);
        return value == null ? 0 : value;
    }

    protected static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    protected static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    protected static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    protected static String like(String value) {
        return "%" + value.replace("%", "").replace("_", "") + "%";
    }

    protected static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    protected static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
