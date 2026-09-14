package com.dwp.services.approval.domain;

final class ApprovalSearchSql {
    private ApprovalSearchSql() { }

    static final String REQUEST_SELECT = """
        SELECT request.request_id,request.request_number,request.title,request.summary,
               workflow.name_ko AS workflow_name_ko,workflow.name_en AS workflow_name_en,
               current_step.step_key AS current_step_key,current_step.step_name AS current_step_name,
               current_step.sequence_number AS current_step_sequence,
               COALESCE(step_count.total_steps,0)::INTEGER AS total_steps,
               request.status,request.priority,request.data_classification,
               info.message AS latest_information_request,request.submitted_at,request.due_at,
               request.completed_at,request.version
          FROM apr_requests request
          JOIN apr_workflow_versions workflow_version
            ON workflow_version.tenant_id = request.tenant_id
           AND workflow_version.workflow_version_id = request.workflow_version_id
          JOIN apr_workflow_definitions workflow
            ON workflow.tenant_id = workflow_version.tenant_id AND workflow.workflow_id = workflow_version.workflow_id
          LEFT JOIN LATERAL (SELECT step_key,step_name,sequence_number FROM apr_steps
              WHERE tenant_id = request.tenant_id AND request_id = request.request_id
                AND status IN ('PENDING','IN_PROGRESS') ORDER BY sequence_number LIMIT 1) current_step ON TRUE
          LEFT JOIN LATERAL (SELECT COUNT(*) AS total_steps FROM apr_steps
              WHERE tenant_id = request.tenant_id AND request_id = request.request_id) step_count ON TRUE
          LEFT JOIN LATERAL (SELECT message FROM apr_request_events
              WHERE tenant_id = request.tenant_id AND request_id = request.request_id
                AND event_type = 'INFORMATION_REQUESTED' ORDER BY occurred_at DESC,event_id LIMIT 1) info ON TRUE
        """;

    static final String VISIBLE_DELEGATION = """
        EXISTS (SELECT 1 FROM apr_delegations delegation
                 WHERE delegation.tenant_id = task.tenant_id AND delegation.delegate_user_id = :userId
                   AND delegation.delegator_user_id IN (:delegators) AND delegation.lifecycle_state = 'ACTIVE'
                   AND CURRENT_TIMESTAMP BETWEEN delegation.starts_at AND delegation.ends_at
                   AND (delegation.scope_type = 'ALL' OR delegation.workflow_id = workflow.workflow_id)
                   AND (task.assignee_user_id = delegation.delegator_user_id
                        OR (task.assignee_user_id IS NULL AND jsonb_exists(delegation.delegated_role_codes,task.candidate_role)
                            AND jsonb_exists(CAST(:delegatedRoles AS jsonb) -> delegation.delegator_user_id::text,task.candidate_role))))
        """;

    static final String CLAIMED_DELEGATION_CURRENT = """
        (task.delegated_from_user_id IS NULL OR (
            task.delegated_from_user_id IN (:delegators)
            AND (task.delegated_authority_role_code IS NULL OR jsonb_exists(
                CAST(:delegatedRoles AS jsonb) -> task.delegated_from_user_id::text,task.delegated_authority_role_code))
            AND EXISTS (SELECT 1 FROM apr_delegations delegation
                         WHERE delegation.tenant_id = task.tenant_id
                           AND delegation.delegator_user_id = task.delegated_from_user_id
                           AND delegation.delegate_user_id = :userId AND delegation.lifecycle_state = 'ACTIVE'
                           AND CURRENT_TIMESTAMP BETWEEN delegation.starts_at AND delegation.ends_at
                           AND (task.delegated_authority_role_code IS NULL OR jsonb_exists(
                               delegation.delegated_role_codes,task.delegated_authority_role_code))
                           AND (delegation.scope_type = 'ALL' OR delegation.workflow_id = workflow.workflow_id))))
        """;

    static final String COMMON = """
        request.deleted_at IS NULL
        AND NOT EXISTS (SELECT 1 FROM apr_record_retention_heads retention WHERE retention.tenant_id=request.tenant_id
            AND retention.request_id=request.request_id AND retention.state<>'LIVE')
        AND EXISTS (SELECT 1 FROM apr_tenants WHERE tenant_id = :tenantId AND lifecycle_state = 'ACTIVE')
        AND (:query = '' OR request.title ILIKE :pattern ESCAPE chr(92)
             OR request.summary ILIKE :pattern ESCAPE chr(92)
             OR request.request_number ILIKE :pattern ESCAPE chr(92))
        AND (:priority = '' OR request.priority = :priority)
        AND (CAST(:workflowId AS uuid) IS NULL OR workflow.workflow_id = CAST(:workflowId AS uuid))
        """;
}
