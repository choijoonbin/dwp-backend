package com.dwp.services.people.hris.employeeservice.employeechange;

/** Named SQL templates for the tenant-scoped EmployeeChangeRepository persistence operations. */
final class EmployeeChangeSql {

    static final String TARGET_FOR_MUTATION_1 = """
                    SELECT person.person_id,worker.worker_id,
                           person.public_id AS person_public_id,
                           person.public_id AS aggregate_public_id,
                           person.version AS aggregate_version,
                           person.display_name,
                           CURRENT_DATE AS effective_from,NULL::DATE AS effective_to
                      FROM ppl_persons person
                      JOIN LATERAL (
                           SELECT candidate.worker_id
                             FROM ppl_workers candidate
                            WHERE candidate.tenant_id=person.tenant_id
                              AND candidate.person_id=person.person_id
                            ORDER BY (candidate.worker_status IN ('ACTIVE','LEAVE')) DESC,
                                     candidate.worker_id
                            LIMIT 1
                      ) worker ON TRUE
                     WHERE person.tenant_id=:tenant AND person.public_id=:person
                       AND person.public_id=:aggregate
                     FOR UPDATE OF person
                    """;

    static final String TARGET_FOR_MUTATION_2 = """
                    SELECT person.person_id,worker.worker_id,
                           person.public_id AS person_public_id,
                           worker.public_id AS aggregate_public_id,
                           worker.version AS aggregate_version,
                           person.display_name,
                           COALESCE(worker.original_hire_date,CURRENT_DATE) AS effective_from,
                           NULL::DATE AS effective_to
                      FROM ppl_workers worker
                      JOIN ppl_persons person
                        ON person.tenant_id=worker.tenant_id
                       AND person.person_id=worker.person_id
                     WHERE worker.tenant_id=:tenant AND person.public_id=:person
                       AND worker.public_id=:aggregate
                     FOR UPDATE OF worker
                    """;

    static final String TARGET_FOR_MUTATION_3 = """
                    SELECT person.person_id,worker.worker_id,
                           person.public_id AS person_public_id,
                           relationship.public_id AS aggregate_public_id,
                           relationship.version AS aggregate_version,
                           person.display_name,relationship.start_date AS effective_from,
                           relationship.end_date AS effective_to
                      FROM ppl_work_relationships relationship
                      JOIN ppl_workers worker
                        ON worker.tenant_id=relationship.tenant_id
                       AND worker.worker_id=relationship.worker_id
                      JOIN ppl_persons person
                        ON person.tenant_id=worker.tenant_id
                       AND person.person_id=worker.person_id
                     WHERE relationship.tenant_id=:tenant AND person.public_id=:person
                       AND relationship.public_id=:aggregate
                     FOR UPDATE OF relationship
                    """;

    static final String TARGET_FOR_MUTATION_4 = """
                    SELECT person.person_id,worker.worker_id,
                           person.public_id AS person_public_id,
                           assignment.public_id AS aggregate_public_id,
                           assignment.version AS aggregate_version,
                           person.display_name,assignment.effective_start_date AS effective_from,
                           assignment.effective_end_date AS effective_to
                      FROM ppl_assignments assignment
                      JOIN ppl_work_relationships relationship
                        ON relationship.tenant_id=assignment.tenant_id
                       AND relationship.work_relationship_id=assignment.work_relationship_id
                      JOIN ppl_workers worker
                        ON worker.tenant_id=relationship.tenant_id
                       AND worker.worker_id=relationship.worker_id
                      JOIN ppl_persons person
                        ON person.tenant_id=worker.tenant_id
                       AND person.person_id=worker.person_id
                     WHERE assignment.tenant_id=:tenant AND person.public_id=:person
                       AND assignment.public_id=:aggregate
                     FOR UPDATE OF assignment
                    """;

    static final String REQUESTER_PERSON_ID_5 = """
                SELECT person_id FROM ppl_persons
                 WHERE tenant_id=:tenant AND public_id=:person
                 FOR SHARE
                """;

    static final String CREATE_6 = """
                INSERT INTO ppl_employee_change_requests(
                    public_id,tenant_id,requester_person_id,target_person_id,
                    target_aggregate_type,target_aggregate_public_id,change_type,
                    effective_date,lifecycle_state,expected_target_version,
                    patch_schema_key,patch_schema_version,encrypted_patch,patch_hash,
                    key_reference,field_groups,population_scope_digest,
                    field_policy_revision,authorization_revision,
                    originating_context_digest,correlation_id,version,created_by,updated_by)
                VALUES(:publicId,:tenant,:requester,:targetPerson,:targetType,:targetAggregate,
                    :changeType,:effectiveDate,'DRAFT',:targetVersion,:schemaKey,1,
                    :encryptedPatch,:patchHash,:keyReference,CAST(:fieldGroups AS jsonb),
                    :populationDigest,:fieldRevision,:authorizationRevision,
                    :contextDigest,:correlation,1,:actor,:actor)
                RETURNING *
                """;

    static final String CREATE_7 = """
                    INSERT INTO ppl_employee_change_evidence(
                        tenant_id,employee_change_request_id,object_public_id,object_version,
                        content_sha256,classification,purpose_code,created_by)
                    VALUES(:tenant,:requestId,:objectId,:objectVersion,:digest,
                        'RESTRICTED',:purpose,:actor)
                    """;

    static final String REQUEST_FOR_MUTATION_8 = """
                SELECT request.*,
                       requester.public_id AS requester_public_id,
                       target.public_id AS target_person_public_id,
                       worker.worker_id AS target_worker_id,
                       target.display_name AS target_display_name
                  FROM ppl_employee_change_requests request
                  JOIN ppl_persons requester
                    ON requester.tenant_id=request.tenant_id
                   AND requester.person_id=request.requester_person_id
                  JOIN ppl_persons target
                    ON target.tenant_id=request.tenant_id
                   AND target.person_id=request.target_person_id
                  JOIN LATERAL (
                       SELECT candidate.worker_id
                         FROM ppl_workers candidate
                        WHERE candidate.tenant_id=target.tenant_id
                          AND candidate.person_id=target.person_id
                        ORDER BY (candidate.worker_status IN ('ACTIVE','LEAVE')) DESC,
                                 candidate.worker_id
                        LIMIT 1
                  ) worker ON TRUE
                 WHERE request.tenant_id=:tenant AND request.public_id=:publicId
                 FOR UPDATE OF request
                """;

    static final String SUBMIT_9 = """
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='SUBMITTED',approval_case_public_id=:approval,
                       approval_workflow_key=:workflowKey,
                       approval_workflow_version=:workflowVersion,
                       approval_subject_revision=version+1,submitted_at=:submitted,
                       version=version+1,updated_by=:actor,updated_at=:submitted
                 WHERE tenant_id=:tenant AND employee_change_request_id=:requestId
                   AND lifecycle_state='DRAFT' AND version=:version
                RETURNING *
                """;

    static final String BEGIN_APPLY_10 = """
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='APPLYING',version=version+1,
                       updated_by=:actor,updated_at=:now
                 WHERE tenant_id=:tenant AND employee_change_request_id=:requestId
                   AND lifecycle_state='APPROVED' AND version=:version
                RETURNING *
                """;

    static final String MARK_APPLIED_11 = """
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state='APPLIED',applied_target_version=:targetVersion,
                       applied_at=:applied,version=version+1,
                       updated_by=:actor,updated_at=:applied
                 WHERE tenant_id=:tenant AND employee_change_request_id=:requestId
                   AND lifecycle_state='APPLYING' AND version=:version
                RETURNING *
                """;

    static final String INSERT_RECEIPT_12 = """
                INSERT INTO ppl_command_receipts(
                    command_receipt_id,tenant_id,command_type,idempotency_key,
                    request_hash,actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,correlation_id,created_at,updated_at)
                VALUES(:receipt,:tenant,:commandType,:idempotency,:digest,:actor,:action,
                    :subject,:population,:fieldRevision,:purpose,:authorizationRevision,
                    :contextDigest,'EMPLOYEE_CHANGE_REQUEST',:target,:expectedVersion,
                    'RUNNING',:correlation,:now,:now)
                ON CONFLICT DO NOTHING
                RETURNING *
                """;

    static final String RECEIPT_13 = """
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant
                   AND ((subject_principal_public_id=:subject
                         AND originating_action=:action AND idempotency_key=:idempotency)
                        OR (command_type=:commandType AND correlation_id=:correlation))
                 ORDER BY CASE WHEN subject_principal_public_id=:subject
                                    AND originating_action=:action
                                    AND idempotency_key=:idempotency THEN 0 ELSE 1 END
                 LIMIT 2
                 FOR UPDATE
                """;

    static final String RECEIPT_FOR_RECONCILIATION_14 = """
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant
                   AND ((actor_public_id=:actor AND originating_action=:action
                         AND idempotency_key=:idempotency
                         AND target_public_id=:target)
                        OR (command_type=:commandType AND correlation_id=:correlation))
                 ORDER BY CASE WHEN actor_public_id=:actor
                                    AND originating_action=:action
                                    AND idempotency_key=:idempotency
                                    AND target_public_id=:target THEN 0 ELSE 1 END
                 LIMIT 2
                 FOR UPDATE
                """;

    static final String COMPLETE_RECEIPT_15 = """
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',result_code=:resultCode,
                       result_reference=CAST(:resultReference AS jsonb),
                       completed_at=:now,updated_at=:now
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                   AND lifecycle_state='RUNNING'
                RETURNING *
                """;

    static final String APPEND_AUDIT_16 = """
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:event,:tenant,'USER',:actor,:action,'EMPLOYEE_CHANGE_REQUEST',
                    :target,'SUCCESS',:correlation,CAST(:snapshot AS jsonb),:occurredAt)
                """;

    static final String APPEND_EVENT_17 = """
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:event,:tenant,'EMPLOYEE_CHANGE_REQUEST',:aggregate,:eventType,
                    CAST(:payload AS jsonb),:correlation,:occurredAt)
                ON CONFLICT (event_id) DO NOTHING
                """;

    static final String WORKBENCH_18 = """
                SELECT request.public_id,request.lifecycle_state,request.change_type,
                       request.effective_date,request.version,request.field_groups::text,
                       request.created_at,target.display_name,
                       requester.public_id AS requester_public_id,
                       worker.worker_id AS target_worker_id
                  FROM ppl_employee_change_requests request
                  JOIN ppl_persons requester
                    ON requester.tenant_id=request.tenant_id
                   AND requester.person_id=request.requester_person_id
                  JOIN ppl_persons target
                    ON target.tenant_id=request.tenant_id
                   AND target.person_id=request.target_person_id
                  JOIN LATERAL (
                       SELECT candidate.worker_id,assignment.manager_assignment_key,
                              organization.public_id AS organization_public_id
                         FROM ppl_workers candidate
                         JOIN ppl_work_relationships relationship
                           ON relationship.tenant_id=candidate.tenant_id
                          AND relationship.worker_id=candidate.worker_id
                          AND relationship.start_date<=CURRENT_DATE
                          AND (relationship.end_date IS NULL
                               OR relationship.end_date>=CURRENT_DATE)
                         JOIN ppl_assignments assignment
                           ON assignment.tenant_id=relationship.tenant_id
                          AND assignment.work_relationship_id=relationship.work_relationship_id
                          AND assignment.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                          AND assignment.effective_start_date<=CURRENT_DATE
                          AND (assignment.effective_end_date IS NULL
                               OR assignment.effective_end_date>=CURRENT_DATE)
                         LEFT JOIN ppl_organizations organization
                           ON organization.tenant_id=assignment.tenant_id
                          AND organization.organization_id=assignment.organization_id
                        WHERE candidate.tenant_id=target.tenant_id
                          AND candidate.person_id=target.person_id
                        ORDER BY assignment.primary_assignment DESC,
                                 assignment.effective_start_date DESC,
                                 (candidate.worker_status IN ('ACTIVE','LEAVE')) DESC,
                                 candidate.worker_id
                        LIMIT 1
                  ) worker ON TRUE
                 WHERE request.tenant_id=:tenant AND request.created_at<=:asOf
                   AND worker.worker_id<>:actorWorker
                   AND (:tenantWide
                        OR worker.manager_assignment_key=:managerAssignment
                        OR worker.organization_public_id IN (:organizationIds))
                 ORDER BY request.public_id
                """;
}
