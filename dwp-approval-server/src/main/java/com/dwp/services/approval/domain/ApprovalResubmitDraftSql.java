package com.dwp.services.approval.domain;

final class ApprovalResubmitDraftSql {
    private ApprovalResubmitDraftSql() {
    }

    static final String OWNED_TERMINAL_SOURCE = """
        SELECT request.version, request.status, request.title, request.summary, request.priority,
               workflow.workflow_id, form.form_id, payload.payload::text
          FROM apr_requests request
          JOIN apr_tenants tenant
            ON tenant.tenant_id = request.tenant_id
           AND tenant.lifecycle_state = 'ACTIVE'
          JOIN apr_workflow_versions workflow_version
            ON workflow_version.tenant_id = request.tenant_id
           AND workflow_version.workflow_version_id = request.workflow_version_id
          JOIN apr_workflow_definitions workflow
            ON workflow.tenant_id = workflow_version.tenant_id
           AND workflow.workflow_id = workflow_version.workflow_id
          JOIN apr_form_versions form_version
            ON form_version.tenant_id = request.tenant_id
           AND form_version.form_version_id = request.form_version_id
          JOIN apr_forms form
            ON form.tenant_id = form_version.tenant_id
           AND form.form_id = form_version.form_id
          JOIN apr_request_payloads payload
            ON payload.tenant_id = request.tenant_id
           AND payload.request_id = request.request_id
         WHERE request.tenant_id = :tenantId
           AND request.request_id = :requestId
           AND request.requester_user_id = :userId
         FOR UPDATE OF request
         FOR SHARE OF tenant, workflow_version, workflow, form_version, form, payload
        """;

    static final String INSERT_DRAFT = """
        INSERT INTO apr_requests (
            request_id, tenant_id, request_number,
            workflow_version_id, form_version_id, title, summary,
            requester_user_id, requester_person_public_id, requester_name,
            status, priority, data_classification, management_resource_set_key,
            source_system, source_reference, created_by, updated_by)
        VALUES (
            :requestId, :tenantId, :requestNumber,
            :workflowVersionId, :formVersionId, :title, :summary,
            :userId, :personPublicId, :requesterName,
            'DRAFT', :priority, :classification, :managementScope,
            'DWP_APPROVAL_RESUBMIT', :sourceRequestId, :userId, :userId)
        """;
}
