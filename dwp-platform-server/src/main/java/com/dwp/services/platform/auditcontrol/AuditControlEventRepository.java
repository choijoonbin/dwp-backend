package com.dwp.services.platform.auditcontrol;

import com.dwp.audit.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class AuditControlEventRepository extends AuditControlJdbcRepository {
    AuditControlEventRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    public int ingest(AuditEvent event, List<String> changedFields, String recordHash) {
        jdbc.getJdbcTemplate().queryForObject(
                "SELECT sys_ensure_audit_event_partition(?)",
                Object.class,
                java.sql.Date.valueOf(event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)));
        MapSqlParameterSource parameters = eventParameters(event, changedFields, recordHash);
        int inserted = jdbc.update("""
                INSERT INTO sys_audit_events (
                    event_id, occurred_at, event_version, tenant_id, category, action,
                    outcome, severity, risk_score, actor_type, actor_id, actor_principal,
                    actor_display_name, actor_roles, source_service, source_module,
                    source_instance, environment, target_type, target_id, target_display_name,
                    reason, correlation_id, trace_id, session_id_hash, client_address_hash,
                    authentication_method, policy_id, policy_decision, approval_id,
                    before_state, after_state, changed_fields, metadata, retention_class, record_hash)
                VALUES (
                    :eventId, :occurredAt, :eventVersion, :tenantId, :category, :action,
                    :outcome, :severity, :riskScore, :actorType, :actorId, :actorPrincipal,
                    :actorDisplayName,
                    COALESCE(string_to_array(NULLIF(:actorRoles, ''), '|'), ARRAY[]::text[]),
                    :sourceService, :sourceModule, :sourceInstance, :environment,
                    :targetType, :targetId, :targetDisplayName, :reason, :correlationId,
                    :traceId, :sessionIdHash, :clientAddressHash, :authenticationMethod,
                    :policyId, :policyDecision, :approvalId, CAST(:beforeState AS jsonb),
                    CAST(:afterState AS jsonb),
                    COALESCE(string_to_array(NULLIF(:changedFields, ''), '|'), ARRAY[]::text[]),
                    CAST(:metadata AS jsonb), :retentionClass, :recordHash)
                ON CONFLICT (occurred_at, event_id) DO NOTHING
                """, parameters);
        if (inserted > 0) updateSourceHealth(event);
        return inserted;
    }

    public void createFinding(AuditEvent event, String ruleKey, String title, String description) {
        jdbc.update("""
                INSERT INTO sys_audit_findings (
                    tenant_id, event_id, event_occurred_at, finding_type, rule_key,
                    severity, risk_score, title, description, source_service, actor_id,
                    target_type, target_id, first_seen_at, last_seen_at)
                VALUES (
                    :tenantId, :eventId, :occurredAt, 'RISK_RULE', :ruleKey,
                    :severity, :riskScore, :title, :description, :sourceService, :actorId,
                    :targetType, :targetId, :occurredAt, :occurredAt)
                ON CONFLICT (tenant_id, event_id, rule_key) WHERE event_id IS NOT NULL
                DO UPDATE SET
                    severity = EXCLUDED.severity,
                    risk_score = GREATEST(sys_audit_findings.risk_score, EXCLUDED.risk_score),
                    last_seen_at = GREATEST(sys_audit_findings.last_seen_at, EXCLUDED.last_seen_at),
                    updated_at = CURRENT_TIMESTAMP
                WHERE sys_audit_findings.case_id IS NULL
                   OR NOT EXISTS (
                       SELECT 1
                         FROM sys_audit_cases audit_case
                        WHERE audit_case.case_id = sys_audit_findings.case_id
                          AND audit_case.status = 'CLOSED')
                """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", event.eventId())
                .addValue("occurredAt", Timestamp.from(event.occurredAt()))
                .addValue("ruleKey", ruleKey)
                .addValue("severity", event.severity())
                .addValue("riskScore", event.riskScore())
                .addValue("title", title)
                .addValue("description", description)
                .addValue("sourceService", event.sourceService())
                .addValue("actorId", event.actorId())
                .addValue("targetType", event.targetType())
                .addValue("targetId", event.targetId()));
    }

    public AuditControlDtos.EventPage events(AuditCriteria criteria, int page, int size) {
        MapSqlParameterSource parameters = criteriaParameters(criteria);
        String where = where(criteria, parameters);
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_events " + where,
                parameters,
                Long.class);
        parameters.addValue("limit", size).addValue("offset", (long) page * size);
        List<AuditControlDtos.Event> content = jdbc.query(
                "SELECT " + EVENT_COLUMNS + " FROM sys_audit_events " + where
                        + " ORDER BY occurred_at DESC, event_id DESC LIMIT :limit OFFSET :offset",
                parameters,
                eventMapper());
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) size);
        return new AuditControlDtos.EventPage(content, page, size, total, totalPages);
    }

    public Optional<AuditControlDtos.Event> event(Long tenantId, UUID eventId) {
        List<AuditControlDtos.Event> events = jdbc.query(
                "SELECT " + EVENT_COLUMNS + " FROM sys_audit_events "
                        + "WHERE tenant_id = :tenantId AND event_id = :eventId "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("eventId", eventId),
                eventMapper());
        return events.stream().findFirst();
    }

    public List<AuditControlDtos.Event> relatedEvents(
            Long tenantId, AuditControlDtos.Event anchor, int limit) {
        return jdbc.query(
                "SELECT " + EVENT_COLUMNS + " FROM sys_audit_events "
                        + "WHERE tenant_id = :tenantId AND event_id <> :eventId "
                        + "AND occurred_at >= :from AND occurred_at <= :to "
                        + "AND ((:correlationId IS NOT NULL AND correlation_id = :correlationId) "
                        + "OR (:actorId IS NOT NULL AND actor_id = :actorId) "
                        + "OR (target_type = :targetType AND target_id = :targetId)) "
                        + "ORDER BY risk_score DESC, occurred_at DESC LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("eventId", anchor.eventId())
                        .addValue("from", Timestamp.from(anchor.occurredAt().minusSeconds(86_400)))
                        .addValue("to", Timestamp.from(anchor.occurredAt().plusSeconds(86_400)))
                        .addValue("correlationId", anchor.correlationId(), Types.VARCHAR)
                        .addValue("actorId", anchor.actorId(), Types.VARCHAR)
                        .addValue("targetType", anchor.targetType())
                        .addValue("targetId", anchor.targetId())
                        .addValue("limit", Math.min(50, Math.max(1, limit))),
                eventMapper());
    }

    public List<AuditControlDtos.Event> exportEvents(AuditCriteria criteria, int limit) {
        MapSqlParameterSource parameters = criteriaParameters(criteria).addValue("limit", limit);
        return jdbc.query(
                "SELECT " + EVENT_COLUMNS + " FROM sys_audit_events "
                        + where(criteria, parameters)
                        + " ORDER BY occurred_at DESC, event_id DESC LIMIT :limit",
                parameters,
                eventMapper());
    }

    public AuditControlDtos.Summary summary(AuditCriteria criteria) {
        MapSqlParameterSource parameters = criteriaParameters(criteria);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) total_events,
                       COUNT(*) FILTER (WHERE severity IN ('HIGH', 'CRITICAL')) high_risk,
                       COUNT(*) FILTER (WHERE outcome = 'DENIED') denied,
                       COUNT(*) FILTER (WHERE outcome = 'FAILED') failed
                  FROM sys_audit_events
                """ + where(criteria, parameters), parameters);
        long findings = scalar("""
                SELECT COUNT(*) FROM sys_audit_findings
                 WHERE tenant_id = :tenantId
                   AND status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING')
                """, criteria.tenantId());
        long cases = scalar("""
                SELECT COUNT(*) FROM sys_audit_cases
                 WHERE tenant_id = :tenantId AND status <> 'CLOSED'
                """, criteria.tenantId());
        Map<String, Object> source = jdbc.queryForMap("""
                SELECT COUNT(*) registered,
                       COUNT(*) FILTER (WHERE delivery_status = 'HEALTHY') healthy
                  FROM sys_audit_source_health WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", criteria.tenantId()));
        return new AuditControlDtos.Summary(
                number(row.get("total_events")), number(row.get("high_risk")),
                number(row.get("denied")), number(row.get("failed")), findings, cases,
                (int) number(source.get("healthy")), (int) number(source.get("registered")));
    }

    public List<AuditControlDtos.TrendPoint> trend(AuditCriteria criteria) {
        MapSqlParameterSource parameters = criteriaParameters(criteria)
                .addValue("bucketSeconds", criteria.window().bucketSeconds());
        return jdbc.query("""
                SELECT to_timestamp(floor(extract(epoch FROM occurred_at) / :bucketSeconds)
                           * :bucketSeconds) bucket,
                       COUNT(*) total,
                       COUNT(*) FILTER (WHERE severity IN ('HIGH', 'CRITICAL')) high_risk,
                       COUNT(*) FILTER (WHERE outcome = 'DENIED') denied
                  FROM sys_audit_events
                """ + where(criteria, parameters) + " GROUP BY bucket ORDER BY bucket",
                parameters,
                (rs, row) -> new AuditControlDtos.TrendPoint(
                        rs.getTimestamp("bucket").toInstant(), rs.getLong("total"),
                        rs.getLong("high_risk"), rs.getLong("denied")));
    }

    public List<AuditControlDtos.Metric> dimension(AuditCriteria criteria, String column, int limit) {
        if (!List.of("category", "outcome", "actor_display_name").contains(column)) {
            throw new IllegalArgumentException("Unsupported audit dimension");
        }
        MapSqlParameterSource parameters = criteriaParameters(criteria).addValue("limit", limit);
        return jdbc.query(
                "SELECT COALESCE(" + column + ", 'Unknown') metric_key, COUNT(*) metric_count "
                        + "FROM sys_audit_events " + where(criteria, parameters)
                        + " GROUP BY metric_key ORDER BY metric_count DESC LIMIT :limit",
                parameters,
                (rs, row) -> new AuditControlDtos.Metric(
                        rs.getString("metric_key"), rs.getLong("metric_count")));
    }

    public List<AuditControlDtos.SourceHealth> sourceHealth(Long tenantId) {
        return jdbc.query("""
                SELECT source_service, last_event_at, last_ingested_at, event_count_24h,
                       rejected_count_24h, delivery_status, last_error
                  FROM sys_audit_source_health
                 WHERE tenant_id = :tenantId
                 ORDER BY delivery_status DESC, source_service
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new AuditControlDtos.SourceHealth(
                        rs.getString("source_service"), instant(rs, "last_event_at"),
                        instant(rs, "last_ingested_at"), rs.getLong("event_count_24h"),
                        rs.getLong("rejected_count_24h"), rs.getString("delivery_status"),
                        rs.getString("last_error")));
    }

    public List<AuditControlDtos.SavedSearch> savedSearches(Long tenantId, String actorId) {
        return jdbc.query("""
                SELECT saved_search_id, name, criteria, shared,
                       (owner_actor_id = :actorId) AS editable, owner_actor_id,
                       created_at, updated_at
                  FROM sys_audit_saved_searches
                 WHERE tenant_id = :tenantId
                   AND (owner_actor_id = :actorId OR shared = TRUE)
                 ORDER BY (owner_actor_id = :actorId) DESC, updated_at DESC, name
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("actorId", actorId), savedSearchMapper());
    }

    public AuditControlDtos.SavedSearch upsertSavedSearch(
            Long tenantId,
            String actorId,
            String name,
            Map<String, Object> criteria,
            boolean shared) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO sys_audit_saved_searches (
                    tenant_id, owner_actor_id, name, criteria, shared)
                VALUES (:tenantId, :actorId, :name, CAST(:criteria AS jsonb), :shared)
                ON CONFLICT (tenant_id, owner_actor_id, name) DO UPDATE SET
                    criteria = EXCLUDED.criteria,
                    shared = EXCLUDED.shared,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING saved_search_id
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("name", name)
                .addValue("criteria", json(criteria))
                .addValue("shared", shared), UUID.class);
        return savedSearch(tenantId, actorId, id)
                .orElseThrow(() -> new IllegalStateException("Saved audit search was not persisted"));
    }

    public boolean deleteSavedSearch(Long tenantId, String actorId, UUID savedSearchId) {
        return jdbc.update("""
                DELETE FROM sys_audit_saved_searches
                 WHERE tenant_id = :tenantId
                   AND owner_actor_id = :actorId
                   AND saved_search_id = :savedSearchId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("savedSearchId", savedSearchId)) == 1;
    }

    private Optional<AuditControlDtos.SavedSearch> savedSearch(
            Long tenantId, String actorId, UUID savedSearchId) {
        return jdbc.query("""
                SELECT saved_search_id, name, criteria, shared,
                       TRUE AS editable, owner_actor_id,
                       created_at, updated_at
                  FROM sys_audit_saved_searches
                 WHERE tenant_id = :tenantId
                   AND owner_actor_id = :actorId
                   AND saved_search_id = :savedSearchId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("savedSearchId", savedSearchId), savedSearchMapper())
                .stream().findFirst();
    }

}
