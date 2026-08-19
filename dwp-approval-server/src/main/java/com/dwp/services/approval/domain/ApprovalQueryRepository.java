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

    private static final String DIRECT_TASK_ACCESS = """
            (task.assignee_user_id = :userId
             OR (task.assignee_user_id IS NULL AND task.candidate_role IN (:roles)))
            """;

    private static final String DELEGATED_TASK_ACCESS = """
            EXISTS (
                SELECT 1
                  FROM apr_delegations delegation
                 WHERE delegation.tenant_id = task.tenant_id
                   AND delegation.delegate_user_id = :userId
                   AND delegation.lifecycle_state = 'ACTIVE'
                   AND CURRENT_TIMESTAMP BETWEEN delegation.starts_at AND delegation.ends_at
                   AND (delegation.scope_type = 'ALL'
                        OR delegation.workflow_key = workflow.workflow_key)
                   AND (
                        task.assignee_user_id = delegation.delegator_user_id
                        OR (task.assignee_user_id IS NULL
                            AND jsonb_exists(
                                delegation.delegated_role_codes,
                                task.candidate_role))
                   )
            )
            """;

    private static final String COMPLETED_BY_ACTOR_ACCESS = """
            task.decision_actor_user_id = :userId
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
        List<String> states = jdbc.query(
                "SELECT lifecycle_state FROM apr_tenants WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", tenantId),
                (result, rowNumber) -> result.getString("lifecycle_state"));
        if (!states.isEmpty()) {
            if (!"ACTIVE".equals(states.get(0))) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "The approval capability is not active for this tenant.");
            }
            return;
        }
        jdbc.update("""
                INSERT INTO apr_tenants (tenant_id)
                VALUES (:tenantId)
                ON CONFLICT (tenant_id) DO NOTHING
                """, new MapSqlParameterSource("tenantId", tenantId));
        jdbc.queryForObject(
                "SELECT seed_approval_form_catalog(:tenantId)",
                new MapSqlParameterSource("tenantId", tenantId),
                Object.class);
        jdbc.queryForObject(
                "SELECT seed_approval_tenant(:tenantId)",
                new MapSqlParameterSource("tenantId", tenantId),
                Object.class);
        jdbc.queryForObject(
                "SELECT seed_approval_product_templates(:tenantId)",
                new MapSqlParameterSource("tenantId", tenantId),
                Object.class);
        jdbc.queryForObject(
                "SELECT seed_approval_form_catalog(:tenantId)",
                new MapSqlParameterSource("tenantId", tenantId),
                Object.class);
    }

    public ApprovalDtos.ApprovalMetrics metrics(ApprovalRequestContext.Actor actor) {
        MapSqlParameterSource params = actorParams(actor);
        return jdbc.queryForObject("""
                WITH visible_tasks AS (
                    SELECT task.*
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
                     WHERE task.tenant_id = :tenantId
                       AND (
                """ + DIRECT_TASK_ACCESS + " OR " + DELEGATED_TASK_ACCESS + """
                       )
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

    public boolean isBlockingPolicyActive(long tenantId, String policyKey) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM apr_policy_rules
                 WHERE tenant_id = :tenantId
                   AND policy_key = :policyKey
                   AND lifecycle_state = 'ACTIVE'
                   AND enforcement_mode = 'BLOCK'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("policyKey", policyKey), Integer.class);
        return count != null && count > 0;
    }

    public List<ApprovalDtos.TaskSummary> tasks(
            ApprovalRequestContext.Actor actor,
            String view,
            int limit) {
        String normalized = view == null ? "INBOX" : view.trim().toUpperCase();
        String statusClause = switch (normalized) {
            case "COMPLETED" -> "task.status IN ('APPROVED', 'REJECTED')";
            case "DELEGATED" -> "task.status IN ('PENDING', 'CLAIMED', 'INFO_REQUESTED')";
            default -> "task.status IN ('PENDING', 'CLAIMED', 'INFO_REQUESTED')";
        };
        String accessClause = switch (normalized) {
            case "COMPLETED" -> COMPLETED_BY_ACTOR_ACCESS;
            case "DELEGATED" -> DELEGATED_TASK_ACCESS;
            default -> "(" + DIRECT_TASK_ACCESS + " OR " + DELEGATED_TASK_ACCESS + ")";
        };
        String orderClause = "COMPLETED".equals(normalized)
                ? "task.completed_at DESC NULLS LAST, task.created_at DESC"
                : "CASE WHEN task.due_at < CURRENT_TIMESTAMP THEN 0 ELSE 1 END, "
                        + "task.risk_score DESC, task.due_at, task.created_at DESC";
        String taskQuery = TASK_SELECT + """
                     WHERE task.tenant_id = :tenantId
                       AND (
                    """ + accessClause + """
                       )
                       AND (
                    """ + statusClause + """
                       )
                     ORDER BY %s
                     LIMIT :limit
                    """.formatted(orderClause);
        return jdbc.query(
                taskQuery,
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
                       AND (
                    """ + DIRECT_TASK_ACCESS + " OR " + DELEGATED_TASK_ACCESS
                        + " OR " + COMPLETED_BY_ACTOR_ACCESS + """
                       )
                    """,
                actorParams(actor).addValue("taskId", taskId),
                (result, rowNumber) -> new TaskAccess(
                        taskSummary(result),
                        result.getLong("requester_user_id"),
                        nullableLong(result, "assignee_user_id"),
                        result.getString("candidate_role"),
                        false,
                        null));
        if (matches.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        TaskAccess match = matches.get(0);
        Long delegatedFrom = delegationSource(actor, taskId);
        return new TaskAccess(
                match.summary(), match.requesterUserId(), match.assigneeUserId(),
                match.candidateRole(), delegatedFrom != null, delegatedFrom);
    }

    private Long delegationSource(ApprovalRequestContext.Actor actor, UUID taskId) {
        return jdbc.query("""
                SELECT delegation.delegator_user_id
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
                  JOIN apr_delegations delegation
                    ON delegation.tenant_id = task.tenant_id
                   AND delegation.delegate_user_id = :userId
                   AND delegation.lifecycle_state = 'ACTIVE'
                   AND CURRENT_TIMESTAMP BETWEEN delegation.starts_at AND delegation.ends_at
                   AND (delegation.scope_type = 'ALL'
                        OR delegation.workflow_key = workflow.workflow_key)
                   AND (
                        task.assignee_user_id = delegation.delegator_user_id
                        OR (task.assignee_user_id IS NULL
                            AND jsonb_exists(
                                delegation.delegated_role_codes,
                                task.candidate_role))
                   )
                 WHERE task.tenant_id = :tenantId AND task.task_id = :taskId
                 ORDER BY delegation.starts_at DESC, delegation.created_at DESC
                 LIMIT 1
                """, actorParams(actor).addValue("taskId", taskId),
                result -> result.next() ? result.getLong(1) : null);
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

    public Map<String, Object> requestFormSchema(long tenantId, UUID requestId) {
        List<String> schemas = jdbc.query(
                """
                SELECT form_version.schema_payload::text
                  FROM apr_requests request
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id = request.tenant_id
                   AND form_version.form_version_id = request.form_version_id
                 WHERE request.tenant_id = :tenantId
                   AND request.request_id = :requestId
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("requestId", requestId),
                (result, rowNumber) -> result.getString(1));
        return schemas.isEmpty() ? Map.of() : json(schemas.get(0));
    }

    public List<ApprovalDtos.TimelineEvent> timeline(long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT event_id, event_type, actor_type, actor_id, outcome,
                       event_data ->> 'actorDisplayName' AS actor_display_name,
                       event_data ->> 'stepName' AS step_name,
                       CASE WHEN event_data ->> 'stepSequence' ~ '^[0-9]+$'
                            THEN (event_data ->> 'stepSequence')::INTEGER END AS step_sequence,
                       COALESCE((event_data ->> 'delegated')::BOOLEAN, FALSE) AS delegated,
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
                        result.getString("actor_display_name"),
                        result.getString("step_name"),
                        nullableInteger(result, "step_sequence"),
                        result.getBoolean("delegated"),
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
        RequestAssetIds assets = jdbc.queryForObject("""
                SELECT workflow_version.workflow_id, form_version.form_id,
                       form_version.schema_payload::text AS form_schema
                  FROM apr_requests approval_request
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id = approval_request.tenant_id
                   AND workflow_version.workflow_version_id = approval_request.workflow_version_id
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id = approval_request.tenant_id
                   AND form_version.form_version_id = approval_request.form_version_id
                 WHERE approval_request.tenant_id = :tenantId
                   AND approval_request.request_id = :requestId
                   AND approval_request.requester_user_id = :userId
                """, actorParams(actor).addValue("requestId", requestId),
                (result, rowNumber) -> new RequestAssetIds(
                        result.getObject("workflow_id", UUID.class),
                        result.getObject("form_id", UUID.class),
                        json(result.getString("form_schema"))));
        if (assets == null) throw new BaseException(ErrorCode.NOT_FOUND);
        return new ApprovalDtos.RequestDetail(
                request,
                assets.workflowId(),
                assets.formId(),
                requestPayload(actor.tenantId(), requestId),
                assets.formSchema(),
                timeline(actor.tenantId(), requestId));
    }

    public List<ApprovalDtos.StageMetric> flow(ApprovalRequestContext.Actor actor) {
        Map<String, Integer> counts = jdbc.query("""
                WITH visible_requests AS (
                    SELECT request.request_id, request.status
                      FROM apr_requests request
                     WHERE request.tenant_id = :tenantId
                       AND request.requester_user_id = :userId
                    UNION
                    SELECT request.request_id, request.status
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
                     WHERE task.tenant_id = :tenantId
                       AND (
                """ + DIRECT_TASK_ACCESS + " OR " + DELEGATED_TASK_ACCESS + """
                       )
                )
                SELECT status, COUNT(*)::INTEGER AS count
                  FROM visible_requests
                 WHERE status NOT IN ('DRAFT', 'WITHDRAWN', 'CANCELLED')
                 GROUP BY status
                """, actorParams(actor), result -> {
            Map<String, Integer> values = new java.util.LinkedHashMap<>();
            while (result.next()) values.put(result.getString("status"), result.getInt("count"));
            return values;
        });
        int atRisk = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
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
                 WHERE task.tenant_id = :tenantId
                   AND task.status IN ('PENDING', 'CLAIMED')
                   AND task.due_at < CURRENT_TIMESTAMP
                   AND (
                """ + DIRECT_TASK_ACCESS + " OR " + DELEGATED_TASK_ACCESS + """
                   )
                """, actorParams(actor), Integer.class);
        List<ApprovalDtos.StageMetric> stages = new ArrayList<>();
        for (String stage : List.of("SUBMITTED", "IN_REVIEW", "NEEDS_INFO", "APPROVED")) {
            int count = counts.getOrDefault(stage, 0);
            stages.add(new ApprovalDtos.StageMetric(
                    stage, count, stage.equals("IN_REVIEW") ? atRisk : 0));
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
                        AS failed_integrations,
                    (SELECT COUNT(*) FROM apr_delegations
                      WHERE tenant_id = :tenantId AND lifecycle_state = 'ACTIVE'
                        AND CURRENT_TIMESTAMP BETWEEN starts_at AND ends_at
                        AND (delegate_person_public_id IS NULL
                             OR delegate_display_name IS NULL))::INTEGER AS identity_gaps,
                    ((SELECT COUNT(*) FROM apr_workflow_versions
                       WHERE tenant_id = :tenantId AND lifecycle_state = 'PUBLISHED'
                         AND created_by = published_by)
                     + (SELECT COUNT(*) FROM apr_form_versions
                         WHERE tenant_id = :tenantId AND lifecycle_state = 'PUBLISHED'
                           AND created_by = published_by))::INTEGER AS sod_violations,
                    (SELECT COUNT(*) FROM apr_tasks
                      WHERE tenant_id = :tenantId
                        AND status IN ('APPROVED', 'REJECTED')
                        AND decision_actor_user_id IS NULL)::INTEGER AS evidence_gaps
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, rowNumber) -> new ApprovalDtos.AdminPulse(
                        result.getInt("published_workflows"),
                        result.getInt("draft_workflows"),
                        result.getInt("active_requests"),
                        result.getInt("overdue_tasks"),
                        result.getInt("failed_integrations"),
                        List.of(
                                assurance("identity", result.getInt("identity_gaps")),
                                assurance("segregation", result.getInt("sod_violations")),
                                assurance("evidence", result.getInt("evidence_gaps")),
                                assurance("delivery", result.getInt("failed_integrations")))));
    }

    private ApprovalDtos.AssuranceSignal assurance(String key, int exceptions) {
        return new ApprovalDtos.AssuranceSignal(
                key, exceptions == 0 ? "ENFORCED" : "ATTENTION", exceptions);
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
                  FROM apr_form_workflow_bindings binding
                  JOIN apr_forms form
                    ON form.tenant_id = binding.tenant_id
                   AND form.form_id = binding.form_id
                 WHERE binding.tenant_id = :tenantId
                   AND binding.workflow_id = :workflowId
                   AND binding.lifecycle_state = 'ACTIVE'
                   AND form.lifecycle_state = 'PUBLISHED'
                 ORDER BY CASE binding.binding_type WHEN 'DEFAULT' THEN 0 ELSE 1 END,
                          binding.priority, form.name_en
                 LIMIT 1
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId),
                (result, rowNumber) -> result.getObject("form_id", UUID.class));
        if (formIds.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return new ApprovalDtos.RequestTemplate(
                workflow.workflow(), workflow.definition(), form(tenantId, formIds.get(0)));
    }

    public ApprovalDtos.RequestTemplate publishedTemplateByForm(long tenantId, UUID formId) {
        List<UUID> workflowIds = jdbc.query("""
                SELECT binding.workflow_id
                  FROM apr_form_workflow_bindings binding
                  JOIN apr_forms form
                    ON form.tenant_id = binding.tenant_id
                   AND form.form_id = binding.form_id
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id = binding.tenant_id
                   AND workflow.workflow_id = binding.workflow_id
                 WHERE binding.tenant_id = :tenantId
                   AND binding.form_id = :formId
                   AND binding.binding_type = 'DEFAULT'
                   AND binding.lifecycle_state = 'ACTIVE'
                   AND form.lifecycle_state = 'PUBLISHED'
                   AND workflow.lifecycle_state = 'PUBLISHED'
                   AND (binding.effective_from IS NULL OR binding.effective_from <= CURRENT_TIMESTAMP)
                   AND (binding.effective_to IS NULL OR binding.effective_to > CURRENT_TIMESTAMP)
                 LIMIT 1
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("formId", formId),
                (result, rowNumber) -> result.getObject("workflow_id", UUID.class));
        if (workflowIds.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        ApprovalDtos.WorkflowDetail workflow = workflow(tenantId, workflowIds.get(0));
        return new ApprovalDtos.RequestTemplate(
                workflow.workflow(), workflow.definition(), form(tenantId, formId));
    }

    public List<ApprovalDtos.FormCategorySummary> formCategories(long tenantId) {
        return jdbc.query("""
                SELECT category.category_id, category.category_key,
                       category.parent_category_id, category.name_ko, category.name_en,
                       category.description_ko, category.description_en, category.icon_key,
                       category.sort_order, category.lifecycle_state, category.version,
                       COUNT(form.form_id)::INTEGER AS form_count
                  FROM apr_form_categories category
                  LEFT JOIN apr_forms form
                    ON form.tenant_id = category.tenant_id
                   AND form.category_id = category.category_id
                   AND form.lifecycle_state <> 'RETIRED'
                 WHERE category.tenant_id = :tenantId
                 GROUP BY category.category_id
                 ORDER BY category.sort_order, category.name_en
                """, new MapSqlParameterSource("tenantId", tenantId),
                (result, rowNumber) -> new ApprovalDtos.FormCategorySummary(
                        result.getObject("category_id", UUID.class),
                        result.getString("category_key"),
                        result.getObject("parent_category_id", UUID.class),
                        result.getString("name_ko"),
                        result.getString("name_en"),
                        result.getString("description_ko"),
                        result.getString("description_en"),
                        result.getString("icon_key"),
                        result.getInt("sort_order"),
                        result.getString("lifecycle_state"),
                        result.getInt("form_count"),
                        result.getLong("version")));
    }

    public List<ApprovalDtos.FormSummary> forms(long tenantId) {
        return forms(tenantId, false);
    }

    public List<ApprovalDtos.FormSummary> publishedForms(long tenantId) {
        return forms(tenantId, true);
    }

    private List<ApprovalDtos.FormSummary> forms(long tenantId, boolean publishedOnly) {
        return jdbc.query("""
                SELECT form.form_id, form.form_key,
                       category.category_id, category.category_key,
                       category.name_ko AS category_name_ko,
                       category.name_en AS category_name_en,
                       form.name_ko, form.name_en, form.description_ko, form.description_en,
                       form.owner_group_ref, form.form_kind,
                       form.lifecycle_state, form.current_version,
                       jsonb_array_length(version.schema_payload -> 'fields') AS field_count,
                       COALESCE(route_count.value, 0)::INTEGER AS route_count,
                       COALESCE(usage_count.value, 0)::BIGINT AS usage_count,
                       form.version, form.updated_at
                  FROM apr_forms form
                  JOIN apr_form_versions version
                    ON version.tenant_id = form.tenant_id
                   AND version.form_id = form.form_id
                   AND version.version_number = form.current_version
                  JOIN apr_form_categories category
                    ON category.tenant_id = form.tenant_id
                   AND category.category_id = form.category_id
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS value
                        FROM apr_form_workflow_bindings binding
                       WHERE binding.tenant_id = form.tenant_id
                         AND binding.form_id = form.form_id
                         AND binding.lifecycle_state = 'ACTIVE'
                  ) route_count ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS value
                        FROM apr_requests request
                        JOIN apr_form_versions used_version
                          ON used_version.tenant_id = request.tenant_id
                         AND used_version.form_version_id = request.form_version_id
                       WHERE request.tenant_id = form.tenant_id
                         AND used_version.form_id = form.form_id
                  ) usage_count ON TRUE
                 WHERE form.tenant_id = :tenantId
                   AND (:publishedOnly = FALSE OR (
                       form.lifecycle_state = 'PUBLISHED'
                       AND category.lifecycle_state = 'ACTIVE'
                       AND EXISTS (
                           SELECT 1 FROM apr_form_workflow_bindings active_binding
                           JOIN apr_workflow_definitions active_workflow
                             ON active_workflow.tenant_id = active_binding.tenant_id
                            AND active_workflow.workflow_id = active_binding.workflow_id
                          WHERE active_binding.tenant_id = form.tenant_id
                            AND active_binding.form_id = form.form_id
                            AND active_binding.binding_type = 'DEFAULT'
                            AND active_binding.lifecycle_state = 'ACTIVE'
                            AND active_workflow.lifecycle_state = 'PUBLISHED')))
                 ORDER BY category.sort_order,
                          CASE form.lifecycle_state WHEN 'PUBLISHED' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                          form.name_en
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("publishedOnly", publishedOnly),
                (result, rowNumber) -> formSummary(result));
    }

    public ApprovalDtos.FormDetail form(long tenantId, UUID formId) {
        List<ApprovalDtos.FormDetail> matches = jdbc.query("""
                SELECT form.form_id, form.form_key,
                       category.category_id, category.category_key,
                       category.name_ko AS category_name_ko,
                       category.name_en AS category_name_en,
                       form.name_ko, form.name_en, form.description_ko, form.description_en,
                       form.owner_group_ref, form.form_kind,
                       form.lifecycle_state, form.current_version,
                       jsonb_array_length(version.schema_payload -> 'fields') AS field_count,
                       COALESCE(route_count.value, 0)::INTEGER AS route_count,
                       COALESCE(usage_count.value, 0)::BIGINT AS usage_count,
                       form.version, form.updated_at,
                       version.schema_payload::text, version.schema_sha256
                  FROM apr_forms form
                  JOIN apr_form_versions version
                    ON version.tenant_id = form.tenant_id
                   AND version.form_id = form.form_id
                   AND version.version_number = form.current_version
                  JOIN apr_form_categories category
                    ON category.tenant_id = form.tenant_id
                   AND category.category_id = form.category_id
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS value
                        FROM apr_form_workflow_bindings binding
                       WHERE binding.tenant_id = form.tenant_id
                         AND binding.form_id = form.form_id
                         AND binding.lifecycle_state = 'ACTIVE'
                  ) route_count ON TRUE
                  LEFT JOIN LATERAL (
                      SELECT COUNT(*) AS value
                        FROM apr_requests request
                        JOIN apr_form_versions used_version
                          ON used_version.tenant_id = request.tenant_id
                         AND used_version.form_version_id = request.form_version_id
                       WHERE request.tenant_id = form.tenant_id
                         AND used_version.form_id = form.form_id
                  ) usage_count ON TRUE
                 WHERE form.tenant_id = :tenantId
                   AND form.form_id = :formId
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("formId", formId),
                (result, rowNumber) -> new ApprovalDtos.FormDetail(
                        formSummary(result),
                        json(result.getString("schema_payload")),
                        result.getString("schema_sha256"),
                        formRoutes(tenantId, formId)));
        if (matches.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return matches.get(0);
    }

    private List<ApprovalDtos.FormRouteSummary> formRoutes(long tenantId, UUID formId) {
        return jdbc.query("""
                SELECT binding.binding_id, workflow.workflow_id, workflow.workflow_key,
                       workflow.name_ko, workflow.name_en, workflow.lifecycle_state,
                       workflow.current_version, workflow.sla_minutes,
                       binding.binding_type, binding.priority
                  FROM apr_form_workflow_bindings binding
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id = binding.tenant_id
                   AND workflow.workflow_id = binding.workflow_id
                 WHERE binding.tenant_id = :tenantId
                   AND binding.form_id = :formId
                   AND binding.lifecycle_state = 'ACTIVE'
                 ORDER BY CASE binding.binding_type WHEN 'DEFAULT' THEN 0 ELSE 1 END,
                          binding.priority, workflow.name_en
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("formId", formId),
                (result, rowNumber) -> new ApprovalDtos.FormRouteSummary(
                        result.getObject("binding_id", UUID.class),
                        result.getObject("workflow_id", UUID.class),
                        result.getString("workflow_key"),
                        result.getString("name_ko"),
                        result.getString("name_en"),
                        result.getString("lifecycle_state"),
                        result.getInt("current_version"),
                        result.getInt("sla_minutes"),
                        result.getString("binding_type"),
                        result.getInt("priority")));
    }

    public List<ApprovalDtos.PolicySummary> policies(long tenantId) {
        return jdbc.query("""
                SELECT policy_id, policy_key, name_ko, name_en, policy_type,
                       enforcement_mode, severity, lifecycle_state,
                       rule_payload::text, version,
                       pending_enforcement_mode, pending_severity,
                       pending_lifecycle_state, pending_rule_payload::text,
                       pending_change_reason, pending_by, pending_at
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
                        result.getLong("version"),
                        result.getObject("pending_by") != null,
                        result.getString("pending_enforcement_mode"),
                        result.getString("pending_severity"),
                        result.getString("pending_lifecycle_state"),
                        json(result.getString("pending_rule_payload")),
                        result.getString("pending_change_reason"),
                        result.getObject("pending_by", Long.class),
                        instant(result, "pending_at")));
    }

    public List<ApprovalDtos.PolicyVersionSummary> policyVersions(
            long tenantId,
            UUID policyId) {
        return jdbc.query("""
                SELECT policy_version_id, version_number, enforcement_mode,
                       severity, lifecycle_state, rule_payload::text,
                       change_reason, submitted_by, submitted_at,
                       published_by, published_at, review_comment
                  FROM apr_policy_rule_versions
                 WHERE tenant_id = :tenantId AND policy_id = :policyId
                 ORDER BY version_number DESC
                 LIMIT 100
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("policyId", policyId),
                (result, rowNumber) -> new ApprovalDtos.PolicyVersionSummary(
                        result.getObject("policy_version_id", UUID.class),
                        result.getInt("version_number"),
                        result.getString("enforcement_mode"),
                        result.getString("severity"),
                        result.getString("lifecycle_state"),
                        json(result.getString("rule_payload")),
                        result.getString("change_reason"),
                        result.getObject("submitted_by", Long.class),
                        instant(result, "submitted_at"),
                        result.getObject("published_by", Long.class),
                        instant(result, "published_at"),
                        result.getString("review_comment")));
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
                       delegate_person_public_id, delegate_display_name, delegate_email,
                       scope_type, workflow_key, starts_at, ends_at,
                       lifecycle_state, reason, version,
                       CASE WHEN delegator_user_id = :userId THEN 'OUTGOING' ELSE 'INCOMING' END
                           AS direction
                  FROM apr_delegations
                 WHERE tenant_id = :tenantId
                   AND (delegator_user_id = :userId OR delegate_user_id = :userId)
                 ORDER BY CASE lifecycle_state WHEN 'ACTIVE' THEN 0 ELSE 1 END,
                          starts_at DESC
                """, actorParams(actor),
                (result, rowNumber) -> new ApprovalDtos.DelegationSummary(
                        result.getObject("delegation_id", UUID.class),
                        result.getLong("delegator_user_id"),
                        result.getLong("delegate_user_id"),
                        result.getObject("delegate_person_public_id", UUID.class),
                        result.getString("delegate_display_name"),
                        result.getString("delegate_email"),
                        result.getString("scope_type"),
                        result.getString("workflow_key"),
                        instant(result, "starts_at"),
                        instant(result, "ends_at"),
                        result.getString("lifecycle_state"),
                        result.getString("reason"),
                        result.getLong("version"),
                        result.getString("direction")));
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

    public List<ApprovalDtos.IntegrationDeliverySummary> integrationDeliveries(
            long tenantId,
            int limit) {
        return jdbc.query("""
                SELECT outbox_id, event_id, request_id, event_type, status,
                       attempt_count, manual_retry_count, available_at,
                       published_at, last_error, created_at, last_retried_at
                  FROM apr_integration_outbox
                 WHERE tenant_id = :tenantId
                 ORDER BY CASE status
                              WHEN 'DEAD' THEN 0
                              WHEN 'FAILED' THEN 1
                              WHEN 'SENDING' THEN 2
                              WHEN 'PENDING' THEN 3
                              ELSE 4
                          END,
                          updated_at DESC, outbox_id
                 LIMIT :limit
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("limit", Math.max(1, Math.min(limit, 100))),
                (result, rowNumber) -> new ApprovalDtos.IntegrationDeliverySummary(
                        result.getObject("outbox_id", UUID.class),
                        result.getObject("event_id", UUID.class),
                        result.getObject("request_id", UUID.class),
                        result.getString("event_type"),
                        result.getString("status"),
                        result.getInt("attempt_count"),
                        result.getInt("manual_retry_count"),
                        instant(result, "available_at"),
                        instant(result, "published_at"),
                        result.getString("last_error"),
                        instant(result, "created_at"),
                        instant(result, "last_retried_at")));
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

    private ApprovalDtos.FormSummary formSummary(ResultSet result) throws SQLException {
        return new ApprovalDtos.FormSummary(
                result.getObject("form_id", UUID.class),
                result.getString("form_key"),
                result.getObject("category_id", UUID.class),
                result.getString("category_key"),
                result.getString("category_name_ko"),
                result.getString("category_name_en"),
                result.getString("name_ko"),
                result.getString("name_en"),
                result.getString("description_ko"),
                result.getString("description_en"),
                result.getString("owner_group_ref"),
                result.getString("form_kind"),
                result.getString("lifecycle_state"),
                result.getInt("current_version"),
                result.getInt("field_count"),
                result.getInt("route_count"),
                result.getLong("usage_count"),
                result.getLong("version"),
                instant(result, "updated_at"));
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
            String candidateRole,
            boolean delegatedAccess,
            Long delegatedFromUserId) {
    }

    private record RequestAssetIds(
            UUID workflowId,
            UUID formId,
            Map<String, Object> formSchema) {
    }
}
