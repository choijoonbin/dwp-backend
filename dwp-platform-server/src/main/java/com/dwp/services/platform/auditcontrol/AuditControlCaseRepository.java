package com.dwp.services.platform.auditcontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class AuditControlCaseRepository extends AuditControlEventRepository {
    AuditControlCaseRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
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

}
