package com.dwp.services.approval.domain;

final class ApprovalCommandInformationSql {

    private ApprovalCommandInformationSql() {
    }

    static final String CURRENT_PAYLOAD_EVIDENCE = """
        SELECT schema_version, payload_sha256
          FROM apr_request_payloads
         WHERE tenant_id = :tenantId AND request_id = :requestId
         FOR UPDATE
        """;

    static final String SUPERSEDE_DECISIONS = """
        UPDATE apr_tasks
           SET status = 'SUPERSEDED',
               decision_invalidated_at = CURRENT_TIMESTAMP,
               decision_invalidation_reason = 'MATERIAL_INFORMATION_RESPONSE',
               completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP),
               version = version + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND status IN ('APPROVED', 'REJECTED', 'INFO_REQUESTED')
        """;

    static final String CANCEL_OPEN_TASKS = """
        UPDATE apr_tasks
           SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
               decision_actor_user_id = NULL,
               decision_actor_person_public_id = NULL,
               decision_reason = NULL,
               decision_payload_revision = NULL,
               decision_payload_sha256 = NULL,
               decision_invalidated_at = NULL,
               decision_invalidation_reason = NULL,
               version = version + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND status IN ('PENDING', 'CLAIMED')
        """;

    static final String RESTART_STEPS = """
        UPDATE apr_steps
           SET status = CASE WHEN sequence_number = 1
                             THEN 'IN_PROGRESS' ELSE 'WAITING' END,
               candidate_role = :candidateRole,
               started_at = CASE WHEN sequence_number = 1
                                 THEN CURRENT_TIMESTAMP ELSE NULL END,
               completed_at = NULL,
               due_at = CURRENT_TIMESTAMP
                        + make_interval(mins => :cumulativeMinutes),
               version = version + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND sequence_number = :sequenceNumber
        """;

    static final String RESTART_FIRST_STEP = """
        SELECT step_id, step_key, step_name, candidate_role, due_at
          FROM apr_steps
         WHERE tenant_id = :tenantId AND request_id = :requestId
           AND sequence_number = 1 AND status = 'IN_PROGRESS'
         FOR UPDATE
        """;
}
