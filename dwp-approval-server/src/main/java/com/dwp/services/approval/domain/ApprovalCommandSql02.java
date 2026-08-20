package com.dwp.services.approval.domain;

final class ApprovalCommandSql02 {

    private ApprovalCommandSql02() {
    }

    static final String PUBLISH_WORKFLOW_UPDATE_APR_WORKFLOW_DEFINITIONS = """
        UPDATE apr_workflow_definitions
           SET lifecycle_state = 'PUBLISHED', version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = :userId
         WHERE tenant_id = :tenantId AND workflow_id = :workflowId
           AND lifecycle_state = 'DRAFT' AND version = :expectedVersion
           AND COALESCE(updated_by, created_by, -1) <> :userId
        """;

    static final String COALESCE_UPDATE_APR_WORKFLOW_VERSIONS = """
        UPDATE apr_workflow_versions
           SET lifecycle_state = 'PUBLISHED', effective_from = CURRENT_TIMESTAMP,
               published_at = CURRENT_TIMESTAMP, published_by = :userId
         WHERE tenant_id = :tenantId AND workflow_id = :workflowId
           AND lifecycle_state = 'DRAFT'
        """;

    static final String WORKFLOW_SELECT_APR_WORKFLOW_DEFINITIONS = """
        SELECT version.workflow_version_id, form_version.form_version_id,
               definition.data_classification,
               form_version.schema_payload::text AS form_schema,
               binding.binding_type,
               binding.condition_payload::text AS binding_condition
          FROM apr_workflow_definitions definition
          JOIN apr_workflow_versions version
            ON version.tenant_id = definition.tenant_id
           AND version.workflow_id = definition.workflow_id
           AND version.version_number = definition.current_version
          JOIN apr_form_workflow_bindings binding
            ON binding.tenant_id = definition.tenant_id
           AND binding.workflow_id = definition.workflow_id
           AND binding.form_id = :formId
           AND binding.lifecycle_state = 'ACTIVE'
           AND (binding.effective_from IS NULL
                OR binding.effective_from <= CURRENT_TIMESTAMP)
           AND (binding.effective_to IS NULL
                OR binding.effective_to > CURRENT_TIMESTAMP)
          JOIN apr_forms form
            ON form.tenant_id = binding.tenant_id
           AND form.form_id = binding.form_id
          JOIN apr_form_versions form_version
            ON form_version.tenant_id = form.tenant_id
           AND form_version.form_id = form.form_id
           AND form_version.version_number = form.current_version
         WHERE definition.tenant_id = :tenantId
           AND definition.workflow_id = :workflowId
           AND definition.lifecycle_state = 'PUBLISHED'
           AND version.lifecycle_state = 'PUBLISHED'
           AND (version.effective_from IS NULL
                OR version.effective_from <= CURRENT_TIMESTAMP)
           AND (version.effective_to IS NULL
                OR version.effective_to > CURRENT_TIMESTAMP)
           AND form.lifecycle_state = 'PUBLISHED'
           AND form_version.lifecycle_state = 'PUBLISHED'
        """;

    static final String OWNED_REQUEST_SELECT_APR_REQUESTS = """
        SELECT request.status, request.title, workflow.sla_minutes,
               workflow_version.definition::text AS workflow_definition,
               form_version.schema_payload::text AS form_schema,
               payload.payload::text AS request_payload,
               binding.binding_type,
               binding.condition_payload::text AS binding_condition
          FROM apr_requests request
          JOIN apr_workflow_versions workflow_version
            ON workflow_version.tenant_id = request.tenant_id
           AND workflow_version.workflow_version_id = request.workflow_version_id
          JOIN apr_workflow_definitions workflow
            ON workflow.tenant_id = workflow_version.tenant_id
           AND workflow.workflow_id = workflow_version.workflow_id
          JOIN apr_form_versions form_version
            ON form_version.tenant_id = request.tenant_id
           AND form_version.form_version_id = request.form_version_id
          JOIN apr_request_payloads payload
            ON payload.tenant_id = request.tenant_id
           AND payload.request_id = request.request_id
          JOIN apr_form_workflow_bindings binding
            ON binding.tenant_id = request.tenant_id
           AND binding.workflow_id = workflow.workflow_id
           AND binding.form_id = form_version.form_id
           AND binding.lifecycle_state = 'ACTIVE'
           AND (binding.effective_from IS NULL
                OR binding.effective_from <= CURRENT_TIMESTAMP)
           AND (binding.effective_to IS NULL
                OR binding.effective_to > CURRENT_TIMESTAMP)
         WHERE request.tenant_id = :tenantId
           AND request.request_id = :requestId
           AND request.requester_user_id = :userId
        """;

    static final String INFORMATION_RUNTIME_SELECT_APR_REQUESTS = """
        SELECT form_version.schema_payload::text AS form_schema,
               payload.payload::text AS request_payload
          FROM apr_requests request
          JOIN apr_form_versions form_version
            ON form_version.tenant_id = request.tenant_id
           AND form_version.form_version_id = request.form_version_id
          JOIN apr_request_payloads payload
            ON payload.tenant_id = request.tenant_id
           AND payload.request_id = request.request_id
         WHERE request.tenant_id = :tenantId
           AND request.request_id = :requestId
           AND request.requester_user_id = :userId
           AND request.status = 'NEEDS_INFO'
        """;

    static final String APPEND_PAYLOAD_REVISION_INSERT_APR_REQUEST_PAYLOAD_VERSIONS = """
        INSERT INTO apr_request_payload_versions (
            payload_version_id, tenant_id, request_id, revision_number,
            payload, payload_sha256, change_type,
            changed_by, change_reason, correlation_id)
        SELECT gen_random_uuid(), payload.tenant_id, payload.request_id,
               payload.schema_version, payload.payload, payload.payload_sha256,
               :changeType, :userId, :reason, :correlationId
          FROM apr_request_payloads payload
         WHERE payload.tenant_id = :tenantId AND payload.request_id = :requestId
        ON CONFLICT (tenant_id, request_id, revision_number) DO NOTHING
        """;

    static final String APPEND_EVENT_INSERT_APR_REQUEST_EVENTS = """
        INSERT INTO apr_request_events (
            event_id, tenant_id, request_id, event_type,
            actor_type, actor_id, outcome, message,
            correlation_id, event_data)
        VALUES (
            :eventId, :tenantId, :requestId, :eventType,
            'USER', :actorId, 'SUCCESS', :message,
            :correlationId, CAST(:eventData AS jsonb))
        """;

    static final String APPEND_INTEGRATION_INSERT_APR_INTEGRATION_OUTBOX = """
        INSERT INTO apr_integration_outbox (
            outbox_id, event_id, tenant_id, request_id,
            event_type, payload, payload_sha256, status)
        VALUES (
            :outboxId, :eventId, :tenantId, :requestId,
            :eventType, CAST(:payload AS jsonb),
            encode(sha256(convert_to(:payload, 'UTF8')), 'hex'), 'PENDING')
        """;

    static final String REQUIRE_CATEGORY_SELECT_APR_FORM_CATEGORIES = """
        SELECT COUNT(*)::INTEGER
          FROM apr_form_categories
         WHERE tenant_id = :tenantId AND category_id = :categoryId
           AND lifecycle_state = 'ACTIVE'
        """;

    static final String REQUIRE_WORKFLOW_SELECT_APR_WORKFLOW_DEFINITIONS = """
        SELECT COUNT(*)::INTEGER
          FROM apr_workflow_definitions
         WHERE tenant_id = :tenantId AND workflow_id = :workflowId
           AND lifecycle_state <> 'RETIRED'
        """;

    static final String VALIDATE_CATEGORY_PARENT_WITH_APR_FORM_CATEGORIES = """
        WITH RECURSIVE descendants AS (
            SELECT category_id
              FROM apr_form_categories
             WHERE tenant_id = :tenantId AND parent_category_id = :categoryId
            UNION ALL
            SELECT child.category_id
              FROM apr_form_categories child
              JOIN descendants parent ON child.parent_category_id = parent.category_id
             WHERE child.tenant_id = :tenantId
        )
        SELECT COUNT(*)::INTEGER FROM descendants WHERE category_id = :parentCategoryId
        """;

    static final String REPLACE_DEFAULT_FORM_ROUTE_UPDATE_APR_FORM_WORKFLOW_BINDINGS = """
        UPDATE apr_form_workflow_bindings
           SET lifecycle_state = 'INACTIVE', version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = :userId
         WHERE tenant_id = :tenantId AND form_id = :formId
           AND binding_type = 'DEFAULT' AND lifecycle_state = 'ACTIVE'
           AND workflow_id <> :workflowId
        """;

    static final String REPLACE_DEFAULT_FORM_ROUTE_UPDATE_APR_FORM_WORKFLOW_BINDINGS_2 = """
        UPDATE apr_form_workflow_bindings
           SET binding_type = 'DEFAULT', lifecycle_state = 'ACTIVE',
               priority = 100, version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = :userId
         WHERE tenant_id = :tenantId AND form_id = :formId
           AND workflow_id = :workflowId
        """;

    static final String REPLACE_DEFAULT_FORM_ROUTE_INSERT_APR_FORM_WORKFLOW_BINDINGS = """
        INSERT INTO apr_form_workflow_bindings (
            binding_id, tenant_id, form_id, workflow_id,
            binding_type, priority, lifecycle_state, created_by, updated_by)
        VALUES (
            :bindingId, :tenantId, :formId, :workflowId,
            'DEFAULT', 100, 'ACTIVE', :userId, :userId)
        """;
}
