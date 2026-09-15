package com.dwp.services.approval.operations;

final class ApprovalOperationsSql {
    private ApprovalOperationsSql() {
    }

    static final String DELIVERY_SELECT = """
            SELECT delivery.outbox_id, delivery.event_id, delivery.request_id,
                   delivery.status, delivery.version, delivery.event_originator_user_id,
                   delivery.assigned_auditor_user_id,
                   delivery.management_resource_set_key,
                   delivery.recovery_auditor_assignment_state,
                   delivery.recovery_auditor_resource_set_key,
                   delivery.recovery_auditor_assignment_revision,
                   delivery.recovery_auditor_assigned_at,
                   delivery.locked_until, request.status AS request_status
              FROM apr_integration_outbox delivery
              JOIN apr_requests request
                ON request.tenant_id = delivery.tenant_id
               AND request.request_id = delivery.request_id
               AND request.management_resource_set_key = delivery.management_resource_set_key
             WHERE delivery.tenant_id = :tenantId
               AND delivery.management_resource_set_key = :managementScope
               AND delivery.outbox_id IN (:targetIds)
             ORDER BY delivery.outbox_id
            """;

    static final String TASK_SELECT = """
            SELECT task.task_id, task.request_id, task.step_id, task.status,
                   task.version, task.assignee_user_id, task.assignee_person_public_id,
                   task.candidate_role, task.delegated_from_user_id,
                   task.delegated_authority_role_code,
                   request.requester_user_id, request.status AS request_status,
                   request.management_resource_set_key,
                   step.status AS step_status, step.candidate_role AS step_candidate_role
              FROM apr_tasks task
              JOIN apr_requests request
                ON request.tenant_id = task.tenant_id
               AND request.request_id = task.request_id
              JOIN apr_steps step
                ON step.tenant_id = task.tenant_id
               AND step.request_id = task.request_id
               AND step.step_id = task.step_id
             WHERE task.tenant_id = :tenantId
               AND request.management_resource_set_key = :managementScope
               AND task.task_id IN (:targetIds)
             ORDER BY task.task_id
            """;

    static final String DELIVERY_UPDATE = """
            UPDATE apr_integration_outbox
               SET status = :statusAfter,
                   attempt_count = CASE WHEN :resetAttempts THEN 0 ELSE attempt_count END,
                   available_at = CASE WHEN :availableNow
                       THEN CURRENT_TIMESTAMP ELSE available_at END,
                   locked_by = NULL,
                   locked_until = NULL,
                   last_error = CASE WHEN :expiredLease
                       THEN COALESCE(last_error, 'Expired delivery lease reconciled by operator')
                       ELSE last_error END,
                   manual_retry_count = manual_retry_count
                       + CASE WHEN :manualRecovery THEN 1 ELSE 0 END,
                   last_retried_at = CASE WHEN :manualRecovery
                       THEN CURRENT_TIMESTAMP ELSE last_retried_at END,
                   last_retried_by = CASE WHEN :manualRecovery
                       THEN :actorUserId ELSE last_retried_by END,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE tenant_id = :tenantId
               AND management_resource_set_key = :managementScope
               AND outbox_id = :targetId
               AND version = :expectedVersion
               AND status = :statusBefore
            """;

    static final String TASK_UPDATE = """
            UPDATE apr_tasks task
               SET assignee_user_id = :assigneeUserId,
                   assignee_person_public_id = :assigneePersonPublicId,
                   candidate_role = :candidateRole,
                   status = 'PENDING',
                   claimed_at = NULL,
                   completed_at = NULL,
                   decision_actor_user_id = NULL,
                   decision_actor_person_public_id = NULL,
                   delegated_from_user_id = NULL,
                   delegated_authority_role_code = NULL,
                   version = version + 1,
                   updated_at = CURRENT_TIMESTAMP
             WHERE task.tenant_id = :tenantId
               AND task.task_id = :targetId
               AND task.request_id = :requestId
               AND task.step_id = :stepId
               AND task.version = :expectedVersion
               AND task.status = :statusBefore
               AND EXISTS (
                    SELECT 1
                      FROM apr_requests request
                      JOIN apr_steps step
                        ON step.tenant_id = request.tenant_id
                       AND step.request_id = request.request_id
                     WHERE request.tenant_id = task.tenant_id
                       AND request.request_id = task.request_id
                       AND request.management_resource_set_key = :managementScope
                       AND request.status = :requestStatus
                       AND request.status IN ('SUBMITTED', 'IN_REVIEW')
                       AND step.step_id = task.step_id
                       AND step.status = :stepStatus
                       AND step.status IN ('PENDING', 'IN_PROGRESS')
                       AND step.candidate_role = :candidateRole
               )
            """;

    static final String OPERATION_INSERT = """
            INSERT INTO apr_operation_batches (
                operation_id, tenant_id, management_resource_set_key,
                actor_user_id, actor_person_public_id, route_contract_key,
                operation_type, command_mode, idempotency_key,
                request_fingerprint, decision_revision, reason, item_count,
                result_receipt, audit_event_id, audit_occurred_at,
                broker_observed_at, committed_at)
            VALUES (
                :operationId, :tenantId, :managementScope,
                :actorUserId, :actorPersonPublicId, :routeContractKey,
                :operationType, :commandMode, :idempotencyKey,
                :requestFingerprint, :decisionRevision, :reason, :itemCount,
                CAST(:resultReceipt AS jsonb), :auditEventId, :committedAt,
                :brokerObservedAt, :committedAt)
            """;

    static final String ITEM_INSERT = """
            INSERT INTO apr_operation_items (
                operation_id, item_sequence, tenant_id, management_resource_set_key,
                actor_user_id, target_type, target_id, request_id,
                expected_version, committed_version, status_before, status_after,
                authority_subject_user_id, authority_subject_person_public_id,
                authority_role_code, broker_revision, broker_observed_at,
                target_fingerprint, committed_at)
            VALUES (
                :operationId, :sequence, :tenantId, :managementScope,
                :actorUserId, :targetType, :targetId, :requestId,
                :expectedVersion, :committedVersion, :statusBefore, :statusAfter,
                :authorityUserId, :authorityPersonPublicId,
                :authorityRole, :brokerRevision, :brokerObservedAt,
                :targetFingerprint, :committedAt)
            """;

    static final String TASK_EVENT_INSERT = """
            INSERT INTO apr_request_events (
                event_id, tenant_id, request_id, event_type, actor_type,
                actor_id, outcome, message, correlation_id, event_data, occurred_at)
            VALUES (
                :eventId, :tenantId, :requestId, 'TASK_REASSIGNED_BY_OPERATOR', 'USER',
                :actorId, 'SUCCESS', :message, :idempotencyKey,
                CAST(:eventData AS jsonb), :committedAt)
            """;
}
