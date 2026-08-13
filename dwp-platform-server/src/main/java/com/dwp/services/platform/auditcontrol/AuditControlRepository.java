package com.dwp.services.platform.auditcontrol;

import com.dwp.audit.AuditEvent;
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

@Repository
public class AuditControlRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final String EVENT_COLUMNS = """
            event_id, occurred_at, ingested_at, tenant_id, category, action, outcome,
            severity, risk_score, actor_type, actor_id, actor_principal, actor_display_name,
            actor_roles, source_service, source_module, source_instance, environment,
            target_type, target_id, target_display_name, reason, correlation_id, trace_id,
            authentication_method, policy_id, policy_decision, approval_id,
            before_state, after_state, changed_fields, metadata, retention_class, record_hash
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditControlRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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

    public List<AuditControlDtos.Finding> findings(Long tenantId, String status, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("limit", Math.min(200, Math.max(1, limit)));
        String statusFilter = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? "" : " AND status = :status ";
        if (!statusFilter.isEmpty()) parameters.addValue("status", status.toUpperCase());
        return jdbc.query("""
                SELECT finding_id, event_id, finding_type, rule_key, severity, risk_score,
                       status, title, description, source_service, actor_id, target_type,
                       target_id, occurrence_count, first_seen_at, last_seen_at, assigned_to,
                       case_id, resolution, updated_at
                  FROM sys_audit_findings WHERE tenant_id = :tenantId
                """ + statusFilter + " ORDER BY risk_score DESC, last_seen_at DESC LIMIT :limit",
                parameters,
                findingMapper());
    }

    public Optional<AuditControlDtos.Finding> updateFinding(
            Long tenantId, UUID findingId, AuditControlDtos.FindingUpdate request) {
        jdbc.update("""
                UPDATE sys_audit_findings
                   SET status = COALESCE(:status, status),
                       assigned_to = :assignedTo,
                       resolution = :resolution,
                       case_id = :caseId,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND finding_id = :findingId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("findingId", findingId)
                .addValue("status", upper(request.status())).addValue("assignedTo", request.assignedTo())
                .addValue("resolution", request.resolution()).addValue("caseId", request.caseId()));
        return finding(tenantId, findingId);
    }

    public Optional<AuditControlDtos.Finding> finding(Long tenantId, UUID findingId) {
        return jdbc.query("""
                SELECT finding_id, event_id, finding_type, rule_key, severity, risk_score,
                       status, title, description, source_service, actor_id, target_type,
                       target_id, occurrence_count, first_seen_at, last_seen_at, assigned_to,
                       case_id, resolution, updated_at
                  FROM sys_audit_findings
                 WHERE tenant_id = :tenantId AND finding_id = :findingId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("findingId", findingId), findingMapper()).stream().findFirst();
    }

    public List<AuditControlDtos.AuditCase> cases(Long tenantId) {
        return jdbc.query("""
                SELECT c.case_id, c.case_number, c.title, c.description, c.severity, c.status,
                       c.owner_actor_id, c.resolution, c.opened_at, c.due_at,
                       CASE
                           WHEN c.status IN ('RESOLVED', 'CLOSED') THEN 'COMPLETED'
                           WHEN c.due_at < CURRENT_TIMESTAMP THEN 'BREACHED'
                           WHEN c.due_at < CURRENT_TIMESTAMP + INTERVAL '4 hours' THEN 'AT_RISK'
                           ELSE 'ON_TRACK'
                       END AS sla_state,
                       c.closed_at, c.created_by,
                       c.updated_by, c.updated_at,
                       (SELECT COUNT(*) FROM sys_audit_case_events e WHERE e.case_id = c.case_id) linked_events,
                       (SELECT COUNT(*) FROM sys_audit_findings f WHERE f.case_id = c.case_id) linked_findings
                  FROM sys_audit_cases c WHERE c.tenant_id = :tenantId
                 ORDER BY (c.status = 'CLOSED'), c.updated_at DESC
                """, new MapSqlParameterSource("tenantId", tenantId), caseMapper());
    }

    public Optional<AuditControlDtos.AuditCase> caseById(Long tenantId, UUID caseId) {
        return cases(tenantId).stream().filter(item -> item.caseId().equals(caseId)).findFirst();
    }

    public void lockCase(Long tenantId, UUID caseId) {
        jdbc.query(
                """
                SELECT case_id
                  FROM sys_audit_cases
                 WHERE tenant_id = :tenantId AND case_id = :caseId
                 FOR UPDATE
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("caseId", caseId),
                (ResultSet resultSet) -> {
                    if (resultSet.next()) resultSet.getObject(1);
                    return null;
                });
    }

    public UUID createCase(Long tenantId, String actorId, AuditControlDtos.CaseCreate request) {
        return jdbc.queryForObject("""
                INSERT INTO sys_audit_cases (
                    tenant_id, title, description, severity, owner_actor_id,
                    due_at, created_by, updated_by)
                VALUES (
                    :tenantId, :title, :description, :severity, :owner,
                    CURRENT_TIMESTAMP + CASE :severity
                        WHEN 'CRITICAL' THEN INTERVAL '4 hours'
                        WHEN 'HIGH' THEN INTERVAL '1 day'
                        WHEN 'MEDIUM' THEN INTERVAL '3 days'
                        ELSE INTERVAL '7 days'
                    END,
                    :actor, :actor)
                RETURNING case_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("title", request.title())
                .addValue("description", request.description()).addValue("severity", upper(request.severity()))
                .addValue("owner", request.ownerActorId()).addValue("actor", actorId), UUID.class);
    }

    public void updateCase(Long tenantId, UUID caseId, String actorId, AuditControlDtos.CaseUpdate request) {
        String status = upper(request.status());
        jdbc.update("""
                UPDATE sys_audit_cases
                   SET title = COALESCE(:title, title),
                       description = COALESCE(:description, description),
                       severity = COALESCE(:severity, severity),
                       status = COALESCE(:status, status),
                       owner_actor_id = :owner,
                       resolution = :resolution,
                       closed_at = CASE WHEN :status = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                       updated_by = :actor,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND case_id = :caseId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("caseId", caseId)
                .addValue("title", request.title()).addValue("description", request.description())
                .addValue("severity", upper(request.severity())).addValue("status", status)
                .addValue("owner", request.ownerActorId()).addValue("resolution", request.resolution())
                .addValue("actor", actorId));
    }

    public int linkEvent(Long tenantId, UUID caseId, String actorId, AuditControlDtos.CaseEventLink request) {
        return jdbc.update("""
                INSERT INTO sys_audit_case_events (
                    case_id, event_id, event_occurred_at, added_by, note)
                SELECT c.case_id, e.event_id, e.occurred_at, :actor, :note
                  FROM sys_audit_cases c
                  JOIN sys_audit_events e ON e.tenant_id = c.tenant_id
                 WHERE c.tenant_id = :tenantId AND c.case_id = :caseId
                   AND e.event_id = :eventId AND e.occurred_at = :occurredAt
                ON CONFLICT (case_id, event_id) DO UPDATE SET note = EXCLUDED.note
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("caseId", caseId)
                .addValue("eventId", request.eventId())
                .addValue("occurredAt", Timestamp.from(request.occurredAt()))
                .addValue("actor", actorId).addValue("note", request.note()));
    }

    public List<AuditControlDtos.Finding> caseFindings(Long tenantId, UUID caseId) {
        return jdbc.query("""
                SELECT f.finding_id, f.event_id, f.finding_type, f.rule_key, f.severity,
                       f.risk_score, f.status, f.title, f.description, f.source_service,
                       f.actor_id, f.target_type, f.target_id, f.occurrence_count,
                       f.first_seen_at, f.last_seen_at, f.assigned_to, f.case_id,
                       f.resolution, f.updated_at
                  FROM sys_audit_findings f
                  JOIN sys_audit_cases c ON c.case_id = f.case_id
                 WHERE c.tenant_id = :tenantId AND c.case_id = :caseId
                 ORDER BY f.risk_score DESC, f.last_seen_at DESC
                """, caseParameters(tenantId, caseId), findingMapper());
    }

    public List<AuditControlDtos.Event> caseEvidence(Long tenantId, UUID caseId) {
        return jdbc.query("""
                SELECT e.*
                  FROM sys_audit_case_events link
                  JOIN sys_audit_cases c ON c.case_id = link.case_id
                  JOIN sys_audit_events e
                    ON e.tenant_id = c.tenant_id
                   AND e.event_id = link.event_id
                   AND e.occurred_at = link.event_occurred_at
                 WHERE c.tenant_id = :tenantId AND c.case_id = :caseId
                 ORDER BY e.occurred_at DESC, e.event_id DESC
                """, caseParameters(tenantId, caseId), eventMapper());
    }

    public List<AuditControlDtos.CaseEntity> caseEntities(Long tenantId, UUID caseId) {
        return jdbc.query("""
                SELECT entity_type, entity_id, display_name, relationship, risk_score,
                       first_seen_at, last_seen_at, attributes
                  FROM sys_audit_case_entities
                 WHERE tenant_id = :tenantId AND case_id = :caseId
                 ORDER BY risk_score DESC, entity_type, display_name, entity_id
                """, caseParameters(tenantId, caseId), (rs, row) -> new AuditControlDtos.CaseEntity(
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("display_name"),
                rs.getString("relationship"), rs.getInt("risk_score"),
                instant(rs, "first_seen_at"), instant(rs, "last_seen_at"),
                jsonMap(rs.getString("attributes"))));
    }

    public List<AuditControlDtos.CaseActivity> caseActivities(Long tenantId, UUID caseId) {
        return jdbc.query("""
                SELECT activity_id, activity_type, actor_id, message, payload, occurred_at
                  FROM sys_audit_case_activities
                 WHERE tenant_id = :tenantId AND case_id = :caseId
                 ORDER BY occurred_at DESC, activity_id DESC
                """, caseParameters(tenantId, caseId), (rs, row) -> new AuditControlDtos.CaseActivity(
                rs.getObject("activity_id", UUID.class), rs.getString("activity_type"),
                rs.getString("actor_id"), rs.getString("message"),
                jsonMap(rs.getString("payload")), instant(rs, "occurred_at")));
    }

    public List<AuditControlDtos.CaseTask> caseTasks(Long tenantId, UUID caseId) {
        return jdbc.query("""
                SELECT task_id, title, description, status, priority, owner_actor_id,
                       due_at, completed_at, created_by, updated_by, created_at, updated_at
                  FROM sys_audit_case_tasks
                 WHERE tenant_id = :tenantId AND case_id = :caseId
                 ORDER BY (status IN ('DONE', 'SKIPPED')), due_at NULLS LAST,
                          CASE priority WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2
                               WHEN 'MEDIUM' THEN 3 ELSE 4 END, created_at
                """, caseParameters(tenantId, caseId), caseTaskMapper());
    }

    public UUID createCaseClosureReport(
            Long tenantId, UUID caseId, String actorId, Map<String, Object> report, String sha256) {
        return jdbc.queryForObject("""
                INSERT INTO sys_audit_case_closure_reports (
                    case_id, tenant_id, report_version, report_data, content_sha256, generated_by)
                SELECT c.case_id, c.tenant_id,
                       COALESCE((
                           SELECT MAX(existing.report_version) + 1
                             FROM sys_audit_case_closure_reports existing
                            WHERE existing.tenant_id = c.tenant_id
                              AND existing.case_id = c.case_id
                       ), 1),
                       CAST(:report AS jsonb), :sha256, :actor
                  FROM sys_audit_cases c
                 WHERE c.tenant_id = :tenantId AND c.case_id = :caseId
                   AND c.status = 'CLOSED'
                RETURNING report_id
                """, caseParameters(tenantId, caseId)
                .addValue("report", json(report)).addValue("sha256", sha256)
                .addValue("actor", actorId), UUID.class);
    }

    public Optional<AuditControlDtos.CaseClosureReport> latestCaseClosureReport(
            Long tenantId, UUID caseId) {
        return jdbc.query("""
                SELECT report.report_id, report.case_id, audit_case.case_number,
                       report.report_version, report.content_sha256, report.generated_by,
                       report.generated_at, report.report_data
                  FROM sys_audit_case_closure_reports report
                  JOIN sys_audit_cases audit_case
                    ON audit_case.case_id = report.case_id
                   AND audit_case.tenant_id = report.tenant_id
                 WHERE report.tenant_id = :tenantId AND report.case_id = :caseId
                 ORDER BY report.report_version DESC
                 LIMIT 1
                """, caseParameters(tenantId, caseId), (rs, row) ->
                new AuditControlDtos.CaseClosureReport(
                        rs.getObject("report_id", UUID.class), rs.getObject("case_id", UUID.class),
                        rs.getLong("case_number"), rs.getInt("report_version"),
                        rs.getString("content_sha256"), rs.getString("generated_by"),
                        instant(rs, "generated_at"), jsonMap(rs.getString("report_data"))))
                .stream().findFirst();
    }

    public UUID createCaseTask(
            Long tenantId, UUID caseId, String actorId, AuditControlDtos.CaseTaskCreate request) {
        return jdbc.queryForObject("""
                INSERT INTO sys_audit_case_tasks (
                    case_id, tenant_id, title, description, priority,
                    owner_actor_id, due_at, created_by, updated_by)
                SELECT c.case_id, c.tenant_id, :title, :description, :priority,
                       :owner, :dueAt, :actor, :actor
                  FROM sys_audit_cases c
                 WHERE c.tenant_id = :tenantId AND c.case_id = :caseId
                RETURNING task_id
                """, caseParameters(tenantId, caseId)
                .addValue("title", request.title()).addValue("description", request.description())
                .addValue("priority", request.priority()).addValue("owner", request.ownerActorId())
                .addValue("dueAt", timestamp(request.dueAt())).addValue("actor", actorId), UUID.class);
    }

    public Optional<AuditControlDtos.CaseTask> updateCaseTask(
            Long tenantId, UUID caseId, UUID taskId, String actorId,
            AuditControlDtos.CaseTaskUpdate request) {
        jdbc.update("""
                UPDATE sys_audit_case_tasks task
                   SET title = COALESCE(:title, task.title),
                       description = COALESCE(:description, task.description),
                       status = COALESCE(:status, task.status),
                       priority = COALESCE(:priority, task.priority),
                       owner_actor_id = COALESCE(:owner, task.owner_actor_id),
                       due_at = COALESCE(:dueAt, task.due_at),
                       completed_at = CASE
                           WHEN :status = 'DONE' THEN CURRENT_TIMESTAMP
                           WHEN :status IS NOT NULL THEN NULL
                           ELSE task.completed_at
                       END,
                       updated_by = :actor,
                       updated_at = CURRENT_TIMESTAMP
                  FROM sys_audit_cases c
                 WHERE task.case_id = c.case_id
                   AND task.tenant_id = c.tenant_id
                   AND c.tenant_id = :tenantId
                   AND c.case_id = :caseId
                   AND task.task_id = :taskId
                """, caseParameters(tenantId, caseId)
                .addValue("taskId", taskId).addValue("title", request.title())
                .addValue("description", request.description()).addValue("status", request.status())
                .addValue("priority", request.priority()).addValue("owner", request.ownerActorId())
                .addValue("dueAt", timestamp(request.dueAt())).addValue("actor", actorId));
        return caseTask(tenantId, caseId, taskId);
    }

    public Optional<AuditControlDtos.CaseTask> caseTask(Long tenantId, UUID caseId, UUID taskId) {
        return jdbc.query("""
                SELECT task_id, title, description, status, priority, owner_actor_id,
                       due_at, completed_at, created_by, updated_by, created_at, updated_at
                  FROM sys_audit_case_tasks
                 WHERE tenant_id = :tenantId AND case_id = :caseId AND task_id = :taskId
                """, caseParameters(tenantId, caseId).addValue("taskId", taskId), caseTaskMapper())
                .stream().findFirst();
    }

    public int recordCaseActivity(
            Long tenantId, UUID caseId, String activityType, String actorId,
            String message, Map<String, Object> payload) {
        return jdbc.update("""
                INSERT INTO sys_audit_case_activities (
                    case_id, tenant_id, activity_type, actor_id, message, payload)
                SELECT c.case_id, c.tenant_id, :activityType, :actor, :message, CAST(:payload AS jsonb)
                  FROM sys_audit_cases c
                 WHERE c.tenant_id = :tenantId AND c.case_id = :caseId
                """, caseParameters(tenantId, caseId)
                .addValue("activityType", activityType).addValue("actor", actorId)
                .addValue("message", message).addValue("payload", json(payload)));
    }

    public void upsertCaseEntity(
            Long tenantId, UUID caseId, String actorId, AuditControlDtos.CaseEntity entity) {
        jdbc.update("""
                INSERT INTO sys_audit_case_entities (
                    case_id, tenant_id, entity_type, entity_id, display_name, relationship,
                    risk_score, first_seen_at, last_seen_at, attributes, added_by)
                SELECT c.case_id, c.tenant_id, :entityType, :entityId, :displayName, :relationship,
                       :riskScore, :firstSeenAt, :lastSeenAt, CAST(:attributes AS jsonb), :actor
                  FROM sys_audit_cases c
                 WHERE c.tenant_id = :tenantId AND c.case_id = :caseId
                ON CONFLICT (case_id, entity_type, entity_id) DO UPDATE SET
                    display_name = COALESCE(EXCLUDED.display_name, sys_audit_case_entities.display_name),
                    relationship = EXCLUDED.relationship,
                    risk_score = GREATEST(sys_audit_case_entities.risk_score, EXCLUDED.risk_score),
                    first_seen_at = LEAST(sys_audit_case_entities.first_seen_at, EXCLUDED.first_seen_at),
                    last_seen_at = GREATEST(sys_audit_case_entities.last_seen_at, EXCLUDED.last_seen_at),
                    attributes = sys_audit_case_entities.attributes || EXCLUDED.attributes
                """, caseParameters(tenantId, caseId)
                .addValue("entityType", entity.entityType()).addValue("entityId", entity.entityId())
                .addValue("displayName", entity.displayName()).addValue("relationship", entity.relationship())
                .addValue("riskScore", entity.riskScore()).addValue("firstSeenAt", timestamp(entity.firstSeenAt()))
                .addValue("lastSeenAt", timestamp(entity.lastSeenAt()))
                .addValue("attributes", json(entity.attributes())).addValue("actor", actorId));
    }

    public AuditControlDtos.RetentionPolicy policy(Long tenantId) {
        jdbc.update("""
                INSERT INTO sys_audit_retention_policies (tenant_id)
                VALUES (:tenantId) ON CONFLICT (tenant_id) DO NOTHING
                """, new MapSqlParameterSource("tenantId", tenantId));
        ensurePolicyBaseline(tenantId);
        return jdbc.queryForObject("""
                SELECT standard_retention_days, extended_retention_days, export_limit_rows,
                       require_export_reason, integrity_enabled, high_risk_threshold,
                       updated_by, updated_at, active_revision_id, active_revision_number
                  FROM sys_audit_retention_policies WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new AuditControlDtos.RetentionPolicy(
                        rs.getInt("standard_retention_days"), rs.getInt("extended_retention_days"),
                        rs.getInt("export_limit_rows"), rs.getBoolean("require_export_reason"),
                        rs.getBoolean("integrity_enabled"), rs.getInt("high_risk_threshold"),
                        rs.getString("updated_by"), instant(rs, "updated_at"),
                        rs.getObject("active_revision_id", UUID.class),
                        rs.getLong("active_revision_number")));
    }

    public List<AuditControlDtos.PolicyRevision> policyRevisions(Long tenantId) {
        policy(tenantId);
        expirePolicyApprovals(tenantId);
        return jdbc.query("""
                SELECT revision.audit_policy_revision_id, revision.revision_number,
                       revision.lifecycle_state, revision.standard_retention_days,
                       revision.extended_retention_days, revision.export_limit_rows,
                       revision.require_export_reason, revision.integrity_enabled,
                       revision.high_risk_threshold, revision.baseline_revision_id,
                       revision.rollback_of_revision_id, revision.incident_case_id,
                       revision.change_reason, revision.diff_data::text,
                       revision.content_sha256, revision.created_by, revision.created_at,
                       revision.submitted_by, revision.submitted_at,
                       revision.published_by, revision.published_at, revision.version,
                       approval.audit_policy_approval_id, approval.lifecycle_state AS approval_state,
                       approval.requested_by, approval.requested_at, approval.expires_at,
                       approval.decided_by, approval.decided_at, approval.decision_reason,
                       approval.version AS approval_version
                  FROM sys_audit_policy_revisions revision
                  LEFT JOIN sys_audit_policy_approvals approval
                    ON approval.audit_policy_revision_id = revision.audit_policy_revision_id
                 WHERE revision.tenant_id = :tenantId
                 ORDER BY revision.revision_number DESC
                """, new MapSqlParameterSource("tenantId", tenantId), policyRevisionMapper());
    }

    public Optional<AuditControlDtos.PolicyRevision> policyRevision(Long tenantId, UUID revisionId) {
        return policyRevisions(tenantId).stream()
                .filter(revision -> revision.revisionId().equals(revisionId))
                .findFirst();
    }

    public UUID createPolicyRevision(
            Long tenantId,
            String actorId,
            AuditControlDtos.PolicyRevisionCreate request,
            UUID baselineRevisionId,
            UUID rollbackOfRevisionId,
            Map<String, Object> diff,
            String contentSha256) {
        lockPolicy(tenantId);
        return jdbc.queryForObject("""
                INSERT INTO sys_audit_policy_revisions (
                    tenant_id, revision_number, lifecycle_state,
                    standard_retention_days, extended_retention_days,
                    export_limit_rows, require_export_reason, integrity_enabled,
                    high_risk_threshold, baseline_revision_id, rollback_of_revision_id,
                    incident_case_id, change_reason, diff_data, content_sha256, created_by)
                SELECT :tenantId, COALESCE(MAX(revision_number), 0) + 1, 'DRAFT',
                       :standardDays, :extendedDays, :exportLimit, :requireReason,
                       :integrityEnabled, :threshold, :baselineRevisionId,
                       :rollbackOfRevisionId, :incidentCaseId, :changeReason,
                       CAST(:diff AS jsonb), :contentSha256, :actor
                  FROM sys_audit_policy_revisions
                 WHERE tenant_id = :tenantId
                RETURNING audit_policy_revision_id
                """, policyRevisionParameters(tenantId, request)
                .addValue("baselineRevisionId", baselineRevisionId)
                .addValue("rollbackOfRevisionId", rollbackOfRevisionId)
                .addValue("diff", json(diff))
                .addValue("contentSha256", contentSha256)
                .addValue("actor", actorId), UUID.class);
    }

    public boolean submitPolicyRevision(
            Long tenantId, UUID revisionId, String actorId, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE sys_audit_policy_revisions
                   SET lifecycle_state = 'IN_REVIEW', submitted_by = :actor,
                       submitted_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = :tenantId AND audit_policy_revision_id = :revisionId
                   AND lifecycle_state = 'DRAFT' AND version = :version
                """, revisionParameters(tenantId, revisionId, expectedVersion)
                .addValue("actor", actorId));
        if (updated != 1) return false;
        jdbc.update("""
                INSERT INTO sys_audit_policy_approvals (
                    audit_policy_revision_id, tenant_id, requested_by)
                VALUES (:revisionId, :tenantId, :actor)
                """, revisionParameters(tenantId, revisionId, expectedVersion)
                .addValue("actor", actorId));
        return true;
    }

    public boolean decidePolicyRevision(
            Long tenantId,
            UUID revisionId,
            UUID approvalId,
            String actorId,
            String decision,
            String reason,
            long expectedApprovalVersion) {
        int approvalUpdated = jdbc.update("""
                UPDATE sys_audit_policy_approvals
                   SET lifecycle_state = :decision, decided_by = :actor,
                       decided_at = CURRENT_TIMESTAMP, decision_reason = :reason,
                       version = version + 1
                 WHERE tenant_id = :tenantId
                   AND audit_policy_revision_id = :revisionId
                   AND audit_policy_approval_id = :approvalId
                   AND lifecycle_state = 'PENDING' AND expires_at > CURRENT_TIMESTAMP
                   AND requested_by <> :actor AND version = :version
                """, revisionParameters(tenantId, revisionId, expectedApprovalVersion)
                .addValue("approvalId", approvalId).addValue("actor", actorId)
                .addValue("decision", decision).addValue("reason", reason));
        if (approvalUpdated != 1) return false;
        int revisionUpdated = jdbc.update("""
                UPDATE sys_audit_policy_revisions
                   SET lifecycle_state = :decision, version = version + 1
                 WHERE tenant_id = :tenantId AND audit_policy_revision_id = :revisionId
                   AND lifecycle_state = 'IN_REVIEW'
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId).addValue("decision", decision));
        if (revisionUpdated != 1) {
            throw new IllegalStateException("Audit policy approval lost revision consistency.");
        }
        return true;
    }

    public boolean publishPolicyRevision(
            Long tenantId, UUID revisionId, String actorId, long expectedVersion) {
        lockPolicy(tenantId);
        int revisionUpdated = jdbc.update("""
                UPDATE sys_audit_policy_revisions
                   SET lifecycle_state = 'PUBLISHED', published_by = :actor,
                       published_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = :tenantId AND audit_policy_revision_id = :revisionId
                   AND lifecycle_state = 'APPROVED' AND version = :version
                """, revisionParameters(tenantId, revisionId, expectedVersion)
                .addValue("actor", actorId));
        if (revisionUpdated != 1) return false;
        jdbc.update("""
                UPDATE sys_audit_policy_revisions
                   SET lifecycle_state = 'SUPERSEDED', version = version + 1
                 WHERE tenant_id = :tenantId AND lifecycle_state = 'PUBLISHED'
                   AND audit_policy_revision_id <> :revisionId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId));
        int activated = jdbc.update("""
                UPDATE sys_audit_retention_policies policy
                   SET standard_retention_days = revision.standard_retention_days,
                       extended_retention_days = revision.extended_retention_days,
                       export_limit_rows = revision.export_limit_rows,
                       require_export_reason = revision.require_export_reason,
                       integrity_enabled = revision.integrity_enabled,
                       high_risk_threshold = revision.high_risk_threshold,
                       active_revision_id = revision.audit_policy_revision_id,
                       active_revision_number = revision.revision_number,
                       updated_by = :actor, updated_at = CURRENT_TIMESTAMP
                  FROM sys_audit_policy_revisions revision
                 WHERE policy.tenant_id = :tenantId
                   AND revision.tenant_id = policy.tenant_id
                   AND revision.audit_policy_revision_id = :revisionId
                   AND revision.lifecycle_state = 'PUBLISHED'
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId).addValue("actor", actorId));
        if (activated != 1) {
            throw new IllegalStateException("Audit policy publication lost active-policy consistency.");
        }
        return true;
    }

    private void ensurePolicyBaseline(Long tenantId) {
        String baseline = jdbc.queryForObject("""
                SELECT concat_ws('|', standard_retention_days, extended_retention_days,
                                  export_limit_rows, require_export_reason,
                                  integrity_enabled, high_risk_threshold)
                  FROM sys_audit_retention_policies
                 WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), String.class);
        jdbc.update("""
                INSERT INTO sys_audit_policy_revisions (
                    tenant_id, revision_number, lifecycle_state,
                    standard_retention_days, extended_retention_days,
                    export_limit_rows, require_export_reason, integrity_enabled,
                    high_risk_threshold, change_reason, diff_data, content_sha256,
                    created_by, published_by, published_at)
                SELECT policy.tenant_id, 1, 'PUBLISHED',
                       policy.standard_retention_days, policy.extended_retention_days,
                       policy.export_limit_rows, policy.require_export_reason,
                       policy.integrity_enabled, policy.high_risk_threshold,
                       'Initial governed baseline', '{}'::jsonb,
                       :contentSha256,
                       COALESCE(policy.updated_by, 'SYSTEM'),
                       COALESCE(policy.updated_by, 'SYSTEM'), policy.updated_at
                  FROM sys_audit_retention_policies policy
                 WHERE policy.tenant_id = :tenantId
                   AND NOT EXISTS (
                       SELECT 1 FROM sys_audit_policy_revisions revision
                        WHERE revision.tenant_id = policy.tenant_id)
                ON CONFLICT (tenant_id, revision_number) DO NOTHING
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("contentSha256", sha256(baseline)));
        jdbc.update("""
                UPDATE sys_audit_retention_policies policy
                   SET active_revision_id = revision.audit_policy_revision_id,
                       active_revision_number = revision.revision_number
                  FROM sys_audit_policy_revisions revision
                 WHERE policy.tenant_id = :tenantId
                   AND policy.active_revision_id IS NULL
                   AND revision.tenant_id = policy.tenant_id
                   AND revision.lifecycle_state = 'PUBLISHED'
                """, new MapSqlParameterSource("tenantId", tenantId));
    }

    private void expirePolicyApprovals(Long tenantId) {
        jdbc.update("""
                UPDATE sys_audit_policy_approvals
                   SET lifecycle_state = 'EXPIRED', version = version + 1
                 WHERE tenant_id = :tenantId AND lifecycle_state = 'PENDING'
                   AND expires_at <= CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", tenantId));
        jdbc.update("""
                UPDATE sys_audit_policy_revisions revision
                   SET lifecycle_state = 'CANCELLED', version = version + 1
                 WHERE revision.tenant_id = :tenantId
                   AND revision.lifecycle_state = 'IN_REVIEW'
                   AND EXISTS (
                       SELECT 1 FROM sys_audit_policy_approvals approval
                        WHERE approval.audit_policy_revision_id = revision.audit_policy_revision_id
                          AND approval.lifecycle_state = 'EXPIRED')
                """, new MapSqlParameterSource("tenantId", tenantId));
    }

    private void lockPolicy(Long tenantId) {
        jdbc.queryForObject("""
                SELECT tenant_id FROM sys_audit_retention_policies
                 WHERE tenant_id = :tenantId FOR UPDATE
                """, new MapSqlParameterSource("tenantId", tenantId), Long.class);
    }

    private MapSqlParameterSource policyRevisionParameters(
            Long tenantId, AuditControlDtos.PolicyRevisionCreate request) {
        return new MapSqlParameterSource("tenantId", tenantId)
                .addValue("standardDays", request.standardRetentionDays())
                .addValue("extendedDays", request.extendedRetentionDays())
                .addValue("exportLimit", request.exportLimitRows())
                .addValue("requireReason", request.requireExportReason())
                .addValue("integrityEnabled", request.integrityEnabled())
                .addValue("threshold", request.highRiskThreshold())
                .addValue("incidentCaseId", request.incidentCaseId())
                .addValue("changeReason", request.reason().trim());
    }

    private MapSqlParameterSource revisionParameters(
            Long tenantId, UUID revisionId, long version) {
        return new MapSqlParameterSource("tenantId", tenantId)
                .addValue("revisionId", revisionId)
                .addValue("version", version);
    }

    public UUID createExport(Long tenantId, String actorId, String criteria, String format) {
        return jdbc.queryForObject("""
                INSERT INTO sys_audit_export_jobs (tenant_id, requested_by, criteria, format, status)
                VALUES (:tenantId, :actor, CAST(:criteria AS jsonb), :format, 'RUNNING')
                RETURNING export_job_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("actor", actorId)
                .addValue("criteria", criteria).addValue("format", format), UUID.class);
    }

    public void completeExport(UUID exportId, byte[] content, int rows, String sha256) {
        jdbc.update("""
                UPDATE sys_audit_export_jobs SET status = 'COMPLETED', content = :content,
                       row_count = :rows, content_sha256 = :sha256,
                       completed_at = CURRENT_TIMESTAMP, expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours'
                 WHERE export_job_id = :exportId
                """, new MapSqlParameterSource("exportId", exportId)
                .addValue("content", content).addValue("rows", rows).addValue("sha256", sha256));
    }

    public Optional<byte[]> exportContent(Long tenantId, UUID exportId) {
        List<byte[]> rows = jdbc.query("""
                SELECT content FROM sys_audit_export_jobs
                 WHERE tenant_id = :tenantId AND export_job_id = :exportId
                   AND status = 'COMPLETED' AND expires_at > CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("exportId", exportId),
                (rs, row) -> rs.getBytes("content"));
        return rows.stream().findFirst();
    }

    public Optional<AuditControlDtos.ExportJob> exportJob(Long tenantId, UUID exportId) {
        List<AuditControlDtos.ExportJob> rows = jdbc.query("""
                SELECT export_job_id, format, status, row_count, content_sha256, error_message,
                       requested_at, completed_at, expires_at
                  FROM sys_audit_export_jobs
                 WHERE tenant_id = :tenantId AND export_job_id = :exportId
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("exportId", exportId),
                (rs, row) -> new AuditControlDtos.ExportJob(
                        rs.getObject("export_job_id", UUID.class), rs.getString("format"),
                        rs.getString("status"), (Integer) rs.getObject("row_count"),
                        rs.getString("content_sha256"), rs.getString("error_message"),
                        instant(rs, "requested_at"), instant(rs, "completed_at"), instant(rs, "expires_at")));
        return rows.stream().findFirst();
    }

    public List<AuditControlDtos.IntegrityCheckpoint> integrity(Long tenantId) {
        return jdbc.query("""
                SELECT checkpoint_id, checkpoint_date, record_count, first_event_at, last_event_at,
                       root_hash, checkpoint_hash, signature_algorithm, verification_status,
                       created_at, verified_at
                  FROM sys_audit_integrity_checkpoints
                 WHERE tenant_id = :tenantId ORDER BY checkpoint_date DESC LIMIT 90
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new AuditControlDtos.IntegrityCheckpoint(
                        rs.getObject("checkpoint_id", UUID.class), rs.getObject("checkpoint_date", LocalDate.class),
                        rs.getLong("record_count"), instant(rs, "first_event_at"), instant(rs, "last_event_at"),
                        rs.getString("root_hash"), rs.getString("checkpoint_hash"),
                        rs.getString("signature_algorithm"), rs.getString("verification_status"),
                        instant(rs, "created_at"), instant(rs, "verified_at")));
    }

    public Map<String, Object> integritySource(Long tenantId, LocalDate date) {
        return jdbc.queryForMap("""
                SELECT COUNT(*) record_count, MIN(occurred_at) first_event_at,
                       MAX(occurred_at) last_event_at,
                       COALESCE(string_agg(record_hash, '' ORDER BY occurred_at, event_id), '') hashes
                  FROM sys_audit_events
                 WHERE tenant_id = :tenantId
                   AND occurred_at >= :from AND occurred_at < :to
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("from", Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC)))
                .addValue("to", Timestamp.from(date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))));
    }

    public String previousCheckpointHash(Long tenantId, LocalDate date) {
        List<String> rows = jdbc.query("""
                SELECT checkpoint_hash FROM sys_audit_integrity_checkpoints
                 WHERE tenant_id = :tenantId AND checkpoint_date < :date
                 ORDER BY checkpoint_date DESC LIMIT 1
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("date", date),
                (rs, row) -> rs.getString(1));
        return rows.stream().findFirst().orElse(null);
    }

    public void saveCheckpoint(
            Long tenantId, LocalDate date, long count, Instant first, Instant last,
            String rootHash, String previous, String checkpointHash, String signature,
            String verificationStatus) {
        jdbc.update("""
                INSERT INTO sys_audit_integrity_checkpoints (
                    tenant_id, checkpoint_date, record_count, first_event_at, last_event_at,
                    root_hash, previous_checkpoint_hash, checkpoint_hash, signature,
                    verification_status, verified_at)
                VALUES (:tenantId, :date, :count, :first, :last, :root, :previous,
                        :checkpoint, :signature, :status, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, checkpoint_date) DO UPDATE SET
                    verification_status = EXCLUDED.verification_status,
                    verified_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("date", date)
                .addValue("count", count).addValue("first", timestamp(first)).addValue("last", timestamp(last))
                .addValue("root", rootHash).addValue("previous", previous)
                .addValue("checkpoint", checkpointHash).addValue("signature", signature)
                .addValue("status", verificationStatus));
    }

    public List<Long> activeTenants() {
        return jdbc.query("SELECT DISTINCT tenant_id FROM sys_audit_events",
                (rs, row) -> rs.getLong(1));
    }

    public int applyRetention() {
        jdbc.getJdbcTemplate().execute("SET LOCAL dwp.audit_retention_bypass = 'on'");
        return jdbc.getJdbcTemplate().update("""
                DELETE FROM sys_audit_events event
                 USING sys_audit_retention_policies policy
                 WHERE event.tenant_id = policy.tenant_id
                   AND event.retention_class <> 'LEGAL_HOLD'
                   AND event.occurred_at < CURRENT_TIMESTAMP -
                       make_interval(days => CASE
                           WHEN event.retention_class = 'EXTENDED'
                               THEN policy.extended_retention_days
                           ELSE policy.standard_retention_days END)
                """);
    }

    private void updateSourceHealth(AuditEvent event) {
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

    private MapSqlParameterSource eventParameters(
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

    private MapSqlParameterSource criteriaParameters(AuditCriteria criteria) {
        return new MapSqlParameterSource("tenantId", criteria.tenantId())
                .addValue("from", Timestamp.from(criteria.from())).addValue("to", Timestamp.from(criteria.to()));
    }

    private MapSqlParameterSource caseParameters(Long tenantId, UUID caseId) {
        return new MapSqlParameterSource("tenantId", tenantId).addValue("caseId", caseId);
    }

    private String where(AuditCriteria criteria, MapSqlParameterSource parameters) {
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

    private RowMapper<AuditControlDtos.Event> eventMapper() {
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

    private RowMapper<AuditControlDtos.Finding> findingMapper() {
        return (rs, row) -> new AuditControlDtos.Finding(
                rs.getObject("finding_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("finding_type"), rs.getString("rule_key"), rs.getString("severity"),
                rs.getInt("risk_score"), rs.getString("status"), rs.getString("title"),
                rs.getString("description"), rs.getString("source_service"), rs.getString("actor_id"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getInt("occurrence_count"),
                instant(rs, "first_seen_at"), instant(rs, "last_seen_at"), rs.getString("assigned_to"),
                rs.getObject("case_id", UUID.class), rs.getString("resolution"), instant(rs, "updated_at"));
    }

    private RowMapper<AuditControlDtos.AuditCase> caseMapper() {
        return (rs, row) -> new AuditControlDtos.AuditCase(
                rs.getObject("case_id", UUID.class), rs.getLong("case_number"), rs.getString("title"),
                rs.getString("description"), rs.getString("severity"), rs.getString("status"),
                rs.getString("owner_actor_id"), rs.getString("resolution"), instant(rs, "opened_at"),
                instant(rs, "due_at"), rs.getString("sla_state"), instant(rs, "closed_at"),
                rs.getString("created_by"), rs.getString("updated_by"),
                instant(rs, "updated_at"), rs.getInt("linked_events"), rs.getInt("linked_findings"));
    }

    private RowMapper<AuditControlDtos.CaseTask> caseTaskMapper() {
        return (rs, row) -> new AuditControlDtos.CaseTask(
                rs.getObject("task_id", UUID.class), rs.getString("title"),
                rs.getString("description"), rs.getString("status"), rs.getString("priority"),
                rs.getString("owner_actor_id"), instant(rs, "due_at"), instant(rs, "completed_at"),
                rs.getString("created_by"), rs.getString("updated_by"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private RowMapper<AuditControlDtos.SavedSearch> savedSearchMapper() {
        return (rs, row) -> new AuditControlDtos.SavedSearch(
                rs.getObject("saved_search_id", UUID.class), rs.getString("name"),
                jsonMap(rs.getString("criteria")), rs.getBoolean("shared"),
                rs.getBoolean("editable"), rs.getString("owner_actor_id"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private RowMapper<AuditControlDtos.PolicyRevision> policyRevisionMapper() {
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

    private List<String> strings(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) return List.of();
        return List.copyOf(Arrays.asList((String[]) array.getArray()));
    }

    private Map<String, Object> jsonMap(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored audit JSON is invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit JSON cannot be serialized", exception);
        }
    }

    private long scalar(String sql, Long tenantId) {
        Long value = jdbc.queryForObject(sql, new MapSqlParameterSource("tenantId", tenantId), Long.class);
        return value == null ? 0 : value;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String like(String value) {
        return "%" + value.replace("%", "").replace("_", "") + "%";
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
