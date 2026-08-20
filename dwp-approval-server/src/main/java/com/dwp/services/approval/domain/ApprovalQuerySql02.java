package com.dwp.services.approval.domain;

final class ApprovalQuerySql02 {

    private ApprovalQuerySql02() {
    }

    static final String FORM_SELECT_APR_FORMS = """
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
        """;

    static final String FORM_ROUTES_SELECT_APR_FORM_WORKFLOW_BINDINGS = """
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
        """;

    static final String POLICIES_SELECT_APR_POLICY_RULES = """
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
        """;

    static final String POLICY_VERSIONS_SELECT_APR_POLICY_RULE_VERSIONS = """
        SELECT policy_version_id, version_number, enforcement_mode,
               severity, lifecycle_state, rule_payload::text,
               change_reason, submitted_by, submitted_at,
               published_by, published_at, review_comment
          FROM apr_policy_rule_versions
         WHERE tenant_id = :tenantId AND policy_id = :policyId
         ORDER BY version_number DESC
         LIMIT 100
        """;

    static final String SIGNATURE_PROVIDERS_SELECT_APR_SIGNATURE_PROVIDERS = """
        SELECT provider_id, provider_key, display_name, provider_type,
               lifecycle_state, capability_metadata::text,
               credential_reference IS NOT NULL AS credential_configured,
               last_health_checked_at, version
          FROM apr_signature_providers
         WHERE tenant_id = :tenantId
         ORDER BY CASE provider_type WHEN 'INTERNAL_ATTESTATION' THEN 0 ELSE 1 END,
                  display_name
        """;

    static final String DELEGATIONS_SELECT_APR_DELEGATIONS = """
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
        """;

    static final String FAILED_INTEGRATION_COUNT_SELECT_APR_INTEGRATION_OUTBOX = """
        SELECT COUNT(*)::INTEGER FROM apr_integration_outbox
         WHERE tenant_id = :tenantId AND status IN ('FAILED', 'DEAD')
        """;

    static final String PENDING_INTEGRATION_COUNT_SELECT_APR_INTEGRATION_OUTBOX = """
        SELECT COUNT(*)::INTEGER FROM apr_integration_outbox
         WHERE tenant_id = :tenantId AND status IN ('PENDING', 'SENDING')
        """;

    static final String INTEGRATION_DELIVERIES_SELECT_APR_INTEGRATION_OUTBOX = """
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
        """;

    static final String ACTIVE_DELEGATION_COUNT_SELECT_APR_DELEGATIONS = """
        SELECT COUNT(*)::INTEGER FROM apr_delegations
         WHERE tenant_id = :tenantId AND lifecycle_state = 'ACTIVE'
           AND CURRENT_TIMESTAMP BETWEEN starts_at AND ends_at
        """;

    static final String BREACHED_TASKS_SQL_STATEMENT = """
        WHERE task.tenant_id = :tenantId
          AND task.status IN ('PENDING', 'CLAIMED')
          AND task.due_at < CURRENT_TIMESTAMP
        ORDER BY task.due_at, task.risk_score DESC
        LIMIT :limit
        """;

    static final String OVERDUE_COUNT_SELECT_APR_TASKS = """
        SELECT COUNT(*)::INTEGER FROM apr_tasks
         WHERE tenant_id = :tenantId
           AND status IN ('PENDING', 'CLAIMED')
           AND due_at < CURRENT_TIMESTAMP
        """;
}
