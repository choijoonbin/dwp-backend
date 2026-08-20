package com.dwp.services.approval.domain;

final class ApprovalCommandSql01 {

    private ApprovalCommandSql01() {
    }

    static final String CREATE_DRAFT_INSERT_APR_REQUESTS = """
        INSERT INTO apr_requests (
            request_id, tenant_id, request_number,
            workflow_version_id, form_version_id, title, summary,
            requester_user_id, requester_person_public_id,
            requester_name,
            status, priority, data_classification, created_by, updated_by)
        VALUES (
            :requestId, :tenantId, :requestNumber,
            :workflowVersionId, :formVersionId, :title, :summary,
            :userId, :personPublicId,
            :requesterName,
            'DRAFT', :priority, :classification, :userId, :userId)
        """;

    static final String APR_REQUESTS_INSERT_APR_REQUEST_PAYLOADS = """
        INSERT INTO apr_request_payloads (
            tenant_id, request_id, payload, payload_sha256, schema_version)
        VALUES (
            :tenantId, :requestId, CAST(:payload AS jsonb),
            encode(sha256(convert_to(:payload, 'UTF8')), 'hex'), 1)
        """;

    static final String UPDATE_DRAFT_UPDATE_APR_REQUESTS = """
        UPDATE apr_requests
           SET workflow_version_id = :workflowVersionId,
               form_version_id = :formVersionId,
               title = :title,
               summary = :summary,
               priority = :priority,
               data_classification = :classification,
               version = version + 1,
               updated_at = CURRENT_TIMESTAMP,
               updated_by = :userId
         WHERE tenant_id = :tenantId
           AND request_id = :requestId
           AND requester_user_id = :userId
           AND status = 'DRAFT'
           AND version = :expectedVersion
        """;

    static final String UPDATE_DRAFT_UPDATE_APR_REQUEST_PAYLOADS = """
        UPDATE apr_request_payloads
           SET payload = CAST(:payload AS jsonb),
               payload_sha256 = encode(sha256(convert_to(:payload, 'UTF8')), 'hex'),
               schema_version = schema_version + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
        """;

    static final String SUBMIT_UPDATE_APR_REQUESTS = """
        UPDATE apr_requests
           SET status = 'IN_REVIEW', submitted_at = CURRENT_TIMESTAMP,
               due_at = CURRENT_TIMESTAMP + make_interval(mins => :slaMinutes),
               version = version + 1, updated_at = CURRENT_TIMESTAMP,
               updated_by = :userId
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND requester_user_id = :userId AND status = 'DRAFT'
           AND version = :expectedVersion
        """;

    static final String SUBMIT_INSERT_APR_STEPS = """
        INSERT INTO apr_steps (
            step_id, tenant_id, request_id, step_key, step_name,
            sequence_number, approval_mode, candidate_role,
            status, started_at, due_at)
        VALUES (
            :stepId, :tenantId, :requestId, :stepKey, :stepName,
            :sequenceNumber, :approvalMode, :candidateRole,
            :status,
            CASE WHEN :status = 'IN_PROGRESS' THEN CURRENT_TIMESTAMP ELSE NULL END,
            CURRENT_TIMESTAMP + make_interval(mins => :cumulativeMinutes))
        """;

    static final String APR_STEPS_INSERT_APR_TASKS = """
        INSERT INTO apr_tasks (
            task_id, tenant_id, request_id, step_id, candidate_role,
            status, risk_score, due_at)
        VALUES (
            :taskId, :tenantId, :requestId, :stepId, :candidateRole,
            'PENDING', CASE WHEN :stepSlaMinutes <= 240 THEN 75 ELSE 45 END,
            CURRENT_TIMESTAMP + make_interval(mins => :stepSlaMinutes))
        """;

    static final String WITHDRAW_UPDATE_APR_REQUESTS = """
        UPDATE apr_requests
           SET status = 'WITHDRAWN', completed_at = CURRENT_TIMESTAMP,
               version = version + 1, updated_at = CURRENT_TIMESTAMP,
               updated_by = :userId
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND requester_user_id = :userId
           AND status IN ('SUBMITTED', 'IN_REVIEW', 'NEEDS_INFO')
           AND version = :expectedVersion
        """;

    static final String IN_UPDATE_APR_TASKS = """
        UPDATE apr_tasks
           SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
               version = version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND status IN ('PENDING', 'CLAIMED', 'INFO_REQUESTED')
        """;

    static final String IN_UPDATE_APR_STEPS = """
        UPDATE apr_steps
           SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
               version = version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND status IN ('WAITING', 'PENDING', 'IN_PROGRESS')
        """;

    static final String RESPOND_TO_INFORMATION_REQUEST_UPDATE_APR_REQUESTS = """
        UPDATE apr_requests
           SET status = 'IN_REVIEW', version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = :userId
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND requester_user_id = :userId
           AND status = 'NEEDS_INFO' AND version = :expectedVersion
        """;

    static final String RESPOND_TO_INFORMATION_REQUEST_UPDATE_APR_REQUEST_PAYLOADS = """
        UPDATE apr_request_payloads
           SET payload = CAST(:payload AS jsonb),
               payload_sha256 = encode(sha256(convert_to(:payload, 'UTF8')), 'hex'),
               schema_version = schema_version + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
        """;

    static final String RESPOND_TO_INFORMATION_REQUEST_UPDATE_APR_TASKS = """
        UPDATE apr_tasks
           SET status = CASE WHEN assignee_user_id IS NULL THEN 'PENDING' ELSE 'CLAIMED' END,
               decision_reason = NULL, version = version + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND status = 'INFO_REQUESTED'
        """;

    static final String CLAIM_UPDATE_APR_TASKS = """
        UPDATE apr_tasks
           SET assignee_user_id = :userId,
               assignee_person_public_id = :personPublicId,
               delegated_from_user_id = :delegatedFromUserId,
               status = 'CLAIMED', claimed_at = CURRENT_TIMESTAMP,
               version = version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND task_id = :taskId
           AND assignee_user_id IS NULL AND status = 'PENDING'
           AND version = :expectedVersion
        """;

    static final String DECIDE_UPDATE_APR_TASKS = """
        UPDATE apr_tasks
           SET assignee_user_id = COALESCE(assignee_user_id, :userId),
               decision_actor_user_id = :userId,
               decision_actor_person_public_id = :personPublicId,
               delegated_from_user_id = COALESCE(
                   delegated_from_user_id, :delegatedFromUserId),
               status = :taskStatus, decision_reason = :reason,
               completed_at = CASE WHEN :taskStatus = 'INFO_REQUESTED'
                                   THEN NULL ELSE CURRENT_TIMESTAMP END,
               version = version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND task_id = :taskId
           AND status IN ('PENDING', 'CLAIMED')
           AND version = :expectedVersion
        """;

    static final String IN_UPDATE_APR_STEPS_2 = """
        UPDATE apr_steps
           SET status = CASE WHEN :taskStatus = 'APPROVED' THEN 'APPROVED'
                             WHEN :taskStatus = 'REJECTED' THEN 'REJECTED'
                             ELSE 'IN_PROGRESS' END,
               completed_at = CASE WHEN :taskStatus IN ('APPROVED', 'REJECTED')
                                   THEN CURRENT_TIMESTAMP ELSE NULL END,
               version = version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId
           AND step_id = (SELECT step_id FROM apr_tasks
                           WHERE tenant_id = :tenantId AND task_id = :taskId)
        """;

    static final String IN_UPDATE_APR_STEPS_3 = """
        UPDATE apr_steps
           SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
               version = version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND status = 'WAITING'
        """;

    static final String IN_UPDATE_APR_REQUESTS = """
        UPDATE apr_requests
           SET status = :requestStatus,
               completed_at = CASE WHEN :requestStatus IN ('APPROVED', 'REJECTED')
                                   THEN CURRENT_TIMESTAMP ELSE NULL END,
               version = version + 1, updated_at = CURRENT_TIMESTAMP,
               updated_by = :userId
         WHERE tenant_id = :tenantId AND request_id = :requestId
        """;

    static final String NEXT_WAITING_STEP_SELECT_APR_STEPS = """
        SELECT step_id, step_key, step_name, candidate_role, due_at
          FROM apr_steps
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND status = 'WAITING'
         ORDER BY sequence_number
         LIMIT 1
         FOR UPDATE
        """;

    static final String ACTIVATE_NEXT_STEP_UPDATE_APR_STEPS = """
        UPDATE apr_steps
           SET status = 'IN_PROGRESS', started_at = CURRENT_TIMESTAMP,
               version = version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND step_id = :stepId AND status = 'WAITING'
        """;

    static final String ACTIVATE_NEXT_STEP_INSERT_APR_TASKS = """
        INSERT INTO apr_tasks (
            task_id, tenant_id, request_id, step_id, candidate_role,
            status, risk_score, due_at)
        VALUES (
            :taskId, :tenantId, :requestId, :stepId, :candidateRole,
            'PENDING', CASE WHEN :dueAt <= CURRENT_TIMESTAMP + INTERVAL '4 hours'
                            THEN 75 ELSE 45 END, :dueAt)
        """;

    static final String CREATE_DELEGATION_SELECT_APR_WORKFLOW_DEFINITIONS = """
        SELECT COUNT(*)::INTEGER
          FROM apr_workflow_definitions
         WHERE tenant_id = :tenantId AND workflow_key = :workflowKey
           AND lifecycle_state = 'PUBLISHED'
        """;

    static final String COUNT_SELECT_APR_DELEGATIONS = """
        SELECT COUNT(*)::INTEGER
          FROM apr_delegations
         WHERE tenant_id = :tenantId AND delegator_user_id = :userId
           AND delegate_user_id = :delegateUserId
           AND lifecycle_state = 'ACTIVE'
           AND scope_type = :scopeType
           AND COALESCE(workflow_key, '') = COALESCE(:workflowKey, '')
           AND starts_at < :endsAt AND ends_at > :startsAt
        """;

    static final String COALESCE_INSERT_APR_DELEGATIONS = """
        INSERT INTO apr_delegations (
            delegation_id, tenant_id, delegator_user_id, delegate_user_id,
            delegate_person_public_id, delegate_display_name, delegate_email,
            delegated_role_codes,
            scope_type, workflow_key, starts_at, ends_at,
            lifecycle_state, reason, created_by, updated_by)
        VALUES (
            :id, :tenantId, :userId, :delegateUserId,
            :delegatePersonPublicId, :delegateDisplayName, :delegateEmail,
            CAST(:delegatedRoles AS jsonb),
            :scopeType, :workflowKey, :startsAt, :endsAt,
            'ACTIVE', :reason, :userId, :userId)
        """;

    static final String REVOKE_DELEGATION_UPDATE_APR_DELEGATIONS = """
        UPDATE apr_delegations
           SET lifecycle_state = 'REVOKED', version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = :userId
         WHERE tenant_id = :tenantId AND delegation_id = :delegationId
           AND delegator_user_id = :userId AND lifecycle_state = 'ACTIVE'
           AND version = :expectedVersion
        """;

    static final String CREATE_FORM_CATEGORY_INSERT_APR_FORM_CATEGORIES = """
        INSERT INTO apr_form_categories (
            category_id, tenant_id, category_key, parent_category_id,
            name_ko, name_en, description_ko, description_en,
            icon_key, sort_order, lifecycle_state, created_by, updated_by)
        VALUES (
            :categoryId, :tenantId, :categoryKey, :parentCategoryId,
            :nameKo, :nameEn, :descriptionKo, :descriptionEn,
            :iconKey, :sortOrder, 'ACTIVE', :userId, :userId)
        """;

    static final String UPDATE_FORM_CATEGORY_UPDATE_APR_FORM_CATEGORIES = """
        UPDATE apr_form_categories
           SET parent_category_id = :parentCategoryId,
               name_ko = :nameKo, name_en = :nameEn,
               description_ko = :descriptionKo, description_en = :descriptionEn,
               icon_key = :iconKey, sort_order = :sortOrder,
               lifecycle_state = :lifecycleState,
               version = version + 1, updated_at = CURRENT_TIMESTAMP,
               updated_by = :userId
         WHERE tenant_id = :tenantId AND category_id = :categoryId
           AND version = :expectedVersion
        """;

    static final String CREATE_FORM_DRAFT_INSERT_APR_FORMS = """
        INSERT INTO apr_forms (
            form_id, tenant_id, form_key, category_id,
            name_ko, name_en, description_ko, description_en,
            owner_group_ref, form_kind, lifecycle_state,
            current_version, created_by, updated_by)
        VALUES (
            :formId, :tenantId, :formKey, :categoryId,
            :nameKo, :nameEn, :descriptionKo, :descriptionEn,
            :ownerGroupRef, 'REQUEST', 'DRAFT', 1, :userId, :userId)
        """;

    static final String APR_FORMS_INSERT_APR_FORM_VERSIONS = """
        INSERT INTO apr_form_versions (
            form_version_id, tenant_id, form_id, version_number,
            schema_payload, schema_sha256, lifecycle_state, created_by)
        VALUES (
            :formVersionId, :tenantId, :formId, 1,
            CAST(:schema AS jsonb),
            encode(sha256(convert_to(:schema, 'UTF8')), 'hex'),
            'DRAFT', :userId)
        """;

    static final String APR_FORM_VERSIONS_INSERT_APR_FORM_WORKFLOW_BINDINGS = """
        INSERT INTO apr_form_workflow_bindings (
            binding_id, tenant_id, form_id, workflow_id,
            binding_type, priority, lifecycle_state, created_by, updated_by)
        VALUES (
            :bindingId, :tenantId, :formId, :workflowId,
            'DEFAULT', 100, 'ACTIVE', :userId, :userId)
        """;

    static final String CREATE_WORKFLOW_DRAFT_SELECT_APR_WORKFLOW_DEFINITIONS = """
        SELECT COUNT(*)::INTEGER
          FROM apr_workflow_definitions
         WHERE tenant_id = :tenantId AND workflow_key = :workflowKey
        """;

    static final String COUNT_INSERT_APR_WORKFLOW_DEFINITIONS = """
        INSERT INTO apr_workflow_definitions (
            workflow_id, tenant_id, workflow_key, name_ko, name_en,
            description_ko, description_en, category, data_classification,
            lifecycle_state, current_version, sla_minutes, allow_self_approval,
            owner_group_ref, created_by, updated_by)
        VALUES (
            :workflowId, :tenantId, :workflowKey, :nameKo, :nameEn,
            :descriptionKo, :descriptionEn, :category, :classification,
            'DRAFT', 1, :slaMinutes, FALSE,
            :ownerGroupRef, :userId, :userId)
        """;

    static final String APR_WORKFLOW_DEFINITIONS_INSERT_APR_WORKFLOW_VERSIONS = """
        INSERT INTO apr_workflow_versions (
            workflow_version_id, tenant_id, workflow_id, version_number,
            definition, definition_sha256, lifecycle_state, created_by)
        VALUES (
            :workflowVersionId, :tenantId, :workflowId, 1,
            CAST(:definition AS jsonb),
            encode(sha256(convert_to(:definition, 'UTF8')), 'hex'),
            'DRAFT', :userId)
        """;

    static final String APR_WORKFLOW_VERSIONS_INSERT_APR_FORMS = """
        INSERT INTO apr_forms (
            form_id, tenant_id, form_key, category_id, name_ko, name_en,
            description_ko, description_en, owner_group_ref,
            lifecycle_state, current_version, created_by, updated_by)
        VALUES (
            :formId, :tenantId, :formKey,
            (SELECT category_id FROM apr_form_categories
              WHERE tenant_id = :tenantId AND category_key = :category),
            :formNameKo, :formNameEn,
            :descriptionKo, :descriptionEn, :ownerGroupRef,
            'DRAFT', 1, :userId, :userId)
        """;

    static final String APR_FORMS_INSERT_APR_FORM_VERSIONS_2 = """
        INSERT INTO apr_form_versions (
            form_version_id, tenant_id, form_id, version_number,
            schema_payload, schema_sha256, lifecycle_state, created_by)
        VALUES (
            :formVersionId, :tenantId, :formId, 1,
            CAST(:schema AS jsonb),
            encode(sha256(convert_to(:schema, 'UTF8')), 'hex'),
            'DRAFT', :userId)
        """;

    static final String APR_FORM_VERSIONS_INSERT_APR_FORM_WORKFLOW_BINDINGS_2 = """
        INSERT INTO apr_form_workflow_bindings (
            binding_id, tenant_id, form_id, workflow_id, binding_type,
            lifecycle_state, priority, created_by, updated_by)
        VALUES (
            :bindingId, :tenantId, :formId, :workflowId, 'DEFAULT',
            'ACTIVE', 100, :userId, :userId)
        """;

    static final String UPDATE_WORKFLOW_DRAFT_UPDATE_APR_WORKFLOW_DEFINITIONS = """
        UPDATE apr_workflow_definitions
           SET name_ko = :nameKo, name_en = :nameEn,
               description_ko = :descriptionKo, description_en = :descriptionEn,
               category = :category, data_classification = :classification,
               sla_minutes = :slaMinutes, owner_group_ref = :ownerGroupRef,
               version = version + 1, updated_at = CURRENT_TIMESTAMP,
               updated_by = :userId
         WHERE tenant_id = :tenantId AND workflow_id = :workflowId
           AND lifecycle_state = 'DRAFT' AND version = :expectedVersion
        """;

    static final String UPDATE_WORKFLOW_DRAFT_UPDATE_APR_WORKFLOW_VERSIONS = """
        UPDATE apr_workflow_versions
           SET definition = CAST(:definition AS jsonb),
               definition_sha256 = encode(
                   sha256(convert_to(:definition, 'UTF8')), 'hex')
         WHERE tenant_id = :tenantId AND workflow_id = :workflowId
           AND lifecycle_state = 'DRAFT'
           AND version_number = (
               SELECT current_version FROM apr_workflow_definitions
                WHERE tenant_id = :tenantId AND workflow_id = :workflowId)
        """;

    static final String UPDATE_FORM_DRAFT_UPDATE_APR_FORMS = """
        UPDATE apr_forms
           SET category_id = :categoryId,
               name_ko = :nameKo, name_en = :nameEn,
               description_ko = :descriptionKo, description_en = :descriptionEn,
               owner_group_ref = :ownerGroupRef,
               version = version + 1, updated_at = CURRENT_TIMESTAMP,
               updated_by = :userId
         WHERE tenant_id = :tenantId AND form_id = :formId
           AND lifecycle_state = 'DRAFT' AND version = :expectedVersion
        """;

    static final String UPDATE_FORM_DRAFT_UPDATE_APR_FORM_VERSIONS = """
        UPDATE apr_form_versions
           SET schema_payload = CAST(:schema AS jsonb),
               schema_sha256 = encode(sha256(convert_to(:schema, 'UTF8')), 'hex')
         WHERE tenant_id = :tenantId AND form_id = :formId
           AND lifecycle_state = 'DRAFT'
           AND version_number = (
               SELECT current_version FROM apr_forms
                WHERE tenant_id = :tenantId AND form_id = :formId)
        """;

    static final String PUBLISH_FORM_UPDATE_APR_FORMS = """
        UPDATE apr_forms form
           SET lifecycle_state = 'PUBLISHED', version = version + 1,
               updated_at = CURRENT_TIMESTAMP, updated_by = :userId
         WHERE form.tenant_id = :tenantId AND form.form_id = :formId
           AND form.lifecycle_state = 'DRAFT'
           AND form.version = :expectedVersion
           AND COALESCE(form.updated_by, form.created_by, -1) <> :userId
           AND EXISTS (
               SELECT 1
                 FROM apr_form_workflow_bindings binding
                 JOIN apr_workflow_definitions workflow
                   ON workflow.tenant_id = binding.tenant_id
                  AND workflow.workflow_id = binding.workflow_id
                WHERE binding.tenant_id = form.tenant_id
                  AND binding.form_id = form.form_id
                  AND binding.binding_type = 'DEFAULT'
                  AND binding.lifecycle_state = 'ACTIVE'
                  AND workflow.lifecycle_state = 'PUBLISHED')
        """;

    static final String EXISTS_UPDATE_APR_FORM_VERSIONS = """
        UPDATE apr_form_versions
           SET lifecycle_state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
               published_by = :userId
         WHERE tenant_id = :tenantId AND form_id = :formId
           AND lifecycle_state = 'DRAFT'
           AND version_number = (
               SELECT current_version FROM apr_forms
                WHERE tenant_id = :tenantId AND form_id = :formId)
        """;

    static final String UPDATE_POLICY_SELECT_APR_POLICY_RULES = """
        SELECT policy_key
          FROM apr_policy_rules
         WHERE tenant_id = :tenantId AND policy_id = :policyId
        """;

    static final String UPDATE_POLICY_UPDATE_APR_POLICY_RULES = """
        UPDATE apr_policy_rules
           SET pending_enforcement_mode = :mode,
               pending_severity = :severity,
               pending_lifecycle_state = :state,
               pending_rule_payload = CAST(:rule AS jsonb),
               pending_change_reason = :changeReason,
               pending_by = :userId,
               pending_at = CURRENT_TIMESTAMP,
               version = version + 1, updated_at = CURRENT_TIMESTAMP,
               updated_by = :userId
         WHERE tenant_id = :tenantId AND policy_id = :policyId
           AND version = :expectedVersion
        """;

    static final String PUBLISH_POLICY_WITH_APR_POLICY_RULES = """
        WITH candidate AS (
            SELECT tenant_id, policy_id,
                   pending_enforcement_mode, pending_severity,
                   pending_lifecycle_state, pending_rule_payload,
                   pending_change_reason, pending_by, pending_at
              FROM apr_policy_rules
             WHERE tenant_id = :tenantId AND policy_id = :policyId
               AND version = :expectedVersion
               AND pending_by IS NOT NULL
               AND pending_by <> :userId
             FOR UPDATE
        ), published AS (
            UPDATE apr_policy_rules policy
               SET enforcement_mode = candidate.pending_enforcement_mode,
                   severity = candidate.pending_severity,
                   lifecycle_state = candidate.pending_lifecycle_state,
                   rule_payload = candidate.pending_rule_payload,
                   pending_enforcement_mode = NULL,
                   pending_severity = NULL,
                   pending_lifecycle_state = NULL,
                   pending_rule_payload = NULL,
                   pending_change_reason = NULL,
                   pending_by = NULL,
                   pending_at = NULL,
                   version = policy.version + 1,
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = :userId
              FROM candidate
             WHERE policy.tenant_id = candidate.tenant_id
               AND policy.policy_id = candidate.policy_id
            RETURNING policy.tenant_id, policy.policy_id,
                      policy.enforcement_mode, policy.severity,
                      policy.lifecycle_state, policy.rule_payload
        )
        INSERT INTO apr_policy_rule_versions (
            policy_version_id, tenant_id, policy_id, version_number,
            enforcement_mode, severity, lifecycle_state, rule_payload,
            change_reason, submitted_by, submitted_at,
            published_by, published_at, review_comment)
        SELECT :policyVersionId, published.tenant_id, published.policy_id,
               COALESCE((
                   SELECT MAX(version_number) + 1
                     FROM apr_policy_rule_versions history
                    WHERE history.tenant_id = published.tenant_id
                      AND history.policy_id = published.policy_id
               ), 1),
               published.enforcement_mode, published.severity,
               published.lifecycle_state, published.rule_payload,
               candidate.pending_change_reason, candidate.pending_by,
               candidate.pending_at, :userId, CURRENT_TIMESTAMP,
               :reviewComment
          FROM published
          JOIN candidate
            ON candidate.tenant_id = published.tenant_id
           AND candidate.policy_id = published.policy_id
        """;

    static final String RETRY_INTEGRATION_DELIVERY_UPDATE_APR_INTEGRATION_OUTBOX = """
        UPDATE apr_integration_outbox
           SET status = 'PENDING', attempt_count = 0,
               available_at = CURRENT_TIMESTAMP,
               locked_by = NULL, locked_until = NULL,
               manual_retry_count = manual_retry_count + 1,
               last_retried_at = CURRENT_TIMESTAMP,
               last_retried_by = :userId,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND outbox_id = :outboxId
           AND status IN ('FAILED', 'DEAD')
        """;

    static final String POLICY_SELECT_APR_POLICY_RULES = """
        SELECT enforcement_mode, lifecycle_state, rule_payload::text
          FROM apr_policy_rules
         WHERE tenant_id = :tenantId AND policy_key = :policyKey
        """;
}
