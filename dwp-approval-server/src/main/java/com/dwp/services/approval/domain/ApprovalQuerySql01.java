package com.dwp.services.approval.domain;

final class ApprovalQuerySql01 {

    private ApprovalQuerySql01() {
    }

    static final String QUERY_SELECT_APR_TASKS = """
        SELECT task.task_id, task.request_id, request.request_number,
               request.title, request.summary,
               workflow.name_ko AS workflow_name_ko,
               workflow.name_en AS workflow_name_en,
               step.step_key, step.step_name, step.sequence_number AS step_sequence,
               request.requester_user_id, request.requester_name,
               request.requester_org_name, task.assignee_user_id,
               task.candidate_role, task.status, request.priority,
               request.data_classification, task.risk_score,
               request.management_resource_set_key,
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

    static final String QUERY_SQL_STATEMENT = """
        (task.assignee_user_id = :userId
         OR (task.assignee_user_id IS NULL AND task.candidate_role IN (:roles)))
        """;

    static final String QUERY_SQL_APR_DELEGATIONS = """
        EXISTS (
            SELECT 1
              FROM apr_delegations delegation
             WHERE delegation.tenant_id = task.tenant_id
               AND delegation.delegate_user_id = :userId
               AND delegation.lifecycle_state = 'ACTIVE'
               AND CURRENT_TIMESTAMP BETWEEN delegation.starts_at AND delegation.ends_at
               AND (delegation.scope_type = 'ALL'
                    OR delegation.workflow_id = workflow.workflow_id)
               AND (
                    task.assignee_user_id = delegation.delegator_user_id
                    OR (task.assignee_user_id IS NULL
                        AND jsonb_exists(
                            delegation.delegated_role_codes,
                            task.candidate_role))
               )
        )
        """;

    static final String JSONB_EXISTS_SQL_STATEMENT = """
        task.decision_actor_user_id = :userId
        """;

    static final String ENSURE_TENANT_INSERT_APR_TENANTS = """
        INSERT INTO apr_tenants (tenant_id)
        VALUES (:tenantId)
        ON CONFLICT (tenant_id) DO NOTHING
        """;

    static final String METRICS_WITH_APR_TASKS = """
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
        """;

    static final String AS_SQL_VISIBLE_TASKS = """
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
        """;

    static final String IS_BLOCKING_POLICY_ACTIVE_SELECT_APR_POLICY_RULES = """
        SELECT COUNT(*)::INTEGER
          FROM apr_policy_rules
         WHERE tenant_id = :tenantId
           AND policy_key = :policyKey
           AND management_resource_set_key = :managementScope
           AND lifecycle_state = 'ACTIVE'
           AND enforcement_mode = 'BLOCK'
        """;

    static final String TASKS_SQL_STATEMENT = """
        WHERE task.tenant_id = :tenantId
          AND (
        """;

    static final String TASKS_SQL_STATEMENT_2 = """
        )
        AND (
        """;

    static final String TASKS_SQL_STATEMENT_3 = """
          )
        ORDER BY %s
        LIMIT :limit
        """;

    static final String TASK_DETAIL_SQL_STATEMENT = """
        WHERE task.tenant_id = :tenantId
          AND task.task_id = :taskId
          AND (
        """;

    static final String TASK_DETAIL_SQL_STATEMENT_2 = """
        )
        """;

    static final String DELEGATION_SOURCE_SELECT_APR_TASKS = """
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
                OR delegation.workflow_id = workflow.workflow_id)
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
        """;

    static final String REQUEST_PAYLOAD_SELECT_APR_REQUEST_PAYLOADS = """
        SELECT payload::text
          FROM apr_request_payloads
         WHERE tenant_id = :tenantId AND request_id = :requestId
        """;

    static final String REQUEST_FORM_SCHEMA_SELECT_APR_REQUESTS = """
        SELECT form_version.schema_payload::text
          FROM apr_requests request
          JOIN apr_form_versions form_version
            ON form_version.tenant_id = request.tenant_id
           AND form_version.form_version_id = request.form_version_id
         WHERE request.tenant_id = :tenantId
           AND request.request_id = :requestId
        """;

    static final String TIMELINE_SELECT_APR_REQUEST_EVENTS = """
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
        """;

    static final String REQUESTS_SELECT_APR_REQUEST_EVENTS = """
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
        """;

    static final String COUNT_SQL_STATEMENT = """
          )
        ORDER BY request.updated_at DESC, request.request_id
        LIMIT :limit
        """;

    static final String REQUEST_SELECT_APR_REQUEST_EVENTS = """
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
        """;

    static final String REQUEST_DETAIL_SELECT_APR_REQUESTS = """
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
        """;

    static final String FLOW_WITH_APR_REQUESTS = """
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
        """;

    static final String AS_SQL_VISIBLE_REQUESTS = """
               )
        )
        SELECT status, COUNT(*)::INTEGER AS count
          FROM visible_requests
         WHERE status NOT IN ('DRAFT', 'WITHDRAWN', 'CANCELLED')
         GROUP BY status
        """;

    static final String IN_SELECT_APR_TASKS = """
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
        """;

    static final String IN_SQL_STATEMENT = """
        )
        """;

    static final String ADMIN_PULSE_SELECT_APR_WORKFLOW_DEFINITIONS = """
        SELECT
            (SELECT COUNT(*) FROM apr_workflow_definitions
              WHERE tenant_id = :tenantId
                AND management_resource_set_key = :managementScope
                AND lifecycle_state = 'PUBLISHED')::INTEGER
                AS published_workflows,
            (SELECT COUNT(*) FROM apr_workflow_definitions
              WHERE tenant_id = :tenantId
                AND management_resource_set_key = :managementScope
                AND lifecycle_state = 'DRAFT')::INTEGER
                AS draft_workflows,
            (SELECT COUNT(*) FROM apr_requests
              WHERE tenant_id = :tenantId
                AND management_resource_set_key = :managementScope
                AND status IN ('SUBMITTED', 'IN_REVIEW', 'NEEDS_INFO'))::INTEGER
                AS active_requests,
            (SELECT COUNT(*) FROM apr_tasks task
              WHERE task.tenant_id = :tenantId
                AND task.status IN ('PENDING', 'CLAIMED')
                AND task.due_at < CURRENT_TIMESTAMP
                AND EXISTS (
                    SELECT 1 FROM apr_requests request
                     WHERE request.tenant_id = task.tenant_id
                       AND request.request_id = task.request_id
                       AND request.management_resource_set_key = :managementScope))::INTEGER
                AS overdue_tasks,
            (SELECT COUNT(*) FROM apr_integration_outbox
              WHERE tenant_id = :tenantId
                AND management_resource_set_key = :managementScope
                AND status IN ('FAILED', 'DEAD'))::INTEGER
                AS failed_integrations,
            (SELECT COUNT(*) FROM apr_delegations delegation
              WHERE delegation.tenant_id = :tenantId
                AND delegation.lifecycle_state = 'ACTIVE'
                AND CURRENT_TIMESTAMP BETWEEN starts_at AND ends_at
                AND (delegate_person_public_id IS NULL
                     OR delegate_display_name IS NULL)
                AND ((
                    delegation.scope_type = 'ALL'
                    AND :managementScope = 'RS_APPROVALS') OR EXISTS (
                    SELECT 1 FROM apr_workflow_definitions workflow
                     WHERE workflow.tenant_id = delegation.tenant_id
                       AND workflow.workflow_id = delegation.workflow_id
                       AND workflow.management_resource_set_key = :managementScope)))::INTEGER
                AS identity_gaps,
            ((SELECT COUNT(*)
                FROM apr_workflow_versions version
                JOIN apr_workflow_definitions definition
                  ON definition.tenant_id = version.tenant_id
                 AND definition.workflow_id = version.workflow_id
               WHERE version.tenant_id = :tenantId
                 AND definition.management_resource_set_key = :managementScope
                 AND version.lifecycle_state = 'PUBLISHED'
                 AND version.created_by = version.published_by)
             + (SELECT COUNT(*)
                  FROM apr_form_versions version
                  JOIN apr_forms form
                    ON form.tenant_id = version.tenant_id
                   AND form.form_id = version.form_id
                 WHERE version.tenant_id = :tenantId
                   AND form.management_resource_set_key = :managementScope
                   AND version.lifecycle_state = 'PUBLISHED'
                   AND version.created_by = version.published_by))::INTEGER
                AS sod_violations,
            (SELECT COUNT(*) FROM apr_tasks task
              WHERE task.tenant_id = :tenantId
                AND task.status IN ('APPROVED', 'REJECTED')
                AND task.decision_actor_user_id IS NULL
                AND EXISTS (
                    SELECT 1 FROM apr_requests request
                     WHERE request.tenant_id = task.tenant_id
                       AND request.request_id = task.request_id
                       AND request.management_resource_set_key = :managementScope))::INTEGER
                AS evidence_gaps
        """;

    static final String WORKFLOWS_SELECT_APR_WORKFLOW_DEFINITIONS = """
        SELECT workflow_id, workflow_key, name_ko, name_en,
               description_ko, description_en, category,
               data_classification, lifecycle_state, current_version,
               sla_minutes, allow_self_approval, owner_group_ref,
               version, updated_at
          FROM apr_workflow_definitions
         WHERE tenant_id = :tenantId
           AND (:workCatalog = TRUE
                OR management_resource_set_key = :managementScope)
           AND (:publishedOnly = FALSE OR lifecycle_state = 'PUBLISHED')
         ORDER BY CASE lifecycle_state WHEN 'PUBLISHED' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                  category, name_en
        """;

    static final String WORKFLOW_SELECT_APR_WORKFLOW_DEFINITIONS = """
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
           AND (:workCatalog = TRUE OR
                definition.management_resource_set_key = :managementScope)
        """;

    static final String PUBLISHED_TEMPLATE_SELECT_APR_FORM_WORKFLOW_BINDINGS = """
        SELECT form.form_id
          FROM apr_form_workflow_bindings binding
          JOIN apr_forms form
            ON form.tenant_id = binding.tenant_id
           AND form.form_id = binding.form_id
          JOIN apr_workflow_definitions workflow
            ON workflow.tenant_id = binding.tenant_id
           AND workflow.workflow_id = binding.workflow_id
         WHERE binding.tenant_id = :tenantId
           AND binding.workflow_id = :workflowId
           AND (:workCatalog = TRUE OR
                form.management_resource_set_key = :managementScope)
           AND binding.lifecycle_state = 'ACTIVE'
           AND form.lifecycle_state = 'PUBLISHED'
           AND workflow.lifecycle_state = 'PUBLISHED'
           AND (binding.effective_from IS NULL
                OR binding.effective_from <= CURRENT_TIMESTAMP)
           AND (binding.effective_to IS NULL
                OR binding.effective_to > CURRENT_TIMESTAMP)
         ORDER BY CASE binding.binding_type WHEN 'DEFAULT' THEN 0 ELSE 1 END,
                  binding.priority, form.name_en
         LIMIT 1
        """;

    static final String PUBLISHED_TEMPLATE_BY_FORM_SELECT_APR_FORM_WORKFLOW_BINDINGS = """
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
           AND (:workCatalog = TRUE OR (
               form.management_resource_set_key = :managementScope
               AND workflow.management_resource_set_key = :managementScope))
           AND binding.binding_type = 'DEFAULT'
           AND binding.lifecycle_state = 'ACTIVE'
           AND form.lifecycle_state = 'PUBLISHED'
           AND workflow.lifecycle_state = 'PUBLISHED'
           AND (binding.effective_from IS NULL OR binding.effective_from <= CURRENT_TIMESTAMP)
           AND (binding.effective_to IS NULL OR binding.effective_to > CURRENT_TIMESTAMP)
         LIMIT 1
        """;

    static final String FORM_CATEGORIES_SELECT_APR_FORM_CATEGORIES = """
        SELECT category.category_id, category.category_key,
               category.parent_category_id, category.name_ko, category.name_en,
               category.description_ko, category.description_en, category.icon_key,
               category.sort_order, category.lifecycle_state, category.version,
               COUNT(form.form_id)::INTEGER AS form_count
          FROM apr_form_categories category
          LEFT JOIN apr_forms form
            ON form.tenant_id = category.tenant_id
           AND form.category_id = category.category_id
           AND form.management_resource_set_key = category.management_resource_set_key
           AND form.lifecycle_state <> 'RETIRED'
         WHERE category.tenant_id = :tenantId
           AND category.management_resource_set_key = :managementScope
         GROUP BY category.category_id
         ORDER BY category.sort_order, category.name_en
        """;

    static final String FORMS_SELECT_APR_FORMS = """
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
           AND (:workCatalog = TRUE OR
                form.management_resource_set_key = :managementScope)
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
                    AND active_workflow.lifecycle_state = 'PUBLISHED'
                    AND (active_binding.effective_from IS NULL
                         OR active_binding.effective_from <= CURRENT_TIMESTAMP)
                    AND (active_binding.effective_to IS NULL
                         OR active_binding.effective_to > CURRENT_TIMESTAMP))))
         ORDER BY category.sort_order,
                  CASE form.lifecycle_state WHEN 'PUBLISHED' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                  form.name_en
        """;
}
