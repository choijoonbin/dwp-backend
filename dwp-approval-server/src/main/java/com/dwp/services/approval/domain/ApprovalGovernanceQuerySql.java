package com.dwp.services.approval.domain;

final class ApprovalGovernanceQuerySql {

    private ApprovalGovernanceQuerySql() {
    }

    static final String DECISION_PAYLOAD = """
        SELECT version.payload::text
          FROM apr_tasks task
          JOIN apr_request_payload_versions version
            ON version.tenant_id = task.tenant_id
           AND version.request_id = task.request_id
           AND version.revision_number = task.decision_payload_revision
           AND version.payload_sha256 = task.decision_payload_sha256
         WHERE task.tenant_id = :tenantId
           AND task.task_id = :taskId
           AND task.status IN (
               'APPROVED', 'REJECTED', 'INFO_REQUESTED', 'SUPERSEDED')
        """;
}
