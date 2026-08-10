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

    private Optional<AuditControlDtos.Finding> finding(Long tenantId, UUID findingId) {
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
                       c.owner_actor_id, c.resolution, c.opened_at, c.closed_at, c.created_by,
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

    public UUID createCase(Long tenantId, String actorId, AuditControlDtos.CaseCreate request) {
        return jdbc.queryForObject("""
                INSERT INTO sys_audit_cases (
                    tenant_id, title, description, severity, owner_actor_id, created_by, updated_by)
                VALUES (:tenantId, :title, :description, :severity, :owner, :actor, :actor)
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

    public void linkEvent(Long tenantId, UUID caseId, String actorId, AuditControlDtos.CaseEventLink request) {
        jdbc.update("""
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

    public AuditControlDtos.RetentionPolicy policy(Long tenantId) {
        jdbc.update("""
                INSERT INTO sys_audit_retention_policies (tenant_id)
                VALUES (:tenantId) ON CONFLICT (tenant_id) DO NOTHING
                """, new MapSqlParameterSource("tenantId", tenantId));
        return jdbc.queryForObject("""
                SELECT standard_retention_days, extended_retention_days, export_limit_rows,
                       require_export_reason, integrity_enabled, high_risk_threshold,
                       updated_by, updated_at
                  FROM sys_audit_retention_policies WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new AuditControlDtos.RetentionPolicy(
                        rs.getInt("standard_retention_days"), rs.getInt("extended_retention_days"),
                        rs.getInt("export_limit_rows"), rs.getBoolean("require_export_reason"),
                        rs.getBoolean("integrity_enabled"), rs.getInt("high_risk_threshold"),
                        rs.getString("updated_by"), instant(rs, "updated_at")));
    }

    public AuditControlDtos.RetentionPolicy updatePolicy(
            Long tenantId, String actorId, AuditControlDtos.RetentionPolicyUpdate request) {
        jdbc.update("""
                INSERT INTO sys_audit_retention_policies (
                    tenant_id, standard_retention_days, extended_retention_days,
                    export_limit_rows, require_export_reason, integrity_enabled,
                    high_risk_threshold, updated_by, updated_at)
                VALUES (:tenantId, :standardDays, :extendedDays, :exportLimit,
                        :requireReason, :integrityEnabled, :threshold, :actor, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    standard_retention_days = EXCLUDED.standard_retention_days,
                    extended_retention_days = EXCLUDED.extended_retention_days,
                    export_limit_rows = EXCLUDED.export_limit_rows,
                    require_export_reason = EXCLUDED.require_export_reason,
                    integrity_enabled = EXCLUDED.integrity_enabled,
                    high_risk_threshold = EXCLUDED.high_risk_threshold,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("standardDays", request.standardRetentionDays())
                .addValue("extendedDays", request.extendedRetentionDays())
                .addValue("exportLimit", request.exportLimitRows())
                .addValue("requireReason", request.requireExportReason())
                .addValue("integrityEnabled", request.integrityEnabled())
                .addValue("threshold", request.highRiskThreshold()).addValue("actor", actorId));
        return policy(tenantId);
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
                instant(rs, "closed_at"), rs.getString("created_by"), rs.getString("updated_by"),
                instant(rs, "updated_at"), rs.getInt("linked_events"), rs.getInt("linked_findings"));
    }

    private RowMapper<AuditControlDtos.SavedSearch> savedSearchMapper() {
        return (rs, row) -> new AuditControlDtos.SavedSearch(
                rs.getObject("saved_search_id", UUID.class), rs.getString("name"),
                jsonMap(rs.getString("criteria")), rs.getBoolean("shared"),
                rs.getBoolean("editable"), rs.getString("owner_actor_id"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
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
}
