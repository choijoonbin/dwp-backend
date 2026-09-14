package com.dwp.services.approval.policyimpact;

import static com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class ApprovalPolicyImpactRepository {
    public static final int LIMIT = 1000;
    private static final int BATCH = 200;
    public enum Kind { WORKFLOW, REQUEST, TASK }
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    public ApprovalPolicyImpactRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    private MapSqlParameterSource params(ApprovalPolicyImpactAuthority.Window scope) {
        return new MapSqlParameterSource().addValue("tenant", scope.tenantId()).addValue("scope", scope.resourceSetKey());
    }
    public Instant now() { return jdbc.getJdbcTemplate().queryForObject("SELECT transaction_timestamp()", java.sql.Timestamp.class).toInstant(); }
    public Head head(ApprovalPolicyImpactAuthority.Window scope, UUID id) {
        return jdbc.query("""
                SELECT p.*, h.policy_version_id, h.version_number AS published_number,
                       h.enforcement_mode AS published_mode, h.severity AS published_severity,
                       h.lifecycle_state AS published_state, h.rule_payload::text AS published_rule,
                       COALESCE(source.source_kind,'UNRECORDED_HISTORICAL_METADATA') AS metadata_provenance,
                       source.captured_at AS source_captured_at
                  FROM apr_policy_rules p JOIN apr_tenants t ON t.tenant_id=p.tenant_id AND t.lifecycle_state='ACTIVE'
                  LEFT JOIN LATERAL (SELECT * FROM apr_policy_rule_versions
                    WHERE tenant_id=p.tenant_id AND policy_id=p.policy_id ORDER BY version_number DESC LIMIT 1) h ON true
                  LEFT JOIN apr_policy_version_source_records source ON source.policy_version_id=h.policy_version_id
                    AND source.tenant_id=h.tenant_id AND source.policy_id=h.policy_id
                 WHERE p.tenant_id=:tenant AND p.management_resource_set_key=:scope AND p.policy_id=:id
                """, params(scope).addValue("id", id), rs -> {
            if (!rs.next()) throw new BaseException(ErrorCode.NOT_FOUND);
            Rules current = new Rules(rs.getString("enforcement_mode"), rs.getString("severity"),
                    rs.getString("lifecycle_state"), object(rs.getString("rule_payload")));
            String pending = rs.getString("pending_rule_payload");
            Rules proposed = pending == null ? null : new Rules(rs.getString("pending_enforcement_mode"),
                    rs.getString("pending_severity"), rs.getString("pending_lifecycle_state"), object(pending));
            var at = rs.getTimestamp("pending_at");
            Long maker = rs.getObject("pending_by", Long.class);
            if ((proposed == null) != (maker == null) || (proposed != null && at == null))
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The pending policy source is incomplete.");
            if (rs.getObject("policy_version_id") == null)
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The published policy history is unavailable.");
            Rules published = new Rules(rs.getString("published_mode"), rs.getString("published_severity"),
                    rs.getString("published_state"), object(rs.getString("published_rule")));
            if (!current.equals(published))
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The current policy differs from its published history.");
            String provenance = rs.getString("metadata_provenance");
            var capturedAt = rs.getTimestamp("source_captured_at");
            if (!("UNRECORDED_HISTORICAL_METADATA".equals(provenance) && capturedAt == null)
                    && !("LEGACY_CAPTURE_TIME".equals(provenance) && capturedAt != null))
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Policy capture provenance is invalid.");
            return new Head(id, rs.getString("policy_key"), rs.getLong("version"),
                    rs.getObject("policy_version_id", UUID.class), rs.getObject("published_number", Integer.class),
                    current, proposed, maker, at == null ? null : at.toInstant(), provenance,
                    capturedAt == null ? null : capturedAt.toInstant());
        });
    }
    private java.util.Map<String, Object> object(String raw) {
        try {
            java.util.Map<String, Object> value = json.readValue(raw, new TypeReference<java.util.Map<String, Object>>() { });
            if (value == null || value.values().stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException();
            return value;
        }
        catch (java.io.IOException | IllegalArgumentException error) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Stored policy source is invalid.");
        }
    }
    public Page scan(ApprovalPolicyImpactAuthority.Window scope, Kind kind) {
        List<Row> rows = new ArrayList<>();
        UUID cursor = null;
        while (rows.size() <= LIMIT) {
            String from = switch (kind) {
                case WORKFLOW -> """
                    FROM apr_workflow_definitions w JOIN apr_workflow_versions v ON v.tenant_id=w.tenant_id
                     AND v.workflow_id=w.workflow_id AND v.version_number=w.current_version
                    WHERE w.tenant_id=:tenant AND w.management_resource_set_key=:scope
                     AND w.lifecycle_state='PUBLISHED' AND v.lifecycle_state='PUBLISHED'
                    """;
                case REQUEST, TASK -> """
                    FROM apr_requests r JOIN apr_workflow_versions v ON v.tenant_id=r.tenant_id
                     AND v.workflow_version_id=r.workflow_version_id
                    JOIN apr_workflow_definitions w ON w.tenant_id=v.tenant_id AND w.workflow_id=v.workflow_id
                    """ + (kind == Kind.TASK ? " JOIN apr_tasks a ON a.tenant_id=r.tenant_id AND a.request_id=r.request_id "
                            + " JOIN apr_steps s ON s.tenant_id=a.tenant_id AND s.request_id=a.request_id AND s.step_id=a.step_id " : "") + """
                    WHERE r.tenant_id=:tenant AND r.management_resource_set_key=:scope
                     AND w.management_resource_set_key=:scope AND r.deleted_at IS NULL
                     AND r.status IN ('DRAFT','SUBMITTED','IN_REVIEW','NEEDS_INFO')
                    """ + (kind == Kind.TASK ? " AND a.status IN ('PENDING','CLAIMED') " : "");
            };
            String id = switch (kind) { case WORKFLOW -> "w.workflow_id"; case REQUEST -> "r.request_id"; case TASK -> "a.task_id"; };
            String version = switch (kind) { case WORKFLOW -> "w.version"; case REQUEST -> "r.version"; case TASK -> "a.version"; };
            String request = kind == Kind.WORKFLOW ? "NULL::uuid" : "r.request_id";
            int count = Math.min(BATCH, LIMIT + 1 - rows.size());
            List<Row> batch = jdbc.query("SELECT " + id + " id," + version + " row_version," + request
                    + " request_id,v.workflow_version_id,v.definition::text definition " + from
                    + (cursor == null ? "" : " AND " + id + ">:cursor ") + " ORDER BY " + id + " LIMIT :limit",
                    params(scope).addValue("cursor", cursor).addValue("limit", count), (rs, index) ->
                            new Row(rs.getObject("id", UUID.class), rs.getObject("workflow_version_id", UUID.class),
                                    rs.getObject("request_id", UUID.class), rs.getLong("row_version"), rs.getString("definition")));
            rows.addAll(batch);
            if (batch.size() < count || rows.size() == LIMIT + 1) break;
            cursor = batch.getLast().id();
        }
        return new Page(rows.subList(0, Math.min(LIMIT, rows.size())), rows.size() > LIMIT);
    }
}
