package com.dwp.services.people.hris.employment;

/** Named SQL templates for the tenant-scoped EmploymentEventRepository persistence operations. */
final class EmploymentEventSql {

    static final String EVENT_SELECT_1 = """
            SELECT event.assignment_event_id,event.public_id,event.tenant_id,
                   event.subject_person_id,event.target_worker_id,event.event_type,
                   event.effective_date,event.command_context::text,event.event_payload::text,
                   event.lifecycle_state,event.impact_snapshot::text,
                   event.input_snapshot_hash,event.impact_snapshot_hash,
                   event.expected_owner_version,event.version,event.created_by,
                   event.correlation_id AS originating_correlation_id,
                   event.approval_request_id,event.approval_decision_event_id,
                   event.approval_decision_revision,event.approval_payload_hash,
                   event.validated_at,event.submitted_at,event.approved_at,event.published_at,
                   event.created_at,event.updated_at,
                   worker.public_id AS published_worker_public_id,
                   relationship.public_id AS published_relationship_public_id,
                   assignment.public_id AS published_assignment_public_id
              FROM ppl_assignment_events event
              LEFT JOIN ppl_workers worker
                ON worker.tenant_id=event.tenant_id
               AND worker.worker_id=event.target_worker_id
              LEFT JOIN ppl_work_relationships relationship
                ON relationship.tenant_id=event.tenant_id
               AND relationship.work_relationship_id=event.published_work_relationship_id
              LEFT JOIN ppl_assignments assignment
                ON assignment.tenant_id=event.tenant_id
               AND assignment.assignment_id=event.published_assignment_id
            """;

    static final String EVENT_2 = """
                 WHERE event.tenant_id=:tenant AND event.public_id=:event
                """;

    static final String LOCK_EVENT_3 = """
                SELECT public_id FROM ppl_assignment_events
                 WHERE tenant_id=:tenant AND public_id=:event
                 FOR UPDATE
                """;

    static final String CREATE_EVENT_4 = """
                INSERT INTO ppl_assignment_events(
                    tenant_id,subject_person_id,target_worker_id,event_type,
                    effective_date,command_context,event_payload,input_snapshot_hash,
                    expected_owner_version,correlation_id,created_by,updated_by)
                VALUES(:tenant,:person,:worker,:eventType,:effectiveDate,
                    CAST(:context AS jsonb),CAST(:payload AS jsonb),:contentDigest,
                    :ownerVersion,:correlation,:actor,:actor)
                RETURNING public_id
                """;

    static final String INSERT_RECEIPT_5 = """
                INSERT INTO ppl_command_receipts(
                    command_receipt_id,tenant_id,command_type,idempotency_key,
                    request_hash,actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,correlation_id,created_at,updated_at)
                VALUES(:receipt,:tenant,:commandType,:key,:digest,:actor,:action,
                    :subject,:population,:fieldRevision,:purpose,:authorizationRevision,
                    :contextDigest,'EMPLOYMENT_EVENT',:target,:expectedVersion,
                    'RUNNING',:correlation,:now,:now)
                ON CONFLICT DO NOTHING
                RETURNING *
                """;

    static final String RECEIPT_BY_IDENTITY_6 = """
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND subject_principal_public_id=:subject
                   AND originating_action=:action AND idempotency_key=:key
                 FOR UPDATE
                """;

    static final String RECEIPT_7 = """
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                """;

    static final String COMPLETE_RECEIPT_8 = """
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',result_code=:resultCode,
                       result_reference=CAST(:result AS jsonb),completed_at=:now,updated_at=:now
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                   AND lifecycle_state='RUNNING'
                RETURNING *
                """;

    static final String VALIDATE_9 = """
                   SET lifecycle_state='VALIDATED',impact_snapshot_hash=:impact,
                       impact_snapshot=CAST(:snapshot AS jsonb),
                       validated_at=clock_timestamp(),validated_by=:actor,
                       version=version+1,updated_by=:actor
                 WHERE tenant_id=:tenant AND public_id=:event
                   AND lifecycle_state='DRAFT' AND version=:expected
                """;

    static final String SUBMIT_10 = """
                   SET lifecycle_state='PENDING_APPROVAL',approval_request_id=:approval,
                       submitted_at=clock_timestamp(),submitted_by=:actor,
                       version=version+1,updated_by=:actor
                 WHERE tenant_id=:tenant AND public_id=:event
                   AND lifecycle_state='VALIDATED' AND version=:expected
                """;

    static final String RECORD_APPROVAL_11 = """
                INSERT INTO ppl_assignment_event_approval_decisions(
                    tenant_id,assignment_event_id,approval_request_id,
                    decision_event_id,decision,decision_actor_public_id,
                    approval_policy_revision,expected_event_version,payload_sha256,
                    correlation_id,decided_at)
                VALUES(:tenant,:eventInternal,:request,:decisionEvent,:decision,:actor,
                    :revision,:expected,:digest,:correlation,:decidedAt)
                ON CONFLICT DO NOTHING
                RETURNING *
                """;

    static final String APPROVAL_DECISION_12 = """
                SELECT * FROM ppl_assignment_event_approval_decisions
                 WHERE tenant_id=:tenant AND decision_event_id=:decisionEvent
                """;

    static final String APPLY_APPROVAL_13 = """
                   SET lifecycle_state=:state,approval_decision_event_id=:decisionEvent,
                       approval_decision_revision=:revision,
                       approval_payload_hash=:digest,approved_at=:decidedAt,
                       approved_by_principal=:actor,version=version+1
                 WHERE tenant_id=:tenant AND public_id=:event
                   AND lifecycle_state='PENDING_APPROVAL'
                   AND approval_request_id=:request AND version=:expected
                """;

    static final String ASSIGNMENTS_14 = """
                SELECT person.public_id AS person_public_id,worker.worker_id,
                       worker.public_id AS worker_public_id,
                       worker.worker_status,worker.version AS worker_version,
                       relationship.public_id AS relationship_public_id,
                       relationship.relationship_type,relationship.start_date,
                       relationship.end_date,relationship.version AS relationship_version,
                       assignment.public_id AS assignment_public_id,
                       assignment.assignment_status,assignment.effective_start_date,
                       assignment.effective_end_date,assignment.primary_assignment,
                       assignment.full_time_equivalent,assignment.version AS assignment_version,
                       employer.public_id AS employer_public_id,
                       employer.version AS employer_version,
                       organization.public_id AS organization_public_id,
                       organization.version AS organization_version,
                       position.public_id AS position_public_id,
                       position.version AS position_version,
                       job.public_id AS job_public_id,job.version AS job_version,
                       location.public_id AS location_public_id,
                       location.version AS location_version,
                       location.calendar_public_id,location.location_key,location.time_zone
                  FROM ppl_assignments assignment
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id=assignment.tenant_id
                   AND relationship.work_relationship_id=assignment.work_relationship_id
                  JOIN ppl_workers worker ON worker.tenant_id=relationship.tenant_id
                   AND worker.worker_id=relationship.worker_id
                  JOIN ppl_persons person ON person.tenant_id=worker.tenant_id
                   AND person.person_id=worker.person_id
                  JOIN ppl_legal_employers employer
                    ON employer.tenant_id=relationship.tenant_id
                   AND employer.legal_employer_id=relationship.legal_employer_id
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id=assignment.tenant_id
                   AND organization.organization_id=assignment.organization_id
                  LEFT JOIN ppl_positions position ON position.tenant_id=assignment.tenant_id
                   AND position.position_id=assignment.position_id
                  LEFT JOIN ppl_job_profiles job ON job.tenant_id=assignment.tenant_id
                   AND job.job_profile_id=assignment.job_profile_id
                  LEFT JOIN ppl_locations location ON location.tenant_id=assignment.tenant_id
                   AND location.location_id=assignment.location_id
                 WHERE assignment.tenant_id=:tenant
                   AND relationship.start_date<=:asOf
                   AND (relationship.end_date IS NULL OR relationship.end_date>=:asOf)
                   AND assignment.effective_start_date<=:asOf
                   AND (assignment.effective_end_date IS NULL
                        OR assignment.effective_end_date>=:asOf)
                """;

    static final String PERSON_15 = """
                SELECT person.person_id,NULL::BIGINT AS worker_id,
                       NULL::UUID AS worker_public_id,0::BIGINT AS worker_version
                  FROM ppl_persons person
                 WHERE person.tenant_id=:tenant AND person.public_id=:publicId
                   AND person.lifecycle_state='ACTIVE'
                """;

    static final String WORKER_16 = """
                SELECT worker.person_id,worker.worker_id,
                       worker.public_id AS worker_public_id,worker.version AS worker_version
                  FROM ppl_workers worker
                 WHERE worker.tenant_id=:tenant AND worker.public_id=:publicId
                   AND worker.worker_status IN ('ACTIVE','LEAVE','PENDING')
                """;

    static final String REFERENCE_17 = """
                    SELECT legal_employer_id AS internal_id,public_id,version,
                           employer_key AS business_key,NULL::VARCHAR AS time_zone,
                           NULL::UUID AS calendar_public_id
                      FROM ppl_legal_employers
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND lifecycle_state='ACTIVE'
                    """;

    static final String REFERENCE_18 = """
                    SELECT organization_id AS internal_id,public_id,version,
                           organization_key AS business_key,NULL::VARCHAR AS time_zone,
                           NULL::UUID AS calendar_public_id
                      FROM ppl_organizations
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND lifecycle_state='ACTIVE'
                    """;

    static final String REFERENCE_19 = """
                    SELECT position_id AS internal_id,public_id,version,
                           position_key AS business_key,NULL::VARCHAR AS time_zone,
                           NULL::UUID AS calendar_public_id
                      FROM ppl_positions
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND position_status IN ('OPEN','FILLED')
                    """;

    static final String REFERENCE_20 = """
                    SELECT job_profile_id AS internal_id,public_id,version,
                           job_key AS business_key,NULL::VARCHAR AS time_zone,
                           NULL::UUID AS calendar_public_id
                      FROM ppl_job_profiles
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND lifecycle_state='ACTIVE'
                    """;

    static final String REFERENCE_21 = """
                    SELECT location_id AS internal_id,public_id,version,
                           location_key AS business_key,time_zone,calendar_public_id
                      FROM ppl_locations
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND lifecycle_state='ACTIVE'
                    """;

    static final String PRIMARY_OVERLAP_22 = """
                SELECT EXISTS(
                    SELECT 1 FROM ppl_assignments assignment
                    JOIN ppl_work_relationships relationship
                      ON relationship.tenant_id=assignment.tenant_id
                     AND relationship.work_relationship_id=assignment.work_relationship_id
                    JOIN ppl_workers worker ON worker.tenant_id=relationship.tenant_id
                     AND worker.worker_id=relationship.worker_id
                    WHERE assignment.tenant_id=:tenant AND worker.person_id=:person
                      AND assignment.primary_assignment=TRUE
                      AND assignment.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                      AND (CAST(:toExclusive AS DATE) IS NULL
                           OR assignment.effective_start_date < CAST(:toExclusive AS DATE))
                      AND (assignment.effective_end_date IS NULL
                           OR assignment.effective_end_date >= :from))
                """;

    static final String INSERT_WORKER_23 = """
                INSERT INTO ppl_workers(
                    tenant_id,person_id,worker_number,worker_type,worker_status,
                    original_hire_date,version,created_by,updated_by)
                VALUES(:tenant,:person,:number,:type,'ACTIVE',:hireDate,1,:actor,:actor)
                RETURNING worker_id,public_id,version
                """;

    static final String ADVANCE_WORKER_24 = """
                UPDATE ppl_workers SET version=version+1,
                       updated_at=clock_timestamp(),updated_by=:actor
                 WHERE tenant_id=:tenant AND worker_id=:worker AND version=:expected
                RETURNING worker_id,public_id,version
                """;

    static final String INSERT_RELATIONSHIP_25 = """
                INSERT INTO ppl_work_relationships(
                    tenant_id,relationship_key,worker_id,legal_employer_id,
                    relationship_type,primary_relationship,start_date,end_date,
                    version,created_by,updated_by)
                VALUES(:tenant,:key,:worker,:employer,:type,:primary,:start,:end,
                    1,:actor,:actor)
                RETURNING work_relationship_id,public_id,version
                """;

    static final String INSERT_ASSIGNMENT_26 = """
                INSERT INTO ppl_assignments(
                    tenant_id,assignment_key,work_relationship_id,effective_start_date,
                    effective_end_date,effective_sequence,assignment_status,
                    primary_assignment,position_id,job_profile_id,organization_id,
                    location_id,business_title,full_time_equivalent,version,
                    created_by,updated_by)
                VALUES(:tenant,:key,:relationship,:starts,:ends,1,'ACTIVE',:primary,
                    :position,:job,:organization,:location,:title,:fte,1,:actor,:actor)
                RETURNING assignment_id,public_id,version
                """;

    static final String PUBLISH_EVENT_27 = """
                   SET lifecycle_state='PUBLISHED',published_at=clock_timestamp(),
                       published_by=:actor,target_worker_id=:worker,
                       published_work_relationship_id=:relationship,
                       published_assignment_id=:assignment,version=version+1,updated_by=:actor
                 WHERE tenant_id=:tenant AND public_id=:event
                   AND lifecycle_state='APPROVED' AND version=:expected
                """;
}
