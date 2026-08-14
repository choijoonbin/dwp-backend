package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class ApprovalQueryRepository {

    private static final String TASK_SELECT = """
            SELECT task.task_id, task.request_id, request.request_number,
                   request.title, request.summary,
                   workflow.name_ko AS workflow_name_ko,
                   workflow.name_en AS workflow_name_en,
                   step.step_key, step.step_name, step.sequence_number AS step_sequence,
                   request.requester_user_id, request.requester_name,
                   request.requester_org_name, task.assignee_user_id,
                   task.candidate_role, task.status, request.priority,
                   request.data_classification, task.risk_score,
                   request.submitted_at, task.due_at, task.version
              FROM apr_tasks task
              JOIN apr_requests request
                ON request.tenant_id = task.tenant_id
               AND request.request_id = task.request_id
              JOIN apr_workflow_versions workflow_version
                ON workflow_version.tenant_id = request.tenant_id
               AND workflow_version.workflow_version_id = request.workflow_version_id
              JOIN apr_workflow_definitions workflow
                ON workflow.tenant_id = workflow_version.tenant_id
               AND workflow.workflow_id = workflow_version.workflow_id
              JOIN apr_steps step
                ON step.tenant_id = task.tenant_id
               AND step.step_id = task.step_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ApprovalQueryRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void ensureTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT seed_approval_tenant(:tenantId)",
                new MapSqlParameterSource("tenantId", tenantId),
                Object.class);
        jdbc.queryForObject(
                "SELECT seed_approval_product_templates(:tenantId)",
                new MapSqlParameterSource("tenantId", tenantId),
                Object.class);
    }

    public ApprovalDtos.ApprovalMetrics metrics(ApprovalRequestContext.Actor actor) {
        MapSqlParameterSource params = actorParams(actor);
        return jdbc.queryForObject("""
                WITH visible_tasks AS (
                    SELECT task.*
                      FROM apr_tasks task
                     WHERE task.tenant_id = :tenantId
                       AND (task.assignee_user_id = :userId
                            OR (task.assignee_user_id IS NULL
                                AND task.candidate_role IN (:roles)))
                ), task_metrics AS (
                    SELECT COUNT(*) FILTER (WHERE status IN ('PENDING', 'CLAIMED'))::INTEGER AS pending,
                           COUNT(*) FILTER (
                               WHERE status IN ('PENDING', 'CLAIMED')
                                 AND due_at >= CURRENT_DATE
                                 AND due_at < CURRENT_DATE + INTERVAL '1 day')::INTEGER AS due_today,
                           COUNT(*) FILTER (
                               WHERE status IN ('PENDING', 'CLAIMED')
                                 AND due_at < CURRENT_TIMESTAMP)::INTEGER AS overdue,
                           COUNT(*) FILTER (WHERE status = 'INFO_REQUESTED')::INTEGER AS needs_information
                      FROM visible_tasks
                ), request_metrics AS (
                    SELECT COUNT(*) FILTER (
                               WHERE status IN ('SUBMITTED', 'IN_REVIEW', 'NEEDS_INFO'))::INTEGER AS in_flight,
                           COALESCE(AVG(EXTRACT(EPOCH FROM (completed_at - submitted_at)) / 3600.0)
                               FILTER (WHERE completed_at IS NOT NULL), 0)::DOUBLE PRECISION AS average_cycle,
                           COALESCE(100.0 * COUNT(*) FILTER (
                               WHERE completed_at IS NOT NULL AND (due_at IS NULL OR completed_at <= due_at))
                               / NULLIF(COUNT(*) FILTER (WHERE completed_at IS NOT NULL), 0), 100.0)
                               ::DOUBLE PRECISION AS sla_compliance
                      FROM apr_requests
                     WHERE tenant_id = :tenantId
                       AND requester_user_id = :userId
                )
                SELECT task_metrics.pending, task_metrics.due_today, task_metrics.overdue,
                       task_metrics.needs_information, request_metrics.in_flight,
                       request_metrics.average_cycle, request_metrics.sla_compliance
                  FROM task_metrics CROSS JOIN request_metrics
                """, params, (result, rowNumber) -> new ApprovalDtos.ApprovalMetrics(
                result.getInt("pending"),
                result.getInt("due_today"),
                result.getInt("overdue"),
                result.getInt("needs_information"),
                result.getInt("in_flight"),
                round(result.getDouble("average_cycle")),
                round(result.getDouble("sla_compliance"))));
    }

    public List<ApprovalDtos.TaskSummary> tasks(
            ApprovalRequestContext.Actor actor,
            String view,
            int limit) {
        String normalized = view == null ? "INBOX" : view.trim().toUpperCase();
        String statusClause = switch (normalized) {
            case "COMPLETED" -> "task.status IN ('APPROVED', 'REJECTED', 'SKIPPED', 'CANCELLED')";
            case "DELEGATED" -> "task.assignee_user_id IS NULL";
            default -> "task.status IN ('PENDING', 'CLAIMED', 'INFO_REQUESTED')";
        };
        return jdbc.query(
                TASK_SELECT + """
                     WHERE task.tenant_id = :tenantId
                       AND (task.assignee_user_id = :userId
                            OR (task.assignee_user_id IS NULL
                                AND task.candidate_role IN (:roles)))
                       AND (
                    """ + statusClause + """
                       )
                     ORDER BY CASE WHEN task.due_at < CURRENT_TIMESTAMP THEN 0 ELSE 1 END,
                              task.risk_score DESC, task.due_at, task.created_at DESC
                     LIMIT :limit
                    """,
                actorParams(actor).addValue("limit", Math.max(1, Math.min(limit, 200))),
                (result, rowNumber) -> taskSummary(result));
    }

    public TaskAccess taskDetail(
            ApprovalRequestContext.Actor actor,
            UUID taskId) {
        List<TaskAccess> matches = jdbc.query(
                TASK_SELECT + """
                     WHERE task.tenant_id = :tenantId
                       AND task.task_id = :taskId
                       AND (task.assignee_user_id = :userId
                            OR (task.assignee_user_id IS NULL
                                AND task.candidate_role IN (:roles)))
                    """,
                actorParams(actor).addValue("taskId", taskId),
                (result, rowNumber) -> new TaskAccess(
                        taskSummary(result),
                        result.getLong("requester_user_id"),
                        nullableLong(result, "assignee_user_id"),
                        result.getString("candidate_role")));
        if (matches.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return matches.get(0);
    }

    public Map<String, Object> requestPayload(long tenantId, UUID requestId) {
        List<String> payloads = jdbc.query(
                """
                SELECT payload::text
                  FROM apr_request_payloads
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("requestId", requestId),
                (result, rowNumber) -> result.getString(1));
        return payloads.isEmpty() ? Map.of() : json(payloads.get(0));
    }

    public List<ApprovalDtos.TimelineEvent> timeline(long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT event_id, event_type, actor_type, actor_id, outcome,
                       message, occurred_at
                  FROM apr_request_events
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                 ORDER BY occurred_at DESC, event_id
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("requestId", requestId),
                (result, rowNumber) -> new ApprovalDtos.TimelineEvent(
                        result.getObject("event_id", UUID.class),
                        result.getString("event_type"),
                        result.getString("actor_type"),
                        result.getString("actor_id"),
                        result.getString("outcome"),
                        result.getString("message"),
                        instant(result, "occurred_at")));
    }

    public List<ApprovalDtos.RequestSummary> requests(
            ApprovalRequestContext.Actor actor,
            String view,
            int limit) {
        String normalized = view == null ? "SUBMITTED" : view.trim().toUpperCase();
        String statusClause = switch (normalized) {
            case "DRAFTS" -> "request.status = 'DRAFT'";
            case "ARCHIVE" -> "request.status IN ('APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED')";
            case "NEEDS_INFO" -> "request.status = 'NEEDS_INFO'";
            default -> "request.status IN ('SUBMITTED', 'IN_REVIEW', 'NEEDS_INFO')";
        };
        return jdbc.query("""
                SELECT request.request_id, request.request_number, request.title,
                       request.summary, workflow.name_ko AS workflow_name_ko,
                       workflow.name_en AS workflow_name_en, request.status,
                       current_step.step_key AS current_step_key,
                       current_step.step_name AS current_step_name,
                       current_step.sequence_number AS current_step_sequence,
                       COALESCE(step_count.total_steps, 0)::INTEGER AS total_steps,
                       request.priority, request.data_classification,
                       (SELECT event.message
                          FROM apr_request_events event
                         WHERE event.tenant_id = request.tenant_id
                           AND event.request_id = request.request_id
                           AND event.event_type = 'INFORMATION_REQUESTED'
                         ORDER BY event.occurred_at DESC, event.event_id
                         LIMIT 1) AS latest_information_request,
                       request.submitted_at, request.due_at, request.completed_at,
                       request.version
                  FROM apr_requests request
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id = request.tenant_id
                   AND workflow_version.workflow_version_id = request.workflow_version_id
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id = workflow_version.tenant_id
                   AND workflow.workflow_id = workflow_version.workflow_id
                  LEFT JOIN LATERAL (
                      SELECT step.step_key, step.step_name, step.sequence_number
                        FROM apr_steps step
                       WHERE step.tenant_id = request.tenant_id
                         AND step.request_id = request.request_id
                         AND step.status IN ('PENDING', 'IN_PROGRESS')
                       ORDER BY step.sequence_number
                       LIMIT 1
                  ) current_step ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*)::INTEGER AS total_steps
                        FROM apr_steps step
                       WHERE step.tenant_id = request.tenant_id
                         AND step.request_id = request.request_id
                  ) step_count ON TRUE
                 WHERE request.tenant_id = :tenantId
                   AND request.requester_user_id = :userId
                   AND (
                """ + statusClause + """
                   )
                 ORDER BY request.updated_at DESC, request.request_id
                 LIMIT :limit
                """,
                actorParams(actor).addValue("limit", Math.max(1, Math.min(limit, 200))),
                (result, rowNumber) -> requestSummary(result));
    }

    public ApprovalDtos.RequestSummary request(
            ApprovalRequestContext.Actor actor,
            UUID requestId) {
        List<ApprovalDtos.RequestSummary> matches = jdbc.query("""
                SELECT request.request_id, request.request_number, request.title,
                       request.summary, workflow.name_ko AS workflow_name_ko,
                       workflow.name_en AS workflow_name_en, request.status,
                       current_step.step_key AS current_step_key,
                       current_step.step_name AS current_step_name,
                       current_step.sequence_number AS current_step_sequence,
                       COALESCE(step_count.total_steps, 0)::INTEGER AS total_steps,
                       request.priority, request.data_classification,
                       (SELECT event.message
                          FROM apr_request_events event
                         WHERE event.tenant_id = request.tenant_id
                           AND event.request_id = request.request_id
                           AND event.event_type = 'INFORMATION_REQUESTED'
                         ORDER BY event.occurred_at DESC, event.event_id
                         LIMIT 1) AS latest_information_request,
                       request.submitted_at, request.due_at, request.completed_at,
                       request.version
                  FROM apr_requests request
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id = request.tenant_id
                   AND workflow_version.workflow_version_id = request.workflow_version_id
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id = workflow_version.tenant_id
                   AND workflow.workflow_id = workflow_version.workflow_id
                  LEFT JOIN LATERAL (
                      SELECT step.step_key, step.step_name, step.sequence_number
                        FROM apr_steps step
                       WHERE step.tenant_id = request.tenant_id
                         AND step.request_id = request.request_id
                         AND step.status IN ('PENDING', 'IN_PROGRESS')
                       ORDER BY step.sequence_number
                       LIMIT 1
                  ) current_step ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*)::INTEGER AS total_steps
                        FROM apr_steps step
                       WHERE step.tenant_id = request.tenant_id
                         AND step.request_id = request.request_id
                  ) step_count ON TRUE
                 WHERE request.tenant_id = :tenantId
                   AND request.request_id = :requestId
                   AND request.requester_user_id = :userId
                """, actorParams(actor).addValue("requestId", requestId),
                (result, rowNumber) -> requestSummary(result));
        if (matches.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return matches.get(0);
    }

    public ApprovalDtos.RequestDetail requestDetail(
            ApprovalRequestContext.Actor actor,
            UUID requestId) {
        ApprovalDtos.RequestSummary request = request(actor, requestId);
        UUID workflowId = jdbc.queryForObject("""
                SELECT workflow_version.workflow_id
                  FROM apr_requests approval_request
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id = approval_request.tenant_id
                   AND workflow_version.workflow_version_id = approval_request.workflow_version_id
                 WHERE approval_request.tenant_id = :tenantId
                   AND approval_request.request_id = :requestId
                   AND approval_request.requester_user_id = :userId
                """, actorParams(actor).addValue("requestId", requestId), UUID.class);
        return new ApprovalDtos.RequestDetail(
                request,
                workflowId,
                requestPayload(actor.tenantId(), requestId));
    }

    public List<ApprovalDtos.StageMetric> flow(long tenantId) {
        Map<String, Integer> counts = jdbc.query("""
                SELECT status, COUNT(*)::INTEGER AS count
                  FROM apr_requests
                 WHERE tenant_id = :tenantId
                   AND status NOT IN ('DRAFT', 'WITHDRAWN', 'CANCELLED')
                 GROUP BY status
                """, new MapSqlParameterSource("tenantId", tenantId), result -> {
            Map<String, Integer> values = new java.util.LinkedHashMap<>();
            while (result.next()) values.put(result.getString("status"), result.getInt("count"));
            return values;
        });
        List<ApprovalDtos.StageMetric> stages = new ArrayList<>();
        for (String stage : List.of("SUBMITTED", "IN_REVIEW", "NEEDS_INFO", "APPROVED")) {
            int count = counts.getOrDefault(stage, 0);
            int atRisk = stage.equals("IN_REVIEW") ? overdueCount(tenantId) : 0;
            stages.add(new ApprovalDtos.StageMetric(stage, count, atRisk));
        }
        return List.copyOf(stages);
    }

    public ApprovalDtos.AdminPulse adminPulse(long tenantId) {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM apr_workflow_definitions
                      WHERE tenant_id = :tenantId AND lifecycle_state = 'PUBLISHED')::INTEGER
                        AS published_workflows,
                    (SELECT COUNT(*) FROM apr_workflow_definitions
                      WHERE tenant_id = :tenantId AND lifecycle_state = 'DRAFT')::INTEGER
                        AS draft_workflows,
                    (SELECT COUNT(*) FROM apr_requests
                      WHERE tenant_id = :tenantId
                        AND status IN ('SUBMITTED', 'IN_REVIEW', 'NEEDS_INFO'))::INTEGER
                        AS active_requests,
                    (SELECT COUNT(*) FROM apr_tasks
                      WHERE tenant_id = :tenantId
                        AND status IN ('PENDING', 'CLAIMED')
                        AND due_at < CURRENT_TIMESTAMP)::INTEGER AS overdue_tasks,
                    (SELECT COUNT(*) FROM apr_integration_outbox
                      WHERE tenant_id = :tenantId AND status IN ('FAILED', 'DEAD'))::INTEGER
                        AS failed_integrations
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, rowNumber) -> new ApprovalDtos.AdminPulse(
                        result.getInt("published_workflows"),
                        result.getInt("draft_workflows"),
                        result.getInt("active_requests"),
                        result.getInt("overdue_tasks"),
                        result.getInt("failed_integrations")));
    }

    public List<ApprovalDtos.WorkflowSummary> workflows(long tenantId, boolean publishedOnly) {
        return jdbc.query("""
                SELECT workflow_id, workflow_key, name_ko, name_en,
                       description_ko, description_en, category,
                       data_classification, lifecycle_state, current_version,
                       sla_minutes, allow_self_approval, owner_group_ref,
                       version, updated_at
                  FROM apr_workflow_definitions
                 WHERE tenant_id = :tenantId
                   AND (:publishedOnly = FALSE OR lifecycle_state = 'PUBLISHED')
                 ORDER BY CASE lifecycle_state WHEN 'PUBLISHED' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                          category, name_en
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("publishedOnly", publishedOnly),
                (result, rowNumber) -> new ApprovalDtos.WorkflowSummary(
                        result.getObject("workflow_id", UUID.class),
                        result.getString("workflow_key"),
                        result.getString("name_ko"),
                        result.getString("name_en"),
                        result.getString("description_ko"),
                        result.getString("description_en"),
                        result.getString("category"),
                        result.getString("data_classification"),
                        result.getString("lifecycle_state"),
                        result.getInt("current_version"),
                        result.getInt("sla_minutes"),
                        result.getBoolean("allow_self_approval"),
                        result.getString("owner_group_ref"),
                        result.getLong("version"),
                        instant(result, "updated_at")));
    }

    public ApprovalDtos.WorkflowDetail workflow(long tenantId, UUID workflowId) {
        List<ApprovalDtos.WorkflowDetail> matches = jdbc.query("""
                SELECT definition.workflow_id, definition.workflow_key,
                       definition.name_ko, definition.name_en,
                       definition.description_ko, definition.description_en,
                       definition.category, definition.data_classification,
                       definition.lifecycle_state, definition.current_version,
                       definition.sla_minutes, definition.allow_self_approval,
                       definition.owner_group_ref, definition.version,
                       definition.updated_at, version.definition::text,
                       version.definition_sha256
                  FROM apr_workflow_definitions definition
                  JOIN apr_workflow_versions version
                    ON version.tenant_id = definition.tenant_id
                   AND version.workflow_id = definition.workflow_id
                   AND version.version_number = definition.current_version
                 WHERE definition.tenant_id = :tenantId
                   AND definition.workflow_id = :workflowId
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId),
                (result, rowNumber) -> new ApprovalDtos.WorkflowDetail(
                        new ApprovalDtos.WorkflowSummary(
                                result.getObject("workflow_id", UUID.class),
                                result.getString("workflow_key"),
                                result.getString("name_ko"),
                                result.getString("name_en"),
                                result.getString("description_ko"),
                                result.getString("description_en"),
                                result.getString("category"),
                                result.getString("data_classification"),
                                result.getString("lifecycle_state"),
                                result.getInt("current_version"),
                                result.getInt("sla_minutes"),
                                result.getBoolean("allow_self_approval"),
                                result.getString("owner_group_ref"),
                                result.getLong("version"),
                                instant(result, "updated_at")),
                        json(result.getString("definition")),
                        result.getString("definition_sha256")));
        if (matches.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return matches.get(0);
    }

    public ApprovalDtos.RequestTemplate publishedTemplate(long tenantId, UUID workflowId) {
        ApprovalDtos.WorkflowDetail workflow = workflow(tenantId, workflowId);
        if (!"PUBLISHED".equals(workflow.workflow().lifecycleState())) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        List<UUID> formIds = jdbc.query("""
                SELECT form.form_id
                  FROM apr_forms form
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id = form.tenant_id
                   AND form.form_key = workflow.workflow_key || '_FORM'
                 WHERE workflow.tenant_id = :tenantId
                   AND workflow.workflow_id = :workflowId
                   AND form.lifecycle_state = 'PUBLISHED'
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId),
                (result, rowNumber) -> result.getObject("form_id", UUID.class));
        if (formIds.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return new ApprovalDtos.RequestTemplate(workflow.workflow(), form(tenantId, formIds.get(0)));
    }

    public List<ApprovalDtos.FormSummary> forms(long tenantId) {
        return jdbc.query("""
                SELECT form.form_id, form.form_key, form.name_ko, form.name_en,
                       form.lifecycle_state, form.current_version,
                       jsonb_array_length(version.schema_payload -> 'fields') AS field_count,
                       form.version, form.updated_at
                  FROM apr_forms form
                  JOIN apr_form_versions version
                    ON version.tenant_id = form.tenant_id
                   AND version.form_id = form.form_id
                   AND version.version_number = form.current_version
                 WHERE form.tenant_id = :tenantId
                 ORDER BY form.lifecycle_state, form.name_en
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, rowNumber) -> new ApprovalDtos.FormSummary(
                        result.getObject("form_id", UUID.class),
                        result.getString("form_key"),
                        result.getString("name_ko"),
                        result.getString("name_en"),
                        result.getString("lifecycle_state"),
                        result.getInt("current_version"),
                        result.getInt("field_count"),
                        result.getLong("version"),
                        instant(result, "updated_at")));
    }

    public ApprovalDtos.FormDetail form(long tenantId, UUID formId) {
        List<ApprovalDtos.FormDetail> matches = jdbc.query("""
                SELECT form.form_id, form.form_key, form.name_ko, form.name_en,
                       form.lifecycle_state, form.current_version,
                       jsonb_array_length(version.schema_payload -> 'fields') AS field_count,
                       form.version, form.updated_at,
                       version.schema_payload::text, version.schema_sha256
                  FROM apr_forms form
                  JOIN apr_form_versions version
                    ON version.tenant_id = form.tenant_id
                   AND version.form_id = form.form_id
                   AND version.version_number = form.current_version
                 WHERE form.tenant_id = :tenantId
                   AND form.form_id = :formId
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("formId", formId),
                (result, rowNumber) -> new ApprovalDtos.FormDetail(
                        new ApprovalDtos.FormSummary(
                                result.getObject("form_id", UUID.class),
                                result.getString("form_key"),
                                result.getString("name_ko"),
                                result.getString("name_en"),
                                result.getString("lifecycle_state"),
                                result.getInt("current_version"),
                                result.getInt("field_count"),
                                result.getLong("version"),
                                instant(result, "updated_at")),
                        json(result.getString("schema_payload")),
                        result.getString("schema_sha256")));
        if (matches.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return matches.get(0);
    }

    public List<ApprovalDtos.PolicySummary> policies(long tenantId) {
        return jdbc.query("""
                SELECT policy_id, policy_key, name_ko, name_en, policy_type,
                       enforcement_mode, severity, lifecycle_state,
                       rule_payload::text, version
                  FROM apr_policy_rules
                 WHERE tenant_id = :tenantId
                 ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                                        WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                          policy_key
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, rowNumber) -> new ApprovalDtos.PolicySummary(
                        result.getObject("policy_id", UUID.class),
                        result.getString("policy_key"),
                        result.getString("name_ko"),
                        result.getString("name_en"),
                        result.getString("policy_type"),
                        result.getString("enforcement_mode"),
                        result.getString("severity"),
                        result.getString("lifecycle_state"),
                        json(result.getString("rule_payload")),
                        result.getLong("version")));
    }

    public List<ApprovalDtos.SignatureProviderSummary> signatureProviders(long tenantId) {
        return jdbc.query("""
                SELECT provider_id, provider_key, display_name, provider_type,
                       lifecycle_state, capability_metadata::text,
                       credential_reference IS NOT NULL AS credential_configured,
                       last_health_checked_at, version
                  FROM apr_signature_providers
                 WHERE tenant_id = :tenantId
                 ORDER BY CASE provider_type WHEN 'INTERNAL_ATTESTATION' THEN 0 ELSE 1 END,
                          display_name
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, rowNumber) -> new ApprovalDtos.SignatureProviderSummary(
                        result.getObject("provider_id", UUID.class),
                        result.getString("provider_key"),
                        result.getString("display_name"),
                        result.getString("provider_type"),
                        result.getString("lifecycle_state"),
                        json(result.getString("capability_metadata")),
                        result.getBoolean("credential_configured"),
                        instant(result, "last_health_checked_at"),
                        result.getLong("version")));
    }

    public List<ApprovalDtos.DelegationSummary> delegations(ApprovalRequestContext.Actor actor) {
        return jdbc.query("""
                SELECT delegation_id, delegator_user_id, delegate_user_id,
                       scope_type, workflow_key, starts_at, ends_at,
                       lifecycle_state, reason, version
                  FROM apr_delegations
                 WHERE tenant_id = :tenantId AND delegator_user_id = :userId
                 ORDER BY starts_at DESC
                """, actorParams(actor),
                (result, rowNumber) -> new ApprovalDtos.DelegationSummary(
                        result.getObject("delegation_id", UUID.class),
                        result.getLong("delegator_user_id"),
                        result.getLong("delegate_user_id"),
                        result.getString("scope_type"),
                        result.getString("workflow_key"),
                        instant(result, "starts_at"),
                        instant(result, "ends_at"),
                        result.getString("lifecycle_state"),
                        result.getString("reason"),
                        result.getLong("version")));
    }

    public int failedIntegrationCount(long tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER FROM apr_integration_outbox
                 WHERE tenant_id = :tenantId AND status IN ('FAILED', 'DEAD')
                """, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count == null ? 0 : count;
    }

    public int pendingIntegrationCount(long tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER FROM apr_integration_outbox
                 WHERE tenant_id = :tenantId AND status IN ('PENDING', 'SENDING')
                """, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count == null ? 0 : count;
    }

    public int activeDelegationCount(long tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER FROM apr_delegations
                 WHERE tenant_id = :tenantId AND lifecycle_state = 'ACTIVE'
                   AND CURRENT_TIMESTAMP BETWEEN starts_at AND ends_at
                """, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count == null ? 0 : count;
    }

    public List<ApprovalDtos.TaskSummary> breachedTasks(long tenantId, int limit) {
        return jdbc.query(TASK_SELECT + """
                 WHERE task.tenant_id = :tenantId
                   AND task.status IN ('PENDING', 'CLAIMED')
                   AND task.due_at < CURRENT_TIMESTAMP
                 ORDER BY task.due_at, task.risk_score DESC
                 LIMIT :limit
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("limit", Math.max(1, Math.min(limit, 100))),
                (result, rowNumber) -> taskSummary(result));
    }

    private int overdueCount(long tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER FROM apr_tasks
                 WHERE tenant_id = :tenantId
                   AND status IN ('PENDING', 'CLAIMED')
                   AND due_at < CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count == null ? 0 : count;
    }

    private MapSqlParameterSource actorParams(ApprovalRequestContext.Actor actor) {
        Set<String> roles = actor.roles().isEmpty() ? Set.of("__NO_ROLE__") : actor.roles();
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId())
                .addValue("roles", roles);
    }

    private ApprovalDtos.TaskSummary taskSummary(ResultSet result) throws SQLException {
        return new ApprovalDtos.TaskSummary(
                result.getObject("task_id", UUID.class),
                result.getObject("request_id", UUID.class),
                result.getString("request_number"),
                result.getString("title"),
                result.getString("summary"),
                result.getString("workflow_name_ko"),
                result.getString("workflow_name_en"),
                result.getString("step_key"),
                result.getString("step_name"),
                result.getInt("step_sequence"),
                result.getString("requester_name"),
                result.getString("requester_org_name"),
                result.getString("status"),
                result.getString("priority"),
                result.getString("data_classification"),
                result.getInt("risk_score"),
                instant(result, "submitted_at"),
                instant(result, "due_at"),
                result.getLong("version"));
    }

    private ApprovalDtos.RequestSummary requestSummary(ResultSet result) throws SQLException {
        return new ApprovalDtos.RequestSummary(
                result.getObject("request_id", UUID.class),
                result.getString("request_number"),
                result.getString("title"),
                result.getString("summary"),
                result.getString("workflow_name_ko"),
                result.getString("workflow_name_en"),
                result.getString("current_step_key"),
                result.getString("current_step_name"),
                nullableInteger(result, "current_step_sequence"),
                result.getInt("total_steps"),
                result.getString("status"),
                result.getString("priority"),
                result.getString("data_classification"),
                result.getString("latest_information_request"),
                instant(result, "submitted_at"),
                instant(result, "due_at"),
                instant(result, "completed_at"),
                result.getLong("version"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private Map<String, Object> json(String value) {
        try {
            return value == null
                    ? Map.of()
                    : objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored approval JSON is invalid.", exception);
        }
    }

    public record TaskAccess(
            ApprovalDtos.TaskSummary summary,
            long requesterUserId,
            Long assigneeUserId,
            String candidateRole) {
    }
}
