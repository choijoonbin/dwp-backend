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

    private static final String TASK_SELECT = ApprovalQuerySql01.QUERY_SELECT_APR_TASKS;

    private static final String DIRECT_TASK_ACCESS = ApprovalQuerySql01.QUERY_SQL_STATEMENT;

    private static final String DELEGATED_TASK_ACCESS = ApprovalQuerySql01.QUERY_SQL_APR_DELEGATIONS;

    private static final String COMPLETED_BY_ACTOR_ACCESS = ApprovalQuerySql01.JSONB_EXISTS_SQL_STATEMENT;

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
        jdbc.update(ApprovalQuerySql01.ENSURE_TENANT_INSERT_APR_TENANTS, new MapSqlParameterSource("tenantId", tenantId));
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
        return jdbc.queryForObject(ApprovalQuerySql01.METRICS_WITH_APR_TASKS + DIRECT_TASK_ACCESS + " OR " + DELEGATED_TASK_ACCESS + ApprovalQuerySql01.AS_SQL_VISIBLE_TASKS, params, (result, rowNumber) -> new ApprovalDtos.ApprovalMetrics(
                result.getInt("pending"),
                result.getInt("due_today"),
                result.getInt("overdue"),
                result.getInt("needs_information"),
                result.getInt("in_flight"),
                round(result.getDouble("average_cycle")),
                round(result.getDouble("sla_compliance"))));
    }

    public boolean isBlockingPolicyActive(long tenantId, String policyKey) {
        Integer count = jdbc.queryForObject(ApprovalQuerySql01.IS_BLOCKING_POLICY_ACTIVE_SELECT_APR_POLICY_RULES, new MapSqlParameterSource()
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
        String taskQuery = TASK_SELECT + ApprovalQuerySql01.TASKS_SQL_STATEMENT + accessClause + ApprovalQuerySql01.TASKS_SQL_STATEMENT_2 + statusClause + ApprovalQuerySql01.TASKS_SQL_STATEMENT_3.formatted(orderClause);
        return jdbc.query(
                taskQuery,
                actorParams(actor).addValue("limit", Math.max(1, Math.min(limit, 200))),
                (result, rowNumber) -> taskSummary(result));
    }

    public TaskAccess taskDetail(
            ApprovalRequestContext.Actor actor,
            UUID taskId) {
        List<TaskAccess> matches = jdbc.query(
                TASK_SELECT + ApprovalQuerySql01.TASK_DETAIL_SQL_STATEMENT + DIRECT_TASK_ACCESS + " OR " + DELEGATED_TASK_ACCESS
                        + " OR " + COMPLETED_BY_ACTOR_ACCESS + ApprovalQuerySql01.TASK_DETAIL_SQL_STATEMENT_2,
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
        return jdbc.query(ApprovalQuerySql01.DELEGATION_SOURCE_SELECT_APR_TASKS, actorParams(actor).addValue("taskId", taskId),
                result -> result.next() ? result.getLong(1) : null);
    }

    public Map<String, Object> requestPayload(long tenantId, UUID requestId) {
        List<String> payloads = jdbc.query(
                ApprovalQuerySql01.REQUEST_PAYLOAD_SELECT_APR_REQUEST_PAYLOADS,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("requestId", requestId),
                (result, rowNumber) -> result.getString(1));
        return payloads.isEmpty() ? Map.of() : json(payloads.get(0));
    }

    public Map<String, Object> requestFormSchema(long tenantId, UUID requestId) {
        List<String> schemas = jdbc.query(
                ApprovalQuerySql01.REQUEST_FORM_SCHEMA_SELECT_APR_REQUESTS,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("requestId", requestId),
                (result, rowNumber) -> result.getString(1));
        return schemas.isEmpty() ? Map.of() : json(schemas.get(0));
    }

    public List<ApprovalDtos.TimelineEvent> timeline(long tenantId, UUID requestId) {
        return jdbc.query(ApprovalQuerySql01.TIMELINE_SELECT_APR_REQUEST_EVENTS,
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
        return jdbc.query(ApprovalQuerySql01.REQUESTS_SELECT_APR_REQUEST_EVENTS + statusClause + ApprovalQuerySql01.COUNT_SQL_STATEMENT,
                actorParams(actor).addValue("limit", Math.max(1, Math.min(limit, 200))),
                (result, rowNumber) -> requestSummary(result));
    }

    public ApprovalDtos.RequestSummary request(
            ApprovalRequestContext.Actor actor,
            UUID requestId) {
        List<ApprovalDtos.RequestSummary> matches = jdbc.query(ApprovalQuerySql01.REQUEST_SELECT_APR_REQUEST_EVENTS, actorParams(actor).addValue("requestId", requestId),
                (result, rowNumber) -> requestSummary(result));
        if (matches.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return matches.get(0);
    }

    public ApprovalDtos.RequestDetail requestDetail(
            ApprovalRequestContext.Actor actor,
            UUID requestId) {
        ApprovalDtos.RequestSummary request = request(actor, requestId);
        RequestAssetIds assets = jdbc.queryForObject(ApprovalQuerySql01.REQUEST_DETAIL_SELECT_APR_REQUESTS, actorParams(actor).addValue("requestId", requestId),
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
        Map<String, Integer> counts = jdbc.query(ApprovalQuerySql01.FLOW_WITH_APR_REQUESTS + DIRECT_TASK_ACCESS + " OR " + DELEGATED_TASK_ACCESS + ApprovalQuerySql01.AS_SQL_VISIBLE_REQUESTS, actorParams(actor), result -> {
            Map<String, Integer> values = new java.util.LinkedHashMap<>();
            while (result.next()) values.put(result.getString("status"), result.getInt("count"));
            return values;
        });
        int atRisk = jdbc.queryForObject(ApprovalQuerySql01.IN_SELECT_APR_TASKS + DIRECT_TASK_ACCESS + " OR " + DELEGATED_TASK_ACCESS + ApprovalQuerySql01.IN_SQL_STATEMENT, actorParams(actor), Integer.class);
        List<ApprovalDtos.StageMetric> stages = new ArrayList<>();
        for (String stage : List.of("SUBMITTED", "IN_REVIEW", "NEEDS_INFO", "APPROVED")) {
            int count = counts.getOrDefault(stage, 0);
            stages.add(new ApprovalDtos.StageMetric(
                    stage, count, stage.equals("IN_REVIEW") ? atRisk : 0));
        }
        return List.copyOf(stages);
    }

    public ApprovalDtos.AdminPulse adminPulse(long tenantId) {
        return jdbc.queryForObject(ApprovalQuerySql01.ADMIN_PULSE_SELECT_APR_WORKFLOW_DEFINITIONS, new MapSqlParameterSource("tenantId", tenantId),
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
        return jdbc.query(ApprovalQuerySql01.WORKFLOWS_SELECT_APR_WORKFLOW_DEFINITIONS,
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
        List<ApprovalDtos.WorkflowDetail> matches = jdbc.query(ApprovalQuerySql01.WORKFLOW_SELECT_APR_WORKFLOW_DEFINITIONS, new MapSqlParameterSource()
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
        List<UUID> formIds = jdbc.query(ApprovalQuerySql01.PUBLISHED_TEMPLATE_SELECT_APR_FORM_WORKFLOW_BINDINGS, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId),
                (result, rowNumber) -> result.getObject("form_id", UUID.class));
        if (formIds.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return new ApprovalDtos.RequestTemplate(
                workflow.workflow(), workflow.definition(), form(tenantId, formIds.get(0)));
    }

    public ApprovalDtos.RequestTemplate publishedTemplateByForm(long tenantId, UUID formId) {
        List<UUID> workflowIds = jdbc.query(ApprovalQuerySql01.PUBLISHED_TEMPLATE_BY_FORM_SELECT_APR_FORM_WORKFLOW_BINDINGS, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("formId", formId),
                (result, rowNumber) -> result.getObject("workflow_id", UUID.class));
        if (workflowIds.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        ApprovalDtos.WorkflowDetail workflow = workflow(tenantId, workflowIds.get(0));
        return new ApprovalDtos.RequestTemplate(
                workflow.workflow(), workflow.definition(), form(tenantId, formId));
    }

    public List<ApprovalDtos.FormCategorySummary> formCategories(long tenantId) {
        return jdbc.query(ApprovalQuerySql01.FORM_CATEGORIES_SELECT_APR_FORM_CATEGORIES, new MapSqlParameterSource("tenantId", tenantId),
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
        return jdbc.query(ApprovalQuerySql01.FORMS_SELECT_APR_FORMS, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("publishedOnly", publishedOnly),
                (result, rowNumber) -> formSummary(result));
    }

    public ApprovalDtos.FormDetail form(long tenantId, UUID formId) {
        List<ApprovalDtos.FormDetail> matches = jdbc.query(ApprovalQuerySql02.FORM_SELECT_APR_FORMS, new MapSqlParameterSource()
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
        return jdbc.query(ApprovalQuerySql02.FORM_ROUTES_SELECT_APR_FORM_WORKFLOW_BINDINGS, new MapSqlParameterSource()
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
        return jdbc.query(ApprovalQuerySql02.POLICIES_SELECT_APR_POLICY_RULES, new MapSqlParameterSource("tenantId", tenantId),
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
        return jdbc.query(ApprovalQuerySql02.POLICY_VERSIONS_SELECT_APR_POLICY_RULE_VERSIONS, new MapSqlParameterSource()
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
        return jdbc.query(ApprovalQuerySql02.SIGNATURE_PROVIDERS_SELECT_APR_SIGNATURE_PROVIDERS, new MapSqlParameterSource("tenantId", tenantId),
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
        return jdbc.query(ApprovalQuerySql02.DELEGATIONS_SELECT_APR_DELEGATIONS, actorParams(actor),
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
        Integer count = jdbc.queryForObject(ApprovalQuerySql02.FAILED_INTEGRATION_COUNT_SELECT_APR_INTEGRATION_OUTBOX, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count == null ? 0 : count;
    }

    public int pendingIntegrationCount(long tenantId) {
        Integer count = jdbc.queryForObject(ApprovalQuerySql02.PENDING_INTEGRATION_COUNT_SELECT_APR_INTEGRATION_OUTBOX, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count == null ? 0 : count;
    }

    public List<ApprovalDtos.IntegrationDeliverySummary> integrationDeliveries(
            long tenantId,
            int limit) {
        return jdbc.query(ApprovalQuerySql02.INTEGRATION_DELIVERIES_SELECT_APR_INTEGRATION_OUTBOX, new MapSqlParameterSource()
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
        Integer count = jdbc.queryForObject(ApprovalQuerySql02.ACTIVE_DELEGATION_COUNT_SELECT_APR_DELEGATIONS, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return count == null ? 0 : count;
    }

    public List<ApprovalDtos.TaskSummary> breachedTasks(long tenantId, int limit) {
        return jdbc.query(TASK_SELECT + ApprovalQuerySql02.BREACHED_TASKS_SQL_STATEMENT, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("limit", Math.max(1, Math.min(limit, 100))),
                (result, rowNumber) -> taskSummary(result));
    }

    private int overdueCount(long tenantId) {
        Integer count = jdbc.queryForObject(ApprovalQuerySql02.OVERDUE_COUNT_SELECT_APR_TASKS, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
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
